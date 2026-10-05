# 数据与持久化

[数据目录](README.md) · [文档首页](../README.md)

书架、位置、笔记和草稿保存在设备私有目录中，由 [LocalStore](../../app/src/main/java/cc/novelia/app/data/storage/LocalStore.kt) 管理。主要格式是 Kotlin Serialization JSON；大正文和图片独立存储。退出账号不会删除这套设备资料。

## 先理解数据身份

| 模型 | 用途 |
| --- | --- |
| `BookRef(provider, id)` | 书籍稳定标识，键为 `provider/id`；`local` 表示导入文档，`wenku` 表示文库父作品 |
| `BookCard` / `SavedBook` | 书目摘要 / 本地收藏，后者还有文件夹、置顶、状态和分卷关系 |
| `Position` | 本机续读锚点：章节、列表位置、像素与字符偏移，以及可选章节进度 |
| `CloudReadingProgress` | 带账号归属的原站章节摘要，不代替本机精确位置 |
| `Note` | 书签位置、摘录和可选笔记正文 |
| `LocalDocument` | 本地目录、章节映射及插图引用 |
| `LibraryState` | 本机资料与设置的聚合快照 |
| `PendingAction` | 带账号的原站待同步意图，不含登录凭据 |

模型位于 [data/model](../../app/src/main/java/cc/novelia/app/data/model)。`LibraryState` 是设备共享资料；元数据缓存和云端待办才按身份隔离。新增字段时先确定归属，不要默认“当前账号”拥有整个书库。

文库父作品与本地分卷分别有自己的 `BookRef`，通过 `parentWenkuKey` 和 `volumeOrder` 关联。增删移动使用 [WenkuVolumes.kt](../../app/src/main/java/cc/novelia/app/data/library/WenkuVolumes.kt) 的规则。

## 文件放在哪里

以下路径相对于 Android `Context.filesDir`：

| 路径 | 内容与用途 |
| --- | --- |
| `library.json` | 主状态，使用 `AtomicFile` 提交 |
| `library-last-good.json` | 最近一次良好副本，供恢复使用 |
| `library-damaged-<UUID>.json` | 恢复前保留的损坏状态 |
| `library-text/<SHA-256>.json` | 草稿长文本；兼容旧版混合载荷中的草稿 |
| `documents/<id>.json` | 本地文档目录 |
| `documents/<id>-chapters/`、`<id>-images/` | 章节正文和插图；文件名使用内容哈希 |
| `documents/<id>.epub / .txt / .srt` | 可用时保留的导入原件 |
| `documents/source-index.json` | 源文件哈希索引，用于导入去重 |
| `chapters/`、`chapter-freshness.json` | 网络正文缓存及获取时间 |
| `metadata/` | 详情缓存和持久失效时间 |
| `downloads/`、`exports/` | 下载文件、导出临时文件 |
| `backup-staging/<UUID>/` | 备份解包与预览暂存 |
| `keyword-catalog.json` | 独立标签词典和分类快照 |
| `webdav-state.json` | WebDAV 已确认版本、数据集绑定和 ETag |

Coil 网络图片在 `cacheDir/images`。书源、桌面图标、会话和 WebDAV 配置另有本机存储；凭据不进入书库和备份。常规阅读资料及导出的 ZIP 没有应用层加密，见[安全与隐私](../quality/security-and-privacy.md)。

## 一次 update 如何落盘

```text
LocalStore.update
  → 修改不可变快照、递增 revision
  → 发布 StateFlow，页面立即看到新值
  → StatePersistence 合并短时间内的更新
  → 先写长文本载荷，再原子提交 library.json
  → 尽力更新良好副本，回收不再需要的载荷
```

[StatePersistence.kt](../../app/src/main/java/cc/novelia/app/data/storage/StatePersistence.kt) 使用单写入器；默认合并窗口为 100 ms，失败后保留最新快照并重试，同时暴露 `persistenceError`。

**`update` 返回只代表内存已更新。** 导出、后台调度等需要持久数据时，在协程中等待 `flush()`，并处理其错误。生命周期中的 `persistState()` 只是异步请求刷新，也不保证调用返回时已完成。

`flush()` 提交取得锁时的最新快照，不阻止之后继续修改。需要状态与文档一起完成的恢复操作应使用专门提交入口。长文本先写、主索引后写的顺序由 [LibraryStateCodec](../../app/src/main/java/cc/novelia/app/data/storage/LibraryStateCodec.kt) 保证；损坏状态存在时会保留载荷供抢救。

## 读取失败为什么进入保护状态

[LibraryRecovery.kt](../../app/src/main/java/cc/novelia/app/data/storage/LibraryRecovery.kt) 区分“首次安装没有文件”和“已有文件读不出来”。后者即使能回退到良好副本，也设置 `recoveryIssue`，阻止普通更新及文档修改。

用户通过恢复入口确认后，`commitRestore` 才保存损坏原件、提交新状态并使旧排队快照失效。关键提交不可取消，避免磁盘已写入而内存仍停在旧状态。完整流程见[备份与恢复](backup-and-recovery.md)。

因此，捕获解码异常后写入空 `LibraryState` 会破坏恢复机会，不能用作自动修复。

## 本地正文按章读取

[DocumentStorage.kt](../../app/src/main/java/cc/novelia/app/data/documents/DocumentStorage.kt) 先写正文文件，再提交目录：

- `documentIndex(id)` 只读目录，适合导航。
- `documentChapter(id, chapterId)` 读一章，适合阅读。
- `document(id)` 拼装全书，留给备份、导出等明确需要全文的操作。
- `saveDocument` 接收完整文档，不能把正文为空的分块目录再次当作整书保存。

读取会校验内容哈希和章节身份。旧单文件格式在读取时尝试迁移；遇到 I/O 失败可继续使用可读旧格式。来源哈希只能帮助寻找重复文档，恢复时还要核对正文和图片。

## 缓存与用户资料分开清理

| 缓存 | 当前预算 |
| --- | --- |
| 网络章节磁盘 | 256 MiB |
| 元数据磁盘 / 热响应内存 | 32 MiB / 16 项、估算 2 MiB |
| 网络章节内存 | 24 项、估算 12 MiB |
| 本地目录内存 | 4 项、估算 32 MiB |
| 本地章节内存 | 12 项、估算 12 MiB |
| Coil 图片磁盘 | 128 MiB |

预算实现见 [LocalCache](../../app/src/main/java/cc/novelia/app/data/cache/LocalCache.kt)、[MetadataCache](../../app/src/main/java/cc/novelia/app/data/cache/MetadataCache.kt) 和 [Application](../../app/src/main/java/cc/novelia/app/NoveliaApplication.kt)。内存权重是估算，不是精确堆占用。

章节文件修改时间用于近似访问时间，译文获取时间单独保存；不能用前者判断译文新鲜度。清缓存递增 `cacheGeneration` 并取消旧章节请求，写回也检查代次。它保留本地文档、书架和下载成品。

## 修改模型时需要一起检查

为字段提供兼容默认值，复用 `StorageFormat.kt` 的 `appJson`。字段改名或改变含义需要显式兼容，不能只依赖“忽略未知键”。大文本和二进制不要塞进高频改写的主状态。

分别检查普通设置 JSON、阅读资料 ZIP 和 WebDAV 的白名单、校验、合并与引用重映射。它们不是同一个格式，也不会自动完整覆盖新增字段。ZIP 格式和合并规则统一在[备份文档](backup-and-recovery.md)维护。

对应测试：`StatePersistenceTest`、`LibraryStateCodecTest`、`DocumentStorageTest`、`DocumentHashIndexTest`、`LibraryBackupTest`，位于 [data 单元测试](../../app/src/test/java/cc/novelia/app/data)。真实文件选择器、空间不足和进程终止仍需设备验证。
