# 原站与反代镜像

[返回开发入门](README.md) · [账号会话](../network/authentication.md)

设置中的「下载与同步 → 书源线路」可选择原站和 XKVI 反代镜像，小说和新论坛 API 都使用所选线路。默认原站，选择在本设备保存；首次使用另一条线路时进入登录页。两条线路对应同一套小说、论坛和账号数据，客户端分别保存各线路的小说与论坛登录会话。本地小说、书架、正文缓存和阅读进度不因线路切换而删除。

| 线路 | 小说接口 | 论坛接口 | 认证接口 |
| --- | --- | --- | --- |
| 原站 | `https://n.novelia.cc/api/` | `https://forum.novelia.cc/api/v1/` | `https://auth.novelia.cc/api/v1/auth/` |
| XKVI | `https://book.xkvi.top/api/` | `https://book.xkvi.top/api/v1/` | `https://book.xkvi.top/api/v1/auth/` |

## 镜像路径分流

以下按站长提供的新路由表核对。切回原站时也按这些路径恢复上游；匹配完整路径段及其子路径，保留末尾斜杠和查询编码。

| 镜像路径 | 上游 | 用途 |
| --- | --- | --- |
| `/api/v1/auth/**` | `auth.novelia.cc` | 统一认证，仅 POST，免入口门禁 |
| `/api/v1/category` | `forum.novelia.cc` | 论坛分区与标签 |
| `/api/v1/post` | `forum.novelia.cc` | 帖子列表、详情、发帖、收藏、帖子评论 |
| `/api/v1/comment` | `forum.novelia.cc` | 评论编辑与删除 |
| `/api/v1/external/comment` | `forum.novelia.cc` | 内嵌评论 API |
| `/api/v1/me/post` | `forum.novelia.cc` | 我的帖子 |
| `/api/v1/me/favorite` | `forum.novelia.cc` | 我的收藏 |
| `/api/v1/me/strikes` 及其子路径 | `auth.novelia.cc` | 处罚记录、已读确认 |
| `/api/v1/me/attention-status` | `auth.novelia.cc` | 未读提醒 |
| 其余 `/api/**` | `n.novelia.cc` | 书站后端 |
| `/files-temp` 及其子路径 | `n.novelia.cc` | TXT / EPUB 下载静态文件 |

2026-10-06 路由表新增的处罚记录、处罚未读及已读确认也随所选线路切换，携带独立论坛 Bearer 令牌。镜像上的这些 GET / PUT 请求需要入口 Cookie；免门禁规则仅适用于 `POST /api/v1/auth/**`。`/api/v1/admin/**` 不属于客户端功能，本轮不适配。论坛 HTML、守则部署信息和诊断探针保留原站地址；表中 API 支持不代表镜像支持论坛网页。

## 打包配置

镜像内容 API 及文件请求额外要求 `Cookie: accessToken=…`。这是镜像入口口令，与账号登录后返回的 JWT、认证刷新 Cookie 不同。应用自动携带入口口令，普通用户不需要填写。`POST /api/v1/auth/**` 免入口门禁，不附加入口口令，仍携带该会话自己的刷新 Cookie。

打包时从环境变量 `NOVELIA_MIRROR_ACCESS_TOKEN` 读取入口口令；没有该环境变量时读取根目录 `.env.mirror` 中的同名属性。此文件已被 `.gitignore` 的 `.env.*` 规则排除。文件格式为 `NOVELIA_MIRROR_ACCESS_TOKEN=<站长提供的入口口令>`，不要把真实值加入示例、测试夹具、日志或 Git。没有配置的安装包保留原站功能，在线路选择中禁用镜像并说明原因。

Debug、本地 Release 和正式发行均使用相同注入逻辑。入口口令会包含在 APK 中；它是供客户端使用的网关凭据，不能当作只有服务器知道的保密密钥。站长更换口令后，打包者需要更新本机/CI 配置并重新构建。

## 请求与登录

`BookSources` 保存来源及变更代次；`BookSourceInterceptor` 在 ECH 之前改写目标地址，路径、重复查询参数和百分号编码保持不变。小说、论坛 API、认证、封面、上传、下载和后台同步复用此网络层。外部图片/CDN 地址保持原样；入口 Cookie 只附在镜像标准 HTTPS 来源，认证 POST 除外。下载、图片的重定向由统一入口跟随，跨来源剥离 Cookie、Authorization 和 Proxy-Authorization。

原站仍使用 WebView 认证。镜像使用原生登录/注册表单，字段与原站公开前端一致：

| POST 路径 | JSON 字段 |
| --- | --- |
| `login` | `app: "n"`（小说）或 `"f"`（论坛）、`username`、`password` |
| `register` | 上述字段加 `email`、`otp` |
| `otp/request` | `email`、`type: "verify"` |
| `refresh?app=n` / `refresh?app=f` | 空请求体，携带该来源认证 Cookie；响应为 JWT 文本 |

登录/注册成功后接收认证 Cookie；响应直接包含符合原站字段的 JWT 时立即保存，否则携带 Cookie 请求刷新获取 JWT。入口口令不会被当作账号令牌。镜像小说 JWT 与刷新 Cookie 在 `session-xkvi` 中加密保存，镜像论坛使用独立密钥和 `forum-session-xkvi`；原站沿用 `session` / `forum-session`。同线路小说已登录时，论坛可复用其 SSO Cookie 换取 `app=f` 令牌，核对账号一致后独立保存 Cookie；退出小说不会破坏已有论坛续期。密码和验证码只保留于当前表单内存，不进入恢复状态、备份和日志。服务器业务错误只显示固定提示，不回显原始响应内容。

实测镜像会将部分原站 4xx 包装为 502。对于明确带 `WWW-Authenticate: Bearer …` 的镜像 502，网络层恢复为 401，使正常的单次续期规则仍可执行；其他 502 不推测为鉴权错误，不因此重放写操作。空表单的字段校验也可能返回 502，不能单凭该状态判断认证服务不可用。

切换线路使小说和论坛的旧请求绑定失效，延迟返回的响应和刷新不能写入新来源的状态。小说与论坛分别恢复所选线路的已保存会话，互不借用应用令牌。页面重建、详情及字数元数据缓存按来源区分；原站/镜像共享的正文缓存及本地资料保留。因为是同一服务端，云端待办仍以账号归属，重新登录同一账号后可继续同步。

镜像不使用原站的 ECH 引擎；网络诊断中的直连/ECH 对照仍针对原站。站内补充网页的 GET 请求复用镜像网络层，网页写操作使用原生页面；入口 Cookie 不放进 WebView Cookie 库。镜像是否代理 HTML 页面由站长决定，API 可用不代表首页可用。

## 回归

JVM：`BookSourceTest` 覆盖全部论坛路由、处罚与未读路由、书站兜底、下载、认证 POST 免门禁、来源映射、失效绑定、Cookie 范围、流式读取及跨域重定向；`ForumAccountApiTest` 覆盖镜像处罚列表、未读提醒及精确的 64 位已读快照请求；`MirrorAuthCookiesTest` 覆盖域和路径匹配、刷新轮换与删除。

设备：`MirrorSessionTest` 与 `ForumMirrorSessionTest` 用虚构账号和本地响应验证小说／论坛的登录、注册、验证码、401 续期、加密存储兼容、SSO 复用及来源和会话隔离；`ForumMirrorLoginTest` 覆盖论坛镜像原生登录入口及页面恢复，`BookSourcePickerTest` 和 `BookSourceNavigationTest` 覆盖设置切换、无需手工输入入口口令、进入登录及 Activity 重建。真实账号登录与邮件发送不属于这些自动测试。
