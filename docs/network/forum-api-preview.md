# 独立论坛 API 预适配

此分支 `codex/forum-api-preview` 的社区入口连接 [测试论坛](https://forum.novelia.cc/c/novel)，主站小说、阅读、下载与小说评论继续连接 `n.novelia.cc`。

## 核对依据

- 核对日期：2026-09-15。
- 测试站公开前端：`/assets/index-DIxwJxpv.js`，内嵌部署提交 `3231a8ee74de09196d625137a3b2b637367d76db`。
- [该部署版本的前端 API 合约](https://github.com/auto-novel/forum/blob/3231a8ee74de09196d625137a3b2b637367d76db/apps/web/src/api.ts)。
- [服务端帖子路由](https://github.com/auto-novel/forum/blob/3231a8ee74de09196d625137a3b2b637367d76db/apps/api/internal/handler/post.go)、[评论路由](https://github.com/auto-novel/forum/blob/3231a8ee74de09196d625137a3b2b637367d76db/apps/api/internal/handler/comment.go)、[认证声明](https://github.com/auto-novel/forum/blob/3231a8ee74de09196d625137a3b2b637367d76db/apps/api/internal/httpx/authn.go)。

## 合约变化

| 功能 | 新版合约 |
| --- | --- |
| API 前缀 | `https://forum.novelia.cc/api/v1/` |
| 分类 | `GET category/`，返回数字 `id`、`slug` 和标签 |
| 列表 | `GET post/`；`category` 传 slug；搜索用 `q`；排序用 `active/newest/views/comments`；标签为逗号分隔 `tag` |
| 分页 | 请求 `page` 从 1 开始，`page_size` 取代 `pageSize`；响应 `{ total, items }`。客户端页码仍从 0 开始，在 API 层换算 |
| 帖子详情 | `GET post/{数字ID}/`；时间为 RFC 3339 字符串；作者改为 `authorId/authorUsername` |
| 发布／编辑 | `POST post/`、`PATCH post/{id}/`；正文为 `{ categoryId, title, content, tagIds }`，返回完整帖子对象 |
| 删除帖子 | `DELETE post/{id}/`，成功可返回无正文的 204 |
| 评论 | `GET/POST post/{id}/comment`；返回平铺分页列表；回复提交 `rootId`，回复子评论时仍使用其根 ID |
| 修改评论 | `PATCH/DELETE comment/{id}`；编辑只提交 `content`；服务端限制发布后 20 分钟内修改 |
| 云端收藏 | `PUT/DELETE post/{id}/favorite`；`GET me/favorite`、`GET me/post` |
| 登录 | 统一认证应用标识 `f`，Origin 为 `https://forum.novelia.cc`；刷新路径 `/api/v1/auth/refresh?app=f` |
| 网页链接 | `https://forum.novelia.cc/p/{id}` |

新论坛与主站分别保存加密令牌并使用不同的 Keystore 别名。论坛请求拒绝主站会话；401 最多刷新一次。社区右上角的账号入口用于论坛登录和退出，统一退出会同时清除本机两个会话。

## 客户端行为

- 分类及标签使用服务器数据；列表搜索请求服务端。
- 新帖 ID 在本地表示为 `f-{id}`，本地收藏及草稿与旧站 ObjectID 区分。旧帖子链接和收藏继续从主站读取；不猜测新旧帖子 ID 对应关系。
- 新建论坛草稿使用 `article:forum-new`；旧版 `article:new` 草稿仍保留，不自动发布到测试站。
- 新版编辑保留帖子的标签，切换分类时清除旧分类标签。标题上限为 500，帖子正文上限为 1,000,000，评论上限为 100,000；最终权限及校验由服务端决定。
- 评论按服务端分页平铺显示，回复显示根评论 ID；根评论可能位于其他页。隐藏／删除评论显示占位文本。
- 保留本地文章收藏，并接入论坛云端收藏和「我的帖子」。
- 新域名帖子链接可进入原生页面；带锚点、分类筛选或编辑路径的链接在站内 WebView 中保留完整 URL。
- 服务端还提供 `external/comment/novel/{subjectKey}`。已提供显式 subject key 的只读接口，但测试站尚未给出主站迁移映射，因此当前小说评论保持原接口。

## 验证

- 已对测试站三个分类的列表、帖子详情和评论执行公开 GET，核对字段与部署源码一致；不会在联调时创建帖子、评论或修改收藏。
- `ForumApiContractTest` 使用 MockWebServer 覆盖分页／搜索、数字 ID、时区与小数秒、创建／PATCH 请求体、标签、平铺回复、204、云端列表、会话隔离、401 刷新上限、503 响应不重试、旧收藏及新旧链接。
- 最终验证：72 项 JVM 测试全部通过（其中新版论坛 9 项）；Debug APK、Android 测试 APK 构建成功；`lintDebug` 通过，0 个错误、22 个警告。
- 构建与离线验证：`./build.ps1 -Tasks @('assembleDebug', 'testDebugUnitTest', 'lintDebug') -Offline`。
- 可选 Android 只读联调：安装测试 APK 后使用 `adb shell am instrument -w -r -e live true -e class cc.novelia.app.integration.ForumLiveReadOnlyTest cc.novelia.app.test/androidx.test.runner.AndroidJUnitRunner`。

测试站仍在变化。当前没有连接 Android 设备，因此设备端只读测试、真实登录及账号写入尚未进行端到端验收；写入合约已用本地模拟服务验证。上线前应重新核对部署版本，并用测试账号验收发帖、编辑、回复、收藏和退出登录。
