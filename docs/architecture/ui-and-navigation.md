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

[AsyncContent](../../app/src/main/java/cc/novelia/app/ui/components/AsyncContent.kt) 区分“换实体”和“刷新当前实体”：换实体重建结果；刷新保留旧内容和组合实例，失败时保留内容并显示重试入口。请求完成前会检查取消。实体键要包含影响结果的条件，账号/书源相关页面还要包含相应身份。

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

优先复用 `Screen`、`AppLazyColumn` / `AppScrollColumn`、`AppDialog`、`AppSheet` 和 `MotionContent`。这些组件在 [ui/components](../../app/src/main/java/cc/novelia/app/ui/components) 与 [ui/theme](../../app/src/main/java/cc/novelia/app/ui/theme)。静态模式的面板会改用对话框，窗口动画也需要关闭。

每次打开面板是一段独立会话，默认从顶部开始；需要保留的输入应与滚动位置分开管理。阅读偏好在一次打开期间保留页签位置，关闭再打开回到初始页签，已保存的设置不变。

应用主题与阅读正文配色分开。单书电子纸只影响该书阅读界面及进出转场；自动分页也可在普通屏幕使用。具体正文布局见[阅读器](../features/reader.md)。

## 外部链接和网页

Activity 接收 `intent.dataString` 或分享文本，只恢复尚未消费的待处理链接，避免重建后再次跳转。

链接分派分两条入口：书源文本由 [BookLinks](../../app/src/main/java/cc/novelia/app/data/catalog/BookLinks.kt) 解析；Markdown 相对地址和站内路由由 [MarkdownLinks](../../app/src/main/java/cc/novelia/app/data/markdown/MarkdownLinks.kt) 处理。支持的书籍、章节、帖子和守则进入原生页面；需要保留网页语义的站内地址进入 `SiteWebScreen`，外站交给浏览器。

站点 WebView 为 SPA 开启 JavaScript 和 DOM storage，关闭文件/content 访问及混合内容，没有原生 JavaScript bridge。返回键先检查 WebView 当前历史，包括 SPA 的历史变化。登录 WebView 的消息校验另见[认证](../network/authentication.md)。

## Markdown 和编辑器

[MarkdownText.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/MarkdownText.kt) 用 Markwon/CommonMark 解析，再由 Android TextView 嵌入 Compose 渲染。站点评分、折叠块、删除线、剧透、图片和锚点都有专门处理；新增语法应修改解析结构，避免全文正则替换破坏代码块或已有链接。

编辑器共享工具栏、预览、链接粘贴和 [EditorDraft.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/EditorDraft.kt) 的草稿生命周期。提交成功只能清理与发送快照相同的内容；慢请求期间继续输入的文字必须保留。帖子与评论的站点、权限和草稿键见[社区](../features/community.md)。

## 修改后的验证

新增页面至少检查返回栈、重复进入、实体切换、刷新失败、旋转、大字号、键盘和静态交互。双栏还要验证“宽屏选书 → 缩窄 → 返回 → 再扩大”，不能只看两张截图。

相关设备测试按功能位于 [ui 测试目录](../../app/src/androidTest/java/cc/novelia/app/ui)。优先看 `RootNavigationTest`、`AdaptiveLibraryTest`、`AsyncContentTest`、`PanelSessionTest`、`ReducedMotionSheetTest` 及 Markdown 测试；运行方法见[测试指南](../quality/testing.md)。
