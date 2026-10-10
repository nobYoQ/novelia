# 书籍详情与文库

[功能目录](README.md) · [文档首页](../README.md)

[BookScreen.kt](../../app/src/main/java/cc/novelia/app/ui/book/BookScreen.kt) 连接发现、书架、阅读、下载和资料编辑。入口参数是 `BookRef(provider, id)`，不同类型的书籍有不同路径。

| 类型 | 主要内容 | 阅读方式 |
| --- | --- | --- |
| 网络小说 | 简介、标签、统计、章节目录、评论 | 直接打开章节 |
| 文库作品 | 出版资料、文件分卷、关联网络小说 | 下载并导入分卷，或读关联网络小说 |
| 本地文档 | 本地信息和章节目录 | 从本地文件读取 |

文库的出版分卷 `volumes`、文件记录 `volumeJp/volumeZh` 和本机挂载的本地文档是三类对象。改出版卷名不会自动改导入文件名，分卷关系见[书架](library.md)。

详情页的本地收藏按钮打开收藏面板。已收藏时可在面板内取消本地收藏，操作按书架的移出规则处理，并可通过提示撤销；原站云端收藏不受影响。

点击标签文字搜索该原文标签；右侧三个点展开“编辑标签翻译”和“屏蔽标签/取消屏蔽标签”，操作菜单不会触发搜索。屏蔽和编辑仍使用原文标签作为身份。

## 继续阅读

目录分组标题没有 `chapterId`，既不能打开，也不计入章节序号。续读候选必须仍在可读目录里：

1. 找到本机保存的有效章节。
2. 找到原站记录的有效章节。
3. 两者不同且都有效时让用户选择。
4. 都不可用时回到第一条可读章节。

规则集中在 [ReadingContinuity.kt](../../app/src/main/java/cc/novelia/app/data/library/ReadingContinuity.kt)。进入正文前保留书目摘要，段落位置由[阅读器](reader.md)恢复。

“最新章节”只跳转可识别的章节 ID；更新时间使用内容数据，不用同步检查时间替代。摘要缺字段时保留同一本书已有的有效信息。

## 目录和缓存

目录搜索只筛选章节标题，不搜索正文。筛选、倒序与定位当前章使用真实章节 ID，不能用显示顺序代替身份。

手动缓存按可读章节序号选择，序号从 1 开始，单批最多 200 章。倒置、越界和超限在请求前拒绝。已有缓存不保证所有译文完整或最新，批次并发和新鲜度规则见[网络章节缓存](../network/network-and-sync.md#章节缓存和预读)。

## 文库下载与上传

译文下载要求分卷有章节，且至少一种引擎已完成相应章节数量。批量下载共用一组选项，每卷独立任务，失败后只重试未入队的部分。文件完成后仍需导入，见[文件与下载](files-and-downloads.md)。

文库上传在界面检查 `canEdit`、EPUB/TXT 类型和 40 MiB 上限，临时文件用完清理，服务端仍会校验。中文文件链接与译文生成下载是不同入口，当前中文文件入口有管理员条件。

## 资料编辑和术语

| 功能 | 实现与注意点 |
| --- | --- |
| 网络小说翻译资料 | [EditBookScreen.kt](../../app/src/main/java/cc/novelia/app/ui/book/EditBookScreen.kt) 更新中文标题、简介和目录翻译 |
| 关联文库 | 单独的 `wenku-id` 请求；前一步成功而这一步失败时，不能声称所有修改都没生效 |
| 文库新建/修改 | [WenkuEditor.kt](../../app/src/main/java/cc/novelia/app/ui/book/WenkuEditor.kt)，包含出版分卷、作者、封面等 |
| 术语表 | [GlossaryScreen.kt](../../app/src/main/java/cc/novelia/app/ui/book/GlossaryScreen.kt)，区分本地个人术语与远端作品术语 |

文库编辑使用 `wenku:new` 或 `wenku:<id>` 草稿。新建时提示可能重复的标题；修改前重读远端可编辑字段，发现变化则提示冲突。字段白名单由 [WenkuEditPayload.kt](../../app/src/main/java/cc/novelia/app/ui/book/WenkuEditPayload.kt) 共用。

重读再比较只能发现已经发生的冲突，两个请求之间仍有并发窗口，不是服务端原子锁。成功后只清理与发送快照相同的草稿，发送期间的新输入保留。

这些编辑和术语请求直接发送，不进入收藏/历史离线队列；修改术语也不会让客户端生成新译文。

## 回归重点

检查本机/原站续读相同、不同或失效，目录只有分组标题，缓存取消与部分失败，远端编辑冲突，以及提交期间继续输入。相关入口包括 `ReadingContinuityTest`、`BookMetadataTest`、`WenkuVolumesTest`、`EditorStateRegressionTest` 和 [ui/book 设备测试](../../app/src/androidTest/java/cc/novelia/app/ui/book)。
