# 界面、导航与交互开发

[返回架构与界面索引](README.md) · [文档总目录](../README.md)

本文说明界面的组织方式、路由、状态生存期和跨设备交互约束。阅读器的正文管线、进度与分页详见 [阅读器开发](../features/reader.md)。返回 [开发文档首页](../README.md)。

## 1. 界面入口与依赖关系

应用采用单 Activity + Jetpack Compose + Navigation Compose。当前代码由 Composable、`AppController` 和应用级数据服务协作，不存在一套每页独立 ViewModel 的框架；扩展功能时应先遵循现有边界，避免同时维护两份页面和业务状态。

界面源码按 [17 个功能与共享子包](source-layout.md) 分类，独立页面各有文件。修改页面时先进入对应的 `ui/<功能>/`；页面专用面板、排序和展示逻辑与页面相邻，跨页面组件在 `ui/components/`。导航控制器、主题、Markdown 和反馈分别放在 `navigation/`、`theme/`、`markdown/` 和 `feedback/`。

| 位置 | 职责 |
| --- | --- |
| [MainActivity.kt](../../app/src/main/java/cc/novelia/app/MainActivity.kt) | 等待书库初始化、提供主题和交互模式、处理外部 Intent、建立导航图、展示全局 Snackbar 和收藏面板 |
| [AppController.kt](../../app/src/main/java/cc/novelia/app/ui/navigation/AppController.kt) | 页面共享的导航、登录续办、消息、书籍/章节入口、详情缓存与云操作入口 |
| [NoveliaApplication.kt](../../app/src/main/java/cc/novelia/app/NoveliaApplication.kt) | 持有 `store`、`api`、`session` 等应用级服务 |
| [Screen.kt](../../app/src/main/java/cc/novelia/app/ui/components/Screen.kt)、[AsyncContent.kt](../../app/src/main/java/cc/novelia/app/ui/components/AsyncContent.kt) | 页面容器、异步加载与刷新状态 |
| [BookComponents.kt](../../app/src/main/java/cc/novelia/app/ui/components/BookComponents.kt)、[PreferenceComponents.kt](../../app/src/main/java/cc/novelia/app/ui/components/PreferenceComponents.kt) | 书籍行与封面、选择项、菜单行及确认输入组件；其他共享组件见 `ui/components/` |
| [LibraryModels.kt](../../app/src/main/java/cc/novelia/app/data/model/LibraryModels.kt)、[ReaderSettings.kt](../../app/src/main/java/cc/novelia/app/data/model/ReaderSettings.kt) | `LibraryState`、阅读偏好、进度等持久化模型 |

启动时，`MainActivity` 等待 `app.initialization`，然后才创建正常页面。`ReportDrawnWhen` 同样受初始化状态控制。如果 `store.recoveryIssue` 不为空，主界面进入书库备份/恢复界面，避免在需要恢复的数据上继续正常操作。新增启动逻辑不能绕过这一分支。

外观订阅先将 `LibraryState` 映射为较小的 `AppAppearance`，再 `distinctUntilChanged`，因此进度或书架数据变化不会直接触发整个主题树更新。类似高层订阅应尽量只选择实际需要的字段。

书籍简略行左侧保留封面，右侧显示书名、副标题（作者等）和更新提示，译文统计保留在详情页。发现、搜索结果和排行榜不显示阅读进度；本地和云端文库主书目也不显示，挂载到本地文库的每个分卷按自己的阅读记录显示进度。网络书架和阅读历史显示可用的本机或当前账号云端进度。

书籍有有效日期时，列表始终用小字显示 `更新于 yyyy-MM-dd`。右侧文字区达到 `320 dp × fontScale` 时放在进度行，窄列表与大字号下单独换行，不因分栏或隐藏进度而丢失日期。网络列表优先采用接口的 `updateAt`（本站发现目录变化的时间），缺失时从详情目录的最新有效章节 `createAt` 补全。详情以信息卡显示“最后更新时间”、`yyyy-MM-dd HH:mm` 及对应章节序号和标题，点击章节可阅读；缺少全部章节时间时仍显示最后一章，但不捏造日期。保存详情卡片时缺失的时间不会覆盖已有日期；不把同步检查时间当作内容更新。文库仅有出版日期时，详情标为“最近出版”。

云端收藏接口没有填充阅读历史，`lastReadAt` 为空表示未知，不能直接当成未读。云端收藏和历史列表中可见的网络小说会补查账号隔离的详情缓存（最多同时两本），用 `lastReadChapterId` 在有效章节目录中的位置显示“读到第 X 章”，并按同一章节号 `X / 总章节数` 填充进度条；加载失败保留已知数据或显示“云端进度待同步”。只有详情明确没有阅读章节时才显示未读，纯云端收藏不会因此加入本地书架。旧版本保存的空历史记录同样按未知处理。

“更新 X 章”是本机更新检查比较两次章节总数的正差，并累计到尚未清除的更新记录；不是未读章节数。手动“标记更新已读”后清除，打开或阅读本书不会自动扣减。只增加译文时显示“有更新”，分卷文件增加则显示“更新 X 卷”。检查对象为本地书架中的在线书籍；纯云端收藏不单独参加检查。手动检查可随时触发，开启自动提醒后约每六小时检查一次。

详情收藏状态分别采用当前账号的云端收藏和本地书架，并叠加当前账号待同步的收藏操作。云端已收藏优先显示“已云端收藏”，登录后收藏管理默认打开云端；云端收藏操作不会隐式创建本地收藏。

## 2. 路由清单与返回栈

导航图的唯一注册位置是 [MainActivity.kt](../../app/src/main/java/cc/novelia/app/MainActivity.kt)。下表是当前注册的路由模板，参数值不能直接视为可信文件路径或 URL。

| 路由 | 页面/用途 |
| --- | --- |
| `shelf` | 自适应书架 |
| `discover?query={query}` | 发现、站内搜索 |
| `community` | 社区列表 |
| `profile` | 我的 |
| `rank` | 排行榜 |
| `wenku-new` | 新建文库条目 |
| `book/{provider}/{id}` | 书籍详情 |
| `reader/{provider}/{id}/{chapter}` | 阅读器 |
| `article/{id}` | 帖子详情 |
| `compose?article={article}` | 新建或编辑帖子 |
| `login` | 登录与账号入口 |
| `settings`、`backup`、`sync` | 偏好、书库备份、云同步状态 |
| `updates`、`downloads`、`tools` | 更新中心、下载管理、文件工具 |
| `notes`、`blocked`、`history` | 笔记、屏蔽管理、阅读历史 |
| `glossary/{provider}/{id}` | 术语表 |
| `edit/{provider}/{id}` | 编辑书籍资料 |
| `about`、`licenses` | 关于、离线开源许可 |
| `web?url={url}` | 无原生页面对应的原站网页 |
| `ocr` | 旧返回栈兼容入口；立即替换为 `tools` |

导航使用方式：

```kotlin
c.book(ref)                      // 在线书打开详情，本地书直接进入正文
c.read(ref, chapterId)            // 章节 ID 由入口进行 Uri.encode
c.go("settings")                 // 普通目的地
c.back()                         // 返回上一层
c.openMarkdownLink(url, baseUrl)  // Markdown 链接的统一解析与分派
```

`AppController.go()` 默认不启用 `launchSingleTop`，同一目的地的不同参数可以拥有独立历史项。`replaceTop = true` 实际只启用 `launchSingleTop`，用于避免同一目的地在栈顶重复入栈，并不执行任意栈顶替换。四个根标签由 Activity 单独执行 `popUpTo(startDestination)`、`saveState`、`restoreState` 和 `launchSingleTop`，不能把这套根标签行为套到普通书籍/帖子跳转上。

移除功能时应考虑已有安装保存的返回栈。`ocr` 的兼容跳转就是现成例子：删掉页面实现后保留旧路由的重定向，避免升级恢复时找不到目的地。

### 外部 Intent 与链接

Activity 接受 `intent.dataString`，没有时再取 `Intent.EXTRA_TEXT`。待处理链接放在 `MutableStateFlow` 中；处理后清空。Activity 重建时仅恢复尚未消费的 `novelia.pendingLink`，不会再次处理已经打开过的启动链接。

`openLink` 经 [BookLinks.kt](../../app/src/main/java/cc/novelia/app/data/catalog/BookLinks.kt) 解析书籍或帖子，无法识别的文本进入发现页搜索。Markdown 使用 [MarkdownLinks.kt](../../app/src/main/java/cc/novelia/app/data/markdown/MarkdownLinks.kt) 的独立规则：

1. 根据文档地址解析相对路径、根路径或 `www.` 链接，只接受具备主机名、无 URL 用户信息的 HTTP(S) 地址。
2. 已支持的书籍、章节、帖子及部分原站列表页面转为原生路由。
3. 其他原站地址交给 `SiteWebScreen`；带跨文档锚点或需要保留查询参数的页面也可能保留网页形式。
4. 外部地址用系统浏览器打开。

不要在 Composable 中分别实现一套链接白名单和 URI 拼接。新增 URL 形式时同时修改解析器及其回归测试。

## 3. 状态与副作用约定

以下规则来自当前实现，尤其关系到刷新、切换账号、旋转和返回栈恢复。

| 状态类型 | 现有做法 | 扩展时注意 |
| --- | --- | --- |
| 应用持久状态 | `c.store.state.collectAsStateWithLifecycle()`；`store.update { copy(...) }` | 不直接改模型内部集合；不要只更新页面副本 |
| 当前账号 | `c.session.profile.collectAsStateWithLifecycle()` | 账号相关请求的键应包含账号，异步结果也应检查会话 |
| 需要重建恢复的页面输入 | `rememberSaveable`，按书籍、帖子或讨论 ID 分区 | 不同实体不得共用无区分的编辑状态 |
| 短暂弹窗、请求 Job、加载态 | `remember`、`rememberCoroutineScope` | 离开组合时应取消；不要存入长期 `LibraryState` |
| 请求/监听 | `LaunchedEffect`、`produceState`、`DisposableEffect` | 正确列依赖；清理监听、窗口属性和资源 |
| 长期持久化 | 应用级 `LocalStore` | Activity 停止还会调用 `persistState()` |

`AppController` 自身由 Activity `remember`，属于当前 Compose 根树，不是持久对象。`afterLogin` 是内存中的待办回调，不应被当成跨进程任务队列。需要长期重试的操作应使用已有的数据层队列机制。

`c.action { ... }` 在主协程中运行操作并统一显示错误，**不会自动把整个代码块转移到 IO 线程**。文件读写、解析大文件应自行 `withContext(Dispatchers.IO)`，CPU 密集转换应使用 `Dispatchers.Default`。捕获异常时必须继续抛出 `CancellationException`。

### 异步内容与刷新

[AsyncContent](../../app/src/main/java/cc/novelia/app/ui/components/AsyncContent.kt) 使用三个触发因素：实体 `key`、外部 `refreshKey` 和内部重试计数。

- 实体键变化：建立新的结果状态，避免展示上一实体的结果。
- 同一实体刷新：保留成功内容和它的组合实例，在上方显示刷新进度。
- 刷新失败：保留旧内容，在底部显示可重试错误；不会把已编辑内容或列表滚动位置换成全页空态。
- 首次失败：显示空态和重试入口。
- 结果提交前调用 `ensureActive()`，取消的旧请求不能覆盖新界面。

新增页面优先复用这套逻辑。列表要提供稳定 `key` 和适当 `contentType`；同一个书籍或评论的身份不应取决于当前排序下标。搜索输入可以复用 `rememberDebouncedQuery`，当前非空查询的等待时间为 180 ms。

## 4. 自适应布局

断点依据当前可用布局宽度，而不是设备类别或物理屏幕像素：

| 范围 | 行为 |
| --- | --- |
| Activity 可用宽度 `< 600 dp` | 根页面显示底部导航 |
| Activity 可用宽度 `>= 600 dp` | 根页面显示侧边 NavigationRail |
| Activity 高度 `< 480 dp` | 侧栏使用紧凑间距，省略标签 |
| 书架局部宽度 `>= 840 dp` | 书架和详情双栏；左侧为宽度的 42%，限制在 360–460 dp |
| 阅读器局部宽度 `>= 840 dp` | 固定 292 dp 目录栏 + 正文 |

因为侧边导航也占宽度，不能只根据整个窗口宽度断言某个子页面一定进入双栏。

[AdaptiveLibrary.kt](../../app/src/main/java/cc/novelia/app/ui/shelf/AdaptiveLibrary.kt) 用 `movableContentOf` 在调整窗口时移动仍存活的组合，并用 `SaveableStateProvider("shelf")`、`SaveableStateProvider("detail:$key")` 保存隐藏面板状态。窄屏选中详情时隐藏底部导航；返回先清除选中项。窄屏直接点击本地小说仍保持一次点击进入正文。

修改这部分时，必须验证“宽屏选中一本书 → 缩窄 → 返回 → 再扩大”以及实体切换，不能仅检查两张静态截图。对应测试是 [AdaptiveLibraryTest](../../app/src/androidTest/java/cc/novelia/app/ui/shelf/AdaptiveLibraryTest.kt)、[ReaderAdaptiveUiTest](../../app/src/androidTest/java/cc/novelia/app/ui/reader/ReaderAdaptiveUiTest.kt) 和 [LibraryInteractionTest](../../app/src/androidTest/java/cc/novelia/app/ui/shelf/LibraryInteractionTest.kt)。

## 5. 主题、电子纸与减少动效

[Theme.kt](../../app/src/main/java/cc/novelia/app/ui/theme/Theme.kt) 定义普通界面的浅色/深色配色和文字层级。阅读页的 `readerColors()` 单独支持跟随应用、纸张、浅色、深色和黑白主题，设置面板保持应用主题。不要以切换全局 `MaterialTheme` 的方式实现一页正文背景。

[AppInteractionMode.kt](../../app/src/main/java/cc/novelia/app/ui/theme/AppInteractionMode.kt) 将下列条件合并为静态交互：电子纸、用户减少动效、系统动画关闭、Compose 动画缩放为零。通过 `LocalEInkMode`、`LocalReducedMotion`、ripple、indication 和 overscroll 的 CompositionLocal 传播。

二者影响范围不同：

- 减少动效：取消动画、波纹和弹性反馈，普通列表仍可连续滚动。
- 全局电子纸：除减少动效外，通用列表改成按屏跳动，部分滑杆/排序使用按钮操作。
- 单书电子纸：由 `ReaderScreen` 局部提供交互模式；Activity 也会取消进出该书阅读器的路由转场。
- 自动分页是独立阅读设置；普通屏幕也能使用自动分页。不能通过“正在分页”推断为电子纸。

新增组件应使用现有入口：

| 需求 | 组件/文件 |
| --- | --- |
| 页面内容变化、按压反馈 | [Motion.kt](../../app/src/main/java/cc/novelia/app/ui/theme/Motion.kt) 的 `MotionContent`、`motionClickable`、`pressFeedback`、`AppMotion` |
| 长列表、普通滚动区 | [AppPaging.kt](../../app/src/main/java/cc/novelia/app/ui/components/AppPaging.kt) 的 `AppLazyColumn`、`AppScrollColumn`、`appVerticalScroll` |
| 弹窗、确认框、菜单 | [AppDialogs.kt](../../app/src/main/java/cc/novelia/app/ui/components/AppDialogs.kt) 的 `AppDialog`、`AppAlertDialog`、`AppDropdownMenu` |
| 通用底部面板 | [AppSheet.kt](../../app/src/main/java/cc/novelia/app/ui/components/AppSheet.kt) 的 `AppSheet` |
| 阅读器面板 | [ReaderSheet.kt](../../app/src/main/java/cc/novelia/app/ui/reader/ReaderSheet.kt) 的 `ReaderSheet` |

静态模式下 `AppSheet` 使用有关闭按钮的对话框；窗口动画也必须由 `AppDialog` 单独关闭，单纯把 Compose 动画时间设为零不够。`AppPaging` 禁止惯性滑动，以抬手后一次翻一屏的方式输入，保留最多 48 dp、且不超过视口 20% 的重叠；同时提供按钮、PageUp/PageDown、滚轮节流和无障碍语义。

`MotionContent` 只在绘制层读取动画值，不保留退出页面；不要为了内容淡入而销毁整个编辑器或列表。静态模式的加载提示用文字替代无限转圈。图标按钮应具备中文内容描述，段落与页码应提供可读取语义，不能让 Canvas 文字成为无障碍空白。

## 6. Markdown、帖子与评论

### 渲染管线

[MarkdownText.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/MarkdownText.kt) 使用 Markwon/CommonMark 解析及 Android `TextView` 渲染，通过 `AndroidView` 嵌入 Compose；不是用 WebView 渲染 Markdown。渲染器包含表格、图片、站点扩展和剧透支持。调用方可以共享 `rememberMarkdownRenderer(c)`，避免为一列评论重复创建渲染器。

| 能力 | 实现与约束 |
| --- | --- |
| 评分、折叠块、删除线、裸链接 | [SiteMarkdownParser.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/SiteMarkdownParser.kt)、[SiteMarkdownPlugin.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/SiteMarkdownPlugin.kt)；扩展在 AST 层处理，保留代码块、行内代码、引用定义与已链接图片 |
| 剧透显示与点击 | [SpoilerMarkdown.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/SpoilerMarkdown.kt) |
| 文档内标题锚点 | [MarkdownAnchors.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/MarkdownAnchors.kt)；先等待实际文字布局，再滚动到标题 |
| 工具栏与文本选择 | [MarkdownEditor.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/MarkdownEditor.kt)、[MarkdownToolbar.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/MarkdownToolbar.kt) |
| 插入模板 | [MarkdownTemplates.kt](../../app/src/main/java/cc/novelia/app/data/markdown/MarkdownTemplates.kt) |
| 点击链接或图片 | 链接按 `documentUrl` 解析；图片地址处理器当前使用原站默认基地址，点击后用 [IllustrationViewer.kt](../../app/src/main/java/cc/novelia/app/ui/components/IllustrationViewer.kt) 查看 |

增加语法时应同时考虑解析、Span 渲染、触摸命中、锚点和工具栏输出，不能只对全文做字符串替换。折叠块 `::: details 标题` 支持嵌套，`::: star 数值` 的值限制在 0–5，`~~文字~~` 由专用节点表示；具体兼容行为以解析器和测试为准。

### 网页兜底

[SiteWebScreen.kt](../../app/src/main/java/cc/novelia/app/ui/web/SiteWebScreen.kt) 承接没有原生等价页的原站地址。为支持原站 SPA，启用 JavaScript 和 DOM storage；关闭文件/content 访问及混合内容，没有原生 JavaScript bridge。链接仍经统一分派，外站交给系统浏览器。页面离开时停止加载并销毁 WebView。

系统返回和工具栏返回都先检查 WebView 当前历史；不能只缓存 `onPageFinished` 时的历史状态，因为 SPA 可以 `pushState`。电子纸和减少动效由 [PagedSiteWebView.kt](../../app/src/main/java/cc/novelia/app/ui/web/PagedSiteWebView.kt) 处理。跨页锚点脚本只读取当前页面 fragment，并在用户操作、页面变化或超时后终止查找。

### 编辑与草稿

社区列表和文章详情分别位于 [CommunityScreen.kt](../../app/src/main/java/cc/novelia/app/ui/community/CommunityScreen.kt) 与 [ArticleScreen.kt](../../app/src/main/java/cc/novelia/app/ui/community/ArticleScreen.kt)；文章编辑器位于 [ComposeArticleScreen.kt](../../app/src/main/java/cc/novelia/app/ui/community/ComposeArticleScreen.kt)，评论位于 [CommentsPanel.kt](../../app/src/main/java/cc/novelia/app/ui/community/CommentsPanel.kt)。文章草稿键为 `article:<id>` 或 `article:new`，评论为 `comment:<site>:<parent>`。文章编辑和预览通过 `SaveableStateHolder` 保存各自状态，输入区根据键盘和剩余高度设定边界。

[EditorDraft.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/EditorDraft.kt) 的 `DraftPersistence` 是编辑器必须保留的约束：离开时读取实时输入；提交成功后仅当当前文本仍等于提交快照时清理草稿。请求期间新增的文字不得被成功回调清掉。文章另有 700 ms 防抖保存，评论在修改时更新草稿。

发布权限与本地编辑能力分开：没有 `canPost` 的账号仍能编辑、预览和保留草稿，提交时再次校验权限。帖子和评论写操作当前直接请求服务端，不应在文档或 UI 中把它们描述成可离线发布的队列。屏蔽用户、隐藏小说评论和已锁定讨论也应在新增展示入口保持一致。

## 7. 新增界面的实施顺序

1. 按 [源码归档规则](source-layout.md) 找到所属功能包、屏幕文件和数据层模型，确定是短暂 UI 状态还是需持久化的数据；新页面使用独立文件并保持 package 与目录一致。
2. 在导航图注册路由；从 `AppController` 或已有回调进入，正确编码参数并保留返回行为。
3. 用生命周期感知状态收集、`AsyncContent` 和明确的协程调度完成加载；账号有关的键和结果校验不能省略。
4. 复用 `Screen`、通用列表和弹窗；同时处理加载、空态、首次失败、刷新失败及重试。
5. 检查窄屏、横屏、大字号、键盘、宽屏、电子纸和减少动效；检查语义和实体键。
6. 在接近改动边界的现有测试中添加有意义的回归用例，再更新文档。

素材相关组件为 [MidoriCompanion.kt](../../app/src/main/java/cc/novelia/app/ui/feedback/MidoriCompanion.kt)、[StickerFeedback.kt](../../app/src/main/java/cc/novelia/app/ui/feedback/StickerFeedback.kt)。这些组件的存在不代表贴纸获得开源授权；授权状态以根目录 [NOTICE.md](../../NOTICE.md) 为准。

## 8. 回归测试定位

| 修改范围 | 优先检查的现有测试 |
| --- | --- |
| 通用加载和刷新 | [AsyncContentTest](../../app/src/androidTest/java/cc/novelia/app/ui/components/AsyncContentTest.kt) |
| 链接解析 | [ReaderAndLinksTest](../../app/src/test/java/cc/novelia/app/ReaderAndLinksTest.kt)、[MarkdownTest](../../app/src/test/java/cc/novelia/app/MarkdownTest.kt) |
| 主题、动画、弹窗 | [MotionTest](../../app/src/androidTest/java/cc/novelia/app/ui/theme/MotionTest.kt)、[ReducedMotionSheetTest](../../app/src/androidTest/java/cc/novelia/app/ui/components/ReducedMotionSheetTest.kt)、[StaticOverlayTest](../../app/src/androidTest/java/cc/novelia/app/ui/components/StaticOverlayTest.kt) |
| 全局电子纸分页 | [AppPagingTest](../../app/src/androidTest/java/cc/novelia/app/ui/components/AppPagingTest.kt)、[EInkAndCloudFilterTest](../../app/src/androidTest/java/cc/novelia/app/ui/reader/EInkAndCloudFilterTest.kt) |
| Markdown 语法与锚点 | [SiteMarkdownTest](../../app/src/test/java/cc/novelia/app/SiteMarkdownTest.kt)、[MarkdownAnchorsTest](../../app/src/test/java/cc/novelia/app/MarkdownAnchorsTest.kt)、[SiteMarkdownInteractionTest](../../app/src/androidTest/java/cc/novelia/app/ui/markdown/SiteMarkdownInteractionTest.kt) |
| Markdown 编辑与草稿 | [EditorStateRegressionTest](../../app/src/test/java/cc/novelia/app/EditorStateRegressionTest.kt)、[EditorDraftLifecycleTest](../../app/src/androidTest/java/cc/novelia/app/ui/markdown/EditorDraftLifecycleTest.kt)、[MarkdownToolbarInteractionTest](../../app/src/androidTest/java/cc/novelia/app/ui/markdown/MarkdownToolbarInteractionTest.kt)、[ArticleEditorLayoutTest](../../app/src/androidTest/java/cc/novelia/app/ui/community/ArticleEditorLayoutTest.kt) |
| 原站网页返回和链接 | [SiteWebNavigationTest](../../app/src/androidTest/java/cc/novelia/app/ui/web/SiteWebNavigationTest.kt) |

测试文件存在不等于每次文档更新都执行过设备测试；具体执行命令、设备前提和结果应写入对应改动的 PR 验证说明，参见 [CONTRIBUTING.md](../../CONTRIBUTING.md)。
