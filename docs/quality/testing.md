# 测试与验收

[返回质量验证索引](README.md) · [文档总目录](../README.md)

## 测试层次

| 层次 | 位置 | 适合验证 | 限制 |
| --- | --- | --- | --- |
| JVM | [app/src/test](../../app/src/test/java/cc/novelia/app) | 纯逻辑、JSON、查询参数、MockWebServer、文件及同步边界 | 不能证明真实 Android UI、WebView、Keystore 和字体排版正确 |
| 设备 instrumentation | [app/src/androidTest](../../app/src/androidTest/java/cc/novelia/app) | Compose 导航、手势、布局、Android 文件和排版行为 | 需要专用设备/模拟器；部分测试修改应用内数据 |
| 显式启用的站点联调 | 同上，`live` / `liveSite` 参数 | 公开接口、已有内容下载、认证表单和站内页面 | 依赖外部网络、站点与内容；默认跳过 |
| 性能 | [benchmark](../../benchmark)、`PerformanceScenarioTest` | 启动、帧时间、数据处理和排版 | 结果需附设备与构建条件，见 [性能指南](performance.md) |
| 手工验收 | 专用真机/模拟器 | 文件选择器、真实 TTS 引擎、后台行为、升级迁移 | 明确记录测试设备与未验证范围 |

当前没有 CI，提交者和维护者需手动执行检查。测试数量会随代码变化，不把固定数字当作门禁；报告中的成功、失败、跳过应分别记录。历史通过记录不能代表当前提交通过。

测试目录与 package 保持一致，分类地图见 [源码目录导航](../architecture/source-layout.md)。JVM 的 `data/` 测试按生产职责分包；设备测试按 `ui/` 界面与组件、`data/backup/`、`integration/`、`performance/` 分类。`--tests`、instrumentation 的 `class` 参数和 IDE 运行配置应使用当前位置对应的全名，应用 ID 与 runner 无需随目录整理修改。

## 常规检查

以下 PowerShell 7 命令在仓库根执行，环境配置见 [构建指南](../development/getting-started.md)。

```powershell
./build-debug.ps1 -Verify
```

这会运行 Debug 构建、JVM 单元测试和 Lint，并归档 APK 与日志。不加 `-Verify` 时只打包；不会运行设备测试。修改依赖、混淆、资源、序列化、发布配置或准备发行时增加：

```powershell
./build-release.ps1 -Verify
```

这个入口生成使用 Debug 测试证书签名的本地 Release 包，不需要发布私钥。若只需未签名产物可加 `-Unsigned`。参数与归档位置见 [构建指南](../development/getting-started.md)。

`build.ps1 -Tasks` 仍可直接组合 Gradle 任务，例如 `@(':app:assembleRelease', ':app:testReleaseUnitTest', ':app:lintRelease')` 默认生成未签名 Release。聚焦某一逻辑测试可以减少反馈时间：

```powershell
./build.ps1 -Tasks @(':app:testDebugUnitTest', '--tests', 'cc.novelia.app.ApiContractTest')
./build.ps1 -Tasks @(':app:testDebugUnitTest', '--tests', 'cc.novelia.app.data.auth.SessionIsolationTest')
```

JVM 报告在 `app/build/reports/tests/testDebugUnitTest/`，XML 结果在 `app/build/test-results/testDebugUnitTest/`。Lint 报告为 `app/build/reports/lint-results-debug.html`；Release 路径替换构建类型。警告不等于检查失败，但新增警告应被解释或修复。

## 按改动选择回归测试

下列为代表性测试，不是目录中全部测试。表内类名均省略 `cc.novelia.app.` 前缀；定位具体函数后再选择关联范围。

| 改动 | 优先测试 |
| --- | --- |
| 请求与会话 | `ApiContractTest`、`NetworkPerformanceTest`、`data.auth.SessionIsolationTest`、`data.network.SharedRequestTest` |
| 云端收藏和待同步 | `CloudFavoritesTest`、`data.sync.CloudMutationQueueTest`、`data.sync.CloudSyncPolicyTest`、`data.sync.CloudSyncRuntimeTest`、`data.sync.BoundCloudSyncTest` |
| 书库状态与恢复 | `data.storage.StatePersistenceTest`、`data.storage.LibraryStateCodecTest`、`data.backup.LibraryBackupTest`、`data.cache.LocalCacheTest`、`MetadataCacheTest` |
| 文档存储与导入 | `data.documents.DocumentStorageTest`、`data.documents.DocumentHashIndexTest`、`FileImportRegressionTest`、`DocumentToolsTest` |
| 手动离线缓存并发 | `data.chapters.ChapterBatchTest`：去重、并发上限、失败/取消、单调进度；设备另查网络策略和缓存代次 |
| 下载/导出生命周期 | `DownloadFilesTest`、`PendingExportFilesTest`、`DownloadCelebrationTest` |
| 阅读投影/进度 | `ReaderProjectionTest`、`ReaderPreferencesTest`、`ReadingContinuityTest`、`ReaderChapterLoadTest`、`ReaderSafetyTest` |
| 书籍摘要、云端元数据和进度展示 | `BookMetadataTest`、`CloudBookMetadataTest`、`BookListPresentationTest`、`FavoritePresentationTest`、`WebCoverTest` |
| 分页/搜索/插图 | `StaticPaginationTest`、`ReaderExactSearchTest`、`ReaderChapterOverscrollTest`、`IllustrationTransformTest` |
| 搜索、关键词、书源 | `ReaderAndLinksTest`、`SearchExpressionBoundaryTest`、`KeywordCatalogTest`、`KeywordObservationTest` |
| 分卷与更新检查 | `WenkuVolumesTest`、`BookUpdatesTest`、`TranslationFreshnessTest`、`data.updates.UpdateCheckOrderTest` |
| 旧后台任务升级兼容 | `data.compat.LegacyWorkerCompatibilityTest`：检查两个旧 Worker 类名可反射加载且保留 WorkManager 构造签名 |
| Markdown 与编辑器 | `MarkdownTest`、`SiteMarkdownTest`、`MarkdownAnchorsTest`、`MarkdownTemplatesTest`、`EditorStateRegressionTest` |

新测试应表达用户能遇到的错误或关键不变量。例如账号切换时旧响应不得写入当前界面、损坏 ZIP 不得覆盖可用书库、双语投影改变后仍定位同一原文段落。不要只断言实现刚赋给自身的值。

各功能的人工核对场景见[业务功能索引](../features/README.md)中的专题末尾；登录与备份分别见[会话验证](../network/authentication.md)和[恢复验证](../data/backup-and-recovery.md)。功能文档中的场景是选择测试范围的依据，不是已执行结果。纯文档迁移的链接、编码和内容保留检查见[文档维护规则](../maintenance/documentation.md)。

## 设备测试

部分设备测试会写入书架、设置、草稿、下载记录或 Cookie；不要使用有真实阅读资料和账号的主力设备。全量设备测试还包含较耗时的性能场景，日常修改可优先选择相关类。

先检查设备；多设备环境可为本次 Gradle 进程设置 `ANDROID_SERIAL`。示例中的序列号需要替换：

```powershell
adb devices
$env:ANDROID_SERIAL = '<专用测试设备序列号>'
./build.ps1 -Tasks @(':app:connectedDebugAndroidTest', '-Pandroid.testInstrumentationRunnerArguments.class=cc.novelia.app.ui.reader.AppFlowTest')
```

全量设备任务为 `:app:connectedDebugAndroidTest`。若只构建和安装测试包：

```powershell
./build.ps1 -Tasks @(':app:assembleDebug', ':app:assembleDebugAndroidTest')
adb -s '<设备序列号>' install -r app/build/outputs/apk/debug/app-debug.apk
adb -s '<设备序列号>' install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s '<设备序列号>' shell am instrument -w -r -e class cc.novelia.app.ui.reader.AppFlowTest cc.novelia.app.test/androidx.test.runner.AndroidJUnitRunner
```

Gradle 设备 HTML 报告位于 `app/build/reports/androidTests/connected/` 下，细分目录以当前 AGP 输出为准。直接 `am instrument` 的结果在命令输出中；部分测试另写截图到应用外部文件目录。`INSTRUMENTATION_FAILED` 或测试中止不能当成通过，默认跳过的联调也不能计为已验证。

| 场景 | 代表性设备测试 |
| --- | --- |
| 基本导航与阅读 | `ui.reader.AppFlowTest`、`ui.reader.ReadingContinuityUiTest`、`ui.shelf.BookSyncUiTest` |
| 书架与分卷 | `ui.shelf.AdaptiveLibraryTest`、`ui.shelf.LibraryInteractionTest`、`ui.shelf.WenkuVolumeFlowTest` |
| 电子纸/静态弹层 | `ui.reader.EInkReaderFlowTest`、`ui.reader.EInkAndCloudFilterTest`、`ui.components.StaticOverlayTest`、`ui.components.ReducedMotionSheetTest` |
| 阅读器布局和插图 | `ui.reader.ReaderAdaptiveUiTest`、`ui.reader.ReaderToolbarOverlayTest`、`ui.reader.IllustrationViewerTest` |
| 编辑与 Markdown | `ui.community.ArticleEditorLayoutTest`、`ui.markdown.EditorDraftLifecycleTest`、`ui.markdown.MarkdownToolbarInteractionTest`、`ui.markdown.SiteMarkdownInteractionTest` |
| 备份与文件工具 | `data.backup.LibraryBackupFlowTest`、`ui.tools.FileToolsUpgradeTest`、`ui.downloads.DownloadSheetLayoutTest` |
| 列表与筛选 | `ui.components.AppPagingTest`、`ui.components.AsyncContentTest`、`ui.discover.FilterPositionTest`、`ui.discover.SearchAssistantTest` |

上表同样省略 `cc.novelia.app.` 前缀。全量任务可能包含被默认跳过的联调用例；`SiteWebNavigationTest` 的联调用例仍通过 `liveSite` 单独控制，不因放入 `ui/web/` 而自动启用。

## 可选真实站点联调

仅在需要确认原站兼容性时显式运行，并先阅读对应测试当前实现。现有开关如下：

| 测试类 | 开关 | 内容 |
| --- | --- | --- |
| [LiveReadOnlyTest](../../app/src/androidTest/java/cc/novelia/app/integration/LiveReadOnlyTest.kt) | `live=true` | 公开目录、详情与已有章节 |
| [DownloadLiveTest](../../app/src/androidTest/java/cc/novelia/app/integration/DownloadLiveTest.kt) | `live=true` | 请求并解析已有译文的 EPUB 下载 |
| [AuthPageTest](../../app/src/androidTest/java/cc/novelia/app/integration/AuthPageTest.kt) | `live=true` | 认证页面表单可见性，不填写凭据 |
| [SiteWebNavigationTest](../../app/src/androidTest/java/cc/novelia/app/ui/web/SiteWebNavigationTest.kt) | `liveSite=true` | 部分用例访问原站教程、链接、锚点和图片，其余用例不受此开关控制 |

例如只选公开阅读测试：

```powershell
./build.ps1 -Tasks @(':app:connectedDebugAndroidTest', '-Pandroid.testInstrumentationRunnerArguments.class=cc.novelia.app.integration.LiveReadOnlyTest', '-Pandroid.testInstrumentationRunnerArguments.live=true')
```

上述测试不代表真实账号发帖、评论、上传、云端收藏变更的生产端到端验收。新增联调不能偷偷引入写操作；这类验收需要明确授权、专用账号和单独的执行计划。

## 发行前手工矩阵

关键路径包括首次安装、本地文件导入、网络章节与离线缓存、切换译文、搜索、书签/笔记、跨章、普通设置与阅读资料备份/恢复、后台下载及 TTS。按改动增加登录续期、账号切换、同步冲突或文库编辑。

UI 至少检查普通模式、减少动效、电子纸、浅深主题、大字体、窄屏与宽屏/横屏。迁移测试分别记录旧版签名是否相同、覆盖安装或备份后重装，勿用一次新装结果代表升级可用。

面板会话回归见 `PanelSessionTest`、`ReaderPanelSessionTest` 和 `FilterPositionTest`：验证关闭再打开回到顶部、阅读偏好重置页签但保留设置、半屏切换动效及减少动效立即生效。剪贴板纯逻辑由 `ClipboardLinksTest` 验证网址识别、查询与锚点保留、去重和设置兼容；前台焦点、系统剪贴板提示及点击跳转仍需设备验证。

PR 中列出执行命令、构建变体、设备/API、成功/失败/跳过数量和报告位置；失败记录应脱敏。文档修改通常只需核对链接、路径、代码和命令，不必为排版变动重跑完整应用测试。
