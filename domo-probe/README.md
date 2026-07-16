# SDA Domo Probe v0.1.0

这是一个只用于验证 ColorOS Domo 流体云匹配链的 LSPosed 探针，不包含多线程下载引擎。

## 作用域

- `com.android.providers.downloads`
- `com.android.systemui`

## 行为

1. 只匹配下载管理服务的 `active` 通知频道。
2. 原地增强现有通知，不创建第二条通知。
3. 写入 `sda.domo.probe=true` 与 `op_fluid_serviceId=sda:domo:<hash>`。
4. 尝试请求 Android 16 promoted ongoing notification。
5. SystemUI 侧只对带探针标记的通知放行 Domo/Live Alert 过滤器。
6. 使用 ROM 自带 `FluidReplaceNotificationManager.updateFluidMap(...)` 登记通知映射。

## 安装与测试

1. 安装 APK。
2. 在 LSPosed 中启用模块，只勾选“下载管理服务”和“系统界面”。
3. Root Shell 执行：

```sh
am force-stop com.android.providers.downloads
kill -9 "$(pidof com.android.systemui)"
```

4. 触发一项持续至少 20 秒的系统下载。
5. 观察通知副标题是否出现 `DOMO 探针 · 模拟 8 线程`，并检查流体云。
6. 导出日志：

```sh
logcat -d -s SDA-DOMO:V > /sdcard/Download/sda_domo_probe_log.txt
```

同时导出 LSPosed 日志，检索 `SDA-DOMO`。

## 日志判定

- `DownloadProvider notify matched`：下载通知匹配成功。
- `Domo package support forced`：SystemUI 包支持限制已放行。
- `Domo fluid map registered`：原生流体映射已登记。
- `shouldFilter=false`：Live Alert 过滤器已放行。
- `OplusLiveAlert isLiveAlert=true`：通知已进入 Live Alert 判定。
- `addLiveAlert matched`：通知已进入 Domo 卡片添加阶段。
- `Domo fluid show map changed`：SystemUI 报告 Domo 卡片显示状态变化。

## 恢复

出现异常时，在 LSPosed 中禁用本模块，然后只需重启相关进程：

```sh
am force-stop com.android.providers.downloads
kill -9 "$(pidof com.android.systemui)"
```

无需重启整机。
