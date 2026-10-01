# ECH 连接测试版

[返回网络与认证索引](README.md)

此分支为 Android 8.0+ 的 ECH 实验版本，不依赖 Google Play 服务或用户安装代理。小说、论坛和认证域名仍为原站地址；不新增中转服务器。

从 `ech.4` 起，`codex/ech-adaptation` 直接基于 `main`，社区页面和账号业务沿用主分支。独立论坛预览分支的业务提交未纳入本分支；保留论坛域名的 ECH 连接能力和匿名探测，供后续论坛适配复用。迁移前已验证的完整版本保存在 `codex/ech-adaptation-before-main-20261001`，原 `codex/forum-api-preview` 分支保持原有提交。

## 手机上验证

1. 安装 `0.2.3-ech.4` 测试 APK，在原先失败的 Wi-Fi 网络上打开。
2. 「我的 → 设置 → ECH 连接测试」默认启用 ECH。点击「运行 ECH 连接诊断」，等待三个域名的结果，可复制给开发者。
3. 测试发现列表、章节正文、主分支社区和文件下载；已登录时再测试账号续期。独立论坛预适配通过诊断末尾的「论坛分类 API」「论坛帖子 API」验证。避免把发帖或评论当作反复重试的测试请求。
4. 如需对照，关闭 ECH 后重新打开 App，再测试同样的页面；已有离线缓存或旧连接不能用于证明新连接成功。移动数据网络也可做同样检查。

诊断对三个域名分别发起全新 TLS 握手，再通过实际 API 使用的桥接路径匿名请求 `/cdn-cgi/trace` 并读到响应结束。结果只包含域名、阶段、HTTP 状态/协议及耗时，不发送账号凭据或输出响应内容。实际 API 调用额外校验 TLS 1.3 和 `ConnectionState.ECHAccepted`，不把连接偶然恢复误报为 ECH 成功。

`ech.2` 修复了 JNI 将 Go 的空切片映射为 `null` 时，Kotlin 错把正常响应结束当作读取异常的问题。该问题会造成「握手通过，页面仍加载失败」。诊断现在区分未收到响应头和响应正文读取失败。

`ech.3` 为实际 TCP/TLS 连接设置 8 秒独立期限，避免自定义 `DialTLSContext` 忽略 `TLSHandshakeTimeout` 后长期卡住。无请求体的 GET/HEAD 在获得连接之前最多尝试 3 次；获得连接后失败、HTTP 错误状态、正文读取错误及所有写请求不在此重试范围内。连接失败后重新解析 ECH 信息，证书验证保持开启。页面显示固定错误原因；诊断额外匿名读取并解析论坛分类、帖子 API，避免把 Cloudflare 检测页成功等同于论坛可用。

## 适配范围

| 路径 | 本版本行为 |
| --- | --- |
| 小说 API、主分支社区、认证 API、会话续期 | ECH 开启时强制走本地 ECH 核心 |
| 后续独立论坛 API | 已保留域名白名单与匿名 API 探测；业务层接入时复用共享网络客户端 |
| 原站文件下载、上传、同域图片 | 流式传输，保留取消；没有 4 MB 整包限制 |
| 第三方图片、更新检查及外部网站 | 原网络实现 |
| 首次登录、注册、找回密码的 WebView 页面 | 仍由系统 WebView 联网，未接管；API 的 ECH 成功不代表网页登录可用 |

ECH 不可用时返回错误，不自动改用明文 SNI，也不因连接失败重放写请求。可通过设置开关手动切回普通 OkHttp。API 保留禁止重定向的策略；下载和图片允许 HTTPS 重定向，跨来源移除 Authorization 和 Cookie，一次性请求体不重放。

## 后续论坛适配的接入点

应用共享 ECH 客户端可通过 `app.api.transport` 取得。后续论坛 API 和独立论坛会话的认证/续期请求都应注入这个客户端；账号状态仍由独立论坛会话维护。例如，在论坛业务层加入后：

```kotlin
val forumHttp = NoveliaApi(
    session = forumSession,
    baseUrl = "https://forum.novelia.cc/api/v1/",
    transport = app.api.transport
)
```

Kotlin 拦截器与 Go 核心均已包含 `n.novelia.cc`、`auth.novelia.cc`、`forum.novelia.cc`。下载和图片派生客户端继续使用 `echRedirects(true)`。`EchForumProbe` 只定义诊断所需的最小响应结构，不依赖论坛业务模型、界面或登录代码；后续论坛功能可以独立演进。

## 实现与限制

- 使用 Jissr Bypass v0.1.1 的加密 DNS 解析与独立握手探测；实际 HTTP 连接由本地适配层管理独立连接期限、Go 1.27.1 标准 TLS 1.3/ECH 和系统证书验证。未使用上游的整包 Android 适配器。
- Kotlin/Go JNI 桥接新增 64 KiB 流式上传/读取、取消和读取空闲超时。上传正文不落盘。连接建立和发送请求阶段使用读取超时作为总预算；默认 API 为 60 秒、下载为 120 秒。
- 默认通过阿里、Cloudflare、Google 的 HTTPS DNS 获取地址和 ECH 配置。核心并发查询，选同一解析器的 A 与 HTTPS 答案；不记录 DNS 响应或请求正文。解析器会收到所查询的域名。
- 上游当前优先使用一个 IPv4 A 地址，未实现完整 IPv6 地址竞速；IP/外层 ECH 主机被阻断、所有 DoH 不可达时仍可能失败。
- 网络切换时清除 DNS/ECH 缓存与空闲连接；支持服务器返回经验证的新 ECH 配置后的握手重试。
- 普通 OkHttp 连接事件不能完整反映桥接层 TLS 耗时；使用本页专门诊断，不启用包含账号内容的网络日志。

## 构建与测试

Windows PowerShell 7 执行：

```powershell
./build-release.ps1 -Verify
```

构建入口首次自动调用 `scripts/build-ech.ps1`；需要 SDK 内的 NDK `28.2.13676358`。官方 Go 1.27.1 ZIP 在 `outputs/ech-tools` 内下载、校验 SHA-256 并展开，不做系统安装。Go 模块通过 `go.sum` 和校验服务核验。四种 ABI 的 AAR 位于 `native/ech/build/novelia-ech.aar`，不提交二进制。

后续可以 `./build-release.ps1 -Verify -Offline`。修改 Go 核心后自动重建。单独运行核心测试使用 `./scripts/build-ech.ps1 -TestOnly -Offline`。其他平台需要按 `go.mod` 的固定工具版本自行运行 `gomobile bind`，生成相同路径的 AAR 后再运行 Gradle。

JVM 回归覆盖：流式大响应、Cookie 多值、错误状态、取消、无自动降级/POST 重放、跨来源重定向与开关。

`EchNativeTest` 检查 JNI 加载与取消回调。其公网测试默认跳过，仅在专用无账号模拟器上显式传入 `echLive=true` 时，通过原生桥接和 App 实际客户端读取原站 `/cdn-cgi/trace` 至结束，并通过 API 解析发现列表、论坛分类和章节正文；不会输出响应内容。

2026-10-01 的 `ech.2` 验证：420 项 JVM 测试、6 项 Go 单元测试和 Android 15 x86_64 模拟器上的 3 项设备测试通过，Lint 为 0 错误。最终优化 Release 包成功安装、启动并加载发现列表；首次发现请求有一次失败，手动重试后成功，因此尚不能视为已解决网络的所有偶发失败。真实手机及原网络仍需复测。

同日 `ech.3` 验证：10 项 Go 单元测试、421 项 JVM 测试、4 项 Android 15 x86_64 设备测试通过；论坛分类和帖子 API 三轮匿名冷连接测试均返回 HTTP 200 与有效 JSON；最终优化 Release 的社区页面成功显示论坛分类和帖子。Lint 为 0 错误、24 条既有警告。APK 签名验证通过，仍需在实际手机原网络复测。

测试 APK 使用项目本地测试证书。若与已安装正式版签名不同，不能直接覆盖；不要为安装测试包直接卸载并丢失阅读资料。
