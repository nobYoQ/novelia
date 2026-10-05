# 独立论坛 API 预适配

此分支 `codex/forum-api-preview` 的社区入口连接 [测试论坛](https://forum.novelia.cc/c/novel)，主站小说、阅读、下载与小说评论继续连接 `n.novelia.cc`。

## 核对依据

- 核对日期：2026-10-01。
- 测试站公开前端：`/assets/index-CaYVyU6k.js`，内嵌部署提交 `692916b5ecd76313ee2fcacffc8dc309f342784b`，构建时间为 2026-09-30 20:27:27（北京时间）。源码对比基线为之前的 `6c65702`，并以匿名公开 GET 核对实际服务端响应。
- [该部署版本的前端 API 合约](https://github.com/auto-novel/forum/blob/692916b5ecd76313ee2fcacffc8dc309f342784b/apps/web/src/api.ts)。
- [服务端帖子路由](https://github.com/auto-novel/forum/blob/692916b5ecd76313ee2fcacffc8dc309f342784b/apps/api/internal/handler/post.go)、[评论路由](https://github.com/auto-novel/forum/blob/692916b5ecd76313ee2fcacffc8dc309f342784b/apps/api/internal/handler/comment.go)、[认证声明](https://github.com/auto-novel/forum/blob/692916b5ecd76313ee2fcacffc8dc309f342784b/apps/api/internal/httpx/authn.go)。

## 合约变化

| 功能 | 新版合约 |
| --- | --- |
| API 前缀 | `https://forum.novelia.cc/api/v1/` |
| 分类 | `GET category/`，返回数字 `id`、`slug` 和标签；分类 2 的 slug 从 `guide` 改为 `announcements`（站务公告），仅管理员可在此发帖 |
| 列表 | `GET post/`；`category` 传 slug；搜索用 `q`；排序用 `active/newest/views/comments`；标签为逗号分隔 `tag` |
| 分页 | 请求 `page` 从 1 开始，`page_size` 取代 `pageSize`；响应 `{ total, items }`。客户端页码仍从 0 开始，在 API 层换算 |
| 帖子详情 | `GET post/{数字ID}/`；时间为 RFC 3339 字符串；作者改为 `authorId/authorUsername` |
| 发布／编辑 | `POST post/`、`PATCH post/{id}/`；正文为 `{ categoryId, title, content, tagIds }`，返回完整帖子对象 |
| 删除帖子 | `DELETE post/{id}/`，成功可返回无正文的 204；普通作者限发布后 20 分钟内，管理员不受时限影响 |
| 一级评论 | `GET post/{id}/comment`；`total` 只统计一级评论，`items` 不再包含子回复；源码包含可选 `replyCount`，本次线上响应未返回此字段 |
| 子回复 | `GET post/{id}/comment/{rootId}/reply`；使用独立的 `page/page_size` 分页，响应 `{ total, items }` |
| 发布评论 | `POST post/{id}/comment`；回复提交 `rootId`，回复子评论时仍使用其根 ID |
| 修改评论 | `PATCH/DELETE comment/{id}`；编辑只提交 `content`，禁止提交 `rootId`（包括 null）；作者限发布后 20 分钟内修改，管理员不受时限影响 |
| 云端收藏 | `PUT/DELETE post/{id}/favorite`；`GET me/favorite`、`GET me/post` |
| 处罚记录 | 认证服务 `https://auth.novelia.cc/api/v1/me/strikes`，携带论坛令牌；分页参数为 `page/page_size`，返回原因、依据、分值及撤销时间 |
| 登录 | 统一认证应用标识 `f`，Origin 为 `https://forum.novelia.cc`；刷新路径 `/api/v1/auth/refresh?app=f` |
| 网页链接 | `https://forum.novelia.cc/p/{id}` |

新论坛与主站分别保存加密令牌并使用不同的 Keystore 别名。论坛请求拒绝主站会话；401 最多刷新一次。两边的退出操作只清除各自的本机会话，不调用全局 SSO 退出接口，不删除共享认证 Cookie，也不清除另一边的令牌。已退出的会话不会因匿名请求收到 401 而自动登录；用户明确进入登录页后仍可复用统一认证。

处罚记录字段与认证行为依据当前论坛所依赖的 [认证 SDK 90f6980](https://github.com/auto-novel/auth/blob/90f6980ebcbd1ba8a4690dba0e2abe98957ec100/packages/auth-api/src/api.ts) 核对。与上次论坛部署依赖的 `e6909c2` 比较，认证 SDK 的 `api.ts` 内容相同，论坛服务端认证声明也未变化。此接口属于账号记录，不是论坛帖子 API。

## 客户端行为

- 分类及标签使用服务器数据；站务公告排首位并作为首次进入的默认分类，小说讨论和意见反馈随后，未知分类保留。普通用户发帖默认选择小说讨论，分类选项排除站务公告；旧草稿若指向公告分类，保留内容并要求重新选择分类。列表搜索请求服务端。帖子排序提供「最近活跃、最新发布、浏览最多、评论最多」，分别发送 `active/newest/views/comments`；切换排序返回第一页，保留分类和搜索词。
- 新帖 ID 在本地表示为 `f-{id}`，本地收藏及草稿与旧站 ObjectID 区分。旧帖子链接和收藏继续从主站读取；不猜测新旧帖子 ID 对应关系。
- 新建论坛草稿使用 `article:forum-new`；旧版 `article:new` 草稿仍保留，不自动发布到测试站。
- 新版编辑保留帖子的标签，最多选择 3 个，切换分类时清除旧分类标签。标题为 2–100 字，正文为 1–20,000 字，评论为 1–1,000 字；按 Unicode 码点计数，emoji 的代理对按一个字计算。正文保留原始空白，评论按网页行为去除首尾空白后提交。旧的超长草稿保留并可继续缩短，超限时禁止提交。最终权限及域名黑名单由服务端决定；论坛返回的文本校验错误会显示具体原因，失败时保留草稿且不自动重发。
- 论坛的 `trusted`、`member`、`admin` 角色可发言，公告限管理员；主站账号权限规则保持原逻辑。帖子删除和评论修改入口在 20 分钟到期后更新，提交时再次检查权限。
- 一级评论按服务端分页显示，各讨论串的子回复正文展开后独立分页加载。缺少 `replyCount` 时，提前以 `page_size=1` 读取回复接口的 `total`，最多 4 个并发请求，显示「查看 X 条回复」或「暂无回复」；请求失败保留未知状态并允许展开重试。计数保存在列表层，滚出显示区域后回来仍保留；刷新及账号／角色变化时重新核对。发布子回复后定位该串末页。隐藏／删除评论默认显示占位文本，已有子回复仍可查看，已隐藏／删除的根评论不能继续回复。管理员可展开服务端返回的原文，切换账号或评论状态时收起。
- 新论坛 Markdown 评分按半星四舍五入，接受非负十进制数并限制到 5；主站与旧文章的评分精度保持原行为。共享 TypeScript 包迁移本身不要求 Android 安装对应依赖。
- 右上角「我的」使用锚定展开面板，集中放置我的帖子、云端收藏、处罚记录、本地收藏和论坛登录／退出。面板支持缩放淡入淡出、返回键／外部点击收起、滚动和大字号，遵循减少动态效果设置。
- 云端收藏和我的帖子沿用各自接口的排序；四种帖子预设用于「全部帖子」。帖子列表同时显示评论数与查看量。处罚记录显示原因、分值、依据、生效／撤销状态及时间，不持久缓存账号处罚数据。社区守则使用原生 `forum-rules` 页面，可从「我的」及首次提示进入；打开时核对线上部署并更新守则，支持手动更新及离线副本，同步失败明确提示。关闭首次提示后持久隐藏，评论输入辅助条不重复显示。
- 新域名帖子链接可进入原生页面；带锚点、分类筛选或编辑路径的链接在站内 WebView 中保留完整 URL。
- 服务端还提供 `external/comment/novel/{subjectKey}` 及 `external/comment/novel/{subjectKey}/{rootId}/reply`。已提供显式 subject key 的只读接口；本次核对未确认主站 subject key 的迁移映射，当前小说评论保持原接口。

## 验证

- 本次对测试站三个分类各抽查两个帖子的列表、详情、一级评论和子回复，全部为匿名公开 GET；未创建帖子、评论或修改收藏。实际响应的一级评论没有 `replyCount`，客户端已兼容该部署差异。
- `ForumApiContractTest` 使用 MockWebServer 覆盖分页／搜索、数字 ID、时区与小数秒、创建／PATCH 请求体、标签、一级评论与子回复独立分页、可选 64 位回复数量、204、云端列表、会话隔离、401 刷新上限、503 响应不重试、旧收藏及新旧链接。
- `ForumAccountApiTest` 覆盖四种排序参数、筛选与分页保留，以及使用论坛令牌访问认证服务处罚记录的字段解析和权限边界。
- 初始预适配验收（历史）：93 项 JVM 测试全部通过；Debug APK、Android 测试 APK 构建成功；`lintDebug` 通过，0 个错误、25 个警告。本次结果见末尾 2026-10-01 记录。
- 初始设备验收（历史）：`ForumAccountUiTest` 与 `ForumSessionIsolationTest` 共 6 项 Android 回归测试通过，覆盖排序选择、个人面板操作与返回键、处罚记录展示，以及双向退出隔离和退出状态持久化。会话测试使用独立存储与合成凭据。
- 初始布局验收（历史）：在 375dp 宽模拟器上核对浅色／深色、减少动态效果；另外两次布局测试覆盖横屏和系统双倍字号，均通过并检查截图。
- 构建与离线验证：`./build.ps1 -Tasks @('assembleDebug', 'assembleDebugAndroidTest', 'testDebugUnitTest', 'lintDebug') -Offline`。
- 可选 Android 只读联调：安装测试 APK 后使用 `adb shell am instrument -w -r -e live true -e class cc.novelia.app.integration.ForumLiveReadOnlyTest cc.novelia.app.test/androidx.test.runner.AndroidJUnitRunner`。

测试站仍在变化。真实账号登录、处罚记录读取及账号写入尚未进行端到端验收；接口合约已核对部署源码，并用本地模拟服务验证。上线前应重新核对部署版本，并用测试账号验收发帖、编辑、回复、收藏及处罚记录。

## 2026-09-20 变基至 0.1.8

- 基于主分支 `a391c4f` 重新应用两个论坛提交，继承版本号 `0.1.8`、版本码 `11`；论坛 API 仍仅位于预览分支。
- 合并初始化、会话刷新、登录续接和社区页面冲突，保留主分支的按章存储、可取消请求、登录后继续收藏、草稿保护及账号隔离。
- 论坛个人面板接入公共动效参数和系统／电子纸减少动效策略，排序菜单复用静态菜单实现；论坛测试适配可挂起的会话刷新接口。
- `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 全部通过；259 项 JVM 测试无失败、错误或跳过。
- API 35 模拟器上 15 项设备回归通过，覆盖论坛个人面板与排序、双向退出隔离、登录后继续收藏、书架操作、静态弹层、书目同步及本地阅读导航。使用受控测试数据，未进行真实账号写入。
- 日志：`artifacts/forum-rebase-0.1.8-build.log`、`artifacts/forum-rebase-0.1.8-device.log`。主分支的 0.1.8 ARM64 发布包曾记录于 `release-0.1.8-verification.md`（主分支后续整理文档时已移除）。

## 2026-09-21 变基至目录重构后的 main

- 基于主分支 `3276456` 重新应用两个论坛提交，保留主分支的目录拆分、书籍列表重构和构建入口。
- 论坛会话、API、数据模型和链接分别迁入 `data/auth`、`data/network`、`data/model` 和 `data/catalog`；论坛页面迁入 `ui/community`，账号入口合并到 `ui/account` 的拆分文件，测试包名同步更新。
- 保留主分支可取消且串行化的会话刷新，以及登录后继续收藏的导航逻辑；论坛匿名请求仍不能通过共享 Cookie 自动恢复已退出的会话。
- `:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:testDebugUnitTest`、`:app:lintDebug` 全部通过；298 项 JVM 测试无失败、错误或跳过，lint 为 0 个错误、29 个警告。
- API 35 模拟器上 16 项设备回归通过，覆盖论坛排序、个人面板、处罚记录展示、双向退出隔离、帖子编辑器、书目同步、静态弹层、书架交互、登录后继续收藏和帖子 Markdown 锚点。使用受控测试数据，未进行真实账号写入。
- 设备测试类位于 `cc.novelia.app.ui.community`、`cc.novelia.app.data.auth`、`cc.novelia.app.ui.shelf`、`cc.novelia.app.ui.components` 和 `cc.novelia.app.ui.markdown`；可选只读联调类为 `cc.novelia.app.integration.ForumLiveReadOnlyTest`。
- 日志：`artifacts/forum-rebase-20260921-build.log`、`artifacts/forum-rebase-20260921-device.log`。

## 2026-09-21 适配论坛部署 6c65702

- 对照已部署脚本和对应源码，更新站务公告分类、默认浏览入口、发布权限、字数与标签上限、作者删除帖子时限、管理员评论权限和社区守则链接。帖子／评论 API 的分页、排序和字段结构仍兼容原合约。
- [ForumRules.kt](../../app/src/main/java/cc/novelia/app/data/model/ForumRules.kt) 集中维护规则；表单即时提示，API 层再次验证请求，域名过滤失败展示服务端原因。未在客户端固化会变化的域名黑名单。
- 最终 Debug APK、测试 APK、全部 JVM 测试和 `lintDebug` 构建通过；307 项 JVM 测试无失败、错误或跳过，lint 为 0 个错误、29 个警告。
- API 35 模拟器上 19 项测试通过：论坛账号面板 6 项、双向退出隔离 2 项、论坛编辑器 2 项、主站帖子编辑器布局 1 项、Markdown 工具栏 4 项、草稿生命周期 3 项、论坛公开只读联调 1 项。
- 新增测试覆盖 Unicode 边界、旧超长内容继续编辑、三个标签上限、公告发帖权限、删除时限、管理员展开隐藏／删除评论，以及正文域名过滤错误和评论 PATCH 不携带 `rootId`。已检查浅色与深色大字号面板截图。
- 只读联调在设备上读取三个分类的列表、详情和评论，全部正常解码。未使用真实账号执行发布、修改、删除或处罚操作。
- 日志：`artifacts/forum-update-20260921-final-build.log`、`artifacts/forum-update-20260921-device.log`。

## 2026-10-01 适配论坛 9 月 30 日构建

- 核对部署 `692916b`，与之前部署 `6c65702` 对比 API、认证和 Markdown 源码，并通过匿名公开 GET 抽查三个分类、六篇帖子及对应评论／回复。
- 跟进一级评论和子回复拆分分页。线上省略 `replyCount` 时仍可展开回复；独立回复接口返回的 `total` 用于数量和分页。新增讨论串加载、重试、屏蔽、角色切换和发布后定位末页的界面测试。
- 论坛评分与网页同步为半星四舍五入，主站和旧文章保留原有评分精度。分类、排序、发帖、收藏、权限与登录合约无需额外调整；补齐外部小说评论的只读回复接口，主站小说评论仍沿用现有接口。
- `:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:lintDebug` 全部通过：393 项 JVM 测试无失败、错误或跳过，lint 为 0 个错误、23 个警告。
- 另用客户端生产序列化器解码本次实际抓取的分类、列表、六篇详情、一级评论和子回复，3 项临时 JVM 验证全部通过，确认缺失 `replyCount` 的根评论仍存在非空回复。验证源码与结果只保存在本地忽略目录。
- 8 项新增讨论串 Android 界面测试已编译到测试 APK。本次环境未连接设备且没有可用 AVD，未执行设备界面测试或真实账号写入验收。
- 本次公开响应、源码快照、检查脚本和验证记录保存在本地忽略目录 `outputs/qa/forum-20260930/`；最终构建日志为 `outputs/logs/build-gradle-20261001-143324-292.log`。未将公开抓取的用户评论作为仓库测试资源提交。
- 实际响应解码验证日志：`outputs/logs/build-gradle-20261001-143943-698.log`；汇总结果：`outputs/qa/forum-20260930/build-verification.json`、`fixture-verification.json`。

## 2026-10-01 社区布局与本地收藏修复

- 修复分类栏右侧空白：空间足够时均分整行，较大字号或更多分类时横向滚动，滚动模式仍至少铺满可用宽度。
- 搜索默认收起，在列表工具栏展开或收起，保留搜索词并支持清空。
- 社区守则改为可离线阅读的原生页面，普通 `/rules` 链接和「我的」菜单均进入该页；处罚记录进入原生账号页。守则内容核对自部署 `692916b` 的 `CommunityRulesView.vue`。
- 守则提示可关闭，关闭状态持久保存，重启及账号切换后仍隐藏。移除评论输入辅助条上方的重复提示；「查看回复」与回复、屏蔽／编辑等按钮共用操作行，空间不足时自动换行。
- 确认本地收藏此前直接展示收藏时的快照。现改为每页 20 条核对最新详情，最多 4 个并发请求，并可手动刷新；打开详情也更新快照。兼容新论坛 ID 和旧主站 ID；请求失败或帖子不可访问时保留收藏并明确标注，不把旧状态当作当前状态。已取消的收藏不会被慢响应恢复，较早请求不能覆盖新详情。
- 四项构建检查全部通过：399 项 JVM 测试无失败、错误或跳过，lint 为 0 个错误、23 个警告。新增 6 项界面回归用例并调整原讨论串／编辑器用例，全部编译到测试 APK；本次没有连接设备，未执行设备测试。
- 构建日志：`outputs/logs/build-gradle-20261001-150953-216.log`；汇总：`outputs/qa/community-ui-20261001/verification.json`。

## 2026-10-01 帖子统计、回复计数与守则同步

- 列表元信息同时显示评论数与查看量，窄屏或大字号时换行；尚未核实的本地收藏不将旧统计标为当前数据。
- 评论列表提前补齐缺失的回复计数，只请求回复接口第一页的一项，使用 `total`。区分零回复与请求失败；回复正文仍只在展开后分页读取。计数在列表层保存，避免 LazyColumn 回收单项后丢失；刷新和账号／角色变化时失效，慢预读取不覆盖展开后取得的新计数。
- 社区守则由纯内置内容改为进入页面检查更新、手动更新和本地缓存。匿名读取原站入口及脚本的部署 SHA，从公开仓库取得该部署的 `CommunityRulesView.vue`；相同构建只检查入口。原生展示模板中的文字和列表，未知结构拒绝替换缓存并显示同步失败；首次离线使用内置副本，后续离线使用上次同步内容。没有后台定时任务。
- 匿名公开 GET 验证 `page=1&page_size=1` 可返回准确回复总数：抽查帖子 953 的根评论 178974 为 7，帖子 1021 的根评论 195306 为 0。当前守则来源仍为部署 `692916b`，测试资源与实际原站源码一致。记录：`outputs/qa/forum-metadata-20261001/public-verification.json`。
- 最终 `:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:lintDebug` 全部通过：407 项 JVM 测试，无失败、错误或跳过；lint 为 0 个错误、23 个警告。回归覆盖缺失／零／失败计数、64 位计数、并发上限及取消／账号切换，以及守则部署更新、进程重建缓存、断网和未知模板保留旧副本。
- 新增 2 项 Android 回归，验证提前展示回复计数、零回复以及 LazyColumn 回收后返回的数量保持；本次仅编译测试 APK，当前没有连接设备，未执行界面回归。最终日志：`outputs/logs/build-gradle-20261001-154438-025.log`；汇总：`outputs/qa/forum-metadata-20261001/verification.json`。

## 2026-10-01 展开回复滚动后重复加载修复

- 原因：此前只将计数保存在列表层，回复正文仍由单条评论内的 `AsyncContent` 临时保存。LazyColumn 回收屏幕外的评论后，返回时恢复了展开状态和页码，但正文丢失，因此重新请求。
- 将已读回复页缓存移到评论列表作用域，每串保留最近三页；返回时以缓存作为首帧内容，收起重开或返回已读页也可复用。单条评论回收不会取消列表拥有的请求，同一页的等待合并，结果完成后仍保存。请求失败可重试，不会取消其他讨论串。
- 缓存绑定帖子、论坛会话代次、用户 ID、角色及评论刷新版本，列表重新载入或离开时失效；旧请求取消，晚响应不能回填。仅保存内存，不持久化回复正文。恢复缓存时同步计数并校正有效页码，不重复执行已经处理的新回复定位。
- 7 项新增 JVM 回归覆盖缓存复用、分页与讨论串隔离、并发等待、滚出后的请求继续／合并、失败重试、失效与晚响应，以及容量限制和总数更新。Android 回归增加展开后滚出／返回不再读取、收起重开，以及读取中收起后恢复正文与数量的断言。
- 最终四项构建检查全部通过：414 项 JVM 测试无失败、错误或跳过；Debug APK 与测试 APK 构建成功，lint 为 0 个错误、23 个警告。当前未连接 Android 设备，界面回归仅完成编译。日志：`outputs/logs/build-gradle-20261001-165449-700.log`；汇总：`outputs/qa/forum-reply-cache-20261001/verification.json`。
