# AGENTS.md — 开发与维护规范

本文件面向**开发者与 AI 编码代理**：项目是什么、边界在哪、怎么改、怎么验、怎么发。
**面向用户的使用说明不放这里，在 [README.md](README.md)。**

任何实现、重构、审查都必须遵守本文件。与最新 Android 强制安全要求冲突时以平台要求为准；
若新要求会突破第 2 节的安全边界，**停止实现并明确报告，不得自行扩大权限**。

---

## 1. 项目是什么

系统启动助手（NetBoot）是一个 Android 应用（API 26–37），让**已由 Root 管理器明确授权本应用使用 `su`** 的手机提供：

1. **PXE 网络启动服务** —— 通过手机现有局域网接口，用 DHCP / ProxyDHCP + TFTP + HTTP Boot + iPXE 给同一网络内的 PC 提供网络引导。
2. **USB 安装介质** —— 把应用私有目录中的镜像临时映射成只读 USB 存储，供 PC 引导。
3. **镜像获取与管理** —— 微软官方 ISO 临时链接获取、多连接断点下载、本地导入、SHA-256 与状态管理。

USB 数据流：`下载或导入镜像 → 制作或复用介质 → 启用 → 使用 → 停止并恢复 USB`。
PXE 数据流：`选择接口与模式 → 配置启动文件和脚本 → 启动 → 使用 → 停止`。
镜像库与 PXE 文件目录独立，下载或导入 ISO 不会自动生成 PXE 安装源。

必须做成可发布、可持续维护的正式产品：**不接受 Demo、原型、占位实现、假数据、只覆盖成功
路径或需要开发者手工收尾的流程。**

### 1.1 Root 前提（不可含糊）

| 状态 | 本项目 |
| --- | --- |
| **Root 管理器已授权本应用**（授权列表允许 `com.sky22333.netboot` 使用 `su`） | 使用通用 `su`，具体管理器兼容性需实测 |

应用启动时自动检测现有 Root 和 USB 配置。权限不可用时提示授予本应用 Root 权限，
**不尝试安装或修补 KernelSU**。

---

## 2. 安全边界（最重要的一节）

这条边界是产品验收标准的一部分：**允许内核 panic 重启，但手机必须始终能正常开机与使用。**

### 允许

- 应用私有目录中的数据库、配置、日志、下载临时文件与镜像。
- 用户通过 SAF 明确选择的目标，仅限已确认的导入/导出。
- configfs 中**经白名单限定**的 USB gadget 节点。这是内核内存态、重启即复位，不是分区内容。

### 绝对禁止

- 写入/删除/覆盖/格式化/刷写任何真实分区块设备，尤其 `/dev/block/*`。
- 写或可写挂载 boot、init_boot、vendor_boot、recovery、abl、xbl、dtbo、vbmeta、
  system、vendor、product、super、odm、persist、modem、metadata 等分区或数据。
- 修改 `/system`、`/vendor`、`/product`、`/system_ext`、`/odm`、`/metadata`、`/persist`、
  `/data/system`、`/data/misc`、`/data/vendor` 等目录。
- 关闭 AVB / dm-verity / SELinux，设置全局 permissive，改启动镜像、装内核、修补 Boot、解 BL。
- 安装 KernelSU 模块、metamodule、OverlayFS 或任何开机脚本。
- 调用 `dd`、`mkfs`、`flash*`、`setenforce 0`、`mount -o rw`、`resetprop` 或等价操作。
- 写 `persist.*` 属性；不修改 Android USB 持久配置。

### 强制门禁

- **任何特权路径写入（包含从状态文件读回的路径）必须先过 `ConfigfsGuard` 的 canonical
  校验**；校验失败一律拒绝执行，**不得降级为"尽力而为"**。
- native 侧（`app/src/main/cpp/media.cpp`）通过调用方传入、验证为普通文件的描述符读写输入与输出，
  临时文件仅在调用方校验后的私有工作目录生成。`disk_initialize` 拒绝非 0 的 drive，
  构建入口断言 `getuid() != 0`；媒体制作全程在**非 root 的应用进程**内完成。
- 设备若无法在上述边界内完成能力，必须明确显示"此设备不支持"。

---

## 3. 技术基线

| 项 | 值 |
| --- | --- |
| minSdk / compileSdk / targetSdk | 26 / 37 / 37 |
| Kotlin & JVM target | 2.4.20 / JVM 17 |
| CI 构建 JDK | 25 |
| AGP / Gradle | 9.4.0 / 9.7.1 |
| NDK（AGP 与 gomobile 共用同一常量） | 28.2.13676358 |
| Go | 1.27.1 |
| 发布 ABI | `arm64-v8a`、`armeabi-v7a`（`x86_64` 仅供模拟器） |

Android 依赖版本**只在 `gradle/libs.versions.toml` 声明**；Go 依赖由 `core-go/go.mod` 固定，
native 源码下载地址与哈希集中在 `app/src/main/cpp/dependencies.cmake`。禁止 `+`、
`latest.release`、SNAPSHOT、Alpha、Beta、RC、EAP 与预发布组件。

升级依赖：核实官方最新**稳定**版 → 读目标版本源码/迁移说明 → 单独提交并更新元数据 →
跑完整门禁（第 6 节）。**不为了"最新"强行拼出二进制不兼容的组合**；不兼容时保留最近一个
已验证稳定组合并在提交信息里写明证据。

---

## 4. 代码结构

Gradle 仅包含 `:app`；`core-go` 是独立 Go 模块，`buildSrc` 提供构建任务。
**禁止**新增 `core`/`common`/`utils`/`domain`/`base` 这类无业务所有权的抽象模块。

```text
app/src/main/java/com/sky22333/netboot/
  MainActivity.kt     单 Activity + Compose，四个一级页面 + 脚本编辑器
  MainViewModel.kt
  PxeFormState.kt     接口、地址池与脚本的编辑状态
  data/              Room、DataStore、镜像与启动文件、微软目录、媒体布局
  download/          下载仓库、前台服务与远端文件探测
  root/              Root Broker、configfs 白名单、USB 控制
  runtime/           会话编排、前台服务与 DHCP 地址池
app/src/main/cpp/    media.cpp、CMakeLists.txt、dependencies.cmake
app/schemas/com.sky22333.netboot.data.AppDatabase/1.json
core-go/mobilecore/  Go 核心与 gomobile 导出（DHCP/TFTP/HTTP/事件/路径校验）
buildSrc/           Go AAR 构建与 ABI 校验任务
gradle/libs.versions.toml
.github/workflows/    发布流水线
```

### 数据流与职责

```text
Miuix Screen → MainViewModel → Repository / Service
                               ├─ Room / DataStore / OkHttp
                               ├─ DownloadService
                               └─ Root Broker → Go AAR / configfs
                            ← StateFlow（运行状态、配置、镜像、任务、事件）
```

- UI 只渲染不可变 `UiState` 并上报事件；Composable 无副作用。
- ViewModel 不持有 Activity / Service / View / NavController / 可变 Context。
- **Repository 是业务数据唯一写入口**；Room 是任务、镜像、事件记录的事实来源；
  DataStore 只放轻量偏好（主题、下载连接数）。
- Service 不是数据源，运行状态写回 `RuntimeRepository`。
- 一个屏幕一个 `UiState`；不要维护多个互相推导的 `MutableStateFlow`。
- 单次导航、Snackbar 等用明确的事件流，不把已消费事件永久留在 StateFlow。
- **默认不加 Domain/UseCase 层**；仅当同一段非平凡逻辑被两个以上 ViewModel 真实复用时才提取。

---

## 5. 关键实现约束

### 5.1 Go 核心

`core-go/mobilecore` 只暴露 gomobile 稳定支持的窄接口：

```text
ValidateConfig(configJson) -> error
Start(configJson, listener) -> error
Stop() -> error
StatusJSON() -> String
```

iPXE 脚本作为 `config.ipxeScript` 随配置传入；修改配置后需重新启动 PXE 才生效。

- 不跨 JNI 暴露 Go struct / map / channel / context / 文件对象。
- Listener 只发**事件码 + 结构化参数**，不发成品文本；由 Kotlin 本地化。
- 高频进度与日志必须合并，禁止每包/每块跨 JNI 回调。
- `Start` 在已运行时返回错误，不隐式重启。`Stop` 可重复调用，取消根 context、关闭 socket 与
  HTTP server 并等待 goroutine 退出。
- Go 核心不认识 Android UI、Room、下载任务或本地化。
- 协议要求：DHCP 两种互斥模式；启动时仅校验配置与绑定端口，**不探测现有 DHCP 服务器、不绑定客户端 UDP 68 端口**；完整 DHCP 由用户确认在隔离网络使用；TFTP 支持 RRQ/重传/
  `blksize`/`tsize` 与并发上限；TFTP 与 HTTP 路径必须 clean+canonical+根边界校验，拒绝
  `..`、绝对路径与符号链接越界；HTTP Boot 支持 HEAD/Range 且**不得把整文件读入内存**。
- 脚本以协议保留名下发（`autoexec.ipxe`、`boot.ipxe`），两者都返回同一份 `config.ipxeScript`。
- 两种 DHCP 模式均监听 UDP 67/4011；PXE 启动发现先于租约处理。仅服务默认启动类型 0、层 0，
  明确的启动项目请求须返回对应的 option 43/71；4011 允许省略该项目。代理模式不抢答普通租约请求。
  `config.bootFile` 留空＝按 option 93 架构选择内置镜像，显式填写＝强制使用；EFI32 与未上报
  架构没有内置镜像，此时不下发启动文件名并记录 `boot_file_unsupported`。

### 5.2 Root Broker

Broker 与主应用在**同一个签名 APK** 内，由 `RootBrokerClient` 启动一个
最小 `app_process`；从自身 APK/安装目录直接加载类与 native 库，**不向可写目录释放或动态
加载可执行代码**。通信走 Android abstract `LocalSocket` 的长度前缀 JSON，**校验 peer UID**。

固定操作码（`BrokerOperation.allowed`，不得增加）：

```text
probe  startNetwork  stopNetwork  attachReadOnlyIso  detachIso  status  shutdown
```

**禁止** `runCommand`、`executeShell`、任意路径 `readFile/writeFile`、任意 mount 或任意
system property 操作。主动关闭或本地 socket 断开后，Broker 在退出路径中停止 Go 并尝试恢复 USB；
USB 清理不依赖 Go 停止成功。强制终止进程或内核异常不保证执行清理，恢复记录保留供下次连接使用。

### 5.3 USB 安装介质

探测**只读**检查 configfs、UDC、当前 gadget/config 和配置可写性，**不得为了探测而写**。
`mass_storage` function 是否能创建在启用阶段确认；探测通过不等于实际启用或电脑启动成功。

挂载流程：

1. 只接受状态 Ready 的镜像；校验存在、普通文件、非符号链接、大小与剩余空间。
2. 显示确认：USB 数据连接会暂时断开，ADB/MTP 可能消失，介质严格只读。
3. 需要转换的 Windows UDF ISO 先在**非 root 进程**中制作私有 FAT32 镜像；超过单文件上限的
   WIM 由 wimlib 拆分。**原始 ISO 不变**，制作须显示真实阶段与进度并支持取消。
4. 记录恢复状态，创建本应用的 `mass_storage` function 并配置 LUN；验证后临时解绑 UDC 并加入配置。
5. **LUN 始终 `ro=1`**。容量与形态决定 `cdrom`：
   - ≤ `2,359,293,952` 字节（`256*60*75-1` 个 2048 字节块）且通过光盘结构检查 → `cdrom=1`；
   - 通过磁盘布局检查的混合 ISO，或应用制作的 FAT32 镜像 → `cdrom=0`；
   - **禁止把普通 Windows ISO 当磁盘直接映射**；超限的非混合 Linux ISO 明确报不支持，
     不得绕过内核容量边界。
6. 绑定 UDC 并回报主机连接状态；主动停止或 Broker 会话结束时解绑、清理本应用创建的节点并
   恢复原始 gadget 配置。拔线事件只刷新连接状态，不等于停止或恢复。

可选的 Windows/官方 WinPE 驱动包从 SAF 文件夹或 ZIP 流式导入私有目录；保留完整 INF 相对路径，
通过同一制作流程写入 `/$WinPEDriver$`，不修改 WIM、不增加 LUN。替换与制作由运行时互斥锁串行处理；
取消或失败保留原选择，完整结果原子发布后写入 Room。选择驱动时不走直接映射路径。
已完成的制作结果按源镜像 SHA-256 和驱动内容哈希复用；制作前要求可用空间不少于 ISO 大小的 5 倍加驱动大小和 512 MiB。
生成的 Windows FAT32 安装盘面向 UEFI，不包含传统 BIOS 引导代码。

只允许操作**当前 gadget 下本应用创建或快照记录**的节点。backing file 位于 `/dev`、是块设备、
符号链接或不在受管目录，一律拒绝。

**兼容性由真实 UDC、OEM gadget 驱动、镜像和 PC 固件决定**，UI 区分不支持、已停止、
已启用、电脑已连接及恢复失败。不得把未知设备风险归因于“临时 Root”，也不得承诺拔线不会重启。

### 5.4 镜像下载器

唯一实现：OkHttp + Coroutines（不用 Android DownloadManager，不在 Go 侧再写一套）。

- 默认 4 连接，可选 1/2/4/8；先探测长度、Range 支持、ETag、Last-Modified。
- 支持 Range 时按闭区间平均分段，`FileChannel` positional write 写入**同一个预分配 `.part`**；
  服务端忽略 Range 或返回 200 时**自动降为单连接**，不并行重复下载。
- 远端身份（总长 + ETag/Last-Modified）变化必须要求重新下载，不允许把不同版本拼在一起。
- 进度写内存，下载期间每 2 s 更新 UI/通知并批量保存分段检查点；退出传输时保存最终检查点。
- 暂停保留 `.part` 与段状态；取消由用户选择是否删除临时文件。
- 完成后校验总长度 → 算 SHA-256 → 原子重命名 → 状态置 Ready。
- **没有微软官方哈希时，SHA-256 只作为本地身份与损坏检测，不得声称"已验证微软签名"。**
- 4xx 不无限重试；每段 IO 失败最多尝试 3 次，重试使用指数退避并带 jitter。
- 全部流式处理，禁止把镜像或大块响应完整载入内存。

下载由用户显式启动的 `DownloadService`（`dataSync` 前台服务）执行。

### 5.5 微软目录

`MicrosoftIsoCatalog` 是唯一实现，流程：创建会话 → 访问会话/遥测初始化端点 → 请求 SKU →
按版本/语言/架构选 SKU → 请求官方临时链接。

- 所有请求与重定向必须 HTTPS 且限定在明确的微软官方域名集合内（逐跳校验）。
- 用系统证书信任；不做会失效的证书 Pinning；**不允许忽略 TLS 错误**。
- 产品/语言/架构是稳定镜像身份，临时 URL 与过期时间只是可刷新凭据；403 只刷新一次后按原
  Range 继续。
- 该接口是**外部易变边界**，解析集中在单个 Catalog 类中并以脱敏响应 fixture 测试。
- **不硬编码声称长期有效的 ISO URL；不代理、不缓存分发微软镜像；UI 不伪造不可用选项。**

---

## 6. 开发流程与门禁

### 本地命令

```bash
# 单元测试 + Lint（快，日常用这个）
./gradlew testDebugUnitTest lintRelease

# 单个测试类
./gradlew testDebugUnitTest --tests '*UsbGadgetControllerTest'

# Release 构建；CI 用发布 tag 覆盖 versionName
./gradlew assembleRelease -PnetbootVersionName=1.0.0

# 模拟器构建（加入 x86_64；该开关同时驱动 AAR 目标、APK split 与 AAR 校验）
./gradlew assembleDebug -PnetbootAbis=arm64-v8a,armeabi-v7a,x86_64

# 需要设备：数据库与 native 媒体生成测试
./gradlew connectedDebugAndroidTest

# Go 侧
cd core-go && gofmt -l . && go vet ./... && go test -race ./...
```

Go race 需要 CGO 和兼容的本机 C 编译器。Windows 可在当前 PowerShell 进程设置
`$env:CGO_ENABLED='1'` 和 `$env:CC='编译器绝对路径'` 后执行；不要用 `go env -w` 修改全局配置。

**注意**：`app/libs/netboot-core.aar` 不在版本管理内，由 `preBuild → verifyGoAar` 自动构建并
**校验 ABI**（必须与 `netbootAbis` 完全一致；默认发布仅含两个 ARM ABI）。构建任务自行按 go.mod 固定版本把
`gobind` 装到构建目录并置于 PATH 首位；**不要手工 `go install` 到全局 `GOPATH/bin`**——
`gomobile bind` 只按 PATH 查找 gobind，全局那份会因版本不匹配而悄悄改变产物。

### 合并前必须全部通过

1. `testDebugUnitTest`（纯 JVM 单测，含 configfs、媒体布局、下载校验、DHCP 池）与 `lintRelease`。
2. `go test -race ./...` 与 `gofmt`、`go vet`。
3. `assembleRelease` 成功，AAR 覆盖两个发布 ABI。
4. **导出的 Room schema 必须已提交**：`git diff --exit-code -- app/schemas` 干净；
   当前数据库版本为 1，仅保留标准生成目录中的 `1.json`，无旧版本迁移代码。
   正式发布后的 schema 升级必须提供显式、可测的迁移，生产构建禁止 destructive migration。
5. 新功能带完整中英文资源、加载/空/错误/恢复交互与自动化测试。
6. Root/configfs 改动必须经过安全边界审查。
7. **不降低测试、不关闭警告、不加临时兼容分支来换取通过。**

### 测试策略

- 日常修复默认仅执行必要的本地测试、Lint 和构建；构建成功后不自动启动模拟器或连接真机验证。设备验证仅在用户明确要求时执行，未验证的设备行为如实报告。

- **CI 执行 JVM 单测、Go race/vet/gofmt、Lint、schema 检查和 Release 构建**；需要设备的 instrumented 测试（数据库、native 媒体生成）
  由开发者在真机/模拟器上跑，见上表命令。
- `TestNetworkStartWithClientPortOccupied` 仅在具备低端口绑定权限的专用 Linux/模拟器上，以 `NETBOOT_NETWORK_TEST=1` 显式启用；它会占用 UDP 67/68/69/4011，禁止在真实业务网络运行。
- `root/` 相关测试必须**注入测试根目录**，针对临时目录执行；**CI 永不触碰真实 `/config`、
  `/sys`、`/dev`**。
- 特权源码审查必须检查第 2 节禁止的路径与操作；当前 CI 没有独立的特权命令扫描器，
  不得将 Lint 或单元测试通过视为完成安全审查。
- 真机只在专用设备上执行：验证映射、PC UEFI 枚举、只读属性、拔线、应用停止、主进程崩溃、
  授权撤销与恢复原 USB 组合。**真机测试不得写分区、不得改 SELinux 全局状态、不得把测试
  镜像指向块设备。**

---

## 7. 发布

发布流水线在 `.github/workflows/build.yml`，**仅支持手动触发**（`workflow_dispatch`），
必须输入发布 tag。

一次运行内按序完成：静态检查 → 单元测试 → R8 Release → PKCS#12 签名与验签 →
`softprops/action-gh-release` 通过 API 创建 release 与 tag，当前上传 `dist/*.apk`。
R8 mapping 在构建目录生成，但当前流水线不上传；正式发布前仍须落实下述产物留存要求。
发布仅允许从 `main` 最新提交进行，tag 必须尚不存在。

CI 通过 `-PnetbootVersionName` 注入去掉前缀 `v` 的 tag；本地默认 `1.0.0`。
关于弹窗读取 `BuildConfig.VERSION_NAME`，点击版本号打开项目 GitHub 仓库。

签名 Secrets（仓库 Actions Secrets）：`SIGNING_KEY_BASE64`（完整 `.p12` 的 Base64）、
`KEY_ALIAS`、`KEY_STORE_PASSWORD`、`KEY_PASSWORD`。**缺任一项必须明确失败，不回退 Debug 签名。**
密钥只在临时目录解出，`apksigner --ks-type PKCS12` 签名并验证后立即删除；密钥与口令不得进入
仓库、Gradle 配置缓存或构建产物。

已发布应用必须沿用**同一密钥**：用
`keytool -importkeystore -deststoretype PKCS12` 转换原库并保留原私钥、证书与 alias，
**不得换新生成的密钥**，否则无法覆盖安装。

正式发布前置条件：独立 Release keystore；按 ABI 拆分 APK + 一个明确标识的 universal APK；
保存 mapping、AAR SHA-256 与依赖清单；完成中英文全流程验收，并在支持设备上跑通
"官方下载 → 暂停/恢复 → 校验 → USB 映射 → PC 识别 → 停止恢复"闭环。

---

## 8. 编码规范

### 通用

- **最少代码实现当前真实需求，只保留一个最佳方案。** 一个状态一个事实来源，一个功能一条
  实现路径。
- 不为理论场景堆叠兜底、重试与抽象；不为极低概率情况增加大量代码。
- 公共组件至少有两个真实调用点才提取；含义不同的相似代码不得为了少几行而错误合并。
- **修 Bug 必须：读真实调用链 → 复现 → 确认根因 → 最小修复。**
  **禁止猜测原因、凭经验判断、用补丁掩盖问题、堆叠防御性代码。**
- 修复任何 Bug 时，必须横向审查其他功能中的同类根因和调用模式，覆盖成功、失败、取消及恢复路径；只修复有证据的问题，并报告审查范围与未验证项。
- 删除实现时同步删除过时注释、配置、依赖与测试。不留 TODO 占位、示例密钥、假接口、
  注释掉的旧实现。
- 注释解释**为什么**与平台限制，不复述代码。

### Kotlin

- 官方编码风格、显式可见性、有业务含义的命名；优先 data class / sealed interface /
  extension / 标准库，不造重复工具函数。
- 协程结构化并由生命周期 owner 管理，**禁止 `GlobalScope`**；公开 suspend API 必须 main-safe。
- 捕获**具体**异常，只在能增加业务语义时转换；禁止吞异常与笼统 `catch (Throwable)`。
- 错误用稳定 error code；日志带可诊断上下文，但**不得记录临时 URL、Root token 或敏感路径**。
- 不用 `!!` 解决可空问题，也不为不可能状态堆多层空判断——用模型和构造约束消除非法状态。

### Go

- `gofmt`、`go vet`、`go test`、`go test -race` 全绿。
- error 带操作上下文并保留 `%w`；**不用 panic 处理外部输入**。
- 所有 goroutine 必须有 context、明确退出条件与 owner；网络读写设 deadline；获取资源后立即
  安排 Close；热路径不制造无界 goroutine/channel 或重复 buffer。

### UI 与国际化

- 保持既有页面布局和统一按钮风格；能明确表达操作的图标优先使用图标按钮，保留无障碍描述。未经用户明确要求，不擅自将图标操作改为文本按钮或重新设计页面。

- 扁平克制、接近 Miuix 原生：纯色背景、清晰分组、统一圆角、低层级阴影；
  禁渐变、玻璃拟态、无意义阴影、过度动画与装饰性模糊。
- 优先直接用 Miuix 组件，仅在缺少平台能力时用 Material 3；**同一页面不混两种视觉语言**。
  不为每个 Miuix 组件套一层机械包装。不使用 `miuix-blur`（最低 API 高于基线）。
- 触控目标 ≥ 48 dp；文字放大到 200% 时核心操作不得截断或不可达；**状态不能只靠颜色区分**。
- 紧凑窗口用底部 NavigationBar，中等/展开用 NavigationRail；不为大屏写第二套业务页面。
- `res/values/strings.xml` 英文为默认，`res/values-zh/strings.xml` 简体中文；声明
  `res/xml/locales_config.xml` 支持 `en` 与 `zh-Hans`。**语言只跟随系统**，不做应用内切换。
- 所有用户可见文本走资源，**禁止硬编码**；事件码由代码返回、Kotlin 映射为本地化文本。

### 性能与资源

- ISO 下载、复制、哈希与 HTTP Boot 全部流式；日志批量入库、进度节流、事务合并，
  禁止每包/每块写库。
- 空闲时 Go 核心、Root Broker、锁与轮询全部停止；**禁止每秒后台轮询**，状态用 Flow、
  callback 或 fd 事件驱动。
- 只有性能分析（Macrobenchmark / JankStats / Profiler / Compose compiler report）证明存在
  问题时才优化重组，不滥加 `remember` / `derivedStateOf` / `@Stable`。
- ⚠️ **尚未建立 Baseline Profile / Macrobenchmark 基准**；引入前必须先有实测证据，
  不得凭推测写性能目标。

---

## 9. 权威资料

实现与升级前**优先读当前源码、当前文档与发布说明**，不根据博客或旧经验推断：

- [项目开发与维护规范](https://raw.githubusercontent.com/sky22333/tools/refs/heads/main/docs/skills/SKILL.md)
- [Android 应用架构](https://developer.android.com/topic/architecture) ·
  [架构建议](https://developer.android.com/topic/architecture/recommendations)
- [Compose UI 架构](https://developer.android.com/develop/ui/compose/architecture) ·
  [Compose 性能](https://developer.android.com/develop/ui/compose/performance)
- [Android 本地化](https://developer.android.com/guide/topics/resources/localization) ·
  [Adaptive Apps](https://developer.android.com/develop/adaptive-apps)
- [后台数据传输选择](https://developer.android.com/develop/background-work/background-tasks/data-transfer-options) ·
  [安全最佳实践](https://developer.android.com/privacy-and-security/security-best-practices)
- [AGP 发布说明](https://developer.android.com/build/releases/agp-9-4-0-release-notes) ·
  [Kotlin 发布记录](https://kotlinlang.org/docs/releases.html)
- [gomobile bind](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile)
- [Miuix 源码](https://github.com/compose-miuix-ui/miuix)
- [KernelSU](https://github.com/tiann/KernelSU) ·
  [App Profile](https://kernelsu.org/zh_CN/guide/app-profile.html)
- [USB gadget configfs（内核）](https://www.kernel.org/doc/html/latest/usb/gadget_configfs.html) ·
  [`storage_common.c`（2.2 GiB 光驱上限的来源）](https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/plain/drivers/usb/gadget/function/storage_common.c)
- [拆分 WIM（微软）](https://learn.microsoft.com/en-us/windows-hardware/manufacture/desktop/split-a-windows-image--wim--file-to-span-across-multiple-dvds)
