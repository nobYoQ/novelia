# 界面与导航

[架构目录](README.md) · [文档首页](../README.md)

界面使用 Compose，导航图集中在 [MainActivity.kt](../../app/src/main/java/cc/novelia/app/MainActivity.kt)。业务页面通过 [AppController](../../app/src/main/java/cc/novelia/app/ui/navigation/AppController.kt) 访问服务和导航；共享列表、弹窗、面板同时处理普通屏幕、电子纸和减少动效。

## 页面和路由

| 路由 | 用途 |
| --- | --- |
| `shelf`、`discover?query={query}`、`community`、`profile` | 四个根标签 |
| `rank`、`keywords` | 排行榜、标签库 |
| `book/{provider}/{id}`、`reader/{provider}/{id}/{chapter}` | 书籍详情、阅读器 |
| `article/{id}`、`compose?article={article}&draft={draft}` | 文章详情、新建/编辑/续写草稿 |
| `login`、`forum-login` | 小说服务登录、独立论坛登录 |
| `forum-rules`、`forum-strikes` | 社区守则、处罚记录 |
| `settings?section={section}`、`backup`、`sync` | 设置分类（section 可省略）、资料备份、网络与同步；`section=NETWORK` 与 `sync` 共用页面 |
| `edge-toolbar`、`edge-toolbar-edit` | 边缘工具栏偏好、快捷入口编辑 |
| `webdav`、`webdav-server` | WebDAV 同步、服务器配置 |
| `updates`、`downloads`、`tools` | 更新中心、下载、文件工具 |
| `notes`、`blocked`、`history` | 笔记、屏蔽、阅读历史 |
| `wenku-new`、`edit/{provider}/{id}`、`glossary/{provider}/{id}` | 文库创建、资料编辑、术语 |
| `about`、`licenses`、`web?url={url}` | 关于、许可证、网页兜底 |
| `ocr` | 旧返回栈兼容入口，重定向至工具页 |

通常使用控制器提供的语义方法：

```kotlin
c.book(ref)                      // 在线书进详情，本地书进正文
c.read(ref, chapterId)
c.go("settings")
c.back()
c.openMarkdownLink(url, baseUrl)
```

通过已有入口编码动态参数，不把外部 URL 或文件名直接拼进路由。`go(replaceTop = true)` 实际设置的是 `launchSingleTop`，并不会弹出任意栈顶页面。

根标签走 [RootNavigation.kt](../../app/src/main/java/cc/novelia/app/ui/navigation/RootNavigation.kt)：保存切走页面的状态，切回书架时显示书架列表而不恢复旧详情子栈。普通书籍、帖子跳转仍保留自己的返回历史。

设置分类保留页内导航和 `rememberSaveable` 分类恢复，通过 `SeekableTransitionState` 驱动整页 `AnimatedContent`，标题与内容一起进出。设置与根导航复用 [AppPageMotion.kt](../../app/src/main/java/cc/novelia/app/ui/theme/AppPageMotion.kt) 的 24 dp 位移、220 ms 入场/位移及 120 ms 淡出；返回反向播放，不启用尺寸动画。退场页面隐藏无障碍入口；静态模式及转场中开启减少动效直接完成切换，不重建当前页面的记忆状态。

## 边缘工具栏覆盖层

[EdgeToolbar.kt](../../app/src/main/java/cc/novelia/app/ui/toolbar/EdgeToolbar.kt) 挂在 `NavHost` 同级，观察工具栏偏好和活动下载数量的轻量投影。圆点与面板不改变正文视口；面板一次测量后按圆点中心定位并夹在可用区域内，内容超高时滚动。根导航报告实际底栏高度和侧栏宽度，阅读器按导航项 ID 报告菜单、阻挡浮层及上下工具栏高度，避免退出中的旧章节影响当前页面。

圆点坐标只使用窗口尺寸及不随系统栏显隐变化的安全边距，页面边距仅参与面板避让，不能用于重新换算圆点位置。圆点的拖动位置和闲置状态独立于导航项保留；临时隐藏和切换路由不唤醒圆点、不重启闲置倒计时，只有面板展开状态在离页时关闭。

共用 `AppDialog`、`AppAlertDialog`、`AppSheet` 和 `AppDropdownMenu` 通过 `AppOverlayRegistry` 登记显示状态，支持嵌套。工具栏只在允许的页面、前台且窗口有焦点、键盘和其他浮层关闭时显示；隐藏或导航后关闭展开面板。面板打开时拦截外部点击和系统返回，并隐藏底层页面的无障碍节点。

显隐过渡通过绘制层透明度实现，面板额外以圆点方向为轴心轻微缩放；退场结束后移除内容，退场期间禁用操作、滚动和无障碍入口。面板离页退场沿用最后展开时的边界，避免新页面的避让区域使面板跳位。动画遵循 `appReducedMotion()`，运行中切到电子纸或减少动效也立即采用目标状态。

普通拖动只更新临时位置，电子纸手势只在协程内累计目标位置、松手后一次更新视觉；两者均在松手后通过 `LocalStore.update` 保存 0–1 相对高度，取消拖动恢复原位置。页面隐藏、导航、边界或电子纸模式变化会取消当前拖动。入口 ID 使用固定白名单；编辑页独立保存草稿，完成后一次提交。偏好模型兼容缺省字段，本机加载修正异常值，设置和整库导入严格校验；WebDAV 投影不包含工具栏偏好。

## 状态和异步加载

应用启动先显示 [StartupScreen](../../app/src/main/java/cc/novelia/app/startup/StartupScreen.kt)，按书库、连接配置和会话、标签、界面缓存的实际准备步骤更新状态。`NoveliaApplication.initialization` 在 IO 作用域完成这些准备，`MainActivity` 观察 `startup.progress`，完成后才装配主导航。初始化失败保留加载界面，点击重试继续；协程取消不会转成可重试错误。加载页不添加最短等待，已有进程恢复时可直接进入主界面。网络更新检查、同步和清理仍按后台任务运行。

| 状态 | 放置方式 |
| --- | --- |
| 持久资料 | 观察 `LocalStore.state`，通过 `update` 提交 |
| 当前身份 | 观察对应小说或论坛 `Session.profile` |
| 可恢复的轻量输入 | `rememberSaveable`，用书籍/帖子/目标 ID 区分 |
| 弹窗、请求 Job、临时加载态 | `remember` 和页面协程作用域 |
| 请求、监听和资源 | `LaunchedEffect`、`produceState`、`DisposableEffect` |

`AppController` 属于当前 Compose 根树，临时字段不能跨进程恢复。`c.action { ... }` 统一处理操作错误，但不会自动把代码移到 IO 调度器。文件读写要显式切换调度器，取消异常要继续传播。

[AsyncContent](../../app/src/main/java/cc/novelia/app/ui/components/base/AsyncContent.kt) 区分“换实体”和“刷新当前实体”：换实体重建结果；刷新保留旧内容和组合实例，失败时保留内容并显示重试入口。请求完成前会检查取消。实体键要包含影响结果的条件，账号/书源相关页面还要包含相应身份。

发现推荐、小说列表、排行榜、云端书架、论坛分类和帖子列表在当前 `AppController` 内保留成功结果。详情返回或根标签切换时，条件相同则以缓存直接恢复内容及列表位置，不重新请求；发现页也不因收藏同步完成而重取。缓存键包含会话/书源及缓存代次，云端书架仍随收藏同步更新，帖子列表另包含分类、页码、搜索、排序、标签和黑名单版本；论坛最多保留 24 个列表结果。手动刷新会更新缓存，刷新失败仍可返回已有内容；发布、编辑、删除或更改帖子收藏后清除论坛列表缓存。进程或控制器重建后首次进入仍会加载。

小说详情、发现列表、社区列表和帖子详情通过 `revealContent` 在成功内容首次出现时播放浮现效果，已有内容刷新不重播。详情内切换简介、目录或分卷、讨论时由分页容器负责横向移动，页面内容保持不透明，避免滑动中途因当前页变化而重播浮现、造成闪烁。发现页标题栏通过动作区宽度动画平滑调整搜索框长度；关于页菜单进入时上浮淡入，连续快速点击版本号五次出现的举手彩蛋使用有限的弹跳、挤压与招手动画。这些动效均遵守统一的减少动效和电子纸设置。

列表使用稳定 key，不用排序下标代替身份。共用页码控件内部从 0 计数、界面从 1 展示；数据分页与电子纸的按屏滚动是两种操作。

## 大屏、电子纸和动效

| 条件 | 当前行为 |
| --- | --- |
| Activity 可用宽度至少 600 dp | 根标签改用侧边导航 |
| 书架局部宽度至少 840 dp | 书架与详情双栏 |
| 阅读器局部宽度至少 840 dp | 常驻目录侧栏 |
| 减少动效或系统关闭动画 | 取消动画、波纹和弹性反馈 |
| 电子纸 | 使用静态交互，通用列表支持按屏翻动 |

断点依据**组件可用宽度**。侧边导航已经占用的空间会影响子页面，不能仅按设备型号判断双栏。

“fuck 圆角”由 `NoveliaTheme` 提供启用的分类集合。主题形状用于普通卡片和容器，自绘形状通过 `appShape` 指定分类。封面裁切统一使用 `appBookCoverShape`，按钮和输入框通过 `appControlShape` 取得形状，弹窗和底部面板使用 `appPanelShape` / `appBottomSheetShape`。这些组件以默认圆角为基准，避免跟随卡片分类一起切换。导航指示器、阅读控件等自绘组件读取自身的分类。

优先复用 `Screen`、`AppLazyColumn` / `AppScrollColumn`、`AppDialog`、`AppSheet` 和 `MotionContent`。这些组件在 [ui/components](../../app/src/main/java/cc/novelia/app/ui/components) 与 [ui/theme](../../app/src/main/java/cc/novelia/app/ui/theme)。静态模式的面板会改用对话框，窗口动画也需要关闭。

每次打开面板是一段独立会话，默认从顶部开始；需要保留的输入应与滚动位置分开管理。阅读偏好在一次打开期间保留页签位置，关闭再打开回到初始页签，已保存的设置不变。

应用主题与阅读正文配色分开。单书电子纸只影响该书阅读界面及进出转场；自动分页也可在普通屏幕使用。具体正文布局见[阅读器](../features/reader.md)。

## 外部链接和网页

Activity 接收 `intent.dataString` 或分享文本，只恢复尚未消费的待处理链接，避免重建后再次跳转。

链接分派分两条入口：书源文本由 [BookLinks](../../app/src/main/java/cc/novelia/app/data/catalog/BookLinks.kt) 解析；Markdown 相对地址和站内路由由 [MarkdownLinks](../../app/src/main/java/cc/novelia/app/data/markdown/MarkdownLinks.kt) 处理。支持的书籍、章节、帖子和守则进入原生页面；需要保留网页语义的站内地址进入 `SiteWebScreen`，外站交给浏览器。

站点 WebView 为 SPA 开启 JavaScript 和 DOM storage，关闭文件/content 访问及混合内容，没有原生 JavaScript bridge。返回键先检查 WebView 当前历史，包括 SPA 的历史变化。登录使用统一原生表单，另见[认证](../network/authentication.md)。

## Markdown 和编辑器

[MarkdownText.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/MarkdownText.kt) 用 Markwon/CommonMark 解析，再由 Android TextView 嵌入 Compose 渲染。站点评分、折叠块、删除线、剧透、图片和锚点都有专门处理；新增语法应修改解析结构，避免全文正则替换破坏代码块或已有链接。

编辑器共享工具栏、预览、链接粘贴和 [EditorDraft.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/EditorDraft.kt) 的草稿生命周期。提交成功只能清理与发送快照相同的内容；慢请求期间继续输入的文字必须保留。帖子与评论的站点、权限和草稿键见[社区](../features/community.md)。

## 修改后的验证

新增页面至少检查返回栈、重复进入、实体切换、刷新失败、旋转、大字号、键盘和静态交互。双栏还要验证“宽屏选书 → 缩窄 → 返回 → 再扩大”，不能只看两张截图。

相关设备测试按功能位于 [ui 测试目录](../../app/src/androidTest/java/cc/novelia/app/ui)。优先看 `RootNavigationTest`、`AdaptiveLibraryTest`、`AsyncContentTest`、`PanelSessionTest`、`ReducedMotionSheetTest` 及 Markdown 测试；运行方法见[测试指南](../quality/testing.md)。
