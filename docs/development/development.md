# 日常开发与扩展

[返回开发入门索引](README.md) · [文档总目录](../README.md)

## 从问题到 PR

先从[业务功能索引](../features/README.md)核对用户行为及数据边界，再定位实现。涉及登录、资料迁移等跨页面问题时，分别阅读[账号与登录](../network/authentication.md)和[备份与恢复](../data/backup-and-recovery.md)，避免只修改按钮所在页面而遗漏后台状态。

先根据 [源码目录导航](../architecture/source-layout.md) 和 [架构地图](../architecture/architecture.md) 找到实现与测试。较大功能先在 Issue 中讨论行为、数据兼容和范围；外部贡献者从 Fork 建立分支，通过 PR 合入 `main`。一个 PR 聚焦一个问题，避免混入格式化、依赖升级和无关重构。

开发时在专用模拟器或测试设备使用虚构书籍与测试账号。复现记录应包含构建版本、系统 API、入口、预期、实际结果和是否开启电子纸/减少动效。可用合成的小说短文与 MockWebServer 响应复现的问题，不需要请求生产站点。

提交前检查差异，按 [测试指南](../quality/testing.md) 执行相关检查，在 PR 中写明结果、未运行范围和原因。用户可见变化写入 PR 描述并同步相关功能文档，发行时由维护者汇总到 GitHub Release 说明；仓库当前不维护独立的 `CHANGELOG.md`。普通贡献不自行升版。模板见 [.github/pull_request_template.md](../../.github/pull_request_template.md)，协作规则见 [CONTRIBUTING.md](../../CONTRIBUTING.md)。

## 代码与资源约定

- Kotlin 沿用现有风格和官方代码风格配置。文件编码为 UTF-8 无 BOM，换行由 [.editorconfig](../../.editorconfig) 和 [.gitattributes](../../.gitattributes) 约束；不要因终端显示问题替换中文文本。
- 用 `BookRef.key`、章节 ID、操作 ID 等稳定键表达身份。书名、翻译标题、列表位置、UI 标签可能变化。
- 持久状态通过 `LocalStore.update` 变换不可变快照；避免在 Compose 重组期间直接读写磁盘。
- 页面观察状态使用生命周期感知收集；长列表使用稳定 key，明确加载、空、错误、取消和重试的行为。
- I/O 使用 `Dispatchers.IO`；CPU 密集逻辑按现有实现安排调度。捕获异常时首先重抛 `CancellationException`，避免离开页面后仍继续下载或提交状态。
- URL 路径段、查询参数和导航参数分别编码；不能对已编码内容再次编码，也不能用字符串拼接绕开主机和协议校验。
- 图片、小说或响应夹具必须有权提交；个人备份、会话、签名材料、生成 APK 和构建缓存不入库。

当前工程未配置 ktlint、detekt 或自动格式化门禁；不要在 PR 中宣称这些检查已通过。Android Lint 和 Kotlin 编译检查不能替代业务回归测试。

## 添加一个页面

1. 先确定页面属于根标签还是详情页，再按 [归档规则](../architecture/source-layout.md) 在 `ui/<功能>/` 中创建独立的 `XxxScreen.kt`，或扩展已有页面。同一功能的面板和展示逻辑就近放置；沿用 `ui/components/` 中的 `Screen`、`AppLazyColumn`、统一弹窗和 `ui/theme/` 的主题组件。
2. 在 [MainActivity.kt](../../app/src/main/java/cc/novelia/app/MainActivity.kt) 注册路由，入口通过 `AppController.go` 或现有语义方法进入。可变字符串参数需要编码；具有不同参数的详情应保留独立历史记录。
3. 明确状态归属：页面瞬时状态留在 Compose；重建后应保留的轻量状态使用合适的保存方式；跨页面持久数据进 `LocalStore`；会话只交给 `Session`。
4. 需要登录的动作使用现有登录继续机制，网络结果应用前保持账号绑定校验；取消登录时不保留失效回调。
5. 覆盖系统返回、重复进入、横屏、600 dp 附近的布局、大字体、电子纸、减少动效以及键盘遮挡。新增导航还要检查外部链接是否需要映射。

路由表和组件选型见 [界面与导航](../architecture/ui-and-navigation.md)。不要直接复制一套独立主题或弹窗实现绕过静态交互模式。

## 添加持久偏好

假设新增一项阅读偏好，修改范围至少包括：

| 环节 | 应检查的内容 |
| --- | --- |
| 模型 | [LibraryModels.kt](../../app/src/main/java/cc/novelia/app/data/model/LibraryModels.kt)、[ReaderSettings.kt](../../app/src/main/java/cc/novelia/app/data/model/ReaderSettings.kt) 等领域模型中的字段默认值能否读取旧 JSON；是否属于全局、单书或临时状态 |
| 编解码 | [LibraryStateCodec.kt](../../app/src/main/java/cc/novelia/app/data/storage/LibraryStateCodec.kt) 中是否有验证/兼容要求；未知值如何处理 |
| 界面 | 默认偏好与单书设置是否都能正确显示；重置、预设和电子纸切换是否影响该字段 |
| 投影/排版 | 是否使缓存或分页结果失效；切换设置后如何保持阅读锚点 |
| 导入导出 | 普通设置备份、阅读资料备份是否应该包含它，导入时是否检查范围 |
| 测试 | 旧数据缺字段、非法值、保存重载、全局和单书优先级、预设切换恢复 |

不要因新增字段立即清空旧书库。备份格式版本与内部状态兼容策略分别维护；当前内部状态没有独立的递增 schema version，依赖字段默认值和显式兼容逻辑。格式升级需增加回归夹具，详见 [数据文档](../data/data-and-storage.md)。

## 添加 API 或云端写操作

1. 确认原站接口的真实方法、路径、字段、权限、分页和副作用。当前客户端的合约入口是 [NoveliaApi.kt](../../app/src/main/java/cc/novelia/app/data/network/NoveliaApi.kt) 与页面调用；原站源码只是核对材料，不是本客户端构建依赖。
2. 为 DTO 选择合理默认值，区分空值、空列表和字段缺失。用 MockWebServer 检查 URL 编码、请求体、响应解析与错误状态，参考 [ApiContractTest.kt](../../app/src/test/java/cc/novelia/app/ApiContractTest.kt)。
3. 通过统一认证请求入口发起请求，长操作在开始时捕获会话绑定，成功后写入本地状态前复核。不要另建一个会无条件复制当前令牌的全局拦截器。
4. 读取接口考虑缓存键是否含账号、何时失效、旧请求是否会覆盖新结果。写操作成功要沿用变更通知，避免页面继续显示写前缓存。
5. 只有经过策略确认可安全重放的操作才加入待同步队列。新增队列类型还要定义去重键、同实体顺序、账号归属、取消和错误展示；发帖、评论或上传不因网络异常自动重发。

完整约束见 [网络与同步](../network/network-and-sync.md)。为新接口添加 401、403、429、取消和账号切换场景；测试不需要真实凭据。

## 添加或变更书源

现有六类网络书源是 `kakuyomu`、`syosetu`、`novelup`、`hameln`、`pixiv`、`alphapolis`。原站负责收录和内容 API；客户端增加名称并不意味着服务器已支持该书源。

服务端具备支持后，检查 [Providers.kt](../../app/src/main/java/cc/novelia/app/data/catalog/Providers.kt) 的 `providers`、[BookLinks.kt](../../app/src/main/java/cc/novelia/app/data/catalog/BookLinks.kt) 的链接解析和反向来源链接、[SearchExpression.kt](../../app/src/main/java/cc/novelia/app/data/catalog/SearchExpression.kt) 与发现页筛选。同步检查章节 ID 格式、详情路由、下载和分享。涉及系统接收域名时还要审查 Manifest；应用内部能够解析链接不代表系统已验证 App Link。

书源测试应覆盖规范链接、尾斜杠、分享文本、大小写/编码、缺失 ID 和不受信任域名。不要用抓取器绕过原站权限来补齐未支持的功能。

## 修改依赖

先说明使用目的、体积和最低 Android 版本影响，再修改实际 Gradle 配置。运行 Debug 与 Release 构建和对应测试，重点关注 R8、序列化、反射、原生 ABI 及资源收缩。

运行 `:app:generateOpenSourceNotices`，检查生成的 Release 依赖清单和 LICENSE/NOTICE。依赖包缺少许可全文时，按 [licenses/README.md](../../licenses/README.md) 补充来源记录及文本；不要仅凭 Maven 坐标推定许可。依赖升级不应顺便升级整套工具链。

## 评审时的完成条件

评审者应能从 PR 描述知道问题、最终行为、测试和限制。涉及数据的改动说明旧版本如何迁移；涉及 UI 的改动给关键设备/模式截图；涉及性能的改动给可复现报告；涉及发行的改动说明签名、版本和对应源码。源码、相关文档与更新记录在同一 PR 保持一致。
