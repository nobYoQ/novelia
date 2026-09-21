# 书籍详情与文库维护

[返回业务功能索引](README.md) · [文档总目录](../README.md)

详情页连接发现、书架、阅读器、下载和社区。入口是 [BookScreen.kt](../../app/src/main/java/cc/novelia/app/ui/book/BookScreen.kt)，路由使用 `BookRef(provider, id)`，通过 provider 区分网络小说、文库作品和本地文档。

## 1. 三类详情的职责

| 类型 | 主要内容 | 阅读与文件入口 |
| --- | --- | --- |
| 网络小说 | 标题、作者、简介、标签、更新信息、章节目录、评论 | 从头阅读、继续阅读、指定章节、章节缓存 |
| 文库作品 | 作品资料、出版分卷、可下载文件、关联网络小说、评论 | 下载后导入本地分卷，或打开关联网络小说 |
| 本地文档 | 本地书籍信息与文档目录 | 从本地章节读取正文，不依赖在线详情 |

文库详情中的 `volumes` 是出版资料，`volumeJp`、`volumeZh` 是文件相关记录；它们与本机已经导入、通过 `parentWenkuKey` 挂载的本地分卷也不同。修改出版卷名不会自动重命名已导入文件。分卷挂载、排序及去重见[书架文档](library.md)。

详情转为 `BookCard` 后可以保存到本地书架。保存或更新摘要时应保留已有用户分组、置顶等选择；部分接口缺少更新时间时，不能用空值抹掉已知时间。相关逻辑见 [BookMetadata.kt](../../app/src/main/java/cc/novelia/app/data/model/BookMetadata.kt) 和 [LocalStore.kt](../../app/src/main/java/cc/novelia/app/data/storage/LocalStore.kt)。

## 2. 继续阅读如何选章节

阅读入口首先需要确认章节仍在当前目录中。目录中的分组标题没有 `chapterId`，不能作为章节打开，也不能参与“第几章”的计算。

1. 检查本机保存的位置，只有对应章节仍可读时才作为候选。
2. 检查详情中的原站阅读章节，只有它仍在可读目录中时才作为候选。
3. 本机和原站候选不同且均有效时，由用户选择；界面不会仅凭两个进度百分比自动覆盖其中一份。
4. 没有有效历史时，回退到第一条可读章节。

`resumeDestination`、`readingDestination` 等逻辑集中在 [ReadingContinuity.kt](../../app/src/main/java/cc/novelia/app/data/library/ReadingContinuity.kt)。打开阅读器前会保存书籍摘要；段落与段内位置如何恢复见[阅读器文档](reader.md)。

最新更新摘要由 [BookUpdateSummary.kt](../../app/src/main/java/cc/novelia/app/ui/book/BookUpdateSummary.kt) 生成。时间来自详情中的更新时间，缺失时显示无时间记录；“最新章节”只有能映射到当前目录才提供阅读跳转。展示标题不能直接充当路由中的章节 ID。

## 3. 目录、搜索与离线缓存

目录支持标题筛选、倒序、定位当前章节和显示已缓存状态。搜索只作用于已取得的目录标题，不搜索正文；正文搜索另见阅读器专题。

范围缓存以可读章节为基准，用户输入的序号从 1 开始，分组标题不计数。现有范围校验限制单次最多 200 章，空范围、倒置范围和越界输入应在发起请求前拒绝。界面顺序倒置后，缓存逻辑仍需要使用真实章节标识。

缓存状态与译文完整度是两种信息：已有章节文件不代表每种翻译引擎都已完成，也不代表缓存中的译文仍然最新。刷新和缓存新鲜度见[网络文档](../network/network-and-sync.md)及[数据文档](../data/data-and-storage.md)。本地文档正文属于用户资料，不通过网络章节清缓存功能删除。

## 4. 文库下载与上传

| 操作 | 客户端条件与处理 | 维护时注意 |
| --- | --- | --- |
| 下载译文 | 章节总数大于零，且某一引擎完成数量达到总数时提供相应下载入口 | 整体满足入口条件不等于用户选定的每个引擎都完整 |
| 打开中文文件链接 | 使用详情返回的中文文件记录，当前入口要求管理员角色 | 跳转原站资源，链接访问权限仍由服务端决定；与译文生成下载流程区分 |
| 上传文库文件 | 要求 `canEdit`；界面检查 EPUB/TXT 类型及 40 MiB 上限 | 文件在临时目录处理，结束时清理；服务端仍会校验 |

下载的鉴权、任务状态、文件校验、保存到系统选择器以及导入挂载见[文件与下载](files-and-downloads.md)。不要用“下载已完成”表示“已导入书架”，这两个步骤可以独立发生。

## 5. 网络小说与文库编辑

### 网络小说资料

[EditBookScreen.kt](../../app/src/main/java/cc/novelia/app/ui/book/EditBookScreen.kt) 读取现有资料，提交中文标题、简介和已有目录翻译映射到 `novel/{provider}/{id}/translation`。关联文库 ID 通过另一个 `wenku-id` 请求更新。

这不是一个跨接口事务：翻译资料保存成功后，关联文库请求仍可能失败。排查时应分别确认两项状态，不能因为最后一步报错就断言所有修改都未生效。此处直接调用 API，不属于收藏/阅读进度的离线重放队列。

### 文库作品与出版分卷

[WenkuEditor.kt](../../app/src/main/java/cc/novelia/app/ui/book/WenkuEditor.kt) 负责新建和修改作品，包含作者、插画师、分类、简介、标签、封面及出版分卷。分卷可编辑 ASIN、卷名、出版社、文库品牌和出版日期；日期以 UTC 零点转换为秒级时间戳。

- 草稿键为 `wenku:{id}` 或 `wenku:new`，约 700 ms 防抖保存，并在页面离开时处理最新值。
- 新建前以原文标题查询可能重复的条目，让用户核对后决定是否继续；这不是数据库唯一约束。
- 修改前重新读取详情，比较可编辑字段是否与进入时一致；发现差异以冲突提示中止提交。
- 请求字段白名单由 [WenkuEditPayload.kt](../../app/src/main/java/cc/novelia/app/ui/book/WenkuEditPayload.kt) 统一生成，冲突比较与真正提交使用同一组字段。
- 成功后仅在当前草稿仍等于已发送快照时清理草稿，避免误删发送期间的新输入。

“读取最新值再提交”能发现已经发生的冲突，但两个 HTTP 请求之间仍存在窗口；不要把客户端比较描述为服务端原子版本锁。所有编辑入口的 `canEdit` 检查只是交互约束，最终授权由服务端完成。

## 6. 术语表

[GlossaryScreen.kt](../../app/src/main/java/cc/novelia/app/ui/book/GlossaryScreen.kt) 区分本地个人术语表和远端作品术语表，并提供 JSON 导入/导出。

本地术语写入 `LibraryState.personalGlossaries`，属于设备资料。远端术语需要编辑权限；提交前再次读取并与初始术语比较，发生变化时提示冲突。远端保存也直接调用 API，不会自动进入离线写入队列。

术语表是资料维护功能。当前客户端不会因为修改术语而自行重新生成译文，也不提供翻译生产任务管理。

## 7. 修改后的核对场景

| 改动范围 | 建议核对 |
| --- | --- |
| 继续阅读 | 本机与云端相同、不同、一方失效、均无进度、目录仅有分组标题 |
| 更新摘要 | 无更新时间、最新章节已删除、目录有分组、标题相同但 ID 不同 |
| 目录缓存 | 正常范围、边界、超过数量上限、取消、部分失败 |
| 文库编辑 | 草稿恢复、可能重复条目、远端冲突、发送期间继续输入 |
| 文库文件 | 翻译数量不完整、不同引擎进度不同、下载后未导入、上传失败 |

可先阅读 [ReadingContinuityTest](../../app/src/test/java/cc/novelia/app/ReadingContinuityTest.kt)、[BookMetadataTest](../../app/src/test/java/cc/novelia/app/BookMetadataTest.kt)、[WenkuVolumesTest](../../app/src/test/java/cc/novelia/app/WenkuVolumesTest.kt) 及 [EditorStateRegressionTest](../../app/src/test/java/cc/novelia/app/EditorStateRegressionTest.kt)。这些是回归入口，不表示已经覆盖表中所有设备与网络场景。
