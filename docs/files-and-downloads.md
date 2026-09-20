# 文件导入、下载与工具开发

[返回开发手册](README.md)

本文说明当前实现中的本地文档处理和远程文件下载。修改相关功能时，应同时考虑磁盘文件、持久化任务状态、会话切换和 Android 文档选择器的生命周期。

## 代码入口

| 职责 | 主要文件 |
| --- | --- |
| URI 暂存、来源去重、导入事务 | [DownloadImport.kt](../app/src/main/java/cc/novelia/app/files/DownloadImport.kt) |
| 格式分派、编码、文本与内存 EPUB 工具 | [DocumentTools.kt](../app/src/main/java/cc/novelia/app/files/DocumentTools.kt) |
| 基于磁盘文件的 EPUB 读取 | [EpubReader.kt](../app/src/main/java/cc/novelia/app/files/EpubReader.kt) |
| EPUB 图片压缩 | [EpubCompressor.kt](../app/src/main/java/cc/novelia/app/files/EpubCompressor.kt) |
| WorkManager 下载及任务操作 | [DownloadWorker.kt](../app/src/main/java/cc/novelia/app/files/DownloadWorker.kt) |
| 下载锁、临时文件与完成提交 | [DownloadFiles.kt](../app/src/main/java/cc/novelia/app/files/DownloadFiles.kt) |
| 工具结果的待导出文件 | [PendingExportFiles.kt](../app/src/main/java/cc/novelia/app/files/PendingExportFiles.kt) |
| 书架导入及原件导出 | [ShelfScreens.kt](../app/src/main/java/cc/novelia/app/ui/ShelfScreens.kt) |
| 下载表单、下载管理 | [DownloadScreens.kt](../app/src/main/java/cc/novelia/app/ui/DownloadScreens.kt) |
| 文件工具、术语表导入导出 | [ToolScreens.kt](../app/src/main/java/cc/novelia/app/ui/ToolScreens.kt) |
| 文档目录和持久化接口 | [LocalStore.kt](../app/src/main/java/cc/novelia/app/data/LocalStore.kt) |

## 本地导入流程

书架通过 `OpenMultipleDocuments` 获取用户选择的 URI，逐个调用 `importDocumentUri`。导入不依赖 URI 对应真实文件路径，也不长期保存外部文档 URI。

1. 在 `Dispatchers.IO` 查询 `OpenableColumns.DISPLAY_NAME` 和可用的文件大小。提供方未返回名称时使用 `导入文档.txt`；已知大小先接受上限检查。
2. 使用 `ContentResolver.openInputStream`，按 64 KiB 缓冲流式复制到 `cacheDir/document-input-*.tmp`。读取过程中再次累计大小并检查协程取消，不能仅信任提供方声明的大小。
3. `importLocalDocument` 在进程内全局 `Mutex` 下计算源文件 SHA-256，并通过 `findDocumentByHash` 查找书架中已有本地副本。完全相同的原始字节直接返回已有 `BookRef`，`imported` 为 `false`。
4. 按扩展名解析，分配新的 UUID。EPUB 插图先写入缓存目录，文件名为图片内容的 SHA-256，不使用压缩包内路径创建磁盘文件。
5. 保存原文件、转移插图、调用 `saveDocument`，最后添加书架 `BookCard`。新导入内容使用 `BookRef("local", id)`。
6. 失败时清理本次新 ID 的文档数据；结束时清理输入和图片暂存文件。已有书籍不会因相同文件再次导入而被替换。

来源去重依据原始文件内容，而不是书名、扩展名或解析后的正文。仅改动 EPUB 元数据也会改变来源哈希。`saveDocument` 将正文交给文档存储层，并更新来源索引；阅读时通过目录和单章接口取数据，不应为常规阅读调用完整文档导出接口。

`importDownloadedDocument` 复用相同流程。首次导入文库分卷时，仅当对应父作品已经在书架中，才建立本地分卷与父作品的关系；再次导入不会覆盖用户主动解除或更改的归属关系。删除下载任务只删除下载副本，已导入书架的文档保留。

## 格式支持与解析规则

### EPUB

正式书架导入使用 `DocumentTools.parseFile` 和 `readEpubFile`。`ZipFile` 先索引并校验压缩包条目，再按需读取当前 XHTML；插图通过输入流写入磁盘，不在导入结果中保留整本图片的 Base64。

- 从 `META-INF/container.xml` 找到 OPF，通过 `manifest` 解析资源，通过 `spine/itemref` 确定阅读顺序，不能以 ZIP 条目顺序代替阅读顺序。
- 封面优先识别 `properties="cover-image"`，其次使用 OPF 的 `meta name="cover"`。
- 用 Jsoup 提取正文，移除 `script`、`style`、`nav`、`rt`、`rp`。块元素和换行构成段落，标题从首个 `h1/h2/h3/title` 提取，缺少时使用“章节”。
- `img` / `image` 的 `src`、`xlink:href` 或 `href` 对应本地资源时，生成 `novelia-image:<sha256>` 段落标记；图片在正文中的相对顺序保留。
- 相对路径会去除片段标识并规范化，`+` 保留为加号；拒绝越出根目录的路径、绝对路径和反斜杠路径，外部 URI 不作为插图来源下载。
- 缺失的正文资源会被跳过；没有任何非空正文或图片标记的文档会导入失败。超过单图限制的图片会跳过，而不是导致整个文档失败。

这是将 EPUB 转为应用内部章节、段落与插图的解析器，不是完整的 EPUB 排版引擎。CSS、脚本、原页面布局、注音和复杂交互不会完整保留；不要据此承诺 EPUB 规范的全面兼容。

### TXT

`decodeText` 识别 UTF-16LE/BE BOM；其他输入先按严格 UTF-8 解码，失败后回退到 GB18030，并去掉文本开头的 BOM。这个过程不是通用的字符集自动检测。

`parseText` 逐行处理：去掉行尾空白、略过空白行，将匹配“第…章/节/卷/話/话/部”、`Chapter 数字`、“序章/终章/終章”等规则的行作为标题。无标题时使用“正文”，每累计 800 个正文段落分成一章。段落正文保留行首空白。

### SRT

SRT 按空行划分字幕块，生成一个标题为“字幕”的章节。序号、时间戳和文本原样保留在每个块内，不提供时间轴播放、时间戳修复或字幕结构验证。

### 两套 EPUB 路径的区别

`DocumentTools.parse` / `parseEpub` 是 `ByteArray` 路径，`unzip` 将 ZIP 内容放入内存；该路径仍供文件工具和部分测试使用。`parseFile` 是书架导入采用的磁盘路径，具有单个正文文件限制、累计正文字数限制、重复路径拒绝和逐条目实际大小验证。

不要把 `parse` 当作 `parseFile` 的等价替代。新增面向用户的导入入口应优先复用 `importDocumentUri` 或 `importLocalDocument`，避免重新引入“整本 EPUB 加全部图片同时驻留内存”的流程。

## 当前容量与边界

下表使用 MiB 表示 `1024 × 1024` 字节；界面提示中写作 MB。

| 边界 | 当前实现 | 超限行为 |
| --- | --- | --- |
| 本地导入原文件 | 64 MiB | 拒绝导入，提供方声明大小和实际读取都检查 |
| 工具界面选入文件 | 64 MiB | `readDocument` 读取时拒绝；文件整体进入内存 |
| EPUB 累计展开内容 | 192 MiB | 拒绝；磁盘导入校验声明大小和已读取内容大小 |
| 磁盘 EPUB ZIP 条目 | 最多 10,000 项，目录也计数 | 拒绝 |
| 磁盘 EPUB 单个 XML / OPF / XHTML | 8 MiB | 拒绝 |
| EPUB 单张插图 | 16 MiB | 跳过该图片 |
| 磁盘导入 TXT/SRT 正文、EPUB 累计正文 | 16 × 1024 × 1024 个字符 | 拒绝；不是字节限制 |
| 网络下载文件 | 512 MiB | 检查响应长度及实际累计字节，超限失败 |
| 文库上传 | 40 MiB，仅 EPUB/TXT | [BookScreen](../app/src/main/java/cc/novelia/app/ui/BookScreen.kt) 在发送前拒绝 |

下载允许的文件可能超过导入上限。因此，“已完成”只代表文件下载完成，不能保证应用内能够导入阅读；用户仍可导出或交给其他应用打开。

内存工具的 `unzip` 使用映射记录条目，计数和重复路径行为不完全等同于磁盘解析器。修改 ZIP 防护时，应分别检查两条路径，不应只为其中一条增加测试。文件大小上限也不等于峰值内存上限，图片重编码与 ZIP 重建可能同时持有多个字节数组。

## 下载状态与并发控制

### 创建任务

下载表单收集内容模式、译文顺序、是否并列译文和输出格式，通过 [NoveliaApi.downloadUrl](../app/src/main/java/cc/novelia/app/data/NoveliaApi.kt) 请求原站生成已有内容的文件。它不创建新的翻译任务。网络小说可选 EPUB/TXT；文库分卷沿用原分卷格式。

表单为任务创建 UUID，替换文件名中的路径及常见非法字符，并添加任务 ID 前缀。`DownloadEntry` 记录文件名、URL、展示状态、进度、错误、可选父作品及当前 `workId`，模型定义见 [Models.kt](../app/src/main/java/cc/novelia/app/data/Models.kt)。

`enqueue` 创建一次性 `DownloadWorker`，输入为任务 ID 与入队时的账号名，以 `download-<id>` 为唯一任务名，使用 `ExistingWorkPolicy.REPLACE`。它先持久化“等待下载”和新的 `workId`，再提交 WorkManager。网络约束为 `CONNECTED`，或启用“仅 Wi-Fi”时的 `UNMETERED`；后者实际表达 Android 的“非按流量计费网络”约束。

### 状态迁移

| 状态 | 进入条件 | 用户后续操作 |
| --- | --- | --- |
| 等待下载 | 新建或重新入队，等待网络和系统调度 | 暂停、删除 |
| 下载中 | Worker 校验当前任务后开始读取 | 暂停、删除 |
| 已完成 | 完整文件提交成功，进度设为 100 | 导入阅读、导出、分享、外部打开、删除 |
| 已暂停 | 取消唯一任务并持久化暂停状态 | 重新开始、删除 |
| 需要登录 | 任务账号与当前会话不符，或认证失败 | 登录后重试、删除 |
| 失败 | 网络、文件响应或其他处理错误 | 重试、删除 |

暂停没有 HTTP Range 断点续传。重试会创建新 Worker 并从头下载，界面明确显示这一行为。网络错误通常返回 `Result.failure()` 等待用户重试；完成或错误状态写盘失败时，Worker 可返回 `Result.retry()`。不要假定所有失败都有自动重试。

### 文件提交与陈旧 Worker

Worker 先获取任务锁，核对记录仍存在、未暂停、`workId` 一致；旧安装中没有 `workId` 的记录由当前 Worker 认领。入队时记录的账号与开始时账号不符时直接失败；下载期间持续检查捕获会话仍有效。

下载先写入 `downloads/<downloadId>-<workId>.part`。网络客户端读超时为 120 秒；初始 URL 必须为 `https://n.novelia.cc`，客户端允许重定向，认证请求通过统一的 `withAuthenticatedResponse`。响应必须成功、不是 `text/html`，非空且符合文件大小约束；已知 Content-Length 时实际总字节必须一致。进度按内容长度计算并节流更新，未知长度时读取阶段保持 0。

完成提交、暂停、删除、重新入队都使用 `DownloadFiles.withTaskLock` 的同任务互斥锁。提交前再次检查会话、协程、任务存在性、`workId`、文件名和暂停状态，然后将同一私有目录中的临时文件移动到目标路径，再标记完成。该设计防止被删除或替换的 Worker 迟到后恢复文件或覆盖新任务。

`DownloadFiles` 维护当前进程活跃 `.part` 集合，清理只处理受识别的非活跃临时文件。应用初始化时回收遗留 `.part`；Worker 在 `finally` 中释放自己的临时文件。删除任务会先取消唯一工作、移除并刷写任务记录，再清理下载文件；其他任务不共享这把单任务锁。

修改此逻辑时，不要把“取消命令完成”视作“Worker 已完全退出”，也不要删除仍活跃的临时文件。

## 文件工具

`ToolsScreen` 在 IO 线程读取用户文件，在 `Dispatchers.Default` 执行处理，保存结果字节供用户预览和主动导出。

| 工具 | 行为与输出 | 开发时需要保留的边界 |
| --- | --- | --- |
| EPUB 转 TXT | 按 spine 顺序输出章节标题和正文，过滤 `novelia-image:` 标记，UTF-8 `.txt` | 不导出图片，也不保留 EPUB 样式 |
| EPUB 图片压缩 | 重建 EPUB ZIP；`mimetype` 作为首个 STORED 条目；对 JPG/JPEG/PNG/WebP 解码、采样、重编码 | 以 2 的幂选择采样倍率，目标最长边约不超过 2000；质量参数 82；仅当重编码图片更小才替换 |
| 文本换行整理 | 合并段内换行，保留空行分段和部分句末/对话标点前后的换行；UTF-8 `.txt` | 是规则整理，用户需要检查预览；不是 OCR 或语义纠错 |
| 片假名统计 | 匹配连续片假名词组，修剪边缘 `・`/`ー`，统计频次降序输出；UTF-8 `.tsv` | 不判定人名，不做词法分析；导出包含“词语/频次”表头 |

图片压缩保留条目路径和扩展名；PNG 仍输出 PNG，WebP 仍输出 WebP，JPEG 仍输出 JPEG。ZIP 重建本身不保证总文件一定变小，原 ZIP 元数据也不是字节级保留。当前工具路径需要把输入、解压内容和结果放入内存，不适合按下载的 512 MiB 上限处理。

## 导出、分享与 URI 权限

### 文档选择器

导入使用 `OpenDocument` / `OpenMultipleDocuments`，导出使用 `CreateDocument` 后经 `ContentResolver.openOutputStream` 写入用户选择的位置。当前流程不调用 `takePersistableUriPermission`：导入即时复制到私有存储，导出只在选择结果返回后使用目标 URI。

书架“导出原文件”优先复制导入时保留的原件。旧数据没有原件时，回退为按章节读取的段落文本输出；这不是重建原 EPUB 的流程。下载导出则直接复制完整下载文件。两类界面都通过 `rememberSaveable` 保存待导出的记录 ID，在选择器返回后重新查找记录，避免依赖已失效的对象引用。

工具结果使用 `PendingExportFiles`：

1. 启动选择器前，将结果写到 `filesDir/exports/tool-export-<uuid>.bin`。
2. 界面仅保存 UUID；活动重建后可根据 ID 找回磁盘上的结果。
3. `finish` 校验 UUID 并复制到目标输出流；成功、取消选择、目标写入失败都会在 `finally` 清理暂存文件。
4. 若启动选择器失败，界面也清理已暂存文件。若暂存内容已经丢失，明确报错，不生成空文件冒充成功。

普通输入、预览和计算结果并未全部持久化；上述机制保护的是已经开始导出的载荷。进程被终止后是否恢复选择器回调仍取决于 Android 生命周期，不能将其描述为任意时刻的任务恢复。

### 外部打开和分享

[AndroidManifest.xml](../app/src/main/AndroidManifest.xml) 将 FileProvider 注册为 `${applicationId}.files`，`exported=false`、`grantUriPermissions=true`。[file_paths.xml](../app/src/main/res/xml/file_paths.xml) 只开放应用私有 `filesDir` 下的 `downloads/` 和 `exports/`。

已完成下载通过 `content://` URI 交给其他应用，附加 `FLAG_GRANT_READ_URI_PERMISSION`。外部打开对 EPUB 使用 `application/epub+zip`，其他下载使用 `text/plain`；分享使用 `application/octet-stream`。不要改用 `file://`，也不要为方便分享而把整个私有文件目录加入 FileProvider。

Manifest 没有申请广泛的存储读写权限，也没有将应用注册成所有 EPUB/TXT 文件的外部打开目标；当前文档导入入口在应用内的系统文档选择器。应用接收的 `ACTION_SEND text/plain` 不能等同于文件附件导入支持。

## 已退役的图像 OCR

当前没有图片 OCR 识别入口，也不再为该功能下载或运行模型。不要根据 `repairOcr` 的历史函数名推断应用仍具备图像文字识别能力。

- [NoveliaApplication.kt](../app/src/main/java/cc/novelia/app/NoveliaApplication.kt) 初始化后调用 [RetiredModels.kt](../app/src/main/java/cc/novelia/app/files/RetiredModels.kt)，清理 `noBackupFilesDir/ocr-models`。
- 清理仅针对这一个旧模型目录，使用不跟随符号链接的文件树遍历，不应扩展为任意下载目录清理。
- 用户旧的校对文本保留在 `drafts["tool:local-ocr"]`。文件工具出现“取回上次校对文本”按钮，将它载入普通文本换行整理功能。
- 旧草稿迁移与模型清理应分别验证，不能把用户校对文本当作模型缓存删除。

## 扩展与修改步骤

### 增加文档格式

1. 定义转换到 `LocalDocument` / `LocalChapter` 的规则，确定标题、段落、插图和原文件的保存方式。
2. 在 `DocumentTools.parseFile` 加入分派，复用容量检查与取消检查；如果需要 `ByteArray` 工具入口，另行评估并实现对应路径。
3. 更新 `LocalStore.documentSource` 的格式白名单和删除原件逻辑，并核对资料备份、恢复与原件导出是否认识新格式。
4. 更新文件选择提示、错误信息、下载外部打开 MIME 类型和本页支持范围。文件选择器过滤不能代替解析器内部校验。
5. 增加正常样本、空正文、损坏文件、超限、编码/路径异常和中途取消测试，验证失败后没有残留的新书架条目。

### 修改下载能力

1. 同时检查 URL 构造、表单选项和服务器接口约束，下载不能凭空生成服务端不存在的格式或译文。
2. 保留 `workId` 和会话绑定检查，在新增状态或操作后继续使用同任务锁；状态字符串由 Worker、持久化模型和界面共同使用。
3. 新增重试或续传机制时明确临时文件归属、服务端内容变化及校验方式；当前“重新开始”的语义必须随实现一起更新。
4. 对新的 `.part` 命名或清理规则同时更新清理与并发测试，检查删除后迟到提交、旧 Worker 覆盖新任务及不同任务相互阻塞。

## 测试入口

| 测试 | 主要覆盖 |
| --- | --- |
| [DocumentToolsTest](../app/src/test/java/cc/novelia/app/DocumentToolsTest.kt) | EPUB spine 顺序、插图顺序、压缩包路径逃逸、SRT 时间戳、编码、文本整理、片假名计数 |
| [FileImportRegressionTest](../app/src/test/java/cc/novelia/app/FileImportRegressionTest.kt) | 磁盘导入图片流、输入和 XHTML 上限、异常 ZIP 大小声明、文本处理取消 |
| [DownloadFilesTest](../app/src/test/java/cc/novelia/app/DownloadFilesTest.kt) | 活跃临时文件保护、删除与完成提交竞争、旧任务提交拒绝、任务间互不阻塞 |
| [PendingExportFilesTest](../app/src/test/java/cc/novelia/app/PendingExportFilesTest.kt) | 仅凭已保存 ID 恢复导出、取消/失败清理、缺失文件、非法 ID、准备过程取消 |
| [RetiredModelsTest](../app/src/test/java/cc/novelia/app/files/RetiredModelsTest.kt) | 旧模型清理与其他目录保留、重复清理 |
| [FileToolsUpgradeTest](../app/src/androidTest/java/cc/novelia/app/FileToolsUpgradeTest.kt) | Android 界面中恢复旧校对文本 |
| [DownloadSheetLayoutTest](../app/src/androidTest/java/cc/novelia/app/DownloadSheetLayoutTest.kt) | 下载表单布局与滚动可访问性 |
| [DownloadLiveTest](../app/src/androidTest/java/cc/novelia/app/DownloadLiveTest.kt) | 真实服务生成 EPUB、WorkManager 下载及解析；需显式 `live=true` |

可从仓库根目录选择运行纯 JVM 回归测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'cc.novelia.app.DocumentToolsTest' --tests 'cc.novelia.app.FileImportRegressionTest' --tests 'cc.novelia.app.DownloadFilesTest' --tests 'cc.novelia.app.PendingExportFilesTest' --tests 'cc.novelia.app.files.RetiredModelsTest'
```

Android Bitmap 重编码、系统文档选择器、外部分享权限、旋转/进程重建及真实下载需要设备或模拟器验证，JVM 测试通过不能替代这些场景。`DownloadLiveTest` 默认跳过，启用后会访问真实服务并写入测试下载；不要在无需网络的回归任务中隐式开启。
