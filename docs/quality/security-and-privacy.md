# 安全与隐私开发约束

[返回质量验证索引](README.md) · [文档总目录](../README.md)

本文说明当前实现的边界和修改时要保留的约束，不是安全审计通过声明。漏洞报告渠道见 [SECURITY.md](../../SECURITY.md)；复现问题使用自己的测试环境和虚构数据。

## 数据边界

| 数据 | 当前处理 | 开发注意点 |
| --- | --- | --- |
| 访问令牌 | [Session.kt](../../app/src/main/java/cc/novelia/app/data/auth/Session.kt) 用 Android Keystore 的 AES-GCM 密钥加密后写入私有 SharedPreferences | 不进入日志、普通状态、备份、崩溃描述或截图 |
| 认证 Cookie | 由 WebView `CookieManager` 管理，会话刷新使用认证站点 Cookie | 不把 Cookie 描述为同样受上述令牌加密机制保护；不要读取导出到调试文件 |
| 本地书库、笔记、草稿、文件 | 应用私有存储和专用导出路径 | 设备共享资料不等于多账号独立数据库，退出登录也不会删除本地资料 |
| 待同步操作 | 本地持久队列带账号归属，执行时绑定登录代次 | 包含写入内容，不能随普通设置或迁移备份导出重放 |
| 用户主动导出的文件 | 通过系统文档选择器或受限 FileProvider 交付 | 离开应用后由接收方或目标存储管理；提醒用户妥善保存备份 |

Keystore 加密只覆盖会话令牌，不代表小说、笔记或整个应用数据已经加密。阅读资料 ZIP 使用哈希校验完整性，没有提供加密或来源签名，不能当作可信来源证明；详见 [数据与备份](../data/data-and-storage.md)。

## 登录与权限

登录页面嵌入原站统一认证，密码由认证网站处理；客户端收到通过来源校验的固定消息后触发会话刷新，刷新成功才完成登录。令牌中的用户、角色和过期信息用于界面与会话管理，客户端解析不是服务端授权替代品。

修改 [LoginScreen.kt](../../app/src/main/java/cc/novelia/app/ui/account/LoginScreen.kt)、[Session.kt](../../app/src/main/java/cc/novelia/app/data/auth/Session.kt) 或 [SessionState.kt](../../app/src/main/java/cc/novelia/app/data/auth/SessionState.kt) 时保持：

- Web 消息校验 origin、主框架和预期消息内容；不把任意网页字符串作为登录成功凭据。
- 请求开始捕获 `SessionBinding`，响应和持久化提交前校验账号及登录代次。退出后同名账号再次登录也是新会话。
- 401 的刷新和重试只限原绑定；不能在重试时悄悄换成另一个当前登录账号。
- 退出登录立即失效旧会话，慢请求不能在退出后重新落盘旧令牌或清除后来的新登录。
- `canPost` / `canEdit` 等界面判断不构成权限保证，403 必须保留含义，不转换为“离线稍后重试”。

相应回归重点是 [SessionIsolationTest.kt](../../app/src/test/java/cc/novelia/app/data/auth/SessionIsolationTest.kt)。云端待同步策略还见 [网络与同步](../network/network-and-sync.md)。

## 网络、链接与 WebView

[Manifest](../../app/src/main/AndroidManifest.xml) 禁止应用明文流量；API 和认证客户端关闭自动重定向。图片加载等路径有自己的配置，不能推断整个应用所有 HTTP 客户端都采用同一重定向策略。

认证 WebView 和站点 WebView 有各自受信任来源和导航规则，入口见 [LoginScreen.kt](../../app/src/main/java/cc/novelia/app/ui/account/LoginScreen.kt)、[SiteWebScreen.kt](../../app/src/main/java/cc/novelia/app/ui/web/SiteWebScreen.kt)、[PagedSiteWebView.kt](../../app/src/main/java/cc/novelia/app/ui/web/PagedSiteWebView.kt)。调整规则时应审查主框架/子框架区别、JavaScript 桥、文件访问、混合内容和站外跳转，不把导航白名单误称为完整子资源防火墙。

API 请求的 Bearer 令牌只能用于本项目预期的受信任接口；不要用认证请求方法抓取任意用户输入 URL。当前下载仅校验初始 URL，随后允许重定向；扩展时应单独审查跳转链和最终目标，不能宣称已有逐跳来源校验。Markdown 链接经 [MarkdownLinks.kt](../../app/src/main/java/cc/novelia/app/data/markdown/MarkdownLinks.kt) 处理，书源文本经 [BookLinks.kt](../../app/src/main/java/cc/novelia/app/data/catalog/BookLinks.kt) 处理；不要绕过它们直接启动任意 URI Scheme。

调试认证故障时记录错误类别和 HTTP 状态即可，不记录 Authorization、Cookie、完整令牌、认证响应体或用户输入密码。诊断中不添加忽略 TLS 错误的逻辑。

## 文件与恢复

外部 URI、文件名、EPUB 条目、图片和 ZIP 备份都是待验证输入。扩展解析器要保留流式读取的容量上限、路径规范化与根目录边界、重复条目/冲突处理、临时文件清理以及取消检查。内存工具路径与磁盘解析路径的限制并不完全相同，不能复用一个未经核对的“安全解压”结论。

恢复先验证清单、版本、大小和哈希，再走现有恢复流程；损坏书库的保护状态不能通过启动时写一份空状态掩盖。新增备份字段使用明确白名单，排除会话、Cookie、待同步操作与下载队列。

导出使用系统 SAF 或 `cc.novelia.app.files` FileProvider。当前 [file_paths.xml](../../app/src/main/res/xml/file_paths.xml) 只暴露 `files/downloads/` 与 `files/exports/`；不能扩大到应用根目录来解决 URI 报错。详见 [文件与下载](../features/files-and-downloads.md)。

## Android 权限与系统备份

权限定义以 [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml) 为准：网络状态、互联网、通知及媒体播放前台服务。应用通过系统文件选择器导入导出，没有申请“管理所有文件”。新增权限必须说明具体功能必要性、拒绝后的行为和相应 API 版本。

系统自动备份已关闭，并在 [data_extraction_rules.xml](../../app/src/main/res/xml/data_extraction_rules.xml) 排除云备份和设备迁移的数据域。项目提供的是用户主动导出的阅读资料迁移路径，不应向用户承诺卸载或换机后由系统自动恢复全部资料。

外部入口是导出的主 Activity，TTS 服务和 FileProvider 不导出。更改 Manifest 时审查 exported、URI grant、深链和前台服务类型，不为测试方便开放新的生产组件。

## 调试、依赖与发行

公开 Issue 和 PR 使用最小脱敏日志，不上传完整 logcat、真实备份、小说内容、账号会话和签名文件。书籍 ID、用户名或本机路径也可能是用户隐私，保留定位所必需的字段即可。

签名私钥放在仓库外，通过进程环境注入构建，不写进 Gradle 源码、命令历史或公开附件。签名操作与证书轮换见 [RELEASING.md](../../RELEASING.md)。`.gitignore` 只能防止新文件被普通添加，不会清除已有提交；发现历史泄露时先确认范围并处理凭据，历史重写另行制定方案。

新依赖和素材需保留来源与许可。当前贴纸授权待确认，不能因为代码采用 GPL 就宣称素材可自由分发，见 [NOTICE.md](../../NOTICE.md)。
