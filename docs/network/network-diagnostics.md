# 网络诊断与 ECH 日志

[网络目录](README.md) · [文档首页](../README.md)

“设置 → 下载与同步 → 网络诊断与日志”用于区分 DNS、连接、TLS、HTTP 和正文解析故障。它比较直连与 ECH，不自动改变线路或关闭证书校验。

## 收集一次可用的故障记录

1. 保持出错时的网络/VPN 状态，运行诊断；结果逐项显示，整轮最长约三分钟。
2. 只有某个页面或下载失败时，开启“记录问题复现过程”，复现一次后停止。
3. 导出网络日志 ZIP；对比不同网络或 ECH 设置时分别导出。

诊断可取消，关闭面板不会自动停止。详细业务记录默认关闭，最多持续 30 分钟，重启进程后关闭；主动诊断不依赖它。日志在禁止备份的私有目录轮换，两个请求日志文件合计不超过 1 MiB，另存诊断摘要，不自动上传。

## 实际检测什么

检测匿名访问小说、认证和论坛，以及小说列表、文库列表、论坛分类和帖子列表。每项各测直连与 ECH，共 14 项、最多两个并发。单请求总时限 35 秒，整轮 180 秒，响应检查上限 2 MiB。

诊断使用业务同款拦截器和原生传输，但有独立连接池；不携带用户凭据，不下载完整小说。401/403/429、JSON 不兼容、正文读取失败与 TCP/TLS 失败分别呈现。请求成功不能证明登录权限或文件保存成功。

旧版独立 Probe 的缓存、握手和超时方式与业务请求不同，其单次超时不能代表当前业务不可用。当前诊断直接比较实际传输路径。

## ECH 的实现入口

| 部分 | 入口 |
| --- | --- |
| Android 客户端与拦截器 | [EchTransport.kt](../../app/src/main/java/cc/novelia/app/data/network/EchTransport.kt)、[EchInterceptor.kt](../../app/src/main/java/cc/novelia/app/data/network/EchInterceptor.kt) |
| 原生桥接 | [EchNativeEngine.kt](../../app/src/main/java/cc/novelia/app/data/network/EchNativeEngine.kt) |
| Go 传输、解析和诊断 | [native/ech](../../native/ech) |
| 诊断编排与日志 | [EchDiagnostics.kt](../../app/src/main/java/cc/novelia/app/data/network/EchDiagnostics.kt)、[NetworkLogStore.kt](../../app/src/main/java/cc/novelia/app/data/network/NetworkLogStore.kt) |
| 固定工具链与构建 | [ech-native.properties](../../gradle/ech-native.properties)、[构建指南](../development/getting-started.md) |

解析器从同一 DoH 提供者取得 ECH 配置及地址，多地址错峰连接。缓存遵守 DNS TTL 且最多五分钟；网络变化取消旧解析。原生 ECH 不使用 Android 系统 HTTP 代理，设备 VPN 仍可能影响流量。

派生客户端在插入应用拦截器后调用 `echCallTimeout()`，确保总计时在最前面，覆盖拦截器、重定向和正文读取。自动连接重试只适用于尚未发出 HTTP 字节的无请求体 GET/HEAD；不为写操作增加重放。

## 网络切换与连接恢复

应用级 `AndroidNetworkMonitor` 监听默认网络、网络能力、链路属性和系统代理通知。`NetworkChanges` 合并 400 毫秒内的事件，忽略旧网络迟到的回调；DNS、路由、VPN、地址或代理变化才更新连接代次。验证状态变化更新可用性，不单独清理连接池；`onAvailable` 本身不代表网络已就绪。

`NetworkConnections` 为新请求选择新一代 OkHttp 连接池，Go ECH 同时更换传输实例并清理解析状态。旧连接中正常进行的传输可以完成，结束后清理旧池。小说、论坛、会话、图片、站内网页、WebDAV 和更新检查通过 `newNetworkCall()` 接入；派生客户端仍共享同一代连接池。WebDAV 和公开更新服务保持独立的认证与传输策略，系统 DownloadManager 管理的 APK 下载不由此接管。

只有 `NoveliaApi.request()` 中完整读取到内存的无请求体 GET/HEAD 允许切网重建。旧代次读取在切网后连续约三秒没有进展时取消；确认新网络就绪后最多重建一次，等待新网络最多五秒。普通 HTTP 错误、证书/ECH 验证错误、用户取消、写操作和流式下载不自动重放。没有待处理的旧代次读取时不运行停滞检查定时器。这不是通用离线队列或下载续传机制。

系统 HTTP 代理变化会更新连接状态，但原生 ECH 仍不使用该代理；此行为与 VPN/TUN 接管流量不同。

## 怎样读导出的 ZIP

| 文件 | 内容 |
| --- | --- |
| `diagnosis.txt` | 最近一轮摘要 |
| `environment.json` | 导出时版本、设备、ABI 和网络配置摘要 |
| `network-current.jsonl` / `network-previous.jsonl` | 一行一个结构化事件 |

按 `id` 关联请求，`run` 关联诊断轮次，`scope` 区分诊断与业务。`target` 是固定接口类别，不是完整 URL。原生事件会批量回收，桥接采集的 `timeMs` 不代表每个阶段实际发生时刻；单次交换按 `exchange`、`elapsedMs` 和 `durationMs` 分析。

`networkGeneration` 关联网络切换事件与请求所属代次，仅在进程内递增；不包含网络标识、DNS 地址或代理详情。

| 失败位置 | 可推断的范围 |
| --- | --- |
| 直连 dns | 系统解析路径 |
| native.doh_ech | 尚未取得匹配的地址/ECH 配置 |
| tcp / native.tcp | 建立连接 |
| tls / native.tls / native.ech | TLS、证书或 ECH 协商 |
| response_headers 之后 | 正文读取、容量或解析 |
| call=closed | 提前关闭，不能当完整下载 |
| native.connection=reused | 本次用了旧连接，不能证明新连接仍可建立 |

日志不保存账号、令牌、Cookie、请求/响应正文、完整 URL、搜索词、SSID、本机 IP 或设备序列号；可记录选中的服务器公网 IP，私网只记分类。日志拥塞或写盘失败不阻断业务，导出会记录丢弃数量。

## 验证与判断边界

`:app:testEchNative` 在 Go 层验证主包和本地解析器依赖；它不执行 Android JNI 运行时，真实桥接另用设备测试。JVM 覆盖拦截器、超时、匿名检测、日志脱敏和取消。开关与命令见[测试指南](../quality/testing.md#可选真实网络验证)。

“仅直连可用”或“仅 ECH 可用”只能证明两条路径有差异。继续比较失败阶段、地址族、代理和网络环境，不凭一次超时断言域名被封锁或服务端故障。历史某设备成功也不能代替故障设备的日志。
