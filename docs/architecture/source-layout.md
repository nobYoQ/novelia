# 源码目录导航与归档规则

[返回架构与界面索引](README.md) · [文档总目录](../README.md) · [架构与状态流](architecture.md) · [界面与导航](ui-and-navigation.md)

界面源码按用户看到的功能组织，数据层按模型、网络、存储等职责组织。先找到所属目录，再找页面、服务或规则；跨功能使用的基础设施有单独目录。目录与 Kotlin package 保持一致，例如书架页面属于 `cc.novelia.app.ui.shelf`，本地状态存储属于 `cc.novelia.app.data.storage`。

## 工程入口

```text
app/src/main/java/cc/novelia/app/
├── MainActivity.kt          # 导航图、Intent、主题装配和全局界面
├── NoveliaApplication.kt    # 应用服务和初始化
├── data/                   # 13 个职责子包，以及旧 Worker 类名兼容入口
├── files/                  # 文件解析、转换、下载和导出
├── reader/                 # 阅读投影、锚点、搜索、分页算法和 TTS
└── ui/                     # 下表中的 17 个功能与共享子包
```

项目仍使用 `app` 和 `benchmark` 两个 Gradle 模块；这些子包只是源码组织方式。界面资源继续位于 `app/src/main/res/`，许可 assets 在构建时生成于 `app/build/generated/openSourceAssets/`；单元测试与设备测试分别位于 `app/src/test/`、`app/src/androidTest/`。移动测试文件并修改 package 后，需要同步更新运行命令中的测试类全名；测试应用 ID 和 runner 不随源码分包改变。

## UI 目录地图

下列路径均相对于 `app/src/main/java/cc/novelia/app/ui/`。

| 目录 | 归档内容 | 主要入口 |
| --- | --- | --- |
| `shelf/` | 本地与云端书架、收藏夹、阅读历史、分卷整理和更新结果 | [AdaptiveLibrary.kt](../../app/src/main/java/cc/novelia/app/ui/shelf/AdaptiveLibrary.kt)、[ShelfScreen.kt](../../app/src/main/java/cc/novelia/app/ui/shelf/ShelfScreen.kt)、[CloudShelf.kt](../../app/src/main/java/cc/novelia/app/ui/shelf/CloudShelf.kt)、[HistoryScreen.kt](../../app/src/main/java/cc/novelia/app/ui/shelf/HistoryScreen.kt) |
| `discover/` | 发现列表、排行榜、辅助搜索与发现页可见性判断 | [DiscoverScreen.kt](../../app/src/main/java/cc/novelia/app/ui/discover/DiscoverScreen.kt)、[RankScreen.kt](../../app/src/main/java/cc/novelia/app/ui/discover/RankScreen.kt)、[SearchAssistantPanel.kt](../../app/src/main/java/cc/novelia/app/ui/discover/SearchAssistantPanel.kt) |
| `book/` | 书籍详情、元数据编辑、文库条目维护和术语表 | [BookScreen.kt](../../app/src/main/java/cc/novelia/app/ui/book/BookScreen.kt)、[EditBookScreen.kt](../../app/src/main/java/cc/novelia/app/ui/book/EditBookScreen.kt)、[WenkuEditor.kt](../../app/src/main/java/cc/novelia/app/ui/book/WenkuEditor.kt)、[GlossaryScreen.kt](../../app/src/main/java/cc/novelia/app/ui/book/GlossaryScreen.kt) |
| `reader/` | 阅读器 Compose 界面、偏好、目录、插图、章节加载状态和离线面板 | [ReaderScreen.kt](../../app/src/main/java/cc/novelia/app/ui/reader/ReaderScreen.kt)、[ReaderPreferences.kt](../../app/src/main/java/cc/novelia/app/ui/reader/ReaderPreferences.kt)、[ReaderTocPane.kt](../../app/src/main/java/cc/novelia/app/ui/reader/ReaderTocPane.kt) |
| `community/` | 社区列表、文章详情、发帖与编辑、评论和分类 | [CommunityScreen.kt](../../app/src/main/java/cc/novelia/app/ui/community/CommunityScreen.kt)、[ArticleScreen.kt](../../app/src/main/java/cc/novelia/app/ui/community/ArticleScreen.kt)、[ComposeArticleScreen.kt](../../app/src/main/java/cc/novelia/app/ui/community/ComposeArticleScreen.kt)、[CommentsPanel.kt](../../app/src/main/java/cc/novelia/app/ui/community/CommentsPanel.kt) |
| `account/` | “我的”入口与认证登录页面 | [ProfileScreen.kt](../../app/src/main/java/cc/novelia/app/ui/account/ProfileScreen.kt)、[LoginScreen.kt](../../app/src/main/java/cc/novelia/app/ui/account/LoginScreen.kt) |
| `settings/` | 阅读与外观设置、屏蔽、云同步状态、阅读资料备份与恢复 | [SettingsScreen.kt](../../app/src/main/java/cc/novelia/app/ui/settings/SettingsScreen.kt)、[BlockedScreen.kt](../../app/src/main/java/cc/novelia/app/ui/settings/BlockedScreen.kt)、[CloudSyncScreen.kt](../../app/src/main/java/cc/novelia/app/ui/settings/CloudSyncScreen.kt)、[LibraryBackupScreen.kt](../../app/src/main/java/cc/novelia/app/ui/settings/LibraryBackupScreen.kt) |
| `notes/` | 书签与笔记列表、筛选及展示模型 | [NotesScreen.kt](../../app/src/main/java/cc/novelia/app/ui/notes/NotesScreen.kt)、[NotePresentation.kt](../../app/src/main/java/cc/novelia/app/ui/notes/NotePresentation.kt) |
| `downloads/` | 下载创建表单、任务列表与恢复提示 | [DownloadSheet.kt](../../app/src/main/java/cc/novelia/app/ui/downloads/DownloadSheet.kt)、[DownloadsScreen.kt](../../app/src/main/java/cc/novelia/app/ui/downloads/DownloadsScreen.kt)、[DownloadPresentation.kt](../../app/src/main/java/cc/novelia/app/ui/downloads/DownloadPresentation.kt) |
| `tools/` | EPUB 转换与压缩、文本换行整理、片假名统计的界面 | [ToolsScreen.kt](../../app/src/main/java/cc/novelia/app/ui/tools/ToolsScreen.kt) |
| `about/` | 帮助、版本、项目链接和离线许可证 | [AboutScreen.kt](../../app/src/main/java/cc/novelia/app/ui/about/AboutScreen.kt)、[OpenSourceLicensesScreen.kt](../../app/src/main/java/cc/novelia/app/ui/about/OpenSourceLicensesScreen.kt) |
| `web/` | 原站网页兜底和 WebView 分页交互 | [SiteWebScreen.kt](../../app/src/main/java/cc/novelia/app/ui/web/SiteWebScreen.kt)、[PagedSiteWebView.kt](../../app/src/main/java/cc/novelia/app/ui/web/PagedSiteWebView.kt) |
| `navigation/` | 共享控制器、根标签切换、导航动作与登录后继续 | [AppController.kt](../../app/src/main/java/cc/novelia/app/ui/navigation/AppController.kt)、[RootNavigation.kt](../../app/src/main/java/cc/novelia/app/ui/navigation/RootNavigation.kt)、[LoginContinuation.kt](../../app/src/main/java/cc/novelia/app/ui/navigation/LoginContinuation.kt) |
| `components/` | 多个功能复用的页面容器、加载态、书籍行、弹窗、分页与文档访问桥接 | [Screen.kt](../../app/src/main/java/cc/novelia/app/ui/components/Screen.kt)、[AsyncContent.kt](../../app/src/main/java/cc/novelia/app/ui/components/AsyncContent.kt)、[BookComponents.kt](../../app/src/main/java/cc/novelia/app/ui/components/BookComponents.kt)、[DocumentAccess.kt](../../app/src/main/java/cc/novelia/app/ui/components/DocumentAccess.kt) |
| `theme/` | 应用与阅读配色、动效、电子纸及减少动效的交互环境 | [Theme.kt](../../app/src/main/java/cc/novelia/app/ui/theme/Theme.kt)、[Motion.kt](../../app/src/main/java/cc/novelia/app/ui/theme/Motion.kt)、[AppInteractionMode.kt](../../app/src/main/java/cc/novelia/app/ui/theme/AppInteractionMode.kt)、[InteractionLocals.kt](../../app/src/main/java/cc/novelia/app/ui/theme/InteractionLocals.kt) |
| `markdown/` | Markdown 编辑、渲染、解析扩展、锚点与草稿生命周期 | [MarkdownText.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/MarkdownText.kt)、[MarkdownEditor.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/MarkdownEditor.kt)、[EditorDraft.kt](../../app/src/main/java/cc/novelia/app/ui/markdown/EditorDraft.kt) |
| `feedback/` | 贴纸展示、全局反馈及下载完成提示 | [MidoriCompanion.kt](../../app/src/main/java/cc/novelia/app/ui/feedback/MidoriCompanion.kt)、[StickerFeedback.kt](../../app/src/main/java/cc/novelia/app/ui/feedback/StickerFeedback.kt) |

## data 目录地图

下列路径均相对于 `app/src/main/java/cc/novelia/app/data/`。

| 目录 | 归档内容 | 主要入口 |
| --- | --- | --- |
| `model/` | 按领域拆分的 DTO、持久化快照、阅读设置和分页容器 | [BookModels.kt](../../app/src/main/java/cc/novelia/app/data/model/BookModels.kt)、[LibraryModels.kt](../../app/src/main/java/cc/novelia/app/data/model/LibraryModels.kt)、[ReaderSettings.kt](../../app/src/main/java/cc/novelia/app/data/model/ReaderSettings.kt) |
| `network/` | HTTP API、可取消请求、共享请求与云端收藏读取 | [NoveliaApi.kt](../../app/src/main/java/cc/novelia/app/data/network/NoveliaApi.kt)、[HttpCalls.kt](../../app/src/main/java/cc/novelia/app/data/network/HttpCalls.kt)、[SharedRequest.kt](../../app/src/main/java/cc/novelia/app/data/network/SharedRequest.kt) |
| `auth/` | 会话加密、账号绑定与会话代次检查 | [Session.kt](../../app/src/main/java/cc/novelia/app/data/auth/Session.kt)、[SessionState.kt](../../app/src/main/java/cc/novelia/app/data/auth/SessionState.kt) |
| `storage/` | 本地状态、合并写入、编解码与损坏恢复 | [LocalStore.kt](../../app/src/main/java/cc/novelia/app/data/storage/LocalStore.kt)、[LibraryStateCodec.kt](../../app/src/main/java/cc/novelia/app/data/storage/LibraryStateCodec.kt)、[StorageFormat.kt](../../app/src/main/java/cc/novelia/app/data/storage/StorageFormat.kt) |
| `library/` | 书架分组、文库挂载与阅读连续性规则 | [WenkuVolumes.kt](../../app/src/main/java/cc/novelia/app/data/library/WenkuVolumes.kt)、[ReadingContinuity.kt](../../app/src/main/java/cc/novelia/app/data/library/ReadingContinuity.kt) |
| `cache/` | 有界内存缓存、章节缓存索引和元数据缓存 | [LocalCache.kt](../../app/src/main/java/cc/novelia/app/data/cache/LocalCache.kt)、[MetadataCache.kt](../../app/src/main/java/cc/novelia/app/data/cache/MetadataCache.kt) |
| `documents/` | 本地文档分块存储与导入哈希索引 | [DocumentStorage.kt](../../app/src/main/java/cc/novelia/app/data/documents/DocumentStorage.kt)、[DocumentHashIndex.kt](../../app/src/main/java/cc/novelia/app/data/documents/DocumentHashIndex.kt) |
| `backup/` | 阅读资料备份、归档校验与恢复合并 | [LibraryBackupService.kt](../../app/src/main/java/cc/novelia/app/data/backup/LibraryBackupService.kt)、[LibraryBackupArchive.kt](../../app/src/main/java/cc/novelia/app/data/backup/LibraryBackupArchive.kt) |
| `sync/` | 云端变更队列、同步策略、会话隔离与后台重放 | [CloudMutationQueue.kt](../../app/src/main/java/cc/novelia/app/data/sync/CloudMutationQueue.kt)、[CloudSyncWorker.kt](../../app/src/main/java/cc/novelia/app/data/sync/CloudSyncWorker.kt) |
| `catalog/` | 书源、链接解析、查询表达式与关键词目录 | [Providers.kt](../../app/src/main/java/cc/novelia/app/data/catalog/Providers.kt)、[BookLinks.kt](../../app/src/main/java/cc/novelia/app/data/catalog/BookLinks.kt)、[KeywordCatalog.kt](../../app/src/main/java/cc/novelia/app/data/catalog/KeywordCatalog.kt) |
| `chapters/` | 章节请求、离线批次与译文新鲜度 | [ChapterRequests.kt](../../app/src/main/java/cc/novelia/app/data/chapters/ChapterRequests.kt)、[ChapterOffline.kt](../../app/src/main/java/cc/novelia/app/data/chapters/ChapterOffline.kt)、[ChapterFreshness.kt](../../app/src/main/java/cc/novelia/app/data/chapters/ChapterFreshness.kt) |
| `updates/` | 书架更新检查、检查顺序与系统通知 | [UpdateWorker.kt](../../app/src/main/java/cc/novelia/app/data/updates/UpdateWorker.kt)、[BookUpdates.kt](../../app/src/main/java/cc/novelia/app/data/updates/BookUpdates.kt)、[AppNotifications.kt](../../app/src/main/java/cc/novelia/app/data/updates/AppNotifications.kt) |
| `markdown/` | 与界面无关的 Markdown 链接与编辑模板规则 | [MarkdownLinks.kt](../../app/src/main/java/cc/novelia/app/data/markdown/MarkdownLinks.kt)、[MarkdownTemplates.kt](../../app/src/main/java/cc/novelia/app/data/markdown/MarkdownTemplates.kt) |

原来汇集不同模型的 `Models.kt` 已按领域拆入 `model/`，书源列表独立到 `catalog/Providers.kt`。`storage/StorageFormat.kt` 集中提供 `appJson` 与 `hashName`；新的模型或存储代码应引用同一序列化配置，避免另起一套格式。系统通知入口 `updates/AppNotifications.kt` 与更新任务实现分别存放。

文件下载的独立网络调度配置位于 `network/DownloadTransport.kt`，由应用级 `NoveliaApi` 复用，任务与文件生命周期仍由 `files/DownloadWorker.kt` 管理。手动缓存章节的有界并行逻辑位于 `chapters/ChapterBatch.kt`，账号、网络策略与落盘检查由 `ChapterOffline.kt` 负责。

书架更新状态变换集中在 [BookUpdateState.kt](../../app/src/main/java/cc/novelia/app/data/updates/BookUpdateState.kt)，详情刷新、后台检查与收藏移动共用基线；已到达章节的更新确认在 [ReadingProgress.kt](../../app/src/main/java/cc/novelia/app/data/library/ReadingProgress.kt)。显式云端收藏创建本地副本的规则在 [CloudFavoriteLocalCopy.kt](../../app/src/main/java/cc/novelia/app/data/library/CloudFavoriteLocalCopy.kt)，与补齐阅读元数据分开。站内新旧域名及端口规则由 [SiteUrls.kt](../../app/src/main/java/cc/novelia/app/data/catalog/SiteUrls.kt) 共用。

`data/` 根目录保留 [CloudSyncWorker.kt](../../app/src/main/java/cc/novelia/app/data/CloudSyncWorker.kt) 和 [UpdateWorker.kt](../../app/src/main/java/cc/novelia/app/data/UpdateWorker.kt) 两个带 `@Keep` 的兼容入口，仅用于升级后继续构造旧版已排队的任务。WorkManager 会把 Worker 类全名保存到内部数据库，旧任务仍可能使用旧名字；因此不能像普通 helper 一样直接删除或改名。实际逻辑位于 `sync/` 与 `updates/`，新开发应从职责包中的实现入手。此次源码分包没有改变原有持久化数据格式或字段。

## 测试目录地图

JVM 测试位于 `app/src/test/java/cc/novelia/app/`，其中原 `data/` 测试按生产职责分别放入 `auth/`、`backup/`、`cache/`、`documents/`、`network/`、`storage/`、`sync/`、`updates/`。`data/compat/` 下的 [LegacyWorkerCompatibilityTest.kt](../../app/src/test/java/cc/novelia/app/data/compat/LegacyWorkerCompatibilityTest.kt) 验证两个旧 Worker 的反射类名与构造签名，防止破坏升级任务恢复。其余既有 JVM 测试沿用原位置。

设备测试位于 `app/src/androidTest/java/cc/novelia/app/`，按下表中的界面、数据与验证职责存放。表中的类名均为示例；运行命令使用完整包名，见 [测试指南](../quality/testing.md)。

| 目录 | 测试内容与示例 |
| --- | --- |
| `ui/account/` | 个人页与账号入口：`ProfileStickerTest` |
| `ui/community/` | 文章编辑页面：`ArticleEditorLayoutTest` |
| `ui/components/` | 共享加载态、页码跳转、分页与静态弹层：`AsyncContentTest`、`PageControlsTest`、`AppPagingTest`、`StaticOverlayTest` |
| `ui/discover/` | 筛选与辅助搜索：`FilterPositionTest`、`SearchAssistantTest` |
| `ui/downloads/` | 下载表单布局：`DownloadSheetLayoutTest` |
| `ui/feedback/` | 贴纸与反馈：`MidoriCompanionTest`、`StickerFallbackTest`、`StickerFeaturesTest` |
| `ui/markdown/` | Markdown 编辑、渲染与草稿生命周期：`EditorDraftLifecycleTest`、`SiteMarkdownInteractionTest` |
| `ui/navigation/` | 根标签切换与返回书架：`RootNavigationTest` |
| `ui/reader/` | 阅读流程、完成与更新确认、排版、电子纸与插图：`AppFlowTest`、`ReaderCompletionFlowTest`、`ReaderAdaptiveUiTest`、`EInkReaderFlowTest` |
| `ui/shelf/` | 书架适配、同步提示与分卷交互：`AdaptiveLibraryTest`、`BookSyncUiTest`、`WenkuVolumeFlowTest` |
| `ui/theme/` | 主题动效：`MotionTest` |
| `ui/tools/` | 文件工具升级行为：`FileToolsUpgradeTest` |
| `ui/web/` | 网页导航与原站链接：`SiteWebNavigationTest`，部分用例需 `liveSite=true` |
| `data/backup/` | Android 文件环境下的备份恢复：`LibraryBackupFlowTest` |
| `integration/` | 需 `live=true` 的公开站点联调：`AuthPageTest`、`DownloadLiveTest`、`LiveReadOnlyTest`、`ForumLinksLiveTest` |
| `performance/` | 离线数据与排版测量：`PerformanceScenarioTest` |

测试 package 与目录对应，例如 `cc.novelia.app.ui.reader.AppFlowTest`；它与生产页面同包但处于不同 source set。目录分类不改变测试开关、数据隔离要求或执行副作用，不能仅凭目录名判断测试是否会访问网络。

## 容易放错的代码

| 修改内容 | 放置位置与边界 |
| --- | --- |
| 阅读偏好控件 | `ui/reader/ReaderPreferences.kt`；全局设置页和单书阅读器共同调用，偏好数据模型在 `data/model/ReaderSettings.kt` |
| 阅读段落投影、进度与 TTS | 顶层 `reader/`；需要 Compose 或界面生命周期的部分放 `ui/reader/` |
| 文库分卷顺序和挂载 | `ui/shelf/WenkuVolumeSheets.kt`、`VolumeReorder.kt`；文库条目元数据编辑放 `ui/book/`，持久数据规则在 `data/library/WenkuVolumes.kt` |
| 个人术语表 | `ui/book/GlossaryScreen.kt` 同时服务本地与原站术语表；文件工具页只提供进入它的入口 |
| 收藏选择和待处理状态展示 | `ui/shelf/FavoriteSheet.kt`、`FavoritePresentation.kt`；笔记的展示模型独立在 `ui/notes/NotePresentation.kt` |
| 下载进度页面与真正的下载任务 | 页面在 `ui/downloads/`；WorkManager、下载文件和解析逻辑在顶层 `files/` |
| 通用弹窗、面板与阅读器面板 | `ui/components/AppDialogs.kt`、`AppSheet.kt` 提供跨页面入口；阅读器专用布局留在 `ui/reader/ReaderSheet.kt` |
| 系统文档选择后的导入/读取桥接 | `ui/components/DocumentAccess.kt`；具体格式解析、持久化和恢复分别由 `files/` 与 `data/` 负责 |
| 错误与日期文字 | `ui/components/UiMessages.kt`；同步时间展示在 `ui/components/SyncTime.kt` |
| 全屏图片查看 | `ui/components/IllustrationViewer.kt` 为不同功能复用；阅读正文内的图片展示位于 `ui/reader/ReaderIllustration.kt` |

## 新文件的放置规则

1. **先按界面归属。** 新页面放入已有功能目录，文件通常命名为 `XxxScreen.kt`。同一个文件可以保留该页面的私有 Composable 和紧密相关 helper；无关的独立页面使用独立文件。只有确实新增了功能边界时才新增目录，并更新上表。
2. **功能专用代码就近存放。** 例如笔记筛选模型、书架撤销逻辑和下载恢复提示与各自页面放在一起。纯 Kotlin 并不自动意味着应该移入全局工具目录。
3. **跨功能复用再提取。** 没有业务归属的通用 UI 可放入 `components/`；主题、Markdown、导航与反馈优先进入已有专用包。新功能不应重新堆回 `ui/` 根目录，也不要创建一个持续增长的杂项文件。
4. **目录与声明同步。** `ui/notes/NotesScreen.kt` 对应 `package cc.novelia.app.ui.notes`。移动文件时更新生产代码、测试和文档中的 imports/路径；按符号实际使用添加引用，避免依赖旧的全包导入。同时检查已提交的 [性能 Profile](../quality/baseline-profiles.md)，其中的类和方法描述符不会自动跟随源码移动。
5. **保持可见性最小。** 同文件 helper 优先 `private`；同模块确需跨文件复用时使用 `internal`。移动代码前先核对文件私有声明，不能只移动文件后把所有 helper 改成公开接口。
6. **保持职责边界。** 网络请求合约、存储、会话和后台任务继续由 `data/`、`files/` 等实现承担；不要为了让目录名称统一而把这些代码塞进页面文件。共享组件新增对具体功能包的依赖时，先考虑是否能用参数或回调表达需求。
7. **让整理可验证。** 纯目录整理保持路由、资源名、测试标识和数据格式不变；验证编译、相关测试及文档链接。若同时改行为，在 PR 中明确说明并提供相应回归结果。

## 查找与维护

从页面定位时，先查目录地图，再读 [MainActivity.kt](../../app/src/main/java/cc/novelia/app/MainActivity.kt) 的路由装配。从业务行为定位时，结合 [架构](architecture.md)、[网络与同步](../network/network-and-sync.md)、[数据与存储](../data/data-and-storage.md)、[阅读器](../features/reader.md) 和 [文件与下载](../features/files-and-downloads.md) 查找实际执行代码。

新增页面、移动文件或拆分组件的 PR 应同时更新本页和受影响的专题文档。Kotlin package 变更不自动改变 Android 应用 ID、路由字符串或持久化键；测试 package 若有改动，需要更新类名筛选参数，但 runner 仍为 `cc.novelia.app.test/androidx.test.runner.AndroidJUnitRunner`。贴纸代码的归档不改变素材授权状态，见 [NOTICE.md](../../NOTICE.md)。
