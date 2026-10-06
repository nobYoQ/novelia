# 独立论坛接口

[网络目录](README.md) · [社区功能](../features/community.md) · [文档首页](../README.md)

当前社区入口使用独立论坛 `forum.novelia.cc`；网络小说和文库小说的评论也已迁至论坛的外部资源接口，旧站文章仍使用小说服务。本文描述当前客户端合约。文件名保留早期的 `preview` 以兼容旧链接，分支适配过程和当时的测试结果已移至[历史记录](../maintenance/history/forum-adaptation.md)。

核对依据为仓库中的 [ForumApi](../../app/src/main/java/cc/novelia/app/data/network/ForumApi.kt)、[NovelCommentApi](../../app/src/main/java/cc/novelia/app/data/network/NovelCommentApi.kt)、[ForumAccountApi](../../app/src/main/java/cc/novelia/app/data/network/ForumAccountApi.kt)、[模型](../../app/src/main/java/cc/novelia/app/data/model/ForumModels.kt) 和合约测试。2026-10-06 已只读核对线上教程、网络小说评论及回复、文库评论分页。

## 基础约定

API 根路径为 `https://forum.novelia.cc/api/v1/`。客户端页码从 0 开始，发请求时转换为从 1 开始的 `page`；页大小字段为 `page_size`，分页响应为 `{ total, items }`。

帖子和评论使用数字 ID，时间为 RFC 3339 字符串。计数和记录 ID 使用 64 位整数；不要经浮点数转换。帖子、帖子评论与论坛账号操作使用 `app=f` 的会话，不接受主站令牌；外部小说评论使用 `app=n` 的小说会话。论坛 API 随书源线路切换，XKVI 的根路径为 `https://book.xkvi.top/api/v1/`；原站和镜像会话分别保存，切换使旧绑定失效。具体路径分流见[书源线路](../development/book-source-mirrors.md)。

## 主要接口

| 操作 | 方法与路径 | 请求要点 |
| --- | --- | --- |
| 分类 | `GET category/` | 读取当前 ID、slug、标签 |
| 帖子列表 | `GET post/` | 分类传 slug，搜索 `q`，排序 `active/newest/views/comments`，`tag` 为逗号分隔 ID |
| 帖子详情 | `GET post/{id}/` | 返回完整帖子 |
| 发布 / 修改 | `POST post/`、`PATCH post/{id}/` | `categoryId, title, content, tagIds` |
| 删除帖子 | `DELETE post/{id}/` | 接受无正文的成功响应 |
| 一级评论 | `GET post/{id}/comment` | total 只统计一级评论 |
| 回复页 | `GET post/{id}/comment/{rootId}/reply` | 独立分页 |
| 新评论 / 回复 | `POST post/{id}/comment` | 回复携带根评论 rootId |
| 修改 / 删除评论 | `PATCH/DELETE comment/{id}` | PATCH 只发 content，不发 rootId，连 null 也不发 |
| 收藏 | `PUT/DELETE post/{id}/favorite` | 直接请求，不进入原站离线队列 |
| 我的收藏 / 帖子 | `GET me/favorite`、`GET me/post` | 论坛认证分页 |

## 小说评论

[NovelCommentApi](../../app/src/main/java/cc/novelia/app/data/network/NovelCommentApi.kt) 使用相同的 API 根路径，但携带小说账号 `app=n` 的令牌；帖子评论继续使用论坛账号 `app=f`。两者的路径和登录入口分别绑定，共用分页、回复缓存和 Markdown 展示。

| 操作 | 方法与路径 | 请求要点 |
| --- | --- | --- |
| 一级评论 | `GET external/comment/novel/{subjectKey}` | 网络小说为 `web-{provider}-{id}`，文库为 `wenku-{id}`；整个 subjectKey 作为单个路径段编码 |
| 回复页 | `GET external/comment/novel/{subjectKey}/{rootId}/reply` | 一级评论与回复独立分页，page 从 1 开始，page_size 为 20 |
| 新评论 / 回复 | `POST external/comment/novel/{subjectKey}` | content，回复携带根评论 rootId |
| 修改 / 删除 | `PATCH/DELETE external/comment/novel/{commentId}` | PATCH 只发送 content |

一级响应可带 replyCount 和 replies 首屏，优先复用。隐藏小说评论和屏蔽用户同时作用于一级评论与回复；草稿按小说资源、账号和回复目标隔离。旧站文章的 `CommentsPanel` 保留原接口。帮助与关于、搜索语法入口均打开新论坛教程 `f-1`。

## 分类、权限和草稿

分类数字 ID 可被服务端重新分配。**权限按 slug 判断，提交使用本次分类列表返回的 ID。** 不要硬编码“某个数字就是公告”。

论坛允许的标题为 2–100 个 Unicode 码点，正文 1–20,000，评论 1–1,000，标签最多 3 个。规则集中在 [ForumRules.kt](../../app/src/main/java/cc/novelia/app/data/model/ForumRules.kt)，不能用 UTF-16 长度替代码点数。

`member`、`trusted`、`admin` 可发言，公告限管理员。普通作者删除帖子、修改或删除评论有发布后 20 分钟限制，管理员不受此时限影响；最终权限仍由服务端校验。

新草稿保存分类 slug，恢复时重新解析当前 ID、过滤不适用标签。仅有数字 ID 的旧草稿保留正文，但要求用户重新选择分类，避免 ID 重用后发错版块。

## 回复计数与首屏缓存

一级评论可携带 `replyCount` 和 `replies: { total, items }`。有效计数包括零，直接使用；只有旧响应缺失或 null 时才用一项回复查询补计数，失败保持未知。

[ForumReplyPageCache](../../app/src/main/java/cc/novelia/app/data/network/ForumReplyPageCache.kt) 校验附带首屏的完整性、根评论、资源及重复 ID，通过后放入列表级缓存。回复默认折叠，展开命中时无需重复请求；异常或旧响应回退独立接口。

每串保留最近三页。同一页并发等待共享请求，滚出屏幕不丢已读正文。刷新、增删改、帖子或论坛身份变化时重建缓存；离开列表后取消请求，回复正文不落盘。

## 处罚未读与社区守则

处罚由认证服务 `https://auth.novelia.cc/api/v1/` 提供，携带所选线路的论坛令牌。这些路径未列入镜像路由，保留认证站地址：

| 接口 | 含义 |
| --- | --- |
| `GET me/attention-status` | 读取 `strikes.hasUnread` |
| `GET me/strikes` | 分页记录与 `latestStrikeId` |
| `PUT me/strikes/read-state` | 用已展示快照 ID 发送 `{ throughId }`，返回 hasUnread |

缺少快照 ID 时不确认已读；确认失败可重试，后来新增的记录不能被旧确认吞掉。读取与确认都绑定原论坛会话，处罚内容和提示状态不持久缓存。

守则没有独立数据 API。[ForumCommunityRulesRepository](../../app/src/main/java/cc/novelia/app/data/network/ForumCommunityRulesRepository.kt) 从线上入口取得部署 SHA，再读取该提交的公开守则源码；解析已知静态结构，不执行脚本。未知结构或网络失败保留缓存，首次离线使用内置副本。当前内置材料见 [ForumCommunityRules.kt](../../app/src/main/java/cc/novelia/app/data/model/ForumCommunityRules.kt)。

## 错误与验证

400/403/404/409 的短纯文本业务原因经校验后展示；HTML、超长内容和内部错误采用通用提示。写操作失败保留草稿，不因模糊失败自动重复发帖或评论。

合约与缓存回归位于 [data/network 测试目录](../../app/src/test/java/cc/novelia/app/data/network)：`ForumApiContractTest`、`ForumAccountApiTest`、`ForumReplyCountsTest`、`ForumReplyPageCacheTest`、`ForumCommunityRulesTest`。页面回归在 [ui/community](../../app/src/androidTest/java/cc/novelia/app/ui/community)，另有守则解析器的 Android 测试。真实站点只读联调及其开关见[测试指南](../quality/testing.md)。
