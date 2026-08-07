# Alpha 15 Android 16 / ColorOS device validation

本页定义 `0.3.0-alpha15` 在合并 PR #13 前的真机验收标准。CI、JVM HTTP 合约测试和 APK 构建全部通过仍不能替代这些测试。

## 工具

仓库提供：

```text
tools/alpha15-device-validation.sh
```

它需要 Root，只读取系统状态、DownloadProvider 私有 cache 的文件列表/大小、模块偏好、网络连接、相关 logcat 与 LSPosed 日志。它不会删除下载文件、卸载应用或修改模块设置。

日志中可能包含系统下载相关包名、文件名、服务器地址等信息。对外分享证据包前先检查隐私内容。

## 基本用法

将脚本复制到手机后：

```sh
su -c 'sh /sdcard/Download/alpha15-device-validation.sh start fresh-range'
```

开始目标下载，至少让任务运行 20–30 秒。期间可手工抓取额外采样：

```sh
su -c 'sh /sdcard/Download/alpha15-device-validation.sh sample'
```

结束并打包：

```sh
su -c 'sh /sdcard/Download/alpha15-device-validation.sh finish /sdcard/Download/test.bin EXPECTED_SHA256'
```

输出：

```text
/sdcard/Download/SDA-Alpha15-Validation-*.tar.gz
```

若暂时没有参考 SHA-256，可省略最后两个参数；至少仍会收集引擎状态、spool、网络和日志。

## 场景 A：Fresh Range download

条件：

- HTTPS/HTTP 大文件，建议 >= 256 MiB；
- 服务器支持单 Range；
- 有强 ETag，或可被当前引擎接受的稳定 Last-Modified；
- 下载前没有同 ID 的部分文件。

预期：

- `engine_status=active`；
- `engine_threads >= 2`；
- sampler 至少一次看到 `sda-range-spool` 中多个 `.part`；
- 完成后 `engine_status=complete`；
- 最终文件 SHA-256 与单线程参考文件一致；
- 完成后当前 session spool 被清理。

失败判定：

- 目标文件 SHA-256 不一致；
- 下载成功但 spool 长期遗留；
- DownloadProvider 崩溃或反复重启；
- `error` 后系统无法继续/重试该任务。

## 场景 B：Resume from partial file

步骤：

1. 先开始一个大文件下载；
2. 下载到 10%–50% 后暂停/断网；
3. 恢复网络或继续下载；
4. 使用新的 validation session 记录续传阶段。

必须证明三方一致：

- 系统原 Range 起点；
- 服务器 `Content-Range` 起点；
- DownloadProvider 目标 FD 当前偏移。

预期：

- 匹配时可进入 `active`；
- 不匹配或本地 FD 偏移不可确认时必须 `fallback`；
- 最终 SHA-256 与参考文件一致。

额外兼容项：

- `Range: bytes=offset-` 允许；
- `Range: bytes=offset-(total-1)` 允许；
- `Range: bytes=offset-middle` 必须回退，禁止被扩展到 EOF。

## 场景 C：Range request downgraded to HTTP 200

使用会忽略 Range，或在 `If-Range` 失配时返回完整实体的服务器。

预期：

- 模块不得把 HTTP 200 当作续传分片；
- `engine_status=fallback`；
- 系统 DownloadProvider 接管；
- 不产生错误拼接和重复字节。

## 场景 D：Pause / Cancel / network loss

分别执行：

- 下载过程中暂停；
- 下载过程中取消；
- 下载过程中切断 Wi-Fi/蜂窝；
- 网络恢复后继续。

预期：

- Range worker 能退出或被系统流程终止；
- 不持续占用连接；
- spool 最终清理；
- 取消后不会继续后台写入；
- 暂停/恢复后最终 SHA-256 正确。

## 场景 E：DownloadProvider process / device restart

至少测试：

- 强制停止下载服务相关进程后恢复；
- 设备重启后继续未完成下载。

预期：

- 历史 spool 不影响新会话；
- 旧 session 目录最终由陈旧清理机制回收；
- 新会话不会误删同一进程中仍登记为 active 的 spool；
- 下载能由系统状态机恢复或明确失败，不出现“表面完成但文件损坏”。

## 场景 F：VPN / proxy / cellular routing

分别在可能的情况下测试：

- 普通 Wi-Fi；
- 蜂窝网络；
- 系统 VPN / TUN；
- 本地代理环境。

重点确认 Range worker 与原 DownloadProvider 使用相同的有效网络路径。当前代码优先复用 ColorOS owner 中可找到的 `Network`，无法获取时退回默认 `URL.openConnection()`；这条路径必须由真机数据决定是否还需要进一步 fail-closed。

## 不支持 Range 的回退矩阵

以下条件均应保持系统原始下载：

- HTTP 200 + 原请求存在 Range；
- HTTP 206 但 `Content-Range` 不匹配；
- HTTP 206 只覆盖中间窗口而不是资源尾部；
- 多 Range；
- Content-Encoding 非 identity；
- 未知总长度；
- DRM 输出流；
- 无稳定验证器；
- 目标 FD 当前偏移不可读取或不匹配；
- spool 空间不足；
- 严格单字节 Range probe 失败。

## 性能记录

完整性通过后再比较性能。至少对同一 URL 做：

1. 系统原始下载；
2. Alpha 15，2 workers；
3. Alpha 15，4 workers；
4. Alpha 15，8 workers。

记录：

- 总字节数；
- 墙钟时间；
- 平均吞吐；
- 是否发生 fallback/error；
- 最终 SHA-256；
- 网络类型。

不要仅以峰值通知速度判断“加速有效”。必须同时满足最终文件完整、系统状态正常以及可重复的总时长改善。

## 合并门槛

PR #13 转 Ready / 合并前至少要求：

- Fresh Range：通过；
- Resume Range：通过；
- Range -> 200 fallback：通过；
- Pause / cancel /断网：通过；
- DownloadProvider 或设备重启：通过；
- 最终 SHA-256：全部正确；
- 至少一轮真实大文件证明并行连接确有总时长收益；
- 无新增 bootloop、DownloadProvider crash loop 或系统下载队列损坏。

在以上条件满足前，Alpha 15 保持 Draft 候选，不标记为稳定版。
