# AGENTS.md — 开发与维护规范

本文件面向**开发者与 AI 编码代理**：项目是什么、边界在哪、怎么改、怎么验、怎么发。
**面向用户的使用说明不放这里，在 [README.md](README.md)。**

任何实现、重构、审查都必须遵守本文件。与最新 Android 强制安全要求冲突时以平台要求为准；
若新要求会突破第 2 节的安全边界，**停止实现并明确报告，不得自行扩大权限**。

---

## 1. 项目是什么

一个 Android 应用（API 26–37），让**已由 KernelSU 明确授权本应用使用 `su`** 的手机提供：

1. **PXE 网络启动服务** —— 通过手机现有局域网接口，用 DHCP / ProxyDHCP + TFTP + HTTP Boot + iPXE 给同一网络内的 PC 提供网络引导。
2. **USB 安装介质** —— 把应用私有目录中的镜像临时映射成只读 USB 存储，供 PC 引导。
3. **镜像获取与管理** —— 微软官方 ISO 临时链接获取、多连接断点下载、本地导入、SHA-256 与状态管理。

数据流：`获取镜像 → 下载或导入 → 选择 PXE/USB → 启动服务 → 使用 → 安全停止并恢复手机状态`。

必须做成可发布、可持续维护的正式产品：**不接受 Demo、原型、占位实现、假数据、只覆盖成功
路径或需要开发者手工收尾的流程。**

### 1.1 Root 前提（不可含糊）

| 状态 | 本项目 |
| --- | --- |
| **KernelSU 已授权本应用**（App Profile / 授权列表允许 `com.sky22333.netboot` 使用 `su`） | ✅ 唯一受支持配置 |

**系统级调度/优先级变动**都可能触发 panic）；内核 IPv4 输出路径可能瘫痪。

应用首次启动**只检测**现有 Root 环境。没有 KernelSU、授权被拒或 Profile 能力不足时，
展示具体原因与进入 KernelSU Manager 的提示，**不尝试安装或修补 KernelSU**。

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
- native 侧（`app/src/main/cpp/media.cpp`）只允许操作**调用方传入的、已验证为普通文件的
  文件描述符**：`disk_initialize` 拒绝非 0 的 drive，且构建入口断言 `getuid() != 0`。
  媒体制作全程在**非 root 的应用进程**内完成。
- 设备若无法在上述边界内完成能力，必须明确显示"此设备不支持"。

---

## 3. 技术基线

| 项 | 值 |
| --- | --- |
| minSdk / compileSdk / targetSdk | 26 / 37 / 37 |
| Kotlin & JVM target | 2.4.20 / JVM 17 |
| AGP / Gradle | 9.4.0 / 9.7.1 |
| NDK（AGP 与 gomobile 共用同一常量） | 28.2.13676358 |
| Go | 1.27.1 |
| 发布 ABI | `arm64-v8a`、`armeabi-v7a`（`x86_64` 仅供模拟器） |

依赖版本**只在 `gradle/libs.versions.toml` 声明**，模块脚本不得散落版本号，禁止 `+`、
`latest.release`、SNAPSHOT、Alpha、Beta、RC、EAP 与预发布组件。

升级依赖：核实官方最新**稳定**版 → 读目标版本源码/迁移说明 → 单独提交并更新元数据 →
跑完整门禁（第 6 节）。**不为了"最新"强行拼出二进制不兼容的组合**；不兼容时保留最近一个
已验证稳定组合并在提交信息里写明证据。

---

## 4. 代码结构

只有三个构建模块，**禁止** `core`/`common`/`utils`/`domain`/`base` 这类无业务所有权的抽象模块。

```text
app/                Kotlin 产品代码
  MainActivity.kt     单 Activity + Compose，四个一级页面 + 脚本编辑器
  MainViewModel.kt
  data/               Room、DataStore、镜像与启动文件仓库、微软目录 API、媒体布局
  download/           DownloadRepository（分片段下载）、DownloadService、RemoteFileProbe
  root/               BrokerProtocol、RootBrokerClient/Main、ConfigfsGuard、UsbGadgetController
  runtime/            RuntimeRepository（会话编排）、RuntimeService（前台服务）、DhcpPoolAllocator
  cpp/                media.cpp + CMakeLists（UDF 读取 → FAT32 生成 → WIM 拆分）
core-go/mobilecore/   精简后的 Go 核心与 gomobile 导出（DHCP/TFTP/HTTP/事件/路径校验）
gradle/libs.versions.toml
.github/workflows/    发布流水线
```

### 数据流与职责

```text
Miuix Screen → ViewModel → Repository / RuntimeController
                               ├─ Room / DataStore / OkHttp
                               ├─ DownloadService
                               └─ Root Broker → Go AAR / configfs
                            ← StateFlow<UiState>
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

**没有 `ReloadMenu`**：iPXE 脚本作为 `config.ipxeScript` 随配置传入，运行中不维护第二份可变配置。

- 不跨 JNI 暴露 Go struct / map / channel / context / 文件对象。
- Listener 只发**事件码 + 结构化参数**，不发成品文本；由 Kotlin 本地化。
- 高频进度与日志必须合并，禁止每包/每块跨 JNI 回调。
- `Start` 幂等：已运行时返回明确状态，不隐式重启。`Stop` 取消根 context、关闭 socket 与
  HTTP server 并等待 goroutine 退出。
- Go 核心不认识 Android UI、Room、下载任务或本地化。
- 协议要求：DHCP 两种互斥模式并**启动前探测现有 DHCP 服务器**；TFTP 支持 RRQ/重传/
  `blksize`/`tsize` 与并发上限；TFTP 与 HTTP 路径必须 clean+canonical+根边界校验，拒绝
  `..`、绝对路径与符号链接越界；HTTP Boot 支持 HEAD/Range 且**不得把整文件读入内存**。

### 5.2 Root Broker

Broker 与主应用在**同一个签名 APK** 内，由 `RuntimeService` 用**编译期常量**命令启动一个
最小 `app_process`；从自身 APK/安装目录直接加载类与 native 库，**不向可写目录释放或动态
加载可执行代码**。通信走 Android abstract `LocalSocket` 的长度前缀 JSON，**校验 peer UID**。

固定操作码（`BrokerOperation.allowed`，不得增加）：

```text
probe  startNetwork  stopNetwork  attachReadOnlyIso  detachIso  status  shutdown
```

**禁止** `runCommand`、`executeShell`、任意路径 `readFile/writeFile`、任意 mount 或任意
system property 操作。主进程断开、主动停止或收到终止信号时，必须停止 Go 服务并**优先恢复
USB 运行状态**。

### 5.3 USB 安装介质

探测**只读**（`/config/usb_gadget`、`/sys/kernel/config/usb_gadget`、UDC、当前 gadget/config/
function、`mass_storage` 可用性、镜像是否为私有目录内的普通文件），**不得为了探测而写**。

挂载流程：

1. 只接受状态 Ready 的镜像；校验存在、普通文件、非符号链接、大小与剩余空间。
2. 显示确认：USB 数据连接会暂时断开，ADB/MTP 可能消失，介质严格只读。
3. 需要转换的 Windows UDF ISO 先在**非 root 进程**中制作私有 FAT32 镜像；超过单文件上限的
   WIM 由 wimlib 拆分。**原始 ISO 不变**，制作须显示真实阶段与进度并支持取消。
4. 临时解绑 UDC，创建独立 `mass_storage` function。
5. **LUN 始终 `ro=1`**。容量与形态决定 `cdrom`：
   - ≤ `2,359,293,952` 字节（`256*60*75-1` 个 2048 字节块）且通过光盘结构检查 → `cdrom=1`；
   - 通过磁盘布局检查的混合 ISO，或应用制作的 FAT32 镜像 → `cdrom=0`；
   - **禁止把普通 Windows ISO 当磁盘直接映射**；超限的非混合 Linux ISO 明确报不支持，
     不得绕过内核容量边界。
6. 绑定 UDC 并回报主机连接状态；停止/异常断开/会话结束时解绑、清理本应用创建的节点并
   恢复原始 gadget 配置。

只允许操作**当前 gadget 下本应用创建或快照记录**的节点。backing file 位于 `/dev`、是块设备、
符号链接或不在受管目录，一律拒绝。

**兼容性由真实 UDC、OEM gadget 驱动与 PC UEFI 决定**，UI 必须区分：不支持 / 可配置 /
已映射 / 主机已连接 / 恢复失败。

### 5.4 镜像下载器

唯一实现：OkHttp + Coroutines（不用 Android DownloadManager，不在 Go 侧再写一套）。

- 默认 4 连接，可选 1/2/4/8；先探测长度、Range 支持、ETag、Last-Modified。
- 支持 Range 时按闭区间平均分段，`FileChannel` positional write 写入**同一个预分配 `.part`**；
  服务端忽略 Range 或返回 200 时**自动降为单连接**，不并行重复下载。
- 远端身份（总长 + ETag/Last-Modified）变化必须要求重新下载，不允许把不同版本拼在一起。
- 进度写内存可高频，UI/通知节流；数据库按 2 s 或每段 8 MiB 检查点。
- 暂停保留 `.part` 与段状态；取消由用户选择是否删除临时文件。
- 完成后校验总长度 → 算 SHA-256 → 原子重命名 → 状态置 Ready。
- **没有微软官方哈希时，SHA-256 只作为本地身份与损坏检测，不得声称"已验证微软签名"。**
- 4xx 不无限重试；瞬时网络错误最多 3 次指数退避并带 jitter。
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

**注意**：`app/libs/netboot-core.aar` 不在版本管理内，由 `preBuild → verifyGoAar` 自动构建并
**校验 ABI**（缺任一发布 ABI 或混入 x86_64 即失败）。构建任务自行按 go.mod 固定版本把
`gobind` 装到构建目录并置于 PATH 首位；**不要手工 `go install` 到全局 `GOPATH/bin`**——
`gomobile bind` 只按 PATH 查找 gobind，全局那份会因版本不匹配而悄悄改变产物。

### 合并前必须全部通过

1. `testDebugUnitTest`（纯 JVM 单测，含 configfs、媒体布局、下载校验、DHCP 池）与 `lintRelease`。
2. `go test -race ./...` 与 `gofmt`、`go vet`。
3. `assembleRelease` 成功，AAR 覆盖两个发布 ABI。
4. **导出的 Room schema 必须已提交**：`git diff --exit-code -- app/schemas` 干净；
   migration 必须显式且可测，生产构建禁止 destructive migration。
5. 新功能带完整中英文资源、加载/空/错误/恢复交互与自动化测试。
6. Root/configfs 改动必须经过安全边界审查。
7. **不降低测试、不关闭警告、不加临时兼容分支来换取通过。**

### 测试策略

- **CI 只跑纯 JVM 单测与 Go 测试**；需要设备的 instrumented 测试（数据库、native 媒体生成）
  由开发者在真机/模拟器上跑，见上表命令。
- `root/` 相关测试必须**注入测试根目录**，针对临时目录执行；**CI 永不触碰真实 `/config`、
  `/sys`、`/dev`**。
- 静态门禁扫描特权源码，出现 `/dev/block`、分区名、`dd`、`mkfs`、`setenforce`、可写 mount、
  `persist.*` 即失败。
- 真机只在专用设备上执行：验证映射、PC UEFI 枚举、只读属性、拔线、应用停止、主进程崩溃、
  授权撤销与恢复原 USB 组合。**真机测试不得写分区、不得改 SELinux 全局状态、不得把测试
  镜像指向块设备。**

---

## 7. 发布

发布流水线在 `.github/workflows/build.yml`，**仅支持手动触发**（`workflow_dispatch`），
必须输入发布 tag。

一次运行内按序完成：静态检查 → 单元测试 → R8 Release → PKCS#12 签名与验签 →
`softprops/action-gh-release` 通过 API 创建 release 与 tag 并发布**签名 APK 与 mapping 文件**。
不要改回 `git tag` + `gh release create`：runner 上没有任何 git 身份，`git tag --annotate`
会直接以 `empty ident name` 失败。

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