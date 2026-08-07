# System Download Accelerator

面向 Android 系统 `DownloadProvider` 的 LSPosed API 102 模块。当前候选版本为 **0.3.0-alpha14**，源码已经从历史编码构建载荷还原为普通、可审查的 Gradle 工程。

## 当前实现

- Android 管理界面、首次运行设置和 Root 授权探测。
- 下载确认界面、默认目录选择、持久 URI 授权和下载历史。
- Android 16 Live Update 下载通知、暂停/继续/取消/重试控制桥。
- ColorOS DownloadProvider 目标方法识别与真正的并行 HTTP Range 下载。
- 运行状态回传到管理界面：`active`、`fallback`、`complete`、`error`、`disabled`。
- LSPosed 作用域固定为 `com.android.providers.downloads`。
- 可选 Root 包装模块，内含 APK 和只读 Rust 一次性诊断器。

## Alpha 14 Range 架构

Alpha 13 只有安全透传，不具备实际加速能力；Alpha 14 已替换该实现。

当前引擎不会让多个 worker 直接写入 DownloadProvider 的目标文件。流程为：

1. ColorOS 建立原始 `HttpURLConnection`，模块读取其 URL、进度、网络绑定和请求配置。
2. 在任何加速数据进入系统写盘路径前执行严格预检。
3. 服务器必须提供已知文件总长度，并具有稳定的强 ETag 或可验证的 Last-Modified。
4. 发送单字节 Range 探测，必须得到精确 `206 Partial Content`、精确 `Content-Range`、相同总长度和相同资源验证器。
5. 通过预检后，由 2–16 个并行 worker 下载互不重叠的 Range 分片到**有界临时分片窗口**。
6. 分片按字节顺序组合为一个 `InputStream`，再传回 ColorOS 原始 copy loop。
7. 目标文件始终只有系统原始 copy loop 一个写入者；系统继续负责进度、目标文件语义、fsync、错误传播和续传。
8. 预检失败时，在暴露任何加速字节前直接使用系统原始传输。
9. 加速运行中发生网络/Range 异常时，不在同一调用内抢写回退，而是将异常交回 DownloadProvider 自己的重试/续传机制。

这与历史 Alpha 12 的并行 `pwrite` 方案不同。旧方案已经从编译源码树移除，不会进入 APK。

## 调度与资源限制

- 最高线程数：2–16，默认 8。
- 初始线程数：2–最高线程数，默认 4。
- 加速阈值：8–4096 MiB，默认 32 MiB。
- 分片大小：4–64 MiB，默认 16 MiB。
- 临时分片只预取有限窗口，不会按整个文件大小一次性占用临时空间。
- 启动 Range 会话前检查临时空间，并额外保留 64 MiB 余量。
- 旧版本遗留的 1024 线程配置会在迁移时被限制到新范围。

## 仍需真机验证的边界

CI 能证明源码、APK、Lint、Xposed 元数据和打包链正确，但无法代替 Android 16 / ColorOS 真机验证。合并前仍需检查：

- 当前 ColorOS 补丁版本的混淆类名和方法签名是否仍匹配；
- 支持 Range 的真实大文件是否出现多个并行连接并保持最终 SHA-256 一致；
- 不支持 Range、未知长度、压缩响应、DRM 输出和缺少稳定验证器时是否正确回退；
- 断网、暂停、取消、下载服务重启和设备重启后的续传行为；
- 临时分片是否按窗口受控并在完成、失败和取消后清理；
- Live Update 通知及控制按钮；
- Root 包装模块覆盖升级行为。

## 构建环境

- JDK 17
- Gradle 8.13
- Android Gradle Plugin 8.13.2
- Android SDK 36
- Android SDK Build Tools 35.0.0
- Android NDK 27.2（仅构建 Root 包装模块）
- Rust 1.86（仅构建只读诊断器）

### 构建 APK

```bash
gradle --no-daemon :app:assembleDebug :app:assembleRelease :app:lintDebug
```

### 构建完整候选产物

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
bash ci/build-module.sh
```

输出位于 `dist/`：

```text
SystemDownloadAccelerator-0.3.0-alpha14-debug.apk
SystemDownloadAccelerator-0.3.0-alpha14.apk
SystemDownloadAccelerator-0.3.0-alpha14-module.zip
APK-CERTIFICATES.txt
SHA256SUMS.txt
```

Alpha Release APK 使用 CI 调试签名，只用于测试和升级链验证。`APK-CERTIFICATES.txt` 记录当前候选证书指纹；不同构建之间不保证签名连续。正式可覆盖升级的发布版必须切换到受保护且固定的发布签名。

## 安装

常规 LSPosed 测试：安装 APK，在 LSPosed 中只勾选系统下载提供程序，然后重启目标进程或设备。

Root 包装模块：从 Magisk、KernelSU 或 APatch 管理器安装模块 ZIP。签名一致时，模块只升级版本号更低的 APK，不降级同版或更高版本，并保留应用数据。若设备上的旧 APK 使用不同签名，模块记录 `signature-mismatch` 并保留原应用，不会自动卸载或清除数据。

## 安全设计

- Root 探测固定执行 `su -c "id -u"`，不接受外部命令。
- 三个导出的 Provider 都在代码中验证调用 UID。
- 下载确认与通知控制广播同时使用签名级权限和随机令牌。
- 确认令牌使用受限格式和一次性状态。
- 下载历史、源 URL 与本地路径不参与 Android 备份。
- 历史直接写目标 FD 的 Range 引擎被隔离在 Git 历史中，不进入当前编译树。
- Range 完整性校验和安全回退策略为强制行为，UI 不允许关闭。
- Rust 诊断器仅由模块 Action 手动运行，不驻留后台。

## 源码恢复

恢复过程和被移除的历史构建机制见 [`docs/SOURCE-RECOVERY.md`](docs/SOURCE-RECOVERY.md)。
