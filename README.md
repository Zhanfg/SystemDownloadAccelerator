# System Download Accelerator

面向 Android 系统 `DownloadProvider` 的现代 LSPosed 模块，使用 libxposed API 102。

## Alpha 1

- 可直接启动的 Android 管理界面
- 下载确认弹窗预览与系统 DownloadManager 入队
- 默认目录选择与持久授权
- 系统下载历史查看
- 直接打开文件
- 通过系统选择器交给 MT 管理器、系统文件管理器或其他兼容文件管理器打开目录
- ColorOS Android 16 下载服务 `d.u(HttpURLConnection)` / `e.u(HttpURLConnection)` 安全 Hook
- Hook 异常时保留系统原始下载逻辑

> Alpha 1 的多线程传输替换仍处于安全验证阶段。当前 Hook 只验证目标方法并继续执行系统原始传输，避免破坏系统下载服务。

## 作用域

- `com.android.providers.downloads`

## 构建

```bash
gradle :app:assembleRelease
```

构建依赖 Android SDK 37、Java 21、Gradle 9.3.1。
