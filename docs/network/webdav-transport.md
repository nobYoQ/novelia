# WebDAV 传输与服务兼容

[WebDAV 功能与合并](webdav-sync.md) · [网络目录](README.md)

本页面向修改 [WebDavClient.kt](../../app/src/main/java/cc/novelia/app/data/webdav/WebDavClient.kt)、[WebDavProperties.kt](../../app/src/main/java/cc/novelia/app/data/webdav/WebDavProperties.kt) 和 [WebDavExchange.kt](../../app/src/main/java/cc/novelia/app/data/webdav/WebDavExchange.kt) 的开发者。同步必须能检测并发修改，不能用“最后上传覆盖”代替合并。

## 先验证服务器能力

“保存并测试”在唯一随机临时文件上检查创建、读取、拒绝重复创建、正确版本更新和拒绝旧版本更新。拒绝后还要确认原正文及版本没有变化，最后清理本次创建的文件。

服务器需要强 ETag 与有效条件请求。读取只拿到正文却无法确定可靠版本时，应停止同步，而不是继续写入。

| 操作 | 约束 |
| --- | --- |
| 普通服务创建 | `If-None-Match: *` |
| 已有文件更新 | `If-Match: <读取到的版本>` |
| 创建/更新冲突 | 重新读取、合并，单次最多四轮 |
| 坚果云官方端点首次创建 | 上传随机临时文件，再 `MOVE` 且 `Overwrite: F` |

## ETag 与读取保持一致

请求使用 identity 编码，避免压缩响应改变实体版本含义。兼容缺少引号的非弱标识，不能去掉 `W/` 把弱版本伪装成强版本。

GET 没有有效 ETag 时，用 `PROPFIND Depth: 0` 读取目标的 `DAV:getetag`，随后按该版本条件读取正文。不能把早先 GET 的正文与后来取得的 ETag 拼在一起；还用不存在的版本确认服务确实执行读取条件。

同一配置的 `manifest.json` 使用上次验证的 ETag 条件读取。返回 304 时复用已验证的标识正文；返回新正文时重新校验数据集，消失时仍停止同步。此缓存只在当前进程保留，配置变化不复用。

属性解析检查确切资源、DAV 命名空间和成功状态，拒绝外部实体及超限 XML。无法证明版本可靠时保留本机资料并报错。

## 坚果云的限定兼容

当前代码仅对官方端点 `dav.jianguoyun.com` 做以下兼容：

- 内部保存标准强 ETag，发出条件请求时使用该端点接受的裸值。
- 禁止覆盖的 MOVE 遇到已有目标可能返回 409。重新读取确切目标，存在则按冲突处理，且连接探测检查原内容未变。
- 临时文件用普通随机名称和 `.cache` 后缀，上传类型为 `application/octet-stream`。随机文件没有共享写者，最终目标仍必须禁止覆盖。

目标确实不存在时才恢复父目录。完整 Destination URL 失败后，核对源文件、补建并验证父目录，再按原目标重试一次；仍失败时再次核对源文件，再试同源绝对路径。最多三次 MOVE，每次保留 `Overwrite: F`，409 后重新确认目标是否已被另一台设备创建。

目录已存在的响应也要核验 `DAV:resourcetype`，不能将所有 403/404/409 都当成功。不要删除已有目标再移动，也不要递归无界重试。清理只针对本次随机文件。

这些是源码中的服务兼容规则，不保证所有 WebDAV 提供商表现相同，也不代表本轮重新完成了真实服务验证。

## 通用请求节流与冷却

自动同步在所有入口共用两分钟最小间隔；编辑触发优先检查待提交类型，远端全量检查保留。具体触发和等待规则见[自动同步](webdav-sync.md#自动同步何时发生)。

识别到限流后停止本轮全部后续请求，包括临时文件清理，以免进一步消耗请求额度；此时可能留下本次随机探测文件。账号冷却覆盖新建客户端实例、配置变化和应用重启，不保存授权码或响应正文。普通维护类 503 不会误报为限流。

限流响应优先遵循 `Retry-After` 的秒数或 HTTP 日期，格式依据 [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3)；HTTP 429 依据 [RFC 6585](https://www.rfc-editor.org/rfc/rfc6585.html#section-4) 识别。没有有效等待时间时统一冷却 30 分钟，不按提供商域名设置请求频率或冷却时长。

## 凭据和提交顺序

WebDAV 客户端独立于小说/论坛传输，仅接受 HTTPS；只允许同源同路径规范化的重定向，不跨站发送凭据。密码不写入同步 JSON、日志、备份或 Worker 参数。

上传前持久化设备时钟和操作，上传后才确认本地结果。配置变更会使旧代次失效，旧网络成功也不能提交到新配置。

## 回归入口

[WebDAV 测试目录](../../app/src/test/java/cc/novelia/app/data/webdav) 包含条件请求、强/弱 ETag、PROPFIND、版本与正文一致、路径编码、重定向、两客户端首次创建竞争和有限重试测试。重点看 `WebDavClientTest`、`WebDavPropertiesTest`、`WebDavNutcloudCompatibilityTest` 与 `WebDavExchangeTest`。

新增服务兼容应限制适用端点，先用 MockWebServer 复现异常响应，再在授权的专用服务目录验证；不能为适配一个提供商放宽所有服务的覆盖保护。
