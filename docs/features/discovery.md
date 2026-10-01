# 发现与搜索

[文档总目录](../README.md) · [功能索引](README.md)

发现页面负责挑选远端作品，包括推荐、网络小说、文库、条件筛选、辅助查询和排行榜。本专题描述客户端构造请求与展示结果的方式，不把本地辅助功能描述为服务端全文检索能力。

## 入口与状态

主要入口为 [DiscoverScreen](../../app/src/main/java/cc/novelia/app/ui/discover/DiscoverScreen.kt)、[RankScreen](../../app/src/main/java/cc/novelia/app/ui/discover/RankScreen.kt) 和 [SearchAssistantPanel](../../app/src/main/java/cc/novelia/app/ui/discover/SearchAssistantPanel.kt)。书源标识统一来自 [Providers](../../app/src/main/java/cc/novelia/app/data/catalog/Providers.kt)。

| 状态 | 用途 | 修改时的约束 |
| --- | --- | --- |
| `query` | 输入框草稿 | 输入并不等于已向服务端提交 |
| `submitted` | 当前结果对应的查询 | 搜索提交后更新并回到第一页 |
| `category/page` | 当前类别及分页 | 切类别或改变筛选时重置页码 |
| `source/type/translate/sort` | 网络小说条件 | 传递原站约定的标识和值，不发送本地中文标签 |
| `webLevel/wenkuLevel` | 分类及分级 | 有效值依账号能力限制，服务端仍做最终校验 |
| 屏蔽集合 | 本地可见性过滤 | 不修改服务端分页总量和远端数据 |

“为你发现”读取网络热门与文库列表并做有限展示，不是客户端训练的个性化推荐模型。发现和榜单以挑选作品为主，不应把书架专属的阅读进度展示机械复制到所有列表。

## 网络小说默认封面

列表、详情与书架共用分类配色，来自接口的 `type` 与 `attentions`，随 `BookCard.novelType/attentions` 一起保存。颜色不再按书籍 ID 选取；贴纸图案仍保持每本书稳定。

| 分级 | 连载中 | 已完结 | 短篇 |
| --- | --- | --- | --- |
| 一般向 | 绿色 | 紫色 | 蓝色 |
| R18 | 粉色 | 桃红色 | 橙色 |

R18 与原站列表筛选保持一致：内容警告中包含 `R18` 或 `性描写` 均使用 R18 配色。Kakuyomu、Novelup 等书源可能只有 `性描写`，不额外附带 `R18`；`R15`、暴力和残酷描写本身不归入 R18，不从标题或自由关键词推断。未知类型或旧书目缺少分级信息时使用中性色；读取详情后可补齐本地已有书目的分类，不额外批量联网。文库和本地书沿用既有封面规则，电子纸模式仍使用单色高对比显示。

## 搜索链路

```mermaid
flowchart LR
    Input[输入文本或链接] --> Submit[提交并记录最近搜索]
    Submit --> Recognize{可识别书源链接}
    Recognize -->|是| Navigate[打开书籍或章节]
    Recognize -->|否| Query[更新查询并重置页码]
    Query --> API[原站分页接口]
    API --> Filter[本地屏蔽过滤]
    Filter --> List[结果列表与分页控件]
```

`BookLinks.parse`从分享文本中提取首个 HTTP(S) 链接，只接受已知主机、路径和合法 ID；未知链接由上层按查询文本处理。新增书源还需同步链接映射、反向来源链接、列表筛选和测试，不能只向名称表增加一项就认为服务器已支持。

列表请求的 `AsyncContent` 身份包含提交查询、页码、有效筛选和账号能力。切换条件后旧结果不能冒充新条件的结果；刷新同一身份则保留已显示内容和位置，相关组件规则见[界面与导航](../architecture/ui-and-navigation.md)。

## 辅助查询与手写语法

[SearchExpression](../../app/src/main/java/cc/novelia/app/data/catalog/SearchExpression.kt)把结构化输入转成站点表达式，负责转义、组合和标签冲突识别。

辅助搜索关闭后保留尚未应用的表单条件，再打开时滚动到面板顶部；表单草稿与面板浏览位置分别管理，不能随草稿恢复上次的滚动位置。

| 输入项 | 客户端生成方式 |
| --- | --- |
| 全部关键词 | 普通词按空格连接，并转义特殊运算符 |
| 任一关键词 | 括号内用 `\|` 连接 |
| 完整短语 | 使用双引号，处理原站预处理敏感字符 |
| 排除词 | 普通词前加 `-` |
| 包含/排除标签 | 使用 `标签$` / `-标签$`，只接受可搜索标签 |
| 数量条件 | 合法非负整数转为 `>数值` / `<数值` |

例如辅助输入包含两个普通词和一个标签，可生成 `魔法 学院 异世界$`；这个示例只说明客户端表达式结构，是否命中取决于服务端数据及解析规则。不要把比较前缀擅自改写成含边界的范围含义。

原站在查询解析前还有按空格处理的步骤，因此尾随美元符号、反斜杠和短语空格都属于兼容边界。追加辅助条件时保留原有手写表达式，只避免重复的同向标签；不要用简单拆词重排整个用户查询。

## 标签与搜索记录

[KeywordStore](../../app/src/main/java/cc/novelia/app/data/catalog/KeywordStore.kt)从用户已经访问的内容观察标签，并保存本地译名和使用记录，不会另行下载全站标签库。目录合并、可搜索性和排序规则在 [KeywordCatalog](../../app/src/main/java/cc/novelia/app/data/catalog/KeywordCatalog.kt)。

辅助搜索保留最多 12 个快捷候选，通过“浏览全部标签”进入 [KeywordLibraryScreen](../../app/src/main/java/cc/novelia/app/ui/discover/KeywordLibraryScreen.kt) 的完整标签库。标签按实际文字宽度自动换行，后台计算行布局并只组合可见行，不截断为候选数量；分类使用可左右滑动的胶囊选项。支持原文、中文译名、别名搜索，选择包含/排除后返回辅助搜索，仍由读者统一应用条件。包含使用选中色，排除使用红色与删除线，两者都保留无障碍状态描述和至少 48dp 触控区域。设置页也提供不携带搜索条件的管理入口。

分类最初在本地内置为题材、人物、情节、其他。读者可以新建、重命名、删除分类和调整标签归属；“全部”是虚拟筛选项，“其他”是保留的默认分类。删除分类会将其标签移到“其他”，不会删除标签。分类最多 100 个，名称最多 40 字符。`KeywordLibrary` 将分类（含空分类）与标签存入同一个原子快照，避免重启时丢失空分类或复活已删除分类。

本地标签翻译与分类是辅助理解与筛选的资料，不会修改原站作品标签。词典保存失败应继续向用户展示错误，不能仅因内存中已出现译名就宣称保存成功。词典及分类可单独通过标签库 JSON 或随阅读资料 ZIP 迁移，普通设置备份的范围见[设置专题](settings-and-notes.md)。

最近搜索和保存搜索是不同集合：最近搜索有容量限制且按使用更新。保存搜索是可命名的完整组合，包含关键词表达式、网络/文库分类、来源、完结状态、翻译条件、排序和分级；没有关键词时也可保存筛选组合。从“保存的搜索”点击名称即可应用整套条件并回到第一页，支持重命名和删除。应用时仍按当前账号权限限制可用分级。

旧版仅含表达式的保存搜索会自动转换为同名组合，其余条件采用默认值。完整组合存于 `LibraryState.savedSearchPresets`，随阅读资料备份导出，合并时按稳定 ID 去重并保留本机同 ID 的名称和条件。普通设置备份仍只包含偏好设置，不包含搜索记录。删除搜索记录不改变书籍、屏蔽或标签翻译。

## 过滤、空结果和界面适配

[BookVisibility](../../app/src/main/java/cc/novelia/app/ui/discover/BookVisibility.kt)按书籍键、标签和作者集合隐藏作品，应用于发现与排行榜。作者名按去掉作品作者名首尾空白后的完整名称匹配，忽略大小写；多作者作品任意一位命中即隐藏，不做子串匹配。屏蔽管理页可手动添加/取消，网络小说与文库详情也提供作者屏蔽入口；它不会修改远端作品、删除本地书目或建立服务端屏蔽关系。

仅在作者屏蔽集合非空且列表摘要缺少作者时，`enrichAuthors` 才通过详情接口补齐作者，最多四个请求并发，复用现有详情缓存。补查失败保留原条目，协程取消继续传播；因此离线且缺少作者资料时，不能保证该条目已按作者过滤。当前页远端有结果、但全部被本地屏蔽后，仍可能出现空列表；不要把它误报为原站无内容。页面应保留放宽筛选、清空查询、重试或检查屏蔽条件的可达入口。

网络小说辅助面板与云端收藏使用共同的自动收起偏好。收起只是显示状态，不能清除已提交条件；关闭自动收起后面板应可保持展开。电子纸的按屏翻动和普通拖动均需检查摘要、展开按钮及列表位置。

## 开发与验证

修改搜索先明确属于表达式生成、API 参数、客户端屏蔽还是输入状态，分别定位测试。应覆盖特殊字符、标签重复与冲突、非法数字、链接中的编码、切账号后有效分级变化、过滤后空页及筛选变化后的页码重置。

| 范围 | 入口 |
| --- | --- |
| 表达式及链接边界 | [SearchExpressionBoundaryTest](../../app/src/test/java/cc/novelia/app/SearchExpressionBoundaryTest.kt)、[ReaderAndLinksTest](../../app/src/test/java/cc/novelia/app/ReaderAndLinksTest.kt) |
| 标签观察与词典持久化 | [KeywordCatalogTest](../../app/src/test/java/cc/novelia/app/KeywordCatalogTest.kt)、[KeywordObservationTest](../../app/src/test/java/cc/novelia/app/KeywordObservationTest.kt) |
| 请求契约 | [ApiContractTest](../../app/src/test/java/cc/novelia/app/ApiContractTest.kt) |
| 作者屏蔽及设置兼容 | [BookVisibilityTest](../../app/src/test/java/cc/novelia/app/BookVisibilityTest.kt) |
| 辅助面板及筛选位置 | [发现设备测试目录](../../app/src/androidTest/java/cc/novelia/app/ui/discover) |
