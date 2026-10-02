# 原站与反代镜像

[返回开发入门](README.md) · [账号会话](../network/authentication.md)

设置中的「下载与同步 → 书源线路」可选择原站和 XKVI 反代镜像。默认原站，选择在本设备保存；首次使用另一条线路时进入登录页。两条线路对应同一套小说和账号数据，客户端分别保存它们的登录会话。本地小说、书架、正文缓存和阅读进度不因线路切换而删除。

| 线路 | 内容接口 | 认证接口 |
| --- | --- | --- |
| 原站 | `https://n.novelia.cc/api/` | `https://auth.novelia.cc/api/v1/auth/` |
| XKVI | `https://book.xkvi.top/api/` | `https://book.xkvi.top/api/v1/auth/` |

## 打包配置

镜像额外要求 `Cookie: accessToken=…`。这是镜像入口口令，与账号登录后返回的 JWT、认证刷新 Cookie 不同。应用自动携带入口口令，普通用户不需要填写。

打包时从环境变量 `NOVELIA_MIRROR_ACCESS_TOKEN` 读取入口口令；没有该环境变量时读取根目录 `.env.mirror` 中的同名属性。此文件已被 `.gitignore` 的 `.env.*` 规则排除。文件格式为 `NOVELIA_MIRROR_ACCESS_TOKEN=<站长提供的入口口令>`，不要把真实值加入示例、测试夹具、日志或 Git。没有配置的安装包保留原站功能，在线路选择中禁用镜像并说明原因。

Debug、本地 Release 和正式发行均使用相同注入逻辑。入口口令会包含在 APK 中；它是供客户端使用的网关凭据，不能当作只有服务器知道的保密密钥。站长更换口令后，打包者需要更新本机/CI 配置并重新构建。

## 请求与登录

`BookSources` 保存来源及变更代次；`BookSourceInterceptor` 在 ECH 之前改写目标地址，路径、重复查询参数和百分号编码保持不变。小说、认证、封面、上传、下载和后台同步复用此网络层。外部图片/CDN 地址保持原样；仅镜像标准 HTTPS 来源附带入口 Cookie。下载、图片的重定向由统一入口跟随，跨来源剥离 Cookie、Authorization 和 Proxy-Authorization。

原站仍使用 WebView 认证。镜像使用原生登录/注册表单，字段与原站公开前端一致：

| POST 路径 | JSON 字段 |
| --- | --- |
| `login` | `app: "n"`、`username`、`password` |
| `register` | 上述字段加 `email`、`otp` |
| `otp/request` | `email`、`type: "verify"` |
| `refresh?app=n` | 空请求体，携带该来源认证 Cookie；响应为 JWT 文本 |

登录/注册成功后接收认证 Cookie；响应直接包含符合原站字段的 JWT 时立即保存，否则携带 Cookie 请求刷新获取 JWT。入口口令不会被当作账号令牌。镜像 JWT 与刷新 Cookie 分别在 `session-xkvi` 中用 Android Keystore 的 AES-GCM 加密，原站沿用 `session`。密码和验证码只保留于当前表单内存，不进入恢复状态、备份和日志。服务器业务错误只显示固定提示，不回显原始响应内容。

实测镜像会将部分原站 4xx 包装为 502。对于明确带 `WWW-Authenticate: Bearer …` 的镜像 502，网络层恢复为 401，使正常的单次续期规则仍可执行；其他 502 不推测为鉴权错误，不因此重放写操作。空表单的字段校验也可能返回 502，不能单凭该状态判断认证服务不可用。

切换线路使旧请求绑定失效，延迟返回的响应和刷新不能写入新来源的状态。页面重建、详情及字数元数据缓存按来源区分；原站/镜像共享的正文缓存及本地资料保留。因为是同一服务端，云端待办仍以账号归属，重新登录同一账号后可继续同步。

镜像不使用原站的 ECH 引擎；网络诊断中的直连/ECH 对照仍针对原站。站内补充网页的 GET 请求复用镜像网络层，网页写操作使用原生页面；入口 Cookie 不放进 WebView Cookie 库。镜像是否代理 HTML 页面由站长决定，API 可用不代表首页可用。

## 回归

JVM：`BookSourceTest` 覆盖来源映射、失效绑定、Cookie 范围、流式读取及跨域重定向；`MirrorAuthCookiesTest` 覆盖域和路径匹配、刷新轮换与删除。

设备：`MirrorSessionTest` 用虚构账号和本地响应验证登录、注册、验证码、401 续期、加密存储兼容和会话隔离；`BookSourcePickerTest` 和 `BookSourceNavigationTest` 覆盖设置切换、无需手工输入入口口令、进入登录及 Activity 重建。真实账号登录与邮件发送不属于这些自动测试。
