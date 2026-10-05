# 账号与会话

[网络目录](README.md) · [文档首页](../README.md)

应用分别保存小说原站、镜像和独立论坛的登录状态。它们可能使用同一套账号体系，但客户端令牌与请求归属不同。主要实现是 [Session.kt](../../app/src/main/java/cc/novelia/app/data/auth/Session.kt) 和 [LoginScreen.kt](../../app/src/main/java/cc/novelia/app/ui/account/LoginScreen.kt)。

## 三种登录入口

| 入口 | 方式 | 认证标识 |
| --- | --- | --- |
| 小说原站 | WebView 中的统一认证页 | `app=n`，Origin 为 `https://n.novelia.cc` |
| 独立论坛 | WebView 中的统一认证页 | `app=f`，Origin 为 `https://forum.novelia.cc` |
| XKVI 镜像 | 原生登录、注册和验证码表单 | 镜像同源认证 API，`app=n` |

原站与论坛的 WebView 包装页嵌入 `auth.novelia.cc`。网页发来 `login_success` 后，客户端检查消息来源，再携带认证 Cookie 请求刷新；**只有拿到有效访问令牌才完成登录**。网页消息本身不含令牌。

自动回调不可用时，“完成登录”按钮走同一刷新过程。镜像表单成功后可直接接收 JWT，或用镜像认证 Cookie 刷新，具体字段见[书源线路](../development/book-source-mirrors.md)。

## 凭据保存与退出

访问令牌经 Android Keystore 的 AES-GCM 密钥加密，保存在对应私有首选项。原站与镜像分开保存，论坛还使用独立密钥别名。镜像刷新 Cookie 同样加密；统一认证 Cookie 由 WebView CookieManager 管理，不能声称它们都使用相同存储方式。

**当前退出是本地退出。** `logout()` 清除选中来源的本地会话并使旧绑定失效，不调用全局 SSO 登出，也不删除原站/论坛共享的认证 Cookie。因此：

- 退出小说服务不会退出论坛，反之亦然。
- 退出镜像会清除它自己的令牌和已保存认证 Cookie。
- 已退出的会话不会因普通匿名请求的 401 自动重新登录。
- 用户主动打开登录页时，仍可能复用已有 SSO 状态。

书架、笔记、草稿、本地文件和章节缓存属于设备资料，退出后继续保留。阅读资料备份和普通设置均不携带凭据。

## 为什么不能在响应回来时读取“当前用户”

例如账号 A 发起收藏，网络等待期间用户切到 B。如果回调直接读取当前用户，A 的操作可能被显示或发送成 B 的操作。

[SessionBinding](../../app/src/main/java/cc/novelia/app/data/auth/SessionState.kt) 为请求保存：

| 字段 | 用途 |
| --- | --- |
| `account` | 发起账号 |
| `generation` | 登录代次；退出再登录同名账号也会变化 |
| `source` | 原站、镜像或论坛 |
| `sourceRevision` | 来源切换代次；切走再切回也使旧请求失效 |

取令牌、刷新和应用结果时都核对原绑定。普通 401 最多刷新一次，刷新锁合并并发续期；不能把原请求改绑到另一个账号后重发。论坛会话不订阅小说线路变化，切换小说镜像不会使论坛会话失效。

## WebView 与界面权限

登录 WebView 开启认证所需的 JavaScript、DOM storage 和第三方 Cookie，关闭文件/content 访问与混合内容。原生消息监听检查允许 origin、主框架和固定消息；不支持监听时使用手动完成入口。

主框架导航限制与子资源加载规则不同，不应把前者描述为所有流量的域名白名单。普通站内浏览 WebView 也不是登录 WebView，见[界面与导航](../architecture/ui-and-navigation.md)。

JWT 字段用于展示用户名、角色和有效期，客户端解析不等于验签授权。`canPost`、`canEdit` 或论坛角色判断只帮助显示入口，最终以服务端响应为准。

## 登录后继续

收藏的续接意图由 [LoginContinuation.kt](../../app/src/main/java/cc/novelia/app/ui/navigation/LoginContinuation.kt) 放入登录导航项的 `SavedStateHandle`，页面重建后仍可恢复。其他 `afterLogin` 回调只存在内存中，消费或取消后需要清理。

登录完成不等于收藏已提交；它只是返回原流程，后续仍要选择收藏夹和处理同步结果。

## 排查和回归

| 现象 | 先查什么 |
| --- | --- |
| 网页显示成功，App 未登录 | 消息回调和手动刷新结果 |
| 反复 401 | 原绑定是否有效、刷新是否成功，而非无限重试 |
| 403 | 服务端权限，不用旧缓存掩盖 |
| “账号或书源已变化” | 请求是否跨越退出、重登或线路切换 |
| 退出后仍显示本地书籍 | 这是设备资料保留，检查会话状态即可 |

回归入口：JVM `SessionIsolationTest`、`ApiContractTest`，设备 `MirrorSessionTest`、`ForumSessionIsolationTest`。真实认证页面测试默认关闭，命令见[测试指南](../quality/testing.md)。不要在日志或夹具里保存真实 Cookie、密码和令牌。
