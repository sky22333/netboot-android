#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <algorithm>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
extern "C" {
#include "udfread.h"
#include "blockinput.h"
#include "ff.h"
#include "diskio.h"
#include "wimlib.h"
}

namespace {
constexpr uint64_t FatLimit = 0xffffffffULL;
constexpr size_t BufferSize = 256 * 1024;
std::mutex buildMutex; // FatFs volume registration is global; one preparation at a time.
int diskFd = -1;
uint64_t diskBytes = 0;
void require(bool ok, const char* code) { if (!ok) throw std::runtime_error(code); }
struct Fd {
    int value;
    explicit Fd(int v) : value(v) { require(v >= 0, "media_io_failed"); }
    ~Fd() { close(value); }
    Fd(const Fd&) = delete;
};
void regular(int fd) {
    struct stat s{};
    require(fstat(fd, &s) == 0 && S_ISREG(s.st_mode), "media_not_regular");
}
bool transfer(int fd, void* data, size_t count, uint64_t offset, bool write) {
    auto* p = static_cast<uint8_t*>(data);
    while (count) {
        auto n = write ? pwrite(fd, p, count, static_cast<off_t>(offset)) : pread(fd, p, count, static_cast<off_t>(offset));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        count -= n; p += n; offset += n;
    }
    return true;
}
struct Input {
    udfread_block_input api{};
    int fd;
    uint32_t blocks;
};
using Udf = std::unique_ptr<udfread, decltype(&udfread_close)>;
using UdfFile = std::unique_ptr<UDFFILE, decltype(&udfread_file_close)>;
Udf openUdf(int fd) {
    regular(fd);
    struct stat s{};
    require(fstat(fd, &s) == 0 && s.st_size > 0 && static_cast<uint64_t>(s.st_size)/2048 <= UINT32_MAX, "media_invalid_iso");
    Udf udf(udfread_init(), udfread_close);
    require(udf != nullptr, "media_memory_failed");
    auto* input = new Input{};
    input->fd = dup(fd);
    input->blocks = s.st_size / 2048;
    input->api.read = [](udfread_block_input* p, uint32_t lba, void* buf, uint32_t n, int) {
        auto* in = reinterpret_cast<Input*>(p);
        if (lba > in->blocks || n > in->blocks - lba) return 0;
        return transfer(in->fd, buf, static_cast<size_t>(n)*2048, static_cast<uint64_t>(lba)*2048, false) ? static_cast<int>(n) : 0;
    };
    input->api.size = [](udfread_block_input* p) { return reinterpret_cast<Input*>(p)->blocks; };
    input->api.close = [](udfread_block_input* p) { auto* in = reinterpret_cast<Input*>(p); close(in->fd); delete in; return 0; };
    if (input->fd < 0 || udfread_open_input(udf.get(), &input->api) < 0) {
        input->api.close(&input->api);
        return Udf(nullptr, udfread_close);
    }
    return udf;
}
struct Entry { std::string path; uint64_t size; bool directory; };
std::string lower(std::string value) {
    for (char& c : value) if (c >= 'A' && c <= 'Z') c += 'a'-'A';
    return value;
}
void enumerate(udfread* udf, const std::string& path, std::vector<Entry>& out, int depth = 0) {
    require(depth < 32 && out.size() < 100000, "media_invalid_iso");
    std::unique_ptr<UDFDIR, decltype(&udfread_closedir)> dir(udfread_opendir(udf, path.c_str()), udfread_closedir);
    require(dir != nullptr, "media_invalid_iso");
    udfread_dirent e{};
    while (udfread_readdir(dir.get(), &e)) {
        std::string name(e.d_name);
        if (name == "." || name == "..") continue;
        require(!name.empty() && name.find_first_of("/\\:") == std::string::npos && name.size() <= 765, "media_invalid_iso");
        std::string child = path == "/" ? "/"+name : path+"/"+name;
        require(child.size() < 2048 && out.size() < 100000, "media_invalid_iso");
        if (e.d_type == UDF_DT_DIR) {
            out.push_back({child, 0, true});
            enumerate(udf, child, out, depth+1);
        } else {
            require(e.d_type == UDF_DT_REG, "media_invalid_iso");
            UdfFile file(udfread_file_open(udf, child.c_str()), udfread_file_close);
            require(file != nullptr && udfread_file_size(file.get()) >= 0, "media_invalid_iso");
            out.push_back({child, static_cast<uint64_t>(udfread_file_size(file.get())), false});
        }
    }
}
bool windows(const std::vector<Entry>& entries) {
    bool setup = false, boot = false, efi = false, image = false;
    for (const auto& e : entries) {
        if (e.directory) continue;
        auto p = lower(e.path);
        setup |= p == "/setup.exe";
        boot |= p == "/sources/boot.wim";
        efi |= p == "/efi/boot/bootx64.efi" || p == "/efi/boot/bootaa64.efi" || p == "/efi/boot/bootia32.efi";
        image |= p == "/sources/install.wim" || p == "/sources/install.esd" || p == "/sources/install.swm";
    }
    if (setup && boot && efi && image) return true;
    if (setup || boot) {
        throw std::runtime_error(std::string("media_windows_layout:missing=") +
            (!setup ? "setup.exe;" : "") + (!boot ? "sources/boot.wim;" : "") +
            (!efi ? "efi/boot;" : "") + (!image ? "sources/install.wim|esd|swm;" : ""));
    }
    return false;
}
struct Progress {
    JNIEnv* env;
    jobject callback;
    jmethodID method;
    uint64_t done = 0, total = 0;
    int stage = 0;
    uint64_t splitBase = 0;
    std::chrono::steady_clock::time_point lastUpdate{};
    bool notify(bool force = false) {
        const auto now = std::chrono::steady_clock::now();
        if (!force && now - lastUpdate < std::chrono::milliseconds(250)) return true;
        lastUpdate = now;
        auto ok = env->CallBooleanMethod(callback, method, stage, static_cast<jlong>(done), static_cast<jlong>(total));
        return !env->ExceptionCheck() && ok;
    }
    void begin(int nextStage, uint64_t bytes = 0) { stage = nextStage; done = 0; total = bytes; require(notify(true), "media_cancelled"); }
    void advance(uint64_t n) { done += n; require(notify(done == total), "media_cancelled"); }
};
struct FatFile {
    FIL file{};
    explicit FatFile(const std::string& path) { require(f_open(&file, path.c_str(), FA_WRITE | FA_CREATE_NEW) == FR_OK, "media_write_failed"); }
    ~FatFile() { f_close(&file); }
    void write(const void* data, UINT n) { UINT actual = 0; require(f_write(&file, data, n, &actual) == FR_OK && actual == n, "media_write_failed"); }
    void finish() { require(f_sync(&file) == FR_OK, "media_write_failed"); }
};
void copyUdf(udfread* udf, const Entry& entry, FatFile* fat, int fd, Progress& progress) {
    UdfFile file(udfread_file_open(udf, entry.path.c_str()), udfread_file_close);
    require(file != nullptr, "media_read_failed");
    std::vector<uint8_t> buffer(BufferSize);
    uint64_t pos = 0;
    while (pos < entry.size) {
        auto n = udfread_file_read(file.get(), buffer.data(), std::min<uint64_t>(buffer.size(), entry.size-pos));
        require(n > 0, "media_read_failed");
        if (fat) fat->write(buffer.data(), n);
        else require(transfer(fd, buffer.data(), n, pos, true), "media_write_failed");
        pos += n;
        progress.advance(n);
    }
}
void copyFile(const std::string& source, const std::string& dest, Progress& progress) {
    Fd fd(open(source.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC));
    regular(fd.value);
    struct stat s{};
    require(fstat(fd.value, &s) == 0 && s.st_size >= 0 && static_cast<uint64_t>(s.st_size) <= FatLimit, "media_file_too_large");
    FatFile out(dest);
    std::vector<uint8_t> buffer(BufferSize);
    uint64_t pos = 0;
    while (pos < static_cast<uint64_t>(s.st_size)) {
        size_t n = std::min<uint64_t>(buffer.size(), s.st_size-pos);
        require(transfer(fd.value, buffer.data(), n, pos, false), "media_read_failed");
        out.write(buffer.data(), n);
        pos += n; progress.advance(n);
    }
    out.finish();
}
enum wimlib_progress_status wimProgress(enum wimlib_progress_msg message, union wimlib_progress_info* info, void* p) {
    auto& progress = *static_cast<Progress*>(p);
    if (message == WIMLIB_PROGRESS_MSG_SPLIT_BEGIN_PART || message == WIMLIB_PROGRESS_MSG_SPLIT_END_PART) {
        progress.splitBase = info->split.completed_bytes;
        progress.done = info->split.completed_bytes;
        progress.total = info->split.total_bytes;
    } else if (message == WIMLIB_PROGRESS_MSG_WRITE_STREAMS) {
        // Combine compressed bytes for this part with previously completed split parts.
        if (progress.stage == 3) progress.done = std::min(progress.total, progress.splitBase + info->write_streams.completed_compressed_bytes);
        else { progress.done = info->write_streams.completed_bytes; progress.total = info->write_streams.total_bytes; }
    }
    return progress.notify(message == WIMLIB_PROGRESS_MSG_SPLIT_BEGIN_PART ||
        message == WIMLIB_PROGRESS_MSG_SPLIT_END_PART ||
        (progress.total > 0 && progress.done == progress.total)) ? WIMLIB_PROGRESS_STATUS_CONTINUE : WIMLIB_PROGRESS_STATUS_ABORT;
}
void checkWim(int error) {
    if (error == WIMLIB_ERR_ABORTED_BY_PROGRESS) throw std::runtime_error("media_cancelled");
    if (error) throw std::runtime_error("media_wim_failed:" + std::to_string(error));
}
void splitInstall(udfread* udf, const Entry& e, const std::string& work, Progress& progress, uint64_t partSize) {
    progress.begin(1, e.size);
    const auto source = work+"/source.wim";
    { Fd fd(open(source.c_str(), O_CREAT | O_EXCL | O_RDWR | O_NOFOLLOW | O_CLOEXEC, 0600)); copyUdf(udf, e, nullptr, fd.value, progress); }
    WIMStruct* raw = nullptr;
    checkWim(wimlib_open_wim(source.c_str(), 0, &raw));
    std::unique_ptr<WIMStruct, decltype(&wimlib_free)> wim(raw, wimlib_free);
    wimlib_register_progress_function(wim.get(), wimProgress, &progress);
    bool solid = false;
    checkWim(wimlib_iterate_lookup_table(wim.get(), 0, [](const wimlib_resource_entry* resource, void* value) {
        if (resource->packed) *static_cast<bool*>(value) = true;
        return 0;
    }, &solid));
    // Exporting to a non-solid WIM also handles oversized solid ESD resources.
    if (solid) {
    progress.begin(2);
    WIMStruct* exported = nullptr;
    checkWim(wimlib_create_new_wim(WIMLIB_COMPRESSION_TYPE_LZX, &exported));
    std::unique_ptr<WIMStruct, decltype(&wimlib_free)> normal(exported, wimlib_free);
    wimlib_register_progress_function(normal.get(), wimProgress, &progress);
    checkWim(wimlib_export_image(wim.get(), WIMLIB_ALL_IMAGES, normal.get(), nullptr, nullptr, 0));
    const auto normalized = work+"/normal.wim";
    checkWim(wimlib_write(normal.get(), normalized.c_str(), WIMLIB_ALL_IMAGES, 0, 2));
    normal.reset(); wim.reset();
    checkWim(wimlib_open_wim(normalized.c_str(), 0, &raw));
    wim.reset(raw);
    wimlib_register_progress_function(wim.get(), wimProgress, &progress);
    }
    const auto first = work+"/install.swm";
    progress.begin(3);
    checkWim(wimlib_split(wim.get(), first.c_str(), partSize, 0));
    std::vector<Entry> parts;
    uint64_t bytes = 0;
    for (int i = 1; ; i++) {
        auto name = i == 1 ? "install.swm" : "install"+std::to_string(i)+".swm";
        auto path = work+"/"+name;
        if (access(path.c_str(), F_OK) != 0) { require(i > 1 && errno == ENOENT, "media_wim_failed"); break; }
        struct stat s{};
        require(stat(path.c_str(), &s) == 0 && s.st_size > 0 && static_cast<uint64_t>(s.st_size) <= FatLimit, "media_file_too_large");
        parts.push_back({name, static_cast<uint64_t>(s.st_size), false});
        bytes += s.st_size;
    }
    progress.begin(4, bytes);
    for (const auto& part : parts) {
        copyFile(work+"/"+part.path, "/sources/"+part.path, progress);
        FILINFO info{};
        require(f_stat(("/sources/"+part.path).c_str(), &info) == FR_OK && info.fsize == part.size, "media_verify_failed");
    }
}
void build(int input, int output, const std::string& work, Progress& progress, uint64_t fileLimit = FatLimit) {
    std::lock_guard<std::mutex> lock(buildMutex);
    regular(output);
    require(getuid() != 0, "media_root_forbidden");
    auto udf = openUdf(input);
    require(udf != nullptr, "media_invalid_iso");
    std::vector<Entry> entries;
    enumerate(udf.get(), "/", entries);
    require(windows(entries), "media_not_windows");
    uint64_t total = 0;
    for (const auto& e : entries) {
        require(e.size < 64ULL*1024*1024*1024, "media_file_too_large");
        total += e.size;
    }
    require(total < 128ULL*1024*1024*1024, "media_file_too_large");
    // Reserve sparse image capacity for expanded WIM data and FAT metadata.
    diskBytes = ((total * 2 + 512ULL*1024*1024 + 511) / 512) * 512;
    require(ftruncate(output, static_cast<off_t>(diskBytes)) == 0, "insufficient_storage");
    diskFd = output;
    FATFS fs{};
    struct Cleanup { ~Cleanup() { f_mount(nullptr, "", 0); diskFd = -1; diskBytes = 0; } } cleanup;
    std::vector<uint8_t> buffer(BufferSize);
    MKFS_PARM format{FM_FAT32, 2, 2048, 0, 0};
    require(f_mkfs("", &format, buffer.data(), buffer.size()) == FR_OK, "media_format_failed");
    require(f_mount(&fs, "", 1) == FR_OK, "media_format_failed");
    uint64_t copyBytes = 0;
    for (const auto& e : entries) if (e.size <= fileLimit) copyBytes += e.size;
    progress.begin(0, copyBytes);
    const Entry* large = nullptr;
    for (const auto& e : entries) {
        if (e.directory) { require(f_mkdir(e.path.c_str()) == FR_OK, "media_write_failed"); continue; }
        if (e.size > fileLimit) {
            auto p = lower(e.path);
            require((p == "/sources/install.wim" || p == "/sources/install.esd") && !large, "media_file_too_large");
            large = &e;
            continue;
        }
        FatFile out(e.path);
        copyUdf(udf.get(), e, &out, -1, progress);
        out.finish();
    }
    if (large) splitInstall(udf.get(), *large, work, progress, std::min<uint64_t>(3800ULL*1024*1024, fileLimit));
    progress.begin(5);
    require(fsync(output) == 0, "media_write_failed");
    // Remount before verifying copied file lengths.
    require(f_mount(nullptr, "", 0) == FR_OK && f_mount(&fs, "", 1) == FR_OK, "media_verify_failed");
    for (const auto& e : entries) {
        if (e.directory || &e == large) continue;
        FILINFO info{};
        require(f_stat(e.path.c_str(), &info) == FR_OK && info.fsize == e.size, "media_verify_failed");
    }
}
void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), message);
}
}

// The disk backend accepts only an open app-private regular file.
extern "C" DSTATUS disk_initialize(BYTE drive) { return drive == 0 && diskFd >= 0 ? 0 : STA_NOINIT; }
extern "C" DSTATUS disk_status(BYTE drive) { return disk_initialize(drive); }
extern "C" DRESULT disk_read(BYTE drive, BYTE* buf, LBA_t sector, UINT count) {
    if (drive || diskFd < 0 || uint64_t(sector)+count > diskBytes/512) return RES_PARERR;
    return transfer(diskFd, buf, size_t(count)*512, uint64_t(sector)*512, false) ? RES_OK : RES_ERROR;
}
extern "C" DRESULT disk_write(BYTE drive, const BYTE* buf, LBA_t sector, UINT count) {
    if (drive || diskFd < 0 || uint64_t(sector)+count > diskBytes/512) return RES_PARERR;
    return transfer(diskFd, const_cast<BYTE*>(buf), size_t(count)*512, uint64_t(sector)*512, true) ? RES_OK : RES_ERROR;
}
extern "C" DRESULT disk_ioctl(BYTE drive, BYTE command, void* value) {
    if (drive || diskFd < 0) return RES_PARERR;
    switch (command) {
        case CTRL_SYNC: return fsync(diskFd) == 0 ? RES_OK : RES_ERROR;
        case GET_SECTOR_COUNT: *static_cast<LBA_t*>(value) = diskBytes/512; return RES_OK;
        case GET_SECTOR_SIZE: *static_cast<WORD*>(value) = 512; return RES_OK;
        case GET_BLOCK_SIZE: *static_cast<DWORD*>(value) = 1; return RES_OK;
        default: return RES_PARERR;
    }
}
extern "C" JNIEXPORT jstring JNICALL Java_com_sky22333_netboot_data_NativeMedia_volumeLabel(JNIEnv* env, jobject, jint input) {
    try {
        regular(input);
        // ECMA-119: primary volume identifier is bytes 41–72 (one-based).
        uint8_t descriptor[2048];
        for (uint64_t sector = 16; sector < 32; ++sector) {
            if (!transfer(input, descriptor, sizeof(descriptor), sector * 2048, false)) break;
            if (std::memcmp(descriptor + 1, "CD001", 5) != 0 || descriptor[6] != 1) continue;
            if (descriptor[0] == 255) break;
            if (descriptor[0] != 1) continue;
            std::string label(reinterpret_cast<char*>(descriptor + 40), 32);
            if (std::all_of(label.begin(), label.end(), [](unsigned char c) { return c >= 32 && c <= 126; }) &&
                label.find_first_not_of(' ') != std::string::npos) return env->NewStringUTF(label.c_str());
        }
        auto udf = openUdf(input);
        const char* label = udf ? udfread_get_volume_id(udf.get()) : nullptr;
        return label ? env->NewStringUTF(label) : nullptr;
    } catch (const std::exception& e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_sky22333_netboot_data_NativeMedia_isWindows(JNIEnv* env, jobject, jint input) {
    try {
        auto udf = openUdf(input);
        require(udf != nullptr, "media_udf_unreadable");
        std::vector<Entry> entries;
        enumerate(udf.get(), "/", entries);
        return windows(entries);
    } catch (const std::exception& e) { fail(env, e.what()); return false; }
}
extern "C" JNIEXPORT void JNICALL Java_com_sky22333_netboot_data_NativeMedia_buildWindows(JNIEnv* env, jobject, jint input, jint output, jstring directory, jobject callback) {
    const char* path = env->GetStringUTFChars(directory, nullptr);
    if (!path) return;
    try {
        auto method = env->GetMethodID(env->GetObjectClass(callback), "onProgress", "(IJJ)Z");
        if (!method) throw std::runtime_error("media_callback_failed");
        Progress progress{env, callback, method};
        checkWim(wimlib_global_init(0));
        build(input, output, path, progress);
    } catch (const std::exception& e) { fail(env, e.what()); }
    env->ReleaseStringUTFChars(directory, path);
}

#ifdef NETBOOT_MEDIA_TESTING
// Debug-only threshold injection exercises real WIM splitting without multi-GB CI fixtures.
extern "C" JNIEXPORT void JNICALL Java_com_sky22333_netboot_data_NativeMediaTest_buildWithSmallLimit(JNIEnv* env, jobject, jint input, jint output, jstring directory, jobject callback) {
    const char* path = env->GetStringUTFChars(directory, nullptr);
    if (!path) return;
    try {
        auto method = env->GetMethodID(env->GetObjectClass(callback), "onProgress", "(IJJ)Z");
        if (!method) throw std::runtime_error("media_callback_failed");
        Progress progress{env, callback, method};
        checkWim(wimlib_global_init(0));
        build(input, output, path, progress, 128*1024);
    } catch (const std::exception& e) { fail(env, e.what()); }
    env->ReleaseStringUTFChars(directory, path);
}
#endif
