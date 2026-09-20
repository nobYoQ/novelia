# 数据模型、持久化与备份

[返回开发手册](README.md)

本文说明当前 Android 客户端的数据组织方式、落盘顺序和恢复边界。阅读入口是 [Models.kt](../app/src/main/java/cc/novelia/app/data/Models.kt)、[LocalStore.kt](../app/src/main/java/cc/novelia/app/data/LocalStore.kt) 和 [NoveliaApplication.kt](../app/src/main/java/cc/novelia/app/NoveliaApplication.kt)。网络会话与待同步操作另见 [网络、认证与同步](network-and-sync.md)。

## 1. 数据归属与核心类型

应用自己的阅读资料使用 Kotlin Serialization JSON 和私有目录文件保存，没有使用 Room 来保存书架。WorkManager、WebView 等依赖仍可能维护自己的内部数据库；不要把“书架使用 JSON”理解成整个应用没有数据库。

| 类型 | 用途与关键关系 |
| --- | --- |
| `BookRef(provider, id)` | 书籍标识，`key` 为 `provider/id`。`wenku` 表示文库作品，`local` 表示导入文档，其余为网络小说站点。 |
| `BookCard` / `SavedBook` | 列表摘要与本地收藏。`SavedBook` 额外保存分组、置顶、阅读状态、更新标记和文库分卷挂载关系。 |
| `WebDetail` / `WenkuDetail` | 原站详情响应，通过 `card()` 转为摘要。详情中的云端收藏信息不等于本地收藏状态。 |
| `Chapter` | 网络章节，包含原文及有道、GPT、Sakura 译文列表。缺失译文使用 `null`，不要自行补成“翻译已完成”。 |
| `Position` | `chapterId`、段落 `index`、像素 `offset`、段内 `textOffset` 和更新时间。恢复进度时不能仅保存屏幕页码。 |
| `Note` | 通过书籍键、章节和段落关联书签/笔记，另存摘录和标题，便于脱离当前阅读页展示。 |
| `LocalDocument` / `LocalChapter` | 本地文档目录、章节正文与图片引用。运行时分块格式和备份中的便携格式不同。 |
| `LibraryState` | 本设备阅读资料与设置的聚合快照，包括本地书架、进度、笔记、草稿、下载记录、待同步操作和更新快照。 |
| `PendingAction` / `CloudSyncStatus` | 账号所属的云端写入意图和同步状态，保存在 `LibraryState` 中；不是登录凭据。 |
| `ReaderSettings` | 默认阅读偏好及每书偏好。电子纸模式保存切换前的翻页偏好，不能把模式切换简化为覆盖几个布尔值。 |

`LibraryState` 是**设备共享资料**，不会在退出登录时自动清空，也没有为每个账号建立独立书架目录。账号隔离主要作用于认证请求、云端待办及元数据缓存。新增账号相关字段时应明确是否需要以账号为键，不能只依赖“当前登录用户”。

本地文库分卷由 `SavedBook.parentWenkuKey` 关联父作品，`volumeOrder` 保存子书籍键。相关纯函数位于 [WenkuVolumes.kt](../app/src/main/java/cc/novelia/app/data/WenkuVolumes.kt) 与 [ReadingContinuity.kt](../app/src/main/java/cc/novelia/app/data/ReadingContinuity.kt)。删除或移动分卷时应通过现有函数维护父子关系，避免留下悬空排序键。

## 2. 文件布局

下表路径相对于 Android `Context.filesDir`，不是仓库内的目录。

| 路径 | 内容 | 性质 |
| --- | --- | --- |
| `library.json` | 小型资料快照及长文本内容哈希指针 | 核心资料，`AtomicFile` 写入 |
| `library-last-good.json` | 最近一次成功写入后尽力保存的副本 | 恢复辅助，不是版本历史 |
| `library-damaged-<UUID>.json` | 显式恢复前保留的损坏主文件 | 诊断/抢救用，不能当缓存清除 |
| `library-text/<SHA-256>.json` | 已保存文章正文和草稿 | 内容寻址，主快照使用 `localLongTextPayload` 指向它 |
| `chapters/<SHA-256>.json` | 网络章节；哈希输入为书籍键和章节 ID | 可重建缓存，磁盘预算 256 MiB |
| `chapter-freshness.json` | 章节实际获取时间 | 与缓存访问时间分开维护，最多记录 4096 项 |
| `metadata/<SHA-256>.json` | 详情等元数据响应，键由账号与 API 路径计算 | 可重建缓存，预算 32 MiB |
| `metadata/invalidated-at` | 最近远端写入引发的缓存失效时间 | 跨进程保留失效边界 |
| `documents/<id>.json` | 本地文档目录与章节文件映射 | 用户资料 |
| `documents/<id>-chapters/<SHA-256>.json` | 本地章节正文 | 用户资料，内容寻址并在读取时验证哈希 |
| `documents/<id>-images/<SHA-256>` | 插图和封面字节 | 用户资料；正文用 `novelia-image:<hash>` 引用 |
| `documents/<id>.epub` / `.txt` / `.srt` | 保留的导入原文件 | 可选原件，是否存在取决于导入/恢复流程 |
| `documents/source-index.json` | 原文件哈希到文档 ID 的索引 | 导入去重辅助，可重新核对文档 |
| `downloads/` | 下载成品和任务暂存文件 | 由下载任务管理，与章节缓存不同 |
| `exports/` | 导出时使用的临时文件 | 不等于用户在系统选择器中选定的最终文件 |
| `backup-staging/<UUID>/` | 待确认恢复的解包目录 | 先验证、预览，再显式合并 |
| `keyword-catalog.json` | 已观察的标签及用户翻译 | 独立 `KeywordStore` 管理 |

`AtomicFile` 还可能产生 `.bak`、`.new` 等伴随文件，不应绕过该 API 随意处理它们。网络图片由 Coil 保存在 `Context.cacheDir/images`，磁盘预算 128 MiB；这与用户本地文档中的图片不是一类数据。

阅读资料 JSON、本地小说和手动导出的备份没有应用层加密。它们依赖 Android 应用私有目录保护；认证令牌的加密方式单独见网络文档。不要把真实用户备份、私有小说、笔记或会话文件加入仓库和测试夹具。

## 3. 状态更新与落盘顺序

UI 通过 `store.state` 的只读 `StateFlow` 观察资料，通过 `store.update { old -> old.copy(...) }` 提交不可变快照。`update` 在同步块内完成状态变换和发布，正常 JSON 编码与文件写入交给 IO 协程，不阻塞 UI 做整库写盘。

```text
UI / Worker
    → LocalStore.update(transform)
    → revision 递增、发布 StateFlow
    → StatePersistence 合并短时间内的快照
    → LibraryStateCodec 写长文本载荷
    → AtomicFile 提交 library.json
    → 尽力更新 library-last-good.json 并回收旧长文本
```

[StatePersistence.kt](../app/src/main/java/cc/novelia/app/data/StatePersistence.kt) 使用单写入器、合并通道和 `Mutex`：默认等待 100 ms 合并快速变化；写入失败保留最新快照，约 1 秒后重试，并通过 `persistenceError` 提供错误状态。`update` 返回代表内存已改变，**不代表磁盘已提交**。生命周期、后台任务、导出前等需要持久化边界的代码，应在 IO 协程等待 `store.flush()`。

`flush()` 会传播写盘失败。进程被系统终止前仍可能存在未落盘窗口，因此不能以“异步保存最终会重试”为理由省略关键边界。应用生命周期调用 `NoveliaApplication.persistState()`；Worker 在成功、取消或结束边界也各自处理刷新。

[LibraryStateCodec.kt](../app/src/main/java/cc/novelia/app/data/LibraryStateCodec.kt) 将文章与草稿移出小快照，使频繁阅读进度更新不必重写长正文。载荷先写，主快照后写；两份状态副本提交后，常规回收保留当前和上一份长文本载荷。若启动时发现损坏，或存在损坏快照文件，会保留载荷历史供恢复使用。

## 4. 损坏保护与恢复

[LibraryRecovery.kt](../app/src/main/java/cc/novelia/app/data/LibraryRecovery.kt) 区分首次启动和“已有文件但读取失败”：

1. 没有任何状态文件时才创建默认资料。
2. 主文件解码失败时尝试最后良好副本；载荷哈希失败也视为读取失败。
3. 无论回退是否成功，都会设置 `recoveryIssue`。此时 `update` 直接返回；文档保存/删除入口也拒绝操作，避免默认值覆盖可抢救资料。
4. 用户显式恢复最后良好副本，或通过资料备份恢复，才进入 `commitRestore`。

`commitRestore` 刷新待写任务，在磁盘锁和状态锁内提交新快照，强制重写长文本载荷，并递增 `revision`，使恢复前排队的旧快照失效。受保护的原主文件会先复制为 `library-damaged-<UUID>.json`。提交边界使用 `NonCancellable`，防止界面关闭导致“已经落盘却没有完成状态切换”。

不要通过捕获异常后调用 `store.update { LibraryState() }` 修复损坏，也不要在清缓存功能中删除 `library*`、`documents` 或关键词词典。

## 5. 本地文档与缓存

### 文档分块

[DocumentStorage.kt](../app/src/main/java/cc/novelia/app/data/DocumentStorage.kt) 接收含完整正文的 `LocalDocument`，先写每个章节的内容寻址文件，最后提交目录。目录保留章节 ID/标题，但正文置空，并用 `chapterFiles` 映射 ID 到哈希。

- 目录导航用 `documentIndex(id)`，单章阅读用 `documentChapter(id, chapterId)`。
- `document(id)` 会拼装全书正文，只适合明确需要完整载荷的导出、备份等工作。
- `saveDocument` 的输入必须是完整文档，即 `chapterFiles` 为空；不要把读取到的分块目录直接当完整文档保存。
- 读取正文时检查内容哈希及章节 ID/标题一致性；文档 ID 和图片哈希都有限定格式，不能把外部文件名直接拼成私有目录路径。
- 旧版单 JSON 文档第一次读取时尝试迁移。若迁移遇到 `IOException`，仍返回可读的旧格式，避免磁盘不足导致旧书无法打开。

[DocumentHashIndex.kt](../app/src/main/java/cc/novelia/app/data/DocumentHashIndex.kt) 用源文件哈希辅助重复导入识别。恢复流程会额外核对完整正文与图片，不能只凭源哈希就认为损坏文档与备份相同。

### 缓存预算和失效

[LocalCache.kt](../app/src/main/java/cc/novelia/app/data/LocalCache.kt) 的内存缓存同时限制项数和估算字节权重：网络章节 24 项/12 MiB，文档目录 4 项/32 MiB，本地章节 12 项/12 MiB。这些是估算预算，不是精确堆内存占用。

网络章节磁盘缓存按访问顺序淘汰，文件修改时间用于近似访问时间，并非获取译文的时间。译文新鲜度必须从 [ChapterFreshness.kt](../app/src/main/java/cc/novelia/app/data/ChapterFreshness.kt) 读取。元数据缓存则使用文件修改时间表示获取时间，不能混用两套语义。

`clearCache()` 会清空网络章节和元数据、清新鲜度记录、递增 `cacheGeneration` 并取消旧代次章节请求。响应写回必须通过 `withCacheGeneration`，否则用户清完缓存后，旧请求可能再次填回已清理的数据。清缓存不会删除本地导入文档、书架和下载成品。

## 6. 备份格式与合并语义

阅读资料备份由 [LibraryBackupService.kt](../app/src/main/java/cc/novelia/app/data/LibraryBackupService.kt) 调度，[LibraryBackupArchive.kt](../app/src/main/java/cc/novelia/app/data/LibraryBackupArchive.kt) 负责 ZIP 流、白名单路径和内容校验。

备份根索引为 `manifest.json`，`format = "novelia-library"`、`version = 1`，包含时间、净化后的 `LibraryState`、本地文档清单、缺失文档清单、关键词词典和每个文件的字节数/SHA-256。当前分块文档导出为含完整正文的便携 JSON，图片独立存放。可选包含 EPUB/TXT/SRT 原件。

备份包含书架、进度、笔记、收藏文章、草稿、偏好、本地文档及标签翻译等阅读资料；`forBackup()` 会清空 `pending`、`downloads` 和 `syncStatus`。令牌、WebView Cookie、网络章节缓存、Coil 缓存不进入该备份。下载成品只有先导入为本地文档，才按本地文档流程备份。

恢复步骤为 `prepare(input)` 解包 → 展示 `BackupPreview` → `restore(stagingId)` 重新验证并合并。UI 只保存不透明 UUID，不保存可控磁盘路径。校验包括：

- 路径只允许索引、指定文档类型及 64 位十六进制图片名；拒绝重复路径、目录项和未引用的资源。
- 单资源上限 128 MiB、索引 16 MiB、总解压数据 1 GiB、ZIP 项数最多 30000。
- 校验长度、SHA-256、文档/章节标识、设置范围、进度非负、分卷关系、图片引用和关键词数量。
- 只支持明确的格式版本；哈希提供完整性校验，不提供发布者签名或真实性认证。

恢复是合并，不是覆盖整个设备：

| 冲突对象 | 当前规则 |
| --- | --- |
| 同一网络书籍、笔记 ID、文章 ID | 保留当前项，加入未存在的项 |
| 阅读进度 | 同一书籍选择 `updatedAt` 较新的项 |
| 单书设置、同名草稿、同一术语翻译 | 当前设备值优先 |
| 屏蔽名单、分组、保存的搜索 | 合并去重；最近搜索最多 20 项 |
| 本地文档 | 完整内容与图片一致时复用；否则生成新 UUID，重映射进度、笔记、偏好和分卷引用 |
| 全局偏好 | 当当前书架、进度、笔记均为空时，以导入资料为基础；否则保留当前全局偏好 |
| 正在运行的下载及云端队列 | 保留本安装的现有任务，备份不能注入任务 |

文件先安装、资料最后提交，提交前失败会回滚本次安装文件。资料提交成功但标签词典保存失败时，会保留暂存目录并返回部分成功提示供重试；不能把这种结果显示为“资料恢复全部失败”。

“导出普通设置”是另一个 `SettingsBackup(version = 1)` JSON 格式，只包含部分阅读/外观/屏蔽设置。它不能代替完整阅读资料备份，导入校验范围也与完整备份不同。入口见 [AccountScreens.kt](../app/src/main/java/cc/novelia/app/ui/AccountScreens.kt)。系统自动备份在 [AndroidManifest.xml](../app/src/main/AndroidManifest.xml) 和 [data_extraction_rules.xml](../app/src/main/res/xml/data_extraction_rules.xml) 中禁用，迁移资料应走应用显式导出流程。

## 7. 增加字段或新存储的步骤

以增加一个普通阅读偏好为例：

1. 在 `ReaderSettings` 或合适的模型加入带兼容默认值的字段。`appJson` 忽略未知键、允许部分输入值强制转换、编码默认值且省略显式空值；不要把这些配置当作完整迁移策略。
2. 使用 `store.update { it.copy(...) }` 修改，保持变换简短，无网络或文件 IO。单书偏好与默认偏好的读写应遵循现有调用方式。
3. 检查设置导出、完整备份校验、恢复合并和 UI 范围是否都认识新字段。字段改名/语义变化需要显式兼容逻辑；旧 JSON 没有该字段时必须仍可加载。
4. 若新数据是大正文或大二进制，不放进高频改写的主快照。设计目录、原子提交顺序、完整性检查、清理边界和备份白名单。
5. 如果会产生云端写操作，再检查账号归属与离线重放；不要把凭据放到 `LibraryState`。

已有持久化模型未统一使用数据库式递增 schema version。`LibraryStateCodec` 的旧格式兼容、`ReaderSettings` 的默认值和备份版本判断共同承担迁移责任，修改时必须覆盖旧数据输入。

## 8. 回归验证入口

相关单元测试均位于 `app/src/test/java/cc/novelia/app`，运行方式见仓库构建文档或 [CONTRIBUTING.md](../CONTRIBUTING.md)。优先选择与改动边界对应的测试：

| 范围 | 测试入口 |
| --- | --- |
| 合并写入、失败重试、flush 顺序 | [StatePersistenceTest](../app/src/test/java/cc/novelia/app/data/StatePersistenceTest.kt) |
| 长文本分离、旧快照、损坏载荷 | [LibraryStateCodecTest](../app/src/test/java/cc/novelia/app/data/LibraryStateCodecTest.kt) |
| 分块文档、哈希验证与迁移 | [DocumentStorageTest](../app/src/test/java/cc/novelia/app/data/DocumentStorageTest.kt)、[DocumentHashIndexTest](../app/src/test/java/cc/novelia/app/data/DocumentHashIndexTest.kt) |
| 备份格式、路径、限额和合并 | [LibraryBackupTest](../app/src/test/java/cc/novelia/app/data/LibraryBackupTest.kt) |
| 内存/磁盘缓存与新鲜度 | [LocalCacheTest](../app/src/test/java/cc/novelia/app/data/LocalCacheTest.kt)、[MetadataCacheTest](../app/src/test/java/cc/novelia/app/MetadataCacheTest.kt)、[TranslationFreshnessTest](../app/src/test/java/cc/novelia/app/TranslationFreshnessTest.kt) |
| 设置兼容、分卷关系、阅读连续性 | [ReaderPreferencesTest](../app/src/test/java/cc/novelia/app/ReaderPreferencesTest.kt)、[WenkuVolumesTest](../app/src/test/java/cc/novelia/app/WenkuVolumesTest.kt)、[ReadingContinuityTest](../app/src/test/java/cc/novelia/app/ReadingContinuityTest.kt) |

涉及 Android 文件选择器、低存储空间、进程终止或卸载重装的行为，还需要设备验证。单元测试通过不代表已经覆盖真实系统文件提供方和所有生命周期中断点。
