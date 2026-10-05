# 社区、评论与内容编辑

[返回业务功能索引](README.md) · [文档总目录](../README.md)

社区功能包括帖子列表、文章正文、评论与回复、本地收藏、Markdown 编辑和草稿恢复。主要代码在 [ui/community](../../app/src/main/java/cc/novelia/app/ui/community) 与 [ui/markdown](../../app/src/main/java/cc/novelia/app/ui/markdown)。登录流程见[账号与登录](../network/authentication.md)。

本预览分支的社区入口使用独立论坛，接口版本、权限规则和验证记录见[独立论坛 API 预适配](../network/forum-api-preview.md)。旧站文章及小说评论仍使用主站接口，两边的登录和退出相互独立。

## 1. 浏览范围与收藏

[CommunityScreen.kt](../../app/src/main/java/cc/novelia/app/ui/community/CommunityScreen.kt) 从服务器加载分类，按站务公告、小说讨论、意见反馈的顺序展示，首次进入默认打开站务公告。在线列表按服务端分页加载，每页 20 条，并支持最近活跃、最新发布、浏览最多、评论最多四种排序。

[ForumFeedControls.kt](../../app/src/main/java/cc/novelia/app/ui/community/ForumFeedControls.kt) 在空间足够时均分分类栏，分类较多或字体较大时允许横向滚动。搜索框默认收起，可从列表工具栏展开、收起；收起保留查询条件，清空按钮解除筛选。

帖子列表同时展示「评论数 · 查看量」，使用服务端 `commentsCount/viewsCount`，大字号或窄屏时元信息自动换行。当前状态尚未核实的本地收藏不会把旧统计当作最新数据。

| 操作 | 实际数据范围 |
| --- | --- |
| 切换社区分类/页码 | 请求所选分类与页码的帖子列表 |
| 在全部帖子中输入搜索词 | 将 `q` 发送给服务器，在所选分类内搜索 |
| 在我的帖子／云端收藏中输入搜索词 | 筛选当前页的帖子标题 |
| 切换本地收藏列表／页码 | 每页读取 20 条本地收藏，并核对各帖子当前远端详情；可手动刷新 |
| 切换云端收藏／我的帖子 | 使用独立论坛会话读取对应分页接口 |
| 屏蔽用户 | 在客户端隐藏相应列表或评论内容，不修改服务端账号关系 |

收藏文章会保存文章资料与正文到本地状态，长正文由持久化层单独存储。当前文章详情页仍会加载远端详情，因此不能由“收藏里保存了正文”推导出所有文章入口都支持离线打开。换机需要保留收藏时，应使用[完整阅读资料备份](../data/backup-and-recovery.md)。

[SavedArticles.kt](../../app/src/main/java/cc/novelia/app/data/library/SavedArticles.kt) 以最多 4 个并发请求核对当前页，成功更新收藏中的标题、锁定、置顶、隐藏、回复数量及正文。打开帖子详情也更新同一收藏快照。网络或登录失败保留收藏并显示「状态未核实 · 显示本地缓存」，不把旧锁定／置顶状态和回复数量当作当前状态；403、404、410 显示「已删除或不可访问」。列表可直接取消本地收藏。提交前核对账号和原快照，已取消的收藏不会恢复，较早响应不能覆盖新详情。

[ForumRulesScreen.kt](../../app/src/main/java/cc/novelia/app/ui/community/ForumRulesScreen.kt) 提供原生社区守则页面，进入时检查原站更新，也支持手动更新。先显示上次保存的守则；首次离线打开时显示随 App 打包的 2026-10-04 副本，包括新增违规条款、处理办法和全部用户权限。权限按操作展示为卡片，分别说明普通未满月、普通已满月及受限用户是否允许，窄屏和大字号可纵向阅读。更新失败或原站结构无法识别时保留旧内容并明确提示，处罚记录按钮进入原生账号记录页。首次使用论坛时提供可关闭的守则提示；关闭状态通过 `forumRulesReminderDismissed` 持久保存，后续列表、文章和编辑器不再显示。评论输入辅助条上方不重复放置提示，守则始终可从「我的」菜单打开。

[ForumCommunityRulesRepository.kt](../../app/src/main/java/cc/novelia/app/data/network/ForumCommunityRulesRepository.kt) 匿名读取线上 `/rules` 的应用入口；构建变化时读取入口脚本的 `commitSha`，再从原站公开仓库下载**该部署提交**的 `CommunityRulesView.vue`。相同构建只检查入口，不重复下载源码；不读取仓库尚未部署的 main。[ForumCommunityRulesParser.kt](../../app/src/main/java/cc/novelia/app/data/model/ForumCommunityRulesParser.kt) 将已知模板文字、列表及静态权限数据转成原生控件，不执行脚本；权限对象、列、循环和允许／不允许图标均通过完整校验后才替换缓存。原站暂无独立守则 API，若构建元信息、源码路径或模板结构发生变化，将提示同步未完成并保留可用副本。

论坛「我的」入口和处罚菜单展示未读提示，仅在社区前台、恢复前台或展开菜单时检查认证服务。成功展示处罚列表后按响应的 `latestStrikeId` 确认已读；缺少字段的旧响应不写入。确认失败不会丢失已经展示的记录，可重试确认；确认后仍有新处罚时提示刷新。所有读取和确认都绑定当时的论坛会话，退出或换号后旧请求不能更新新账号，状态和处罚正文不持久保存。

[ArticleScreen.kt](../../app/src/main/java/cc/novelia/app/ui/community/ArticleScreen.kt) 负责文章正文、讨论入口、收藏以及编辑/删除入口。论坛按用户 ID 和角色判断权限，普通作者仅能在发帖后 20 分钟内删除，管理员不受时限影响；旧站文章仍按用户名显示作者操作。服务端负责最终权限校验。删除文章会发送远端请求，与移除本地收藏不同。

## 2. 帖子编辑与提交

[ComposeArticleScreen.kt](../../app/src/main/java/cc/novelia/app/ui/community/ComposeArticleScreen.kt) 对新建和编辑使用同一编辑流程。

```mermaid
flowchart TD
    A[新建或载入文章] --> B[恢复本地草稿]
    B --> C[编辑标题、分类与正文]
    C --> D[Markdown 预览]
    D --> C
    C --> E[检查登录、发帖权限与长度]
    E --> F[捕获提交快照并发送]
    F --> G{请求成功}
    G -->|否| C
    G -->|是| H[仅清理与已提交内容相同的草稿]
```

论坛标题要求 2～100 个 Unicode 码点，正文为 1～20000，标签最多 3 个；规则集中在 [ForumRules.kt](../../app/src/main/java/cc/novelia/app/data/model/ForumRules.kt)。旧站文章仍要求标题 2～80 字符、正文 2～20000 字符。论坛超长草稿保留内容并提示缩短，不截断正文。界面输入限制不能替代服务端校验。

未满足发帖权限时，用户仍可以编辑、预览并保留草稿；提交阶段再次判断登录及所属站点的发布权限。论坛允许 `member`、`trusted`、`admin` 发言，但站务公告仅管理员可发布，权限按分类 `slug` 判断，不依赖可重分配的数字 ID。发送时保存提交快照，避免将请求发起后的输入误认为已经发布。

社区的“新建帖子草稿”和“新帖草稿箱”入口不要求登录。草稿箱可同时保留多篇新帖，按标题和所属站点识别、续写或删除；旧站草稿可离线续写，论坛编辑器进入时仍需加载远端分类与标签。旧站草稿继续使用主站编辑器，不会自动迁移或发布到论坛。发布仍沿用所属站点的登录和权限检查，不会自动排队发送。

论坛新建使用 `POST post/`，修改使用 `PATCH post/{id}/`；旧站草稿发布使用 `POST article`，文章修改保留 `PUT article/{id}`。这些请求直接发送，不加入云端收藏和阅读进度的离线队列。请求失败应保留草稿并展示错误；如果出现“响应失败但服务端可能已接收”的情况，先核对远端文章再重试，不能默认安全地重复新建。

## 3. 草稿生命周期

| 草稿类型 | 键 | 保存内容 |
| --- | --- | --- |
| 新论坛帖子 | `article:forum-new:<UUID>` | 每篇独立保存标题、分类 slug／ID、正文、标签 ID；兼容旧 `article:forum-new` |
| 旧站新帖草稿 | `article:new:<UUID>` / `article:new` | 原草稿保留，继续使用主站编辑器，不自动发布到论坛 |
| 已有帖子 | `article:{id}` | 对应文章的编辑内容 |
| 评论/回复 | `comment:{site}:{parent}` | 当前评论目标的文本 |
| 论坛评论/回复 | `forum-comment:{postId}:{userId或guest}:{root或回复根ID或edit-ID}` | 当前论坛账号与评论目标的文本 |
| 文库资料 | `wenku:new` / `wenku:{id}` | 文库表单，详见[书籍详情](book-details.md) |

草稿属于设备共享的 `LibraryState.drafts`。论坛评论键包含用户 ID，帖子和主站评论键没有账号前缀。退出登录不会自动清除这些资料，切换账号后仍需核对提交目标与内容。

论坛恢复草稿时按保存的 `slug` 查找当前分类，重新取得 ID 并过滤不属于当前分类的标签。10.5 线上分类 ID 改为公告 `1`、反馈 `2`、小说 `100`；只有数字 ID 的旧论坛草稿保留标题和正文，要求用户重新选择分类才允许发布，避免 ID 重用造成误发布。

文章编辑采用约 700 ms 防抖保存，同时通过 [EditorDraft.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/EditorDraft.kt) 处理页面离开时的最新内容。编辑/预览切换、Compose 重组与状态恢复都需要保留输入；不能只依赖 `remember` 中的临时文本。

“保存草稿并退出”会等待本地持久化完成后返回；保存失败时保留编辑器和内容，并显示持久化错误。新帖成功发布只清理对应草稿，其他新帖不受影响。旧版固定草稿会出现在草稿箱，可按原内容继续编辑。

提交成功后的清理是有条件的：当前内容仍等于已发送快照才清除，否则保存新内容。新增编辑器时应沿用这套逻辑，避免用户在慢请求期间继续输入后丢失草稿。

## 4. 评论与回复

[ForumCommentsPanel.kt](../../app/src/main/java/cc/novelia/app/ui/community/ForumCommentsPanel.kt) 分页读取一级评论，直接使用 2026-10-01 部署始终返回的 `replyCount` 显示「查看 X 条回复」或「暂无回复」。有效计数包括 `0`，均无需额外请求。[ForumCommentThread.kt](../../app/src/main/java/cc/novelia/app/ui/community/ForumCommentThread.kt) 展开后通过 `post/{postId}/comment/{rootId}/reply` 独立分页加载子回复正文，每页 20 条，并用回复页 `total` 更新数量。仅兼容旧响应缺失／null 计数时，列表提前以 `page=1&page_size=1` 读取该接口的 `total`，最多 4 个并发请求。读取失败保持未知，允许展开重试，不误报零回复。计数保存在列表层，滚出屏幕后回来仍保留；刷新评论、发送／删除、切换账号或角色时重新核对。「查看回复」与回复、屏蔽／编辑等操作在同一行，空间不足时自动换行。发送子回复后展开对应讨论串并定位末页；账号或角色切换后清除已加载回复。回复提交根评论 `rootId`，修改只传 `content`。评论最多 1000 个 Unicode 码点；普通作者发布后 20 分钟内可编辑或删除，管理员不受时限限制，并可展开隐藏／删除评论的原文。

[ForumReplyPageCache.kt](../../app/src/main/java/cc/novelia/app/data/network/ForumReplyPageCache.kt) 在评论列表层保存已读回复，每串保留最近三页。收起再展开、滚出显示区域再回来时，当前页直接从内存恢复；同一页的并发等待共用一次请求。正在读取的请求属于列表作用域，单条评论被回收只取消该行等待，结果仍可保存供返回时使用。读取失败不缓存，可重试且不影响其他讨论串。评论列表重新载入、发送／修改／删除、帖子或论坛会话／角色变化时重建缓存，页面退出取消请求并丢弃数据；回复正文不落盘。返回讨论串保留用户选定的页码，不重播已经处理过的新回复定位事件。

[CommentsPanel.kt](../../app/src/main/java/cc/novelia/app/ui/community/CommentsPanel.kt) 继续服务于主站作品和旧文章评论。调用方提供 `site` 与父目标，不应只凭显示标题推断评论归属。以下为主站评论行为：

- 列表请求为 `GET comment`，查询参数包括 `site`、`page`、`pageSize` 和 `parentId`，每页 20 条。
- 发送请求为 `POST comment`，请求体中的父目标字段叫 `parent`，与读取参数 `parentId` 不同。
- 一级列表展示未被屏蔽的回复摘要，最多取两条；完整回复在回复面板中继续加载。
- 内容隐藏状态显示相应提示；被本地屏蔽用户的评论及摘要会被过滤。
- 锁定讨论限制发送入口，但不等于不能查看已有内容。
- 评论的更多菜单提供删除自己的评论或屏蔽其他用户；屏蔽是本地操作，并在当前评论面板显示“撤销”，回复面板同样可撤销。删除确认按钮标明“删除评论”或“删除文章”。

评论输入持续保存到对应草稿键。发送成功后，只有当前输入仍与发送文本相同才清空。删除、发送均是直接远端请求，不应对所有 HTTP 失败统一套用离线重放。

## 5. Markdown 渲染与链接

渲染入口是 [MarkdownText.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/MarkdownText.kt)，站点扩展语法由 [SiteMarkdownParser.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/SiteMarkdownParser.kt) 处理。工具栏、模板插入与预览应使用相同语义，防止“编辑器里看到的”和最终正文不一致。

新论坛的 `::: star` 评分与 2026-09-30 网页构建一致：只接受非负十进制数，上限为 5，按半星四舍五入；正文、编辑预览和评论共用此规则。主站与旧文章沿用原有评分精度。

维护时尤其注意：

1. 围栏代码块中的内容应作为代码保留，不能用全局字符串替换处理星号、链接或站点扩展标记。
2. 自动链接处理基于解析结构，已有链接和图片节点不能再次包装成链接。
3. 折叠详情、删除线等站点扩展需要覆盖嵌套、闭合和未闭合输入。
4. 站内书籍/文章链接、站点 WebView 页面和外部浏览器链接由各自入口处理；渲染器不直接拼装任意导航路由。

旧原站域名 `books.fishhawk.top` 会转换为 `https://n.novelia.cc`，正文裸链接、表格中的 Markdown 链接及分享入口采用相同的域名映射。书籍、章节和文章优先进入原生页面；带跨文档锚点或没有原生对应页的链接由站内 WebView 承接，保留原始查询参数与锚点。仅识别明确的站点域名，不会仅因外站路径含有 `/novel/` 就将其改写。裸链接后的中英文混用括号说明（如 `URL(说明）`）会留在正文中，不再作为 URL 的一部分。

更完整的组件约束、Markdown 能力和 WebView 边界见 [UI 与导航](../architecture/ui-and-navigation.md)。

## 6. 维护与验证

| 场景 | 要检查的结果 |
| --- | --- |
| 列表搜索后翻页 | 全部帖子保留服务端搜索条件；我的帖子／云端收藏的搜索范围仍为当前页 |
| 编辑与预览反复切换 | 标题、正文、分类和输入位置按现有编辑器约定保留 |
| 页面退出/重建后进入 | 对应草稿可以恢复，不串到其他文章或评论目标 |
| 慢请求中继续输入 | 成功提示不能清掉未发送的新内容 |
| 权限不足或断网 | 内容仍可编辑和保存草稿，远端操作准确提示失败 |
| 屏蔽与锁定 | 一级评论、回复摘要和回复面板行为一致 |
| 代码块、图片与嵌套链接 | 不被站点扩展或自动链接逻辑误改 |

纯解析与状态回归入口包括 [MarkdownTest](../../app/src/test/java/cc/novelia/app/MarkdownTest.kt)、[SiteMarkdownTest](../../app/src/test/java/cc/novelia/app/SiteMarkdownTest.kt) 和 [EditorStateRegressionTest](../../app/src/test/java/cc/novelia/app/EditorStateRegressionTest.kt)。生命周期与交互见 [EditorDraftLifecycleTest](../../app/src/androidTest/java/cc/novelia/app/ui/markdown/EditorDraftLifecycleTest.kt) 及 [MarkdownToolbarInteractionTest](../../app/src/androidTest/java/cc/novelia/app/ui/markdown/MarkdownToolbarInteractionTest.kt)。真实发帖和评论会产生远端内容，不应作为默认自动验证步骤。
