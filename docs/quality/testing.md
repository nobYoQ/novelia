# 测试与验收

[质量目录](README.md) · [文档首页](../README.md)

按改动选择验证层次。纯逻辑用 JVM 测试，Android 生命周期和排版用设备测试，服务端兼容用显式联调。[Preview APK](../../.github/workflows/preview-apk.yml) 自动构建 Release 预览包及其依赖的 Go 单元测试，包含 AGP 内置的致命问题 Lint 检查，不运行 JVM 单元测试、完整 Lint 或设备测试。PR 需要写明实际运行结果，不能把测试文件存在或默认跳过当成通过。

## 常用命令

以下在仓库根目录的 PowerShell 7 执行，环境见[构建指南](../development/getting-started.md)。

```powershell
# Debug 构建、JVM 单元测试、Lint，并归档 APK
./scripts/build-debug.ps1 -Verify

# 含 R8 的本地 Release 检查，使用测试证书
./scripts/build-release.ps1 -Verify

# 聚焦一个逻辑测试
./build.ps1 -Tasks @(':app:testDebugUnitTest', '--tests', 'cc.novelia.app.data.network.ApiContractTest')

# Go 原生包及本地解析器依赖测试
./build.ps1 -Tasks @(':app:testEchNative')
```

`-Verify` 不运行设备测试。修改依赖、资源、序列化、反射、混淆或发行配置时需要 Release 检查。普通构建可能首次下载工具/依赖，已有完整缓存时才加 `-Offline`。

| 报告 | 位置 |
| --- | --- |
| JVM HTML / XML | `app/build/reports/tests/testDebugUnitTest/` / `app/build/test-results/testDebugUnitTest/` |
| Lint | `app/build/reports/lint-results-debug.html` |
| Gradle 设备报告 | `app/build/reports/androidTests/connected/` |
| 脚本日志 | `outputs/logs/` |

Release 报告对应替换变体名。警告、失败和跳过分别记录。

## 按功能选测试

下表省略统一前缀 `cc.novelia.app.`，类名以各文件 package 为准。

| 修改范围 | JVM 入口 |
| --- | --- |
| 请求、账号和书源 | `data.network.ApiContractTest`、`data.auth.SessionIsolationTest`、`data.network.BookSourceTest` |
| ECH 和日志 | `data.network.EchInterceptorTest`、`EchApiContractTest`、`EchDiagnosticsTest`、`NetworkLoggingTest`（后三者也在 data.network） |
| 网络切换与请求代次 | `data.network.NetworkChangesTest`、`NetworkConnectionsTest`；Go `network_test.go` |
| 论坛协议与回复 | `data.community.ForumApiContractTest`、`ForumAccountApiTest`、`ForumBlacklistApiTest`、`ForumCommunityRulesTest`、`ForumRulesTest`、`ForumReplyPageCacheTest`（均在 data.community） |
| 原站队列 | `data.sync.CloudMutationQueueTest`、`CloudSyncPolicyTest`、`BoundCloudSyncTest`（均在 data.sync） |
| WebDAV | `data.webdav.WebDavMergeTest`、`WebDavProjectionTest`、`WebDavExchangeTest`、`WebDavClientTest`（均在 data.webdav） |
| 持久化和恢复 | `data.storage.StatePersistenceTest`、`data.storage.LibraryStateCodecTest`、`data.backup.LibraryBackupTest` |
| 边缘工具栏偏好、同步与定位 | `ui.toolbar.EdgeToolbarSettingsTest`、`ui.toolbar.EdgeToolbarGeometryTest` |
| 导入、下载、导出 | `files.FileImportRegressionTest`、`files.importing.DocumentImportBatchTest`、`files.importing.DocumentImportNameTest`、`files.downloads.DownloadFilesTest`、`files.exporting.PendingExportFilesTest` |
| 阅读投影、分页、定位 | `reader.ReaderProjectionTest`、`reader.ReaderExactSearchTest`、`reader.StaticPaginationTest`、`ui.reader.ReaderChapterLoadTest` |
| 位置、历史、更新 | `data.library.ReadingProgressUpdatesTest`、`data.storage.LocalReadingProgressTest`、`data.updates.BookUpdateStateTest` |
| 应用版本与下载 | `data.appupdate.AppReleaseTest`、`AppReleaseDownloadCoordinatorTest`、`AppUpdateSettingsTest`（均在 data.appupdate） |
| 搜索和标签 | `data.catalog.SearchExpressionBoundaryTest`、`NovelLocalFilterTest`、`KeywordLibraryTest`、`SavedSearchPresetTest`（均在 data.catalog） |
| Markdown 和草稿 | `ui.markdown.SiteMarkdownTest`、`data.markdown.MarkdownLinkPasteTest`、`ui.markdown.EditorStateRegressionTest`、`ui.community.ArticleDraftsTest` |
| 旧 Worker 兼容 | `data.compat.LegacyWorkerCompatibilityTest` |

完整文件在 [JVM 测试目录](../../app/src/test/java/cc/novelia/app)。新用例应复现真实故障或保护不变量，例如换号后旧响应不能写回、损坏备份不能覆盖资料、重排后定位同一原文段。

## 设备测试

部分测试修改应用书架、设置、草稿、Cookie 或文件，使用专用模拟器/设备。先确认序列号，再选择相关类：

```powershell
adb devices
$env:ANDROID_SERIAL = '<专用测试设备序列号>'
./build.ps1 -Tasks @(':app:connectedDebugAndroidTest', '-Pandroid.testInstrumentationRunnerArguments.class=cc.novelia.app.ui.reader.AppFlowTest')
```

全量任务为 `:app:connectedDebugAndroidTest`，包含较耗时的场景；只编译测试包用 `:app:assembleDebugAndroidTest`。编译成功不代表设备测试已运行。

设备测试按 [ui](../../app/src/androidTest/java/cc/novelia/app/ui)、[data](../../app/src/androidTest/java/cc/novelia/app/data)、[files](../../app/src/androidTest/java/cc/novelia/app/files)、[integration](../../app/src/androidTest/java/cc/novelia/app/integration) 和 [performance](../../app/src/androidTest/java/cc/novelia/app/performance) 等职责组织。重点入口包括：

- 导航和双栏：`RootNavigationTest`、`AdaptiveLibraryTest`。
- 边缘工具栏：`EdgeToolbarTest`，覆盖闲置后单击展开、拖动保存、跨页面及临时隐藏时保留位置和闲置计时、显隐中间帧与快速反向切换、静态模式即时显隐、电子纸松手定位和拖动取消；`ReaderToolbarOverlayTest` 额外验证阅读菜单、系统状态栏和历史页切换不挪动圆点，以及单书电子纸设置对工具栏显隐和拖动的作用。
- 正文和手势：`ReaderAdaptiveUiTest`、`ReaderToolbarOverlayTest`、`ReaderTapNavigationTest`、`ReaderSpacingTest`；位置恢复与阅读返回另看 `ReadingContinuityUiTest`、`ReaderCompletionFlowTest`。阅读器组件拆分仍需覆盖工具栏开关不改变正文视口、滚动/分页互换保留锚点、后台再前台及段落测量。
- 论坛与编辑：`ForumEditorUiTest`、`ForumAccountUiTest`、`ForumCommentThreadTest`、`ForumLayoutUiTest`、`EditorDraftLifecycleTest`；原生链接粘贴与撤销另见 `ui.markdown.MarkdownLinkPasteTest`。
- 会话：`NativeLoginSessionTest`、`MirrorSessionTest`、`ForumMirrorSessionTest`、`ForumSessionIsolationTest`、`ForumSharedAuthTest`；原生登录界面另见 `integration.AuthPageTest`、`ui.account.ForumMirrorLoginTest` 和 `ui.settings.BookSourceNavigationTest`。
- Android 平台解析：`ForumCommunityRulesParserAndroidTest`。
- 文件/恢复：`BookExportUiTest`、`LibraryBackupFlowTest`、`files.downloads.BookFileActionsTest`、`files.importing.DownloadImportCleanupTest`；书架移除时的持久化清理仍由 `data.storage.ShelfCopyCleanupTest` 覆盖。
- 系统文件分享：`files.importing.SharedDocumentIntentsTest` 检查分享菜单的类型匹配和附件读取；`ui.shelf.SharedDocumentImportTest` 检查冷启动、运行中再次分享、ClipData 多文件、旋转恢复、重复文件和不支持格式。可以使用 `-PisolatedLibraryTests=true` 构建独立测试包。
- 静态交互：`EInkReaderFlowTest`、`ReducedMotionSheetTest`、`PanelSessionTest`。`EInkReaderFlowTest` 同时覆盖物理按键、滚动/分页锚点互换、搜索与重新进入阅读；离开阅读器后按现有“阅读与朗读”“外观与操作”分类检查设置。
- 设置分类转场：`SettingsMotionTest` 覆盖六个分类的进入/返回中间帧、快速系统返回、电子纸与减少动效直接切换、途中关闭动画及保留滚动位置；`SettingsCategoriesTest` 验证分类与重建恢复。

直接使用 instrumentation 时 runner 为 `cc.novelia.app.test/androidx.test.runner.AndroidJUnitRunner`。测试中止、`INSTRUMENTATION_FAILED` 和跳过都不能记作通过。

## 可选真实网络验证

默认关闭，仅在需要核对外部服务时显式选择测试与开关：

| 测试 | 开关 | 范围 |
| --- | --- | --- |
| `integration.LiveReadOnlyTest` | `live=true` | 公开小说列表、详情、章节 |
| `integration.ForumLiveReadOnlyTest` | `live=true` | 论坛公开读取 |
| `integration.ForumLinksLiveTest` | `live=true` | 公开文章中的链接解析 |
| `integration.DownloadLiveTest` | `live=true` | 生成、下载、解析已有译文文件 |
| `ui.web.SiteWebNavigationTest` | `liveSite=true` | 部分站内网页用例 |
| `integration.EchNativeTest` | `echLive=true` | Android 原生 ECH 联网与桥接 |
| Go `ech_live_test.go` | 环境变量 `NOVELIA_ECH_LIVE=1` | Go 公网检查 |

例如：

```powershell
./build.ps1 -Tasks @(':app:connectedDebugAndroidTest', '-Pandroid.testInstrumentationRunnerArguments.class=cc.novelia.app.integration.LiveReadOnlyTest', '-Pandroid.testInstrumentationRunnerArguments.live=true')
```

下载联调会访问服务并写测试文件；“只读”不等于没有本地副作用。真实发帖、上传、修改和删除需要单独授权及专用账号，不属于普通联调。

## 提交前如何说明结果

记录提交、命令、变体、报告位置，以及设备测试的型号/API。只列实际执行的结果，说明未验证项；历史记录中的固定测试数量不作为当前门禁。

UI 检查普通/电子纸/减少动效、深浅主题、大字号、窄屏/横屏、键盘和返回栈。迁移检查区分新装、同签名覆盖升级和换签名备份恢复。真实性能结论按[性能指南](performance.md)测量。

纯文档修改核对源码依据、相对链接、标题锚点、命令语法和编码即可，通常无需重跑应用构建。
