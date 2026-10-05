# 独立论坛 API 预适配

此分支 `codex/forum-api-preview` 的社区入口连接 [测试论坛](https://forum.novelia.cc/c/novel)，主站小说、阅读、下载与小说评论继续连接 `n.novelia.cc`。

## 核对依据

- 核对日期：2026-09-21。
- 测试站公开前端：`/assets/index-YZCWd1-v.js`，内嵌部署提交 `6c65702e9fdf6c8a5cf21654a36596a2f508a494`。适配以此部署为准，上游 main 的后续提交未作为已部署行为处理。
- [该部署版本的前端 API 合约](https://github.com/auto-novel/forum/blob/6c65702e9fdf6c8a5cf21654a36596a2f508a494/apps/web/src/api.ts)。
- [服务端帖子路由](https://github.com/auto-novel/forum/blob/6c65702e9fdf6c8a5cf21654a36596a2f508a494/apps/api/internal/handler/post.go)、[评论路由](https://github.com/auto-novel/forum/blob/6c65702e9fdf6c8a5cf21654a36596a2f508a494/apps/api/internal/handler/comment.go)、[认证声明](https://github.com/auto-novel/forum/blob/6c65702e9fdf6c8a5cf21654a36596a2f508a494/apps/api/internal/httpx/authn.go)。

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
| 评论 | `GET/POST post/{id}/comment`；返回平铺分页列表；回复提交 `rootId`，回复子评论时仍使用其根 ID |
| 修改评论 | `PATCH/DELETE comment/{id}`；编辑只提交 `content`，禁止提交 `rootId`（包括 null）；作者限发布后 20 分钟内修改，管理员不受时限影响 |
| 云端收藏 | `PUT/DELETE post/{id}/favorite`；`GET me/favorite`、`GET me/post` |
| 处罚记录 | 认证服务 `https://auth.novelia.cc/api/v1/me/strikes`，携带论坛令牌；分页参数为 `page/page_size`，返回原因、依据、分值及撤销时间 |
| 登录 | 统一认证应用标识 `f`，Origin 为 `https://forum.novelia.cc`；刷新路径 `/api/v1/auth/refresh?app=f` |
| 网页链接 | `https://forum.novelia.cc/p/{id}` |

新论坛与主站分别保存加密令牌并使用不同的 Keystore 别名。论坛请求拒绝主站会话；401 最多刷新一次。两边的退出操作只清除各自的本机会话，不调用全局 SSO 退出接口，不删除共享认证 Cookie，也不清除另一边的令牌。已退出的会话不会因匿名请求收到 401 而自动登录；用户明确进入登录页后仍可复用统一认证。

处罚记录字段与认证行为依据论坛所依赖的 [认证 SDK 7301b75](https://github.com/auto-novel/auth/blob/7301b75bde57f3fabf3e26a41617644d46b36ced/packages/auth-api/src/api.ts) 核对。此接口属于账号记录，不是论坛帖子 API。

## 客户端行为

- 分类及标签使用服务器数据；站务公告排首位并作为首次进入的默认分类，小说讨论和意见反馈随后，未知分类保留。普通用户发帖默认选择小说讨论，分类选项排除站务公告；旧草稿若指向公告分类，保留内容并要求重新选择分类。列表搜索请求服务端。帖子排序提供「最近活跃、最新发布、浏览最多、评论最多」，分别发送 `active/newest/views/comments`；切换排序返回第一页，保留分类和搜索词。
- 新帖 ID 在本地表示为 `f-{id}`，本地收藏及草稿与旧站 ObjectID 区分。旧帖子链接和收藏继续从主站读取；不猜测新旧帖子 ID 对应关系。
- 新建论坛草稿使用 `article:forum-new`；旧版 `article:new` 草稿仍保留，不自动发布到测试站。
- 新版编辑保留帖子的标签，最多选择 3 个，切换分类时清除旧分类标签。标题为 2–100 字，正文为 1–20,000 字，评论为 1–1,000 字；按 Unicode 码点计数，emoji 的代理对按一个字计算。正文保留原始空白，评论按网页行为去除首尾空白后提交。旧的超长草稿保留并可继续缩短，超限时禁止提交。最终权限及域名黑名单由服务端决定；论坛返回的文本校验错误会显示具体原因，失败时保留草稿且不自动重发。
- 论坛的 `trusted`、`member`、`admin` 角色可发言，公告限管理员；主站账号权限规则保持原逻辑。帖子删除和评论修改入口在 20 分钟到期后更新，提交时再次检查权限。
- 评论按服务端分页平铺显示，回复显示根评论 ID；根评论可能位于其他页。隐藏／删除评论默认显示占位文本，管理员可展开服务端返回的原文，切换账号或评论状态时收起。
- 右上角「我的」使用锚定展开面板，集中放置我的帖子、云端收藏、处罚记录、本地收藏和论坛登录／退出。面板支持缩放淡入淡出、返回键／外部点击收起、滚动和大字号，遵循减少动态效果设置。
- 云端收藏和我的帖子沿用各自接口的排序；四种帖子预设用于「全部帖子」。处罚记录显示原因、分值、依据、生效／撤销状态及时间，不持久缓存账号处罚数据。“我的”和帖子／评论表单提供社区守则入口，在站内打开 `/rules`。
- 新域名帖子链接可进入原生页面；带锚点、分类筛选或编辑路径的链接在站内 WebView 中保留完整 URL。
- 服务端还提供 `external/comment/novel/{subjectKey}`。已提供显式 subject key 的只读接口，但测试站尚未给出主站迁移映射，因此当前小说评论保持原接口。

## 验证

- 已对测试站三个分类的列表、帖子详情和评论执行公开 GET，核对字段与部署源码一致；不会在联调时创建帖子、评论或修改收藏。
- `ForumApiContractTest` 使用 MockWebServer 覆盖分页／搜索、数字 ID、时区与小数秒、创建／PATCH 请求体、标签、平铺回复、204、云端列表、会话隔离、401 刷新上限、503 响应不重试、旧收藏及新旧链接。
- `ForumAccountApiTest` 覆盖四种排序参数、筛选与分页保留，以及使用论坛令牌访问认证服务处罚记录的字段解析和权限边界。
- 变基前验证：93 项 JVM 测试全部通过；Debug APK、Android 测试 APK 构建成功；`lintDebug` 通过，0 个错误、25 个警告。
- `ForumAccountUiTest` 与 `ForumSessionIsolationTest` 共 6 项 Android 回归测试通过，覆盖排序选择、个人面板操作与返回键、处罚记录展示，以及双向退出隔离和退出状态持久化。会话测试使用独立存储与合成凭据。
- 在 375dp 宽模拟器上核对浅色／深色、减少动态效果；另外两次布局测试覆盖横屏和系统双倍字号，均通过并检查截图。
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
