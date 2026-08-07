# System Download Accelerator

面向 Android 系统 `DownloadProvider` 的 LSPosed API 102 模块。当前候选版本为 **0.3.0-alpha15**。

## 当前实现

- Android 管理界面、首次运行设置和 Root 授权探测。
- 下载确认、默认目录、持久 URI 授权和下载历史。
- Android 16 Live Update 下载通知及暂停/继续/取消/重试控制桥。
- ColorOS DownloadProvider 目标方法识别与有界并行 HTTP Range 下载。
- 运行状态回传：`active`、`fallback`、`complete`、`error`、`disabled`。
- LSPosed 作用域固定为 `com.android.providers.downloads`。
- 可选 Magisk / KernelSU / APatch 包装模块和只读 Rust 一次性诊断器。

## Alpha 15：续传对齐硬化

Alpha 15 不允许仅凭 HTTP 206 或 Content-Length 猜测当前写入位置。进入并行 Range 前必须证明**系统原请求、服务器响应和本地续传窗口对齐**：

1. 在 ColorOS 配置原始 `HttpURLConnection` 时记录原始 `Range` 请求状态。
2. 如果该状态无法可靠读取，直接使用系统下载，不进入加速。
3. 新下载仅允许“原请求无 Range + HTTP 200 + 已知长度”。
4. 续传仅允许“原请求存在单一 Range + HTTP 206”。
5. `Content-Range` 起点必须与原请求 Range 起点完全一致。
6. 原请求带 Range 但服务器降级为 HTTP 200 时强制回退，禁止把响应错误解释为从 0 开始的新任务。
7. 资源仍必须具有强 ETag 或可验证 Last-Modified，并通过严格单字节 206 探测。

这些规则由 `RangeProtocol` 纯 Java 层实现，并由 JVM 单测覆盖，运行时代码直接复用同一套偏移与分片几何逻辑。

## Range 数据路径

当前引擎不会让 worker 直接写 DownloadProvider 的目标文件：

1. 系统建立原始连接并进入 ColorOS copy loop。
2. Alpha 15 完成续传对齐、长度、编码、验证器和 206 探测预检。
3. 2–16 个 worker 下载不重叠的 Range 分片到 DownloadProvider **私有 cache 目录**中的有界 spool。
4. 分片按字节顺序组合成单一 `InputStream`。
5. 该流交回 ColorOS 原始 copy loop；目标文件始终只有系统一个写入者。
6. 预检失败时，在任何加速字节进入系统写入路径前直接回退原传输。
7. 运行中故障交回 DownloadProvider 自身重试/续传机制，避免模块自行抢写目标 FD。

历史 Alpha 12 的并发 `pwrite` 方案已从当前编译树删除。

## 调度与资源限制

- 最高线程数：2–16，默认 8。
- 初始线程数：2–最高线程数，默认 4。
- 加速阈值：8–4096 MiB，默认 32 MiB。
- 分片大小：4–64 MiB，默认 16 MiB。
- 临时分片只维持有限预取窗口。
- spool 固定在 DownloadProvider 私有 cache，不再依赖 `java.io.tmpdir` 或进程工作目录。
- 会话启动前检查可用空间，并额外保留 64 MiB。
- worker 对 ColorOS 连接配置方法的调用由模块专用锁串行化，不锁 vendor owner 对象，避免潜在 monitor 死锁。
- 运行时无法读取模块偏好时默认关闭加速，系统下载路径优先。

## 自动验证

CI 当前执行：

- 源码/Manifest/Xposed 作用域安全门；
- `RangeProtocolTest` JVM 单测；
- Debug 与 Release APK 全量重编；
- Android Lint；
- APK 签名和 Xposed 元数据验真；
- Rust 1.86 ARM64 诊断器交叉编译；
- Root 模块 ZIP 结构、内嵌 APK 字节一致性与 SHA-256。

JVM 单测至少覆盖：

- 新任务 HTTP 200 合法路径；
- 续传 Range 被服务器降级为 HTTP 200 的拒绝；
- 无法读取原 Range 请求状态时 fail-closed；
- HTTP 206 起点与原请求不一致的拒绝；
- 多 Range / 畸形 Content-Range 的拒绝；
- worker 数量不超过分片数或硬上限；
- 分片边界连续且最后一块正确收尾。

## 仍需真机验证

CI 不能代替 Android 16 / ColorOS 真机。合并前仍需检查：

- 当前 ColorOS 补丁版本的 `d/e`、`g/u/t/s` 混淆结构是否匹配；
- 新下载和已有部分文件续传都能正确进入/退出 Range 模式；
- Range→200 降级时确实只走系统路径且文件不损坏；
- 支持 Range 的大文件存在多个并行连接且最终 SHA-256 与单线程参考文件一致；
- 不支持 Range、未知长度、压缩响应、DRM、缺少稳定验证器时正确回退；
- 断网、暂停、取消、下载服务重启、设备重启后的续传；
- spool 在完成、失败、取消后正确清理；
- Live Update 与 Root 包装模块覆盖升级。

## 构建环境

- JDK 17
- Gradle 8.13
- Android Gradle Plugin 8.13.2
- Android SDK 36
- Android SDK Build Tools 35.0.0
- Android NDK 27.2
- Rust 1.86

### 构建 APK 与单测

```bash
gradle --no-daemon \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  :app:assembleRelease \
  :app:lintDebug
```

### 构建完整候选

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
bash ci/build-module.sh
```

输出：

```text
SystemDownloadAccelerator-0.3.0-alpha15-debug.apk
SystemDownloadAccelerator-0.3.0-alpha15.apk
SystemDownloadAccelerator-0.3.0-alpha15-module.zip
APK-CERTIFICATES.txt
SHA256SUMS.txt
```

Alpha Release APK 当前仍使用 CI 调试签名，仅用于测试。正式可持续覆盖升级的发布版必须使用固定、受保护的发布签名。

## 安装

常规 LSPosed 测试：安装 APK，在 LSPosed 中只勾选 `com.android.providers.downloads`，然后重启目标进程或设备。

Root 包装模块：从 Magisk、KernelSU 或 APatch 管理器安装模块 ZIP。签名兼容时仅升级更低版本；签名不一致时保留原应用和数据，并记录 `signature-mismatch`。

## 源码恢复

恢复记录见 [`docs/SOURCE-RECOVERY.md`](docs/SOURCE-RECOVERY.md)，当前 Range 架构见 [`docs/REAL_MODULE_IMPLEMENTATION.md`](docs/REAL_MODULE_IMPLEMENTATION.md)。
