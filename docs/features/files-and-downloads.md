# 文件导入、下载与导出

[功能目录](README.md) · [文档首页](../README.md)

文件功能有三步：**下载得到文件，导入建立本地文档，导出交给系统文件提供方或其他应用。** 各步独立，下载完成不表示已导入；删除下载任务也不删除已导入副本。

## 主要实现

| 职责 | 入口 |
| --- | --- |
| URI 暂存、来源去重、导入提交 | [DownloadImport.kt](../../app/src/main/java/cc/novelia/app/files/importing/DownloadImport.kt) |
| 多文件批次 | [DocumentImportBatch.kt](../../app/src/main/java/cc/novelia/app/files/importing/DocumentImportBatch.kt) |
| 系统文件选择与备用入口 | [FilteredDocumentPicker.kt](../../app/src/main/java/cc/novelia/app/ui/components/documents/FilteredDocumentPicker.kt)、[DocumentPickerTypes.kt](../../app/src/main/java/cc/novelia/app/ui/components/documents/DocumentPickerTypes.kt) |
| 格式分派与文本工具 | [DocumentTools.kt](../../app/src/main/java/cc/novelia/app/files/DocumentTools.kt) |
| 磁盘 EPUB 解析与对照识别 | [EpubReader.kt](../../app/src/main/java/cc/novelia/app/files/epub/EpubReader.kt)、[EpubChapter.kt](../../app/src/main/java/cc/novelia/app/files/epub/EpubChapter.kt) |
| 下载任务与文件锁 | [DownloadWorker.kt](../../app/src/main/java/cc/novelia/app/files/DownloadWorker.kt)、[DownloadFiles.kt](../../app/src/main/java/cc/novelia/app/files/downloads/DownloadFiles.kt) |
| 原件/正文导出和临时文件 | [BookExport.kt](../../app/src/main/java/cc/novelia/app/files/exporting/BookExport.kt)、[PendingExportFiles.kt](../../app/src/main/java/cc/novelia/app/files/exporting/PendingExportFiles.kt) |
| 下载与工具页面 | [ui/downloads](../../app/src/main/java/cc/novelia/app/ui/downloads)、[ui/tools](../../app/src/main/java/cc/novelia/app/ui/tools) |

## 导入流程

在文件管理器中长按 EPUB、TXT、SRT，选择“分享 → Novelia”，即可进入书架的“本地文件”并导入，支持单文件和多文件。接收入口兼容 `EXTRA_STREAM` 和仅提供 `ClipData` 的文件管理器，也接收标记为 `application/octet-stream` 的文件；实际导入仍按扩展名和内容校验。只有明确的书籍 MIME 才能为缺少扩展名的文件补齐格式，不支持的文件会在导入清单中报告失败。纯文本链接分享继续沿用原有链接导航。

分享使用发送应用授予的临时 URI 读取权限，无需申请全部存储空间权限；导入成功后保留自己的副本。尚未交给书架的文件随 Activity 保存，正在进行的批次由页面 ViewModel 保留，旋转屏幕不会重复启动；已有批次运行时，新分享的文件等待其结束后导入。

系统文件选择器按各入口可处理的 MIME 类型筛选：

筛选约束的是文件能否被选择。具体显示方式由系统文件选择器和文件提供方决定：不支持的文件可能被隐藏，也可能保留在列表中但置灰、不可选；文件夹仍可进入。MuMu Android 15 的系统选择器实测采用置灰方式，因此列表中仍能看到其他格式不代表筛选未生效。

| 入口 | 默认格式 |
| --- | --- |
| 书架顶部、本地文件空页面 | EPUB、TXT、SRT，支持多选 |
| 阅读字体 | TTF、OTF、TTC，支持多选 |
| 阅读资料备份 | ZIP |
| 普通设置（含阅读偏好）、标签库、术语表 | JSON |
| 文库分卷上传 | EPUB、TXT |
| EPUB 转 TXT、EPUB 图片压缩 | EPUB |
| 文本换行整理、片假名统计 | 文本文件，包括 SRT、JSON、XML |

未选中文件返回后，原页面会显示可关闭的“找不到文件？”提示，点击“显示所有文件”可重新打开不限制类型的选择器，兼容文件类型标记不准确的文件提供方。备用入口仅对本次选择生效，后续普通导入仍默认筛选；文件选中后继续由原有流程校验格式和内容。字体筛选兼容新旧字体类型名称；普通导入不放行通用二进制类型，JSON 和 ZIP 也不混入“所有文件”通配符。

系统文档选择器返回 URI，不保证有真实路径。导入通过 ContentResolver 流式复制到私有暂存，检查实际大小，再计算原文件 SHA-256：

```text
URI → 有界复制 → 来源哈希去重 → 解析
    → 保存原件/插图/分块正文 → 加入书架
```

同一原始字节返回已有文档，保留进度；仅书名相同不会去重。修改 EPUB 元数据也会改变来源哈希。失败清理本次新文档及暂存，不替换已有书籍。

多文件批次逐项报告读取、去重、解析和保存，单项失败后继续。页面 ViewModel 保留旋转期间的批次，支持暂停、继续未处理项和只重试失败项；批次清单不跨进程保存，成功导入的书籍会持久化。

下载文件复用同一导入流程。文库分卷在“开始阅读”或批量导入时确保父作品存在并挂载；重复文档复用原副本和位置。父项已有的收藏夹与状态保留。

文库分卷下载完成后，点击详情页分卷即可阅读；首次点击自动导入，已有副本直接续读。`LibraryState.downloadLinks` 独立保存下载、来源书籍/分卷与本地副本的关联，即使移出书架或删除下载任务仍可定位副本，备份恢复会重写本地 ID。

两个清理设置保持独立：“导入书架后删除下载”仅在副本成功持久化后删除源下载；“移出书架时删除本地副本”只控制移出本地书目时的导入副本，不额外删除下载。“彻底删除”不受这两个开关限制，会统一清理书架、副本、对应下载及本机阅读资料。下载管理普通“删除”仍只清理下载；书架主动“删除本地小说”则同步清理对应下载，保留阅读资料。整本文库删除包含挂载分卷、来源关联中的脱离分卷及尚未导入的下载任务，单卷删除保留父书与其他卷。旧下载通过来源 URL 和完整文件哈希补回关联，不按书名批量匹配。

升级前已经清理下载源、且没有保留来源或挂载关系的独立副本无法可靠回溯所属作品，仍需在本地文件中手动删除。不同作品通过哈希去重共用的副本，在删除来源作品时会保留其他作品仍使用的文件。

## 支持的格式

| 格式 | 处理方式 |
| --- | --- |
| EPUB | 读取 container/OPF，按 spine 顺序取正文；提取章节、段落和本地插图 |
| TXT | UTF-16 BOM、严格 UTF-8、失败回退 GB18030；识别常见章标题，无标题长文按段数分章 |
| SRT | 按空行保留字幕块、序号和时间戳，作为一个章节阅读 |

EPUB 是内容提取，不是完整排版引擎。CSS、脚本、注音和复杂原页面布局不会完整保留。内部资源路径规范化后校验，图片按内容哈希保存，不按压缩包路径落盘，也不下载外部插图 URI。

双语 EPUB 只凭可靠的下载模式、样式或语言标记建立对照组，不按文字形状或奇偶段猜测。旧文件可从保留原件保守恢复元数据，必须保持段落对应；无法确认时保留可读正文。

书架导入使用磁盘路径 `parseFile`，逐项读取；文件工具仍有 ByteArray/内存解包路径。两者限额和内存行为不同，新增导入入口优先复用现有 URI 导入。

## 容量限制

以下 MiB 均为 1024 × 1024 字节，界面可能写作 MB。

| 入口 | 当前限制 |
| --- | --- |
| 本地原文件 / 工具选入文件 | 64 MiB |
| EPUB 累计展开内容 | 192 MiB |
| 磁盘 EPUB 项数 | 10,000，目录也计数 |
| 单个 XML / OPF / XHTML | 8 MiB |
| 单张 EPUB 插图 | 16 MiB，超限跳过该图 |
| 磁盘导入累计正文 | 16 × 1024 × 1024 个字符 |
| 网络下载 | 512 MiB |
| 文库上传 | 40 MiB，仅 EPUB/TXT |

**下载上限大于导入上限。** 大文件可能下载成功，但不能在应用内导入，仍可导出或外部打开。工具文件整体进入内存，容量上限不等于峰值内存。整库 ZIP 另见[备份限额](../data/backup-and-recovery.md#格式校验)。

## 下载任务如何完成

表单请求服务端生成已有译文文件，不创建翻译任务。任务记录 UUID、账号、URL、文件名、来源书目和 `workId`，先持久化再提交 WorkManager。文库批量下载逐卷入队，失败重试不重复已成功任务。

| 状态 | 后续操作 |
| --- | --- |
| 等待 / 下载中 | 暂停、删除 |
| 已完成 | 导入、导出、分享、外部打开 |
| 已暂停 | 重新开始 |
| 需要登录 | 登录原账号后重试 |
| 失败 | 查看原因、手动重试 |

传输使用两个独立下载调度槽，不占满正文 API 的调度器。文件写入 `<downloadId>-<workId>.part`，检查大小、空响应、HTML 和已知 Content-Length。账号与来源绑定持续校验，镜像路由和重定向使用统一网络层。

提交、暂停、删除和重新入队共用同任务锁。提交前重新检查记录存在、workId、文件名、暂停状态、会话和取消，然后移动文件并标记完成。取消命令返回不代表旧 Worker 已退出，迟到任务不得恢复已删除文件。

同一 Worker 内的瞬时网络中断最多自动恢复两次。有强 ETag 和已知长度时使用 `Range` / `If-Range` 续传，追加前校验版本、最终 URL、范围和总长度；不支持范围、版本变化或没有可靠校验器时从头下载。取消、换号、HTTP 错误和存储错误不触发自动恢复。失败后仍可手动重试，特定状态持久化失败才返回 Worker retry。

暂停或进程终止后重试**从头下载**，不复用其他 Worker 的临时文件。临时文件清理避开当前活跃任务。恢复策略与时限另见[网络恢复](../network/network-diagnostics.md#网络切换与连接恢复)。

## 导出与分享

系统 SAF 按实际格式设置 MIME，写入时截断旧内容。导入即时复制、导出即时写入，不长期保存外部 URI 权限。

书架导出优先使用保留原件；缺少原件时按现有正文导出能力处理。重命名改变默认导出文件名，不重写 EPUB 原始元数据。

下载多选中，单文件直接交付，多个已完成文件才打成 ZIP。导出和分享的 ZIP 都按作品身份建立目录：`[作者] 小说名`，有插画师时为 `[作者×插画师] 小说名`；多位作者或插画师用 `、` 连接。同一作品的各卷、格式和译文放在同一目录，目录内同名文件加序号；不同作品即使目录名相同，也用序号区分。作者缺失时仅用书名；旧下载优先利用书架和本机详情缓存补齐资料，不联网查询。无法识别来源的旧下载各自建目录，不按标题猜测合并。路径中的非法字符会替换，过长名称会缩短。

分组只改变 ZIP 内的路径，应用私有下载目录、原始文件和单文件导出/分享保持不变。打包与删除/提交共用锁。导出临时文件通过保存 ID 支持界面重建，完成或取消后清理。分享使用只读 FileProvider 权限，文件保留到接收方读取，后续清理过期分享包。

FileProvider 仅暴露 downloads 和 exports，不能为解决路径错误开放应用私有根目录。

## 文件工具和旧版兼容

工具提供 EPUB 转 TXT、EPUB 图片压缩、文本换行整理和片假名频次统计。转换不保留 EPUB 图片/样式；压缩仅替换更小的重编码图片，不保证整个 ZIP 一定缩小；文本整理和统计都是规则处理。

图像 OCR 已退役。旧 `ocr` 路由转到工具页，遗留模型可清理，用户旧校对文字继续保留在草稿中，不能当缓存删掉。

## 验证重点

JVM 优先看 `DocumentImportBatchTest`、`FileImportRegressionTest`、`LocalEpubReadingTest`、`DownloadFilesTest`、`DownloadArchiveTest` 和 `PendingExportFilesTest`。

重点覆盖 ZIP 路径和大小、取消、重复导入、旧文件兼容、删除后迟到提交、批量部分失败与导出重建。Bitmap、文件提供方、分享权限和实际下载需要设备测试，方法见[测试指南](../quality/testing.md)。
