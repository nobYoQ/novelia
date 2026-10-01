# 网络、认证与同步

[返回网络与同步索引](README.md) · [文档总目录](../README.md)

本文记录客户端当前的 HTTP、WebView 登录、缓存和离线写入行为。主要实现位于 [NoveliaApi.kt](../../app/src/main/java/cc/novelia/app/data/network/NoveliaApi.kt)、[Session.kt](../../app/src/main/java/cc/novelia/app/data/auth/Session.kt)、[AppController.kt](../../app/src/main/java/cc/novelia/app/ui/navigation/AppController.kt)。本地持久化与备份另见 [数据模型、持久化与备份](../data/data-and-storage.md)。

## 1. 服务端与调用入口

默认 API 根地址为 `https://n.novelia.cc/api/`，统一认证地址为 `https://auth.novelia.cc`。这些是当前代码配置，不是由 GitHub Release 地址决定的；切换 APK 分发渠道不会切换内容服务端。

`NoveliaApplication` 持有应用级 `Session`、`NoveliaApi` 和 `LocalStore`。Compose 页面通过 `AppController` 使用它们，Worker 也复用应用实例。不要每个页面创建独立 API/云写入队列，否则可能破坏跨页面的写入排序。

| 入口 | 使用场景 |
| --- | --- |
| `api.get<T>` / `request` | 通用 API 请求；`get` 在 Default 调度器解码并观察已返回的标签 |
| `api.webList` / `wenkuList` / `cloudFavorites` | 有明确参数约定的分页列表，默认每页 20 项 |
| `c.detail<T>(path, forceNetwork)` | 带磁盘缓存、写入失效与普通断网回退的详情 |
| `c.chapter(ref, id, forceNetwork)` | 本地或网络章节加载，返回内容与是否来自本地的标记 |
| `c.cloudMutation(...)` | 允许离线保存的收藏/阅读历史意图入口 |
| `api.uploadVolume(...)` | 文库卷文件 multipart 上传，字段名 `jp` |
| `DownloadWorker` | 后台生成文件下载，流式写入和任务归属检查 |

原生 API OkHttp 默认连接超时 20 秒、读取超时 60 秒，关闭自动重定向。`request` 使用 `Accept: application/json`；GET/HEAD 无 body，其他方法默认 JSON，可显式传 `text/plain`。不要把普通 HTTP 请求都当作后台重试任务。

[HttpCalls.kt](../../app/src/main/java/cc/novelia/app/data/network/HttpCalls.kt) 把 OkHttp 回调接到可取消协程：取消会调用底层 `Call.cancel()`，响应在 `use` 中关闭。扩展 HTTP 功能应沿用它，避免仅取消 UI 等待而让响应体继续下载。

## 2. 当前使用的 API 资源

以下是客户端调用导航，不是服务端完整接口规范。请求结构以实际调用文件和 [ApiContractTest.kt](../../app/src/test/java/cc/novelia/app/ApiContractTest.kt) 为准。

| 资源 | 客户端操作 | 主要调用处 |
| --- | --- | --- |
| `novel`、`novel/rank/{provider}`、`wenku` | 搜索、筛选、榜单列表 | [DiscoverScreen.kt](../../app/src/main/java/cc/novelia/app/ui/discover/DiscoverScreen.kt)、[RankScreen.kt](../../app/src/main/java/cc/novelia/app/ui/discover/RankScreen.kt)、`NoveliaApi` |
| `novel/{provider}/{id}`、`wenku/{id}` | 详情和更新检查 | `AppController.detail`、[UpdateWorker.kt](../../app/src/main/java/cc/novelia/app/data/updates/UpdateWorker.kt) |
| `novel/{provider}/{id}/chapter/{chapterId}` | 网络章节和译文 | [ChapterRequests.kt](../../app/src/main/java/cc/novelia/app/data/chapters/ChapterRequests.kt) |
| `user/favored` | 查询网络/文库收藏夹 | [CloudShelf.kt](../../app/src/main/java/cc/novelia/app/ui/shelf/CloudShelf.kt) |
| `user/favored-web/{folder}`、`user/favored-wenku/{folder}` | 收藏列表；增加书籍标识后为加入/移出收藏 | [CloudFavorites.kt](../../app/src/main/java/cc/novelia/app/data/network/CloudFavorites.kt)、`CloudShelf`、[FavoriteSheet.kt](../../app/src/main/java/cc/novelia/app/ui/shelf/FavoriteSheet.kt) |
| `user/read-history`、`user/read-history/{provider}/{id}` | 历史列表、清空历史、提交章节 ID | [HistoryScreen.kt](../../app/src/main/java/cc/novelia/app/ui/shelf/HistoryScreen.kt)、[ReaderScreen.kt](../../app/src/main/java/cc/novelia/app/ui/reader/ReaderScreen.kt) |
| `user/read-history/paused` | 暂停/恢复云端历史 | [HistoryScreen.kt](../../app/src/main/java/cc/novelia/app/ui/shelf/HistoryScreen.kt) |
| `article`、`article/{id}`、`comment`、`comment/{id}` | 社区读取、发布、修改、删除 | [CommunityScreen.kt](../../app/src/main/java/cc/novelia/app/ui/community/CommunityScreen.kt)、[ArticleScreen.kt](../../app/src/main/java/cc/novelia/app/ui/community/ArticleScreen.kt)、[ComposeArticleScreen.kt](../../app/src/main/java/cc/novelia/app/ui/community/ComposeArticleScreen.kt)、[CommentsPanel.kt](../../app/src/main/java/cc/novelia/app/ui/community/CommentsPanel.kt) |
| `novel/{provider}/{id}/glossary`、`wenku/{id}/glossary` | 修改术语表 | [GlossaryScreen.kt](../../app/src/main/java/cc/novelia/app/ui/book/GlossaryScreen.kt) |
| `novel/{provider}/{id}/translation`、`.../wenku-id`、`wenku/{id}` | 资料编辑、关联文库 | [EditBookScreen.kt](../../app/src/main/java/cc/novelia/app/ui/book/EditBookScreen.kt)、[WenkuEditor.kt](../../app/src/main/java/cc/novelia/app/ui/book/WenkuEditor.kt) |
| `novel/{provider}/{id}/file`、`wenku/{id}/file/{volume}` | 生成下载链接 | `NoveliaApi.downloadUrl` |
| `wenku/{id}/volume/{name}` | 上传卷文件 | `NoveliaApi.uploadVolume` |

动态路径段使用 `encodeSegment`，查询参数通过 `HttpUrl` 构造。下载 URL 中 `translations` 是重复参数，不能压成单值 Map；`mode`、`translationsMode`、`type` 和 `filename` 的语义也应保持。云端收藏列表的空来源筛选有意表示“没有选中来源”，不能自动替换为全选；`webList` 的空 `provider` 参数则回退为全部书源，两处语义不同。

客户端没有通用冲突解决协议。例如术语表和文库编辑会在提交前重读并比较，但这不等于服务端原子 compare-and-set；同时编辑仍需尊重服务端最终结果。新增写操作应先确认原站的权限和并发语义。

## 3. WebView 登录与信任边界

[LoginScreen.kt](../../app/src/main/java/cc/novelia/app/ui/account/LoginScreen.kt) 用 `https://n.novelia.cc` 作为本地包装页 base URL，iframe 加载 `https://auth.novelia.cc/?app=n&theme=system`。密码由认证网页处理，原生层通过 Cookie 刷新接口取得访问令牌。

登录 WebView 开启 JavaScript、DOM Storage 和第三方 Cookie；关闭文件访问、Content URI 访问，禁止混合内容。主框架内仅放行 HTTPS 的 `auth.novelia.cc` 和 `n.novelia.cc`；其他 HTTPS 主框架链接交给外部浏览器。子框架的 HTTPS 请求并非同样的两域白名单，不应描述成“全部 WebView 流量仅限两个域”。

自动完成登录采用 WebMessage listener：

1. 包装页脚本仅接受 `auth.novelia.cc` 发来的 `login_success` 消息。
2. 原生 listener 仅在支持 `WEB_MESSAGE_LISTENER` 的 WebView 上注册，允许源为 `https://n.novelia.cc`。
3. 原生回调再次检查主框架、HTTPS、host 和固定消息内容，再触发刷新。
4. 不支持 listener 或自动回调未完成时，用户可点“完成登录”手动刷新。

消息只触发刷新，不携带令牌给原生层。不要用不限定来源的 JavaScript bridge 替换它，也不要直接把网页消息当成已登录证明。

普通原站浏览由 [SiteWebScreen.kt](../../app/src/main/java/cc/novelia/app/ui/web/SiteWebScreen.kt) 承担，它没有原生 JavaScript bridge。链接经 [MarkdownLinks.kt](../../app/src/main/java/cc/novelia/app/data/markdown/MarkdownLinks.kt) 解析：拒绝非 HTTP(S)、无 host 和带用户信息的 URL；有原生页面的链接进入原生路由，其他同站页面留在 WebView，外站链接走外部浏览器。站内判断委托 [SiteUrls.kt](../../app/src/main/java/cc/novelia/app/data/catalog/SiteUrls.kt)，接受 `n.novelia.cc` 与旧域名 `books.fishhawk.top`，同时检查 HTTP(S)、无用户信息，以及未显式指定端口或使用对应默认端口（HTTP 80 / HTTPS 443）。旧域名转换为当前 HTTPS 地址，保留原始编码、查询和锚点；当前域名的 HTTP 地址不会在这里统一升级，应用清单另行禁用明文网络流量。此规则与登录消息的 HTTPS origin 校验不同，修改时应分别测试 URL 解析、主框架导航和消息来源。

## 4. 令牌持久化与账号代次

`Session` 从 WebView `CookieManager` 取得认证 Cookie，请求 `POST /api/v1/auth/refresh?app=n`，附 `Origin: https://n.novelia.cc`。认证客户端关闭重定向；成功后更新访问令牌和响应中的 Cookie。

访问令牌以 AES/GCM 加密后存入名为 `session` 的 SharedPreferences，密钥由 Android Keystore 的 `novelia.session` 别名管理。客户端解析 JWT payload 中的用户名、角色、注册时间和过期时间供 UI 使用；这段解析不是客户端验签或服务端权限授权。`Profile.canPost/canEdit` 只是界面资格提示，实际请求权限仍由服务端检查。

[SessionState.kt](../../app/src/main/java/cc/novelia/app/data/auth/SessionState.kt) 定义 `SessionBinding(account, generation)`。请求在开始时捕获 binding，之后使用 `tokenFor(binding)` / `ensureCurrent(binding)` 检查：

- 退出再登录同一用户名也属于新代次，旧请求不能继续提交。
- 普通 401 刷新不能把原账号请求改绑到另一个账号。
- 刷新提交检查原 binding，旧刷新不能覆盖后来的登录。
- 注销先清本地状态、提升代次、移除 Cookie，再请求远端 logout；慢响应不能清掉之后的新登录。

令牌刷新由 `Mutex` 合并。若另一个请求已刷新了同一会话的令牌，等待者复用新令牌，不再重复刷新。

`withAuthenticatedResponse` 在响应读取前后验证 binding，401 时最多刷新并重发一次；失败或第二次 401 抛 `ApiException(401)`。它移除调用方原有 Authorization 并附当前 binding 的 Bearer。这个函数自身不做请求目标域名白名单，调用方必须先限定目标；不要将任意用户 URL 直接送进认证请求助手。

## 5. 缓存与章节请求

### 详情元数据

`c.detail<T>` 的缓存键为 `SHA-256("账号或guest:API路径")`。默认优先使用五分钟内、且晚于最近远端写入的缓存；强制刷新绕过此快速命中。读取失败仅在普通 `IOException` 时回退缓存，`ApiException`（包括 401/403/404）会保留错误，不用旧数据掩盖权限和资源变化。

成功远端写入后，`NoveliaApi.recordMutation()` 更新内存时间，并通知 [MetadataCache.kt](../../app/src/main/java/cc/novelia/app/data/cache/MetadataCache.kt) 保存失效时间。缓存写回还检查请求期间的写入时间、当前账号和缓存代次。已成功的远端写入不会因本地缓存失效失败而被重新报告为写入失败，避免用户重复发帖。

### 章节与预取

章节磁盘缓存按书籍键/章节 ID 保存，不按账号分目录。它是设备共享阅读缓存；不要宣称退出登录会清除以前获取的正文。网络中的请求身份则含账号和登录代次，防止切换账号后继续提交旧请求结果。

`ChapterRequests` 使用 [SharedRequest.kt](../../app/src/main/java/cc/novelia/app/data/network/SharedRequest.kt) 合并相同 `api + binding + cacheGeneration + book + chapter` 的同时请求。一个订阅者离开不会取消其他订阅者；最后一个离开时取消底层任务。清缓存会取消旧代次，写回时也再次检查代次。

`c.chapter` 默认先用缓存；`forceNetwork = true` 时必须从网络刷新，失败不会伪装成刷新成功。成功响应即使缓存写盘失败也可以供前台阅读，因此前台读到正文不等于已经具备离线副本。

[ChapterOffline.kt](../../app/src/main/java/cc/novelia/app/data/chapters/ChapterOffline.kt) 的显式缓存批次最多 200 章，去重后通过 [ChapterBatch.kt](../../app/src/main/java/cc/novelia/app/data/chapters/ChapterBatch.kt) 的最多三个 worker 并行处理，已有缓存直接复用。每章检查会话与缓存代次，新增网络请求前检查网络策略，确认章节已写入磁盘后才计入完成数；进度回调串行递增。失败或取消停止整批，成功缓存的章节保留，重试会跳过它们。自动预取仍沿 `nextId` 顺序读取最多 5 章，并防止循环；两种入口都沿用共享章节请求。

注意“仅 Wi-Fi”存在两种当前语义：章节预取检查 Wi-Fi transport；文件下载的 WorkManager 约束使用 `UNMETERED`（非计费网络）。不要将它们在文档或界面实现中视为完全相同的条件。

## 6. 离线写入队列

收藏与阅读历史通过 `c.cloudMutation` 构造带 UUID、账号、方法、相对路径、body 和 Content-Type 的 `PendingAction`，交给应用级 [CloudMutationQueue.kt](../../app/src/main/java/cc/novelia/app/data/sync/CloudMutationQueue.kt)。

```text
用户操作 → 捕获 SessionBinding → 按账号/资源加锁
         → PUT/DELETE 先更新本地意图
         → 执行认证请求
         → 成功移除待办 / 可重试故障保留待办
         → 持久化后由 CloudSyncWorker 重放
```

只有经过这个入口的 PUT/DELETE 可以离线排队。POST 不排队；发布文章、评论、新建收藏夹、资料编辑等直接 API 调用也没有自动离线重放。不能仅因某接口方法是 PUT 就认为现有产品会离线保存它。

排序键由“账号 + 资源路径”组成。收藏的资源键忽略收藏夹段，所以同一本书在收藏夹之间移动、加入、删除都串行，新意图取代旧意图。同账号的独立资源不会共用这一把资源锁。重放取得锁后重新确认待办 ID 仍存在，防止发送已被新操作替代的旧值。

先记录再发送是为应对“远端已成功、协程随后取消”的不确定结果；下一次可能重复发送同一幂等写入。因此新增可排队操作必须确认重复执行安全，不能把发帖、计数累加或支付类操作套入这套机制。当前流程不是网络和本地文件的分布式事务，也不保证任意时刻进程崩溃都没有待落盘窗口。

[CloudSync.kt](../../app/src/main/java/cc/novelia/app/data/sync/CloudSync.kt) 的失败分类为：

| 故障 | 行为 |
| --- | --- |
| 普通网络 IO、408、429、5xx | 保留并自动重试；本轮停止继续发送，避免冲击服务端 |
| 401 | 标记需要登录，停止本轮；等待重新登录 |
| 403、404、409 等其他 API 错误 | 重放时保留并标为需人工处理，跳过该资源继续其他项 |
| `SessionChangedException` | 立即结束当前账号的运行，保留其他尚未处理意图 |
| 协程取消 | 传播取消，不转换成普通失败提示 |

前台首次提交遇到非可重试 API 错误时会移除该次意图并抛错；上表“保留并标记”针对已有待办的重放。两者不能混为一谈。

## 7. 后台调度和手动同步

[CloudSyncWorker.kt](../../app/src/main/java/cc/novelia/app/data/sync/CloudSyncWorker.kt) 的 `synchronizePending` 由 [BoundCloudSync.kt](../../app/src/main/java/cc/novelia/app/data/sync/BoundCloudSync.kt) 串行化完整同步运行。等待锁前后都验证原 binding，等待期间换账号不能悄悄改变操作归属。

- 单次重放最多处理 100 项，仅选择 binding 所属账号，也可按书籍键筛选。
- 自动模式跳过已阻塞的操作；手动模式允许所选操作再次尝试。
- 成功移除待办，并按账号维护成功时间、错误原因、阻塞 ID 和登录需求；`inFlight` 仅存在于内存，不写入磁盘。
- `finally` 中以 `NonCancellable` 等待 `store.flush()`，保留已完成操作的移除结果。

启用自动同步后，应用观察待办和会话，等待约 750 ms 消除短暂前台写入，先刷新磁盘再安排一次性任务；任务按账号哈希命名、使用 `KEEP`，要求网络连接。另有每 15 分钟的周期兜底任务，指数退避基准为 30 秒。系统可以延迟任务，15 分钟不是精确同步承诺。关闭自动同步会取消相应标签任务，但不删除待办，也不关闭手动同步。

本地资料损坏保护期间不执行同步。账号退出后本地待办仍留在设备，重新登录对应账号后才可能重放。过期对话框中的删除操作也需通过 `removePendingForSession` 校验原代次，避免删除另一次登录的意图。

书架更新是另一个 [UpdateWorker](../../app/src/main/java/cc/novelia/app/data/updates/UpdateWorker.kt)：开启后约每六小时检查、要求网络和非低电量；保存处理游标，每本之间等待 1.5 秒，并持续落盘。它检测章节/译文/分卷变化，不负责检查 GitHub APK 新版本。

后台实现按职责位于 `data/sync/` 与 `data/updates/`，系统通知逻辑独立于 [AppNotifications.kt](../../app/src/main/java/cc/novelia/app/data/updates/AppNotifications.kt)。`data/` 根目录的旧 `CloudSyncWorker`、`UpdateWorker` 是带 `@Keep` 的兼容入口，继承新包中的实现；WorkManager 持久化保存了旧 Worker 全名，升级后仍需能构造这些类。调整后台类名时必须考虑已排队任务，不能只验证新安装调度。

## 8. 文件下载的不同语义

[DownloadWorker.kt](../../app/src/main/java/cc/novelia/app/files/DownloadWorker.kt) 先验证初始 URL 为 HTTPS 的 `n.novelia.cc`，捕获创建者账号与本次会话。下载客户端允许重定向，读取超时为 120 秒；网络图片加载器也允许重定向。因此不能把原生 API 的“关闭重定向”泛化到所有网络流量。

文件下载限制为 512 MiB，流式读取时持续检查取消与会话，拒绝 HTML、空内容和已知长度不匹配的结果。临时文件由 [DownloadFiles.kt](../../app/src/main/java/cc/novelia/app/files/DownloadFiles.kt) 管理，提交前核对任务 ID、WorkManager workId、文件名和暂停状态，防止旧任务覆盖新任务或已删除记录。

一般网络下载失败会显示失败并等待用户重试；重试重新下载，不是 HTTP Range 续传。只有特定持久化失败路径返回 WorkManager `Result.retry()`。不要将下载行为描述为云同步队列同样的自动重试策略。

## 9. 扩展与测试要求

新增一个只读接口时：定义带兼容默认值的响应模型，使用 `api.get<T>` 或明确 binding 的 `request`；用 `encodeSegment` 处理动态路径，查询参数交给 `HttpUrl`；决定是否适合 `c.detail` 的缓存语义；用 MockWebServer 验证路径、编码、参数和错误，不调用真实用户账号。

新增一个云端写操作时：先判断是否幂等、是否允许离线保存，再选择直接 API 或 `cloudMutation`；明确资源排序键以及账号归属；若它属于书籍操作，更新 [BookSync.kt](../../app/src/main/java/cc/novelia/app/data/sync/BookSync.kt) 的路径识别；覆盖账号切换、取消、旧意图被覆盖、403 阻塞和429退避场景。远端成功后的本地更新失败不应诱导用户重复发布。

修改域名或登录流程时，需要一起检查 API 根地址、认证 Origin、包装页、消息来源、下载目标校验、站内链接识别和 Manifest 深链。仅改一个 base URL 无法完成迁移。不要在日志、Issue、截图或测试断言输出中记录真实令牌和 Cookie。

| 验证范围 | 现有测试 |
| --- | --- |
| API 参数、重复查询项、401 | [ApiContractTest](../../app/src/test/java/cc/novelia/app/ApiContractTest.kt)、[CloudFavoritesTest](../../app/src/test/java/cc/novelia/app/CloudFavoritesTest.kt) |
| 刷新/注销/换号隔离 | [SessionIsolationTest](../../app/src/test/java/cc/novelia/app/data/auth/SessionIsolationTest.kt) |
| 同资源排序、新意图覆盖、重放 | [CloudMutationQueueTest](../../app/src/test/java/cc/novelia/app/data/sync/CloudMutationQueueTest.kt) |
| 同步分类、手动与自动模式 | [CloudSyncPolicyTest](../../app/src/test/java/cc/novelia/app/data/sync/CloudSyncPolicyTest.kt)、[CloudSyncRuntimeTest](../../app/src/test/java/cc/novelia/app/data/sync/CloudSyncRuntimeTest.kt)、[BoundCloudSyncTest](../../app/src/test/java/cc/novelia/app/data/sync/BoundCloudSyncTest.kt) |
| 共享请求与取消 | [SharedRequestTest](../../app/src/test/java/cc/novelia/app/data/network/SharedRequestTest.kt)、[NetworkPerformanceTest](../../app/src/test/java/cc/novelia/app/NetworkPerformanceTest.kt) |
| 缓存和阅读边界 | [MetadataCacheTest](../../app/src/test/java/cc/novelia/app/MetadataCacheTest.kt)、[ReaderChapterLoadTest](../../app/src/test/java/cc/novelia/app/ReaderChapterLoadTest.kt) |
| 手动缓存并发、取消和进度 | [ChapterBatchTest](../../app/src/test/java/cc/novelia/app/data/chapters/ChapterBatchTest.kt) |
| 下载提交竞态、删除/重试 | [DownloadFilesTest](../../app/src/test/java/cc/novelia/app/DownloadFilesTest.kt) |
| URL 和 Markdown 路由 | [ReaderAndLinksTest](../../app/src/test/java/cc/novelia/app/ReaderAndLinksTest.kt)、[MarkdownAnchorsTest](../../app/src/test/java/cc/novelia/app/MarkdownAnchorsTest.kt)、[ForumLinksRegressionTest](../../app/src/test/java/cc/novelia/app/ForumLinksRegressionTest.kt) |

真实 WebView Cookie、Android Keystore、不同系统 WebView 版本、后台限制与账号切换还需设备验证。MockWebServer 和纯单元测试验证客户端契约，不能证明生产服务端或认证网页始终维持相同协议。
