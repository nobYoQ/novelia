# 网络与原站同步

[网络目录](README.md) · [文档首页](../README.md)

客户端连接三类服务：小说原站或镜像、独立论坛、用户自己的 WebDAV。它们共享部分底层能力，但账号、请求和同步规则不能混用。

## 请求从哪里发出

| 服务 | 入口 | 认证与线路 |
| --- | --- | --- |
| 小说 | [NoveliaApi](../../app/src/main/java/cc/novelia/app/data/network/NoveliaApi.kt) | `https://n.novelia.cc/api/`，可切换镜像；小说会话 |
| 论坛 | [ForumApi](../../app/src/main/java/cc/novelia/app/data/network/ForumApi.kt) | `https://forum.novelia.cc/api/v1/`；独立论坛会话 |
| 论坛账号记录 | [ForumAccountApi](../../app/src/main/java/cc/novelia/app/data/network/ForumAccountApi.kt) | 认证服务的 `/api/v1/me/...`；论坛令牌 |
| WebDAV | [data/webdav](../../app/src/main/java/cc/novelia/app/data/webdav) | 用户指定 HTTPS 服务；独立凭据和客户端 |

这些地址是当前客户端配置，不是本轮在线可用性验证。登录与退出见[认证](authentication.md)，镜像配置见[书源线路](../development/book-source-mirrors.md)，论坛请求见[论坛接口](forum-api-preview.md)。

[NoveliaApplication](../../app/src/main/java/cc/novelia/app/NoveliaApplication.kt) 创建应用级 API 和传输。小说请求先经 `BookSourceInterceptor` 绑定线路，再按目标与设置选择传输。论坛不经过小说镜像路由；WebDAV 不共享原站 Cookie、Bearer 或 ECH。ECH 的诊断和原生入口见[网络诊断](network-diagnostics.md)。

## 选用哪个调用入口

| 入口 | 用途 |
| --- | --- |
| `api.get<T>` / `request` | 普通 API 读取或直接写入 |
| `webList` / `wenkuList` / `cloudFavorites` | 有约定参数的小说列表 |
| `c.detail<T>(path, forceNetwork)` | 带元数据缓存的小说详情 |
| `c.chapter(ref, id, forceNetwork)` | 本地文档或网络章节 |
| `c.cloudMutation(...)` | 允许离线排队的原站收藏和历史操作 |
| `api.uploadVolume(...)` | 文库 multipart 上传 |
| `DownloadWorker` | 后台流式下载和文件提交 |

路径段用 `encodeSegment`，查询参数用 URL 构造器。下载的 `translations` 是重复参数，不能压成单值 Map。小说列表中空 provider 与云端收藏中空来源选择的含义不同，应沿各自接口处理。

[HttpCalls.kt](../../app/src/main/java/cc/novelia/app/data/network/HttpCalls.kt) 把协程取消传给底层 Call，并关闭响应。普通 API 关闭自动重定向；下载、图片使用自己的重定向策略，不能把一种配置概括成全应用行为。

## 请求与缓存都要检查身份

鉴权请求捕获 `SessionBinding`，获取令牌、刷新和提交结果时检查同一绑定。401 最多刷新并重发一次，退出的会话不会因匿名请求自动续登。认证助手不替任意用户 URL 判断可信目标，调用方必须限定用途。

小说详情缓存按**来源、账号和 API 路径**区分，通常优先使用五分钟内且晚于最近写操作的缓存。`forceNetwork` 跳过快速命中；普通 I/O 错误可回退缓存，401/403/404 等 API 错误保留原义。

成功写操作通知元数据缓存失效。响应回写还要核对会话、缓存代次和请求期间的写入时间。远端已经写成功而本地失效处理失败，不能诱导用户再次提交同一帖子。

## 章节缓存和预读

网络正文缓存按书籍和章节保存，属于设备共享资料，不随退出账号删除。在途请求则包含会话、来源和 `cacheGeneration` 等身份。

[SharedRequest](../../app/src/main/java/cc/novelia/app/data/network/SharedRequest.kt) 合并相同章节请求：一个等待者离开不影响其他人，最后一个离开才取消底层任务。清缓存使旧代次失效，阻止迟到响应回填。

`c.chapter` 默认优先缓存，强制网络刷新失败会报错。前台得到正文不保证缓存落盘成功。显式离线批次只有确认写盘后才计数：

| 入口 | 当前策略 |
| --- | --- |
| 自动预读 | 沿 `nextId` 顺序读取，最多五章，失败不阻断正文 |
| 手动缓存 | 每批最多 200 章、最多三个 worker；复用已有缓存，取消或失败后保留已完成部分 |
| 译文新鲜度 | 使用单独记录的获取时间，不用文件访问时间代替 |

实现见 [ChapterOffline.kt](../../app/src/main/java/cc/novelia/app/data/chapters/ChapterOffline.kt)。章节预读的“仅 Wi-Fi”检查 Wi-Fi transport；文件下载的同名设置使用 WorkManager `UNMETERED`，即非计费网络。

## 哪些操作可以离线排队

只有经过 `cloudMutation` 的可重放 PUT/DELETE 才进入 [CloudMutationQueue](../../app/src/main/java/cc/novelia/app/data/sync/CloudMutationQueue.kt)。帖子、评论、上传、新建收藏夹和其他直接 API 操作不自动进入队列，论坛收藏也不使用这套原站队列。

```text
捕获会话 → 按账号和资源加锁 → 记录本地意图 → 尝试发送
                                               ├─ 成功：移除待办
                                               └─ 可重试失败：保留待办
持久化待办 → WorkManager → 校验原账号 → 重放
```

同一本书的加入、移动和取消收藏共用资源顺序；新意图替换旧意图。后台拿锁后还要确认待办 ID 仍存在。不同资源可以独立处理。

先记录再发送是为了应对“服务端已处理，但响应丢失”的情况，因此操作必须可安全重复。前台发送前并非每次都等待写盘；后台入队前会等待 `flush()`。这不是跨网络与磁盘的原子事务。

## 失败与后台调度

| 结果 | 已有待办重放时的处理 |
| --- | --- |
| 网络 I/O、408、429、5xx | 保留，结束本轮，稍后重试 |
| 401 | 保留并提示重新登录，停止本轮 |
| 403、404、409 等其他 API 错误 | 标记需人工处理，继续其他资源 |
| 会话变化 | 结束旧账号这一轮 |
| 协程取消 | 继续传播取消 |

前台第一次提交遇到不可重试 API 错误时，会移除该次意图并报错；不要与上表的重放处理混淆。

[BoundCloudSync](../../app/src/main/java/cc/novelia/app/data/sync/BoundCloudSync.kt) 串行化整轮同步，单次最多处理 100 项。自动模式跳过已阻塞项，手动模式允许重试。任务结束时刷新状态，避免成功移除的待办再次出现。

自动同步会观察待办和会话，短暂合并后先落盘再入队；另有 15 分钟周期兜底。WorkManager 可延迟执行，周期不表示准确的执行时刻。关闭自动同步保留待办和手动入口；账号 A 的待办不会发给账号 B。

书籍更新检查是另一套 Worker，开启后按六小时周期检查本地收藏的在线作品，记录章节、译文或分卷变化；它不检查 APK 新版本。

## 下载与验证

下载使用独立的两个调度槽，流式写入临时文件，再检查账号、任务 ID、workId 和暂停状态后提交。普通失败等待手动重试，重新开始从头下载，没有 Range 续传。镜像路由和重定向会剥离跨来源凭据。完整任务规则见[文件与下载](../features/files-and-downloads.md)。

修改网络优先看 `ApiContractTest`、`SessionIsolationTest`、`SharedRequestTest`、`CloudMutationQueueTest` 和 `BoundCloudSyncTest`。目录与命令见[测试指南](../quality/testing.md)。MockWebServer 验证客户端协议，真实 WebView、Keystore 和服务端兼容性另行验收。
