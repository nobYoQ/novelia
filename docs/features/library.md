# 书架与阅读资料管理

[文档总目录](../README.md) · [功能索引](README.md)

书架汇集本机收藏、原站云端收藏和已导入的阅读副本。本专题说明整理、展示和更新规则；正文处理见[阅读器](reader.md)，远端请求和重放见[网络与同步](../network/network-and-sync.md)。

## 功能入口与数据归属

| 对象 | 保存位置 | 主要入口 |
| --- | --- | --- |
| 本地书目、收藏夹、置顶、阅读状态 | `LibraryState.books/folders` | [ShelfScreen](../../app/src/main/java/cc/novelia/app/ui/shelf/ShelfScreen.kt) |
| 云端收藏夹及收藏关系 | 原站账号；未发送意图保存在本机队列 | [CloudShelf](../../app/src/main/java/cc/novelia/app/ui/shelf/CloudShelf.kt)、[FavoriteSheet](../../app/src/main/java/cc/novelia/app/ui/shelf/FavoriteSheet.kt) |
| 精确阅读位置 | `LibraryState.positions`，按 `BookRef.key` 索引 | [HistoryScreen](../../app/src/main/java/cc/novelia/app/ui/shelf/HistoryScreen.kt) |
| 文库父项与本地分卷关系 | `SavedBook.parentWenkuKey/volumeOrder` | [WenkuVolumes](../../app/src/main/java/cc/novelia/app/data/library/WenkuVolumes.kt) |
| 更新快照与未读更新摘要 | `updateSnapshots/bookUpdates` | [UpdateWorker](../../app/src/main/java/cc/novelia/app/data/updates/UpdateWorker.kt) |

本机资料由设备共享，不因切换账号建立另一套书架。云端收藏与其待同步操作带账号归属。只在云端收藏一本书，不意味着本机已导入正文；反过来，导入一个 EPUB 也不会自动在原站创建收藏。

## 本地收藏和整理

`LocalStore.saveBook` 按稳定书籍引用更新摘要和文件夹，同时保留已有书目的其他管理属性。元数据缺少更新时间时，`withKnownUpdateTime` 尽量保留同一本书已知的有效时间，避免一次不完整列表响应抹掉详情信息。

本地收藏夹重命名、删除和批量移动都通过状态变换完成。删除收藏夹会把其中书目移入默认收藏，文件仍然存在；默认收藏具有特殊地位，不能把它当作普通文件夹随意删除。

需要区分三个操作：

| 操作 | 结果 |
| --- | --- |
| 从本地书架移除 | 移除收藏条目并整理分卷关联，不等于删除正文文件或所有阅读资料 |
| 删除本地小说 | `LocalStore.removeDocument` 清理文档、章节、插图、原件及对应书目 |
| 取消云端收藏 | 向原站提交删除收藏关系的意图，不替用户删除本机文件 |

[ShelfUndo](../../app/src/main/java/cc/novelia/app/ui/shelf/ShelfUndo.kt)按当前状态合并被移除的条目，不能直接恢复整个旧书架快照，否则会覆盖用户在撤销等待期间的其他操作。若父项已被另一个操作删除，撤销也要处理失效挂载关系。

## 云端收藏与待同步展示

云端列表按账号及网络小说/文库类别加载。收藏夹聚合入口和真实可写收藏夹的区别由 [CloudFavorites](../../app/src/main/java/cc/novelia/app/data/network/CloudFavorites.kt)处理，不应只根据显示名称拼接口。

列表顶部使用两行工具区：第一行切换“网络小说 / 文库小说”，第二行提供收藏夹选择、排序和网络小说筛选入口。收藏夹菜单同时提供新建、重命名、删除和刷新；聚合收藏与默认收藏仍遵循原有管理限制。状态、分级、翻译、标题/作者搜索和来源多选集中在筛选面板，排序不再重复出现；仅在启用条件时显示筛选数量和摘要，重置筛选不改变收藏夹与排序。

筛选默认收起，切换页面或打开书籍后收起；再次展开时从顶部开始，已选条件保持不变。选项沿用即时筛选，搜索可由键盘、搜索图标或“完成”提交；“完成”同时关闭面板。“云端筛选自动收起”设置只控制滚动列表时的自动收起，不影响离开页面后的收起行为。大字体或较窄窗口允许工具栏换行，保留至少 48dp 的点击区域。

管理收藏时，界面结合服务端已知关系与本账号最新待同步意图生成状态。`PUT` 表示目标收藏夹，`DELETE` 表示用户已经请求取消；“待同步”不能展示为远端已经确认。实现见 [FavoritePresentation](../../app/src/main/java/cc/novelia/app/ui/shelf/FavoritePresentation.kt)。

同一书目的收藏、移动和删除共用资源顺序。前台操作覆盖较早的同资源待办，后台重放也必须遵守这一顺序。云端文件夹新建等接口不能仅凭“属于收藏功能”就推断支持离线排队，具体调用和失败策略见网络专题。

## 文库分卷

文库父项代表作品，子卷是 `local` 类型的已导入文档。挂载时要求父作品已在本地收藏中，子项必须是本地书目；父子身份不能合并成一个 `BookRef`。

分组展示遵循以下规则：

- 普通书架在文库父项下显示分卷；本地视图可以平铺本地文档。
- 子卷跟随有效父项的文件夹；解除挂载后继承相应文件夹归属。
- 搜索只命中分卷时保留父项上下文；命中父项时保留该组分卷。
- 手动顺序只在同一父项的完整兄弟集合内调整；没有手动顺序的部分按自然数字标题排序。
- 普通交互可拖拽，电子纸使用按钮调整；不要让手势改变另一文库下的归属。

下载后首次导入可挂载到仍存在的文库父项；再次导入命中已有文档时保留用户后来做出的移出或换组选择。跨卷继续阅读依据当前挂载顺序，由阅读器在合适时机提示。

## 阅读进度和更新时间

[BookListPresentation](../../app/src/main/java/cc/novelia/app/ui/components/BookListPresentation.kt)集中计算列表状态。本机百分比按章节等权，再用当前章段落位置估计章内进度；这不是按全书字数计算的精确完成率。旧位置缺少章节序号时显示“继续阅读”，不编造百分比。

云端记录通常只知道读到的章节。列表显示“读到第 N 章”或“有阅读记录”，解析尚未完成时可以显示“云端进度待同步”。书目被标记“读完”以及云端优先显示场景有专门规则；展示结果不能回写成阅读器的精确锚点。

云端时间以秒表示，本机 `Position.updatedAt` 以毫秒表示，比较前必须换算。[CloudBookMetadataLoader](../../app/src/main/java/cc/novelia/app/data/library/CloudBookMetadata.kt)以有限并发补充可见条目章节信息，响应应用前复核会话；不会因此把云端独有收藏自动加入本地书架。

## 历史与更新检查

历史页面分本机与云端。本机历史使用精确位置，云端历史读取账号列表。暂停历史会影响后续位置记录；清空某一侧历史不代表另一侧也已清空。详情页发现本机和云端章节不同，会让用户选择这次从哪里继续，见[书籍详情](book-details.md)。

后台检查和完整详情刷新共用同一更新基线，比较章节数、各引擎译文计数与文库分卷 ID。收藏或移动收藏夹时保留旧基线；缺少旧计数时先建立基线，数量下降不视为新增，相同内容重复获取不重复计数，迟到的旧响应不能回退基线。尚未到达的新增章节会累计，随阅读逐章确认；最新章已打开时书架进度为 100% 并消除本轮章节提示。译文变化单独显示“译文更新”；手动标记更新已读仍保留译文缓存刷新时间。通知是否相关还会考虑阅读模式及优先引擎。

定时任务受系统调度约束，手动与定时检查共用锁。每本书处理后保存游标，避免长书架任务被中断时总是只检查前部；实现与增量规则见 [BookUpdates](../../app/src/main/java/cc/novelia/app/data/updates/BookUpdates.kt)。

## 开发与验收

先定位是在改变收藏关系、列表展示、分卷关系，还是持久资料；这些改动通常使用不同的纯函数和测试。至少核对重复收藏、切换账号、离线移动后删除、撤销期间另一次修改、缺失云端章节、旧进度缺字段、分卷父项消失和更新基线缺失。

| 验证范围 | 测试入口 |
| --- | --- |
| 收藏展示与账号待办 | [FavoritePresentationTest](../../app/src/test/java/cc/novelia/app/FavoritePresentationTest.kt)、[CloudFavoritesTest](../../app/src/test/java/cc/novelia/app/CloudFavoritesTest.kt) |
| 进度与元数据兼容 | [BookListPresentationTest](../../app/src/test/java/cc/novelia/app/BookListPresentationTest.kt)、[BookMetadataTest](../../app/src/test/java/cc/novelia/app/BookMetadataTest.kt)、[CloudBookMetadataTest](../../app/src/test/java/cc/novelia/app/CloudBookMetadataTest.kt) |
| 分卷、继续阅读与更新 | [WenkuVolumesTest](../../app/src/test/java/cc/novelia/app/WenkuVolumesTest.kt)、[ReadingContinuityTest](../../app/src/test/java/cc/novelia/app/ReadingContinuityTest.kt)、[BookUpdatesTest](../../app/src/test/java/cc/novelia/app/BookUpdatesTest.kt) |
| 界面布局和操作 | [书架设备测试目录](../../app/src/androidTest/java/cc/novelia/app/ui/shelf) |
