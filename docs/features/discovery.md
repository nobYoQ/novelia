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

最近搜索和保存搜索是不同集合：最近搜索有容量限制且按使用更新，保存搜索保留用户主动保存的表达式；删除搜索记录不改变书籍、屏蔽或标签翻译。

## 过滤、空结果和界面适配

[BookVisibility](../../app/src/main/java/cc/novelia/app/ui/discover/BookVisibility.kt)按书籍键和标签集合隐藏作品。当前页远端有结果、但全部被本地屏蔽后，仍可能出现空列表；不要把它误报为原站无内容。页面应保留放宽筛选、清空查询、重试或检查屏蔽条件的可达入口。

网络小说辅助面板与云端收藏使用共同的自动收起偏好。收起只是显示状态，不能清除已提交条件；关闭自动收起后面板应可保持展开。电子纸的按屏翻动和普通拖动均需检查摘要、展开按钮及列表位置。

## 开发与验证

修改搜索先明确属于表达式生成、API 参数、客户端屏蔽还是输入状态，分别定位测试。应覆盖特殊字符、标签重复与冲突、非法数字、链接中的编码、切账号后有效分级变化、过滤后空页及筛选变化后的页码重置。

| 范围 | 入口 |
| --- | --- |
| 表达式及链接边界 | [SearchExpressionBoundaryTest](../../app/src/test/java/cc/novelia/app/SearchExpressionBoundaryTest.kt)、[ReaderAndLinksTest](../../app/src/test/java/cc/novelia/app/ReaderAndLinksTest.kt) |
| 标签观察与词典持久化 | [KeywordCatalogTest](../../app/src/test/java/cc/novelia/app/KeywordCatalogTest.kt)、[KeywordObservationTest](../../app/src/test/java/cc/novelia/app/KeywordObservationTest.kt) |
| 请求契约 | [ApiContractTest](../../app/src/test/java/cc/novelia/app/ApiContractTest.kt) |
| 辅助面板及筛选位置 | [发现设备测试目录](../../app/src/androidTest/java/cc/novelia/app/ui/discover) |
