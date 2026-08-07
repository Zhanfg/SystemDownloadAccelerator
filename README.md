# System Download Accelerator

面向 Android 系统 `DownloadProvider` 的 LSPosed API 102 模块。当前恢复版本为 **0.3.0-alpha13**，源码已经从历史编码构建载荷还原为普通、可审查的 Gradle 工程。

## 当前实现

- Android 管理界面、首次运行设置和 Root 授权探测。
- 下载确认界面、默认目录选择、持久 URI 授权和下载历史。
- Android 16 Live Update 下载通知、暂停/继续/取消/重试控制桥。
- ColorOS DownloadProvider 目标方法识别与安全 Hook。
- LSPosed 作用域固定为 `com.android.providers.downloads`。
- 可选 Root 包装模块，内含 APK 和只读 Rust 一次性诊断器。

## 重要边界

当前 Hook 仍处于**安全透传模式**：识别到 ColorOS 的 `u(HttpURLConnection)` 后，完整执行系统原始传输并原样返回结果。真正替换系统传输的分段下载协调器尚未启用，因此本版本不能宣称已经提供稳定的多线程加速。

以下内容仍需 Android 16 / ColorOS 真机验证：

- 不同系统补丁版本下的混淆类名和方法签名；
- 下载确认、系统进程重启和异常恢复；
- Live Update 通知权限及控制按钮；
- LSPosed 热加载和作用域行为；
- Root 包装模块升级 APK 的行为。

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
SystemDownloadAccelerator-0.3.0-alpha13-debug.apk
SystemDownloadAccelerator-0.3.0-alpha13.apk
SystemDownloadAccelerator-0.3.0-alpha13-module.zip
SHA256SUMS.txt
```

Alpha Release APK 使用调试签名，只用于测试和升级链验证。正式发布必须切换到受保护且固定的发布签名。

## 安装

常规 LSPosed 测试：安装 APK，在 LSPosed 中只勾选系统下载提供程序，然后重启目标进程或设备。

Root 包装模块：从 Magisk、KernelSU 或 APatch 管理器安装模块 ZIP。模块只在已安装 APK 的版本低于嵌入版本时执行升级，不会降级同版或更高版本；应用数据会保留。

## 安全设计

- Root 探测固定执行 `su -c "id -u"`，不接受外部命令。
- 三个导出的 Provider 都在代码中验证调用 UID。
- 确认令牌使用受限格式和一次性状态。
- 下载历史、源 URL 与本地路径不参与 Android 备份。
- Rust 诊断器仅由模块 Action 手动运行，不驻留后台。

## 源码恢复

恢复过程和被移除的历史构建机制见 [`docs/SOURCE-RECOVERY.md`](docs/SOURCE-RECOVERY.md)。
