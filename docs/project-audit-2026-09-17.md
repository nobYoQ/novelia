# 项目审查：性能、交互与可靠性

> 本文保留修复前的发现和行号。对应修复及最新验证结果见 [审查问题修复](audit-fixes-2026-09-17.md)。

审查日期：2026-09-17。范围覆盖生产 Kotlin 源码 58 个文件、约 6,532 行，以及构建配置、Manifest、18 个 JVM 测试文件和 21 个设备测试文件。

最应优先处理的是账号隔离、异步编辑数据丢失和两处崩溃路径。性能方面，收益较明确的方向是大文件内存控制、导入查重索引、朗读主线程工作和分页重排取消。

以下 P1 表示建议下一轮优先修复的崩溃、数据丢失或账号隔离问题；P2 表示有明确触发条件的功能缺陷或优化项，不表示已测得严重卡顿。除特别注明的 Markdown 探针外，发现来自代码审查，并未声称在设备上复现。

## 优先修复

### F01 · P1 · 在途请求和待同步操作可能跨账号执行

位置：[NoveliaApi.kt:25](/E:/Project/novelia/app/src/main/java/cc/novelia/app/data/NoveliaApi.kt:25)、[Session.kt:43](/E:/Project/novelia/app/src/main/java/cc/novelia/app/data/Session.kt:43)、[AppController.kt:118](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/AppController.kt:118)。

普通请求的每次执行都读取当前 token；遇到 401 时，如果 token 已经变化，`refreshIfCurrent` 仅判断新 token 非空，然后使用新 token 重试。A 账号发出的请求在切换到 B 后可能按 B 身份重放。待同步列表虽然按账号筛选，但循环内每次请求仍使用共享的当前会话，执行期间也没有账号约束。

建议让请求绑定发起时的账号和会话代次，账号变化后终止旧请求、旧重试和旧同步任务；同一账号正常刷新访问令牌仍可继续。验证应覆盖延迟 401、同步执行中切换账号，以及同账号刷新三种情况。

### F02 · P1 · 旧离线操作会覆盖已经成功的新操作

位置：[AppController.kt:109](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/AppController.kt:109)、[ReaderScreen.kt:230](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/ReaderScreen.kt:230)。

`cloudMutation` 只在失败入队时替换相同账号、相同路径的旧记录。后续在线操作成功并不会清除或使旧记录失效。例如离线阅读第 5 章入队，恢复网络后第 6 章同步成功，再点击“待同步操作”，仍会提交第 5 章，导致云端进度倒退。收藏的添加/移除也有同类顺序问题。

建议按账号和资源维护操作版本，将在线提交和队列重放统一排序；成功时仅清除不晚于该次提交的旧版本，避免误删并发产生的新操作。补“旧失败→新成功→重放”与“同步期间产生新修改”回归测试。

### F03 · P1 · 分页结果与正文版本错配可导致越界

位置：[EInkPage.kt:146](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/EInkPage.kt:146)、[EInkPage.kt:161](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/EInkPage.kt:161)。

`produceState` 的 key 改变后，旧 measured 值会保留到新副作用执行，而组合阶段已经使用新的 paragraphs。旧页的段落索引直接用于 `paragraphs[index]`。如果显示语言/译文切换后段落数减少，当前页仍引用已消失的段落，就存在越界崩溃窗口。

建议把输入版本、正文和测量结果绑定为同一个快照，渲染前核对版本；重排期间不使用旧页索引访问新正文。设备回归可构造两段中文、仅一段有效日文，停在第二段后切日文，并覆盖快速切换与旋转。

### F04 · P1 · 特殊 Markdown 可使解析器抛出异常（已独立复现）

位置：[SpoilerMarkdown.kt:71](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/SpoilerMarkdown.kt:71)、[MarkdownText.kt:53](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/MarkdownText.kt:53)。

剧透解析从有限 Unicode 标点范围内使用 `first` 寻找输入未使用的字符，没有找不到时的回退。仅 50 个 UTF-16 字符的构造输入即可覆盖所有候选，稳定抛出 `NoSuchElementException`。UI 在组合期间直接解析，缺少解析失败后的文本回退。

独立 Java 探针直接调用本次编译出的实际解析器，普通剧透通过，候选耗尽案例成功复现；没有修改生产代码或 Gradle 配置。探针：[SpoilerSentinelProbe.java](/E:/Project/novelia/artifacts/audit/SpoilerSentinelProbe.java)。

建议候选耗尽时安全回退为普通 Markdown/文本，并为外部内容解析设置错误边界；长期可消除基于有限备用字符的替换机制。将该短输入纳入正式回归测试。

### F05 · P1 · 评论发送成功会清空发送期间的新输入

位置：[CommunityScreens.kt:170](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/CommunityScreens.kt:170)。

发送时只禁用了发送按钮，编辑器仍可输入。发送 A 后，在网络等待期间继续输入 B；A 成功后无条件清空 text 并删除草稿，B 没有发送却丢失。

建议提交不可变快照，成功时只在当前输入仍与提交快照一致时清空；也可以明确禁用发送期间的编辑。补延迟 POST 响应时继续输入的测试。

### F06 · P1 · 术语表将未提交的新编辑标记为已保存

位置：[ToolScreens.kt:75](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/ToolScreens.kt:75)、[ToolScreens.kt:77](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/ToolScreens.kt:77)。

PUT 提交版本 A 后，用户仍能新增、编辑或删除词条形成 B。响应成功后使用当前 `data` 更新 `original`，因此本地 B 被标记为已保存、保存按钮禁用，服务器实际只有 A。

建议请求体和成功后的基线都使用同一 submittedData 快照，让等待期间的新编辑保持未保存状态。补延迟 PUT 返回后继续编辑并重新进入页面的测试。

### F07 · P1 · 下载与导入上限冲突，拒绝前已分配大块内存

位置：[DownloadWorker.kt:37](/E:/Project/novelia/app/src/main/java/cc/novelia/app/files/DownloadWorker.kt:37)、[DownloadImport.kt:10](/E:/Project/novelia/app/src/main/java/cc/novelia/app/files/DownloadImport.kt:10)、[DocumentTools.kt:22](/E:/Project/novelia/app/src/main/java/cc/novelia/app/files/DocumentTools.kt:22)。

下载接受最高 512 MB，导入器只接受 64 MB，但导入先 `readBytes()`，之后才检查 64 MB 上限。因此 64–512 MB 文件会先分配整块内存，再被拒绝；低内存设备可能在校验前 OOM。下载界面仍会提供“导入阅读”。

EPUB 路径还同时保留解压内容、缓冲区、图片 Base64 和解析结果；192 MB 的解压预算不能等同于实际峰值内存预算。

建议读取前检查长度并统一产品限制；解压条目与插图流式落盘，避免整个 ZIP 和图片字符串同时驻留。补大小边界、高展开率 EPUB，并在低内存设备测峰值 RSS/Java heap。OOM 风险未做设备实测。

## 功能与交互改进

| 编号 | 优先级与位置 | 触发条件、影响和建议 |
| --- | --- | --- |
| F08 | P2 · [CommunityScreens.kt:119](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/CommunityScreens.kt:119)、[WenkuEditor.kt:31](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/WenkuEditor.kt:31) | 草稿只在 700 ms 防抖后写入 store，立即返回会取消该副作用，丢失最后输入。应即时更新内存草稿，由已有持久化层合并磁盘写入，或离页时提交最新快照。测试应覆盖减少动效下输入后立即返回。 |
| F09 | P2 · [Session.kt:47](/E:/Project/novelia/app/src/main/java/cc/novelia/app/data/Session.kt:47)、[Session.kt:63](/E:/Project/novelia/app/src/main/java/cc/novelia/app/data/Session.kt:63) | 刷新有同步锁，退出和 clear 没有相同的结果提交约束。退出后较晚完成的刷新可以重新写入旧 token/profile。退出应推进会话代次，拒绝旧刷新结果。用可控延迟测试竞态；实际发生频率未测。 |
| F10 | P2 · [UpdateWorker.kt:22](/E:/Project/novelia/app/src/main/java/cc/novelia/app/data/UpdateWorker.kt:22) | 更新检查固定取前 60 本在线收藏，没有轮转；手动检查也使用该 Worker。书架次序不变时第 61 本以后不会被检查。改为分批遍历或保存轮转游标，补 61 本及多轮调度测试。 |
| F11 | P2 · [ReaderScreen.kt:223](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/ReaderScreen.kt:223) | 连续滚动与自动分页切换仅传段号，丢失段内偏移。长段落跨多屏时会回到段首，后续持久化这个退后的位置。统一使用段落标识和字符偏移作为锚点，验证多页长段的双向切换。 |
| F12 | P2 · [DiscoverScreens.kt:33](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/DiscoverScreens.kt:33)、[DiscoverScreens.kt:111](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/DiscoverScreens.kt:111) | 网络小说 0–2 与文库 0–6 的 level 共用状态。可编辑账号选择文库“文学”(3) 再切网络小说时，界面显示被钳制为 R18，请求却仍发送 3。拆分 webLevel / wenkuLevel，并验证各分类切换后的请求参数。 |
| F13 | P2 · [DownloadScreens.kt:52](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/DownloadScreens.kt:52)、[ShelfScreens.kt:69](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/ShelfScreens.kt:69) | 导出对象仅存在 remember。系统保存文件对话框期间 Activity 重建后，对象变为 null，拿到 URI 后静默跳过写入。持久保存下载 ID / BookRef，再恢复对应文件；补重建后导出内容完整性测试。 |
| F14 | P2 · [WenkuEditor.kt:37](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/WenkuEditor.kt:37) | 并发更新检查仅比较部分字段，但提交还包括作者、画师、封面、分级和标签。别人只改这些字段时会漏报冲突并可能覆盖。至少比较全部将提交字段；若服务端支持，采用版本条件更新。补逐字段并发修改测试。 |
| F15 | P2 · [DownloadWorker.kt:26](/E:/Project/novelia/app/src/main/java/cc/novelia/app/files/DownloadWorker.kt:26)、[DownloadScreens.kt:100](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/DownloadScreens.kt:100) | Worker 临时文件是 ID-WorkID.part，删除任务时却清理 ID.part。进程终止导致 finally 未执行后，删除任务可能遗留大文件。按有效任务集合回收遗留临时文件，并测试进程终止后的删除/重试。 |
| F16 | P2 · [DownloadWorker.kt:31](/E:/Project/novelia/app/src/main/java/cc/novelia/app/files/DownloadWorker.kt:31) | 下载直接使用当前访问 token，不复用普通 API 的 401 刷新流程。任务长时间等待 Wi-Fi 后，即使刷新会话有效也可能失败。统一使用绑定账号的认证重试层，验证过期访问令牌下的延后执行。 |
| F17 | P2 · [MainActivity.kt:39](/E:/Project/novelia/app/src/main/java/cc/novelia/app/MainActivity.kt:39)、[MainActivity.kt:130](/E:/Project/novelia/app/src/main/java/cc/novelia/app/MainActivity.kt:130) | onCreate 无条件重新消费启动 Intent，导航状态恢复后可能再次打开已处理过的外部链接；onNewIntent 又未更新 Activity 持有的 intent。建议显式记录链接事件是否已消费，并同步当前 Intent。验证由链接启动、继续导航、旋转，以及再接收另一链接后的恢复。 |
| F18 | P2 · [ReadAloudService.kt:51](/E:/Project/novelia/app/src/main/java/cc/novelia/app/reader/ReadAloudService.kt:51)、[ReadAloudService.kt:61](/E:/Project/novelia/app/src/main/java/cc/novelia/app/reader/ReadAloudService.kt:61) | 纯插图等场景可能得到空朗读队列，仍启动前台服务；configureAndSpeak 直接返回，界面停在准备中，直到定时结束。开始前拒绝空队列并给出可理解的反馈。 |

导出状态应只保存恢复所需的 ID 等小对象，避免把完整文档放入 Bundle；参见 [Android：保存 Compose UI 状态](https://developer.android.com/develop/ui/compose/state-saving)。

## 性能优化

下表确认的是代码路径和复杂度。没有设备帧时、启动时间或内存采样，因此不附改善百分比。

| 编号 | 优先级与位置 | 机制、建议和验证 |
| --- | --- | --- |
| F19 | P2 · [DownloadImport.kt:11](/E:/Project/novelia/app/src/main/java/cc/novelia/app/files/DownloadImport.kt:11)、[ShelfScreens.kt:47](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/ShelfScreens.kt:47)、[LocalStore.kt:93](/E:/Project/novelia/app/src/main/java/cc/novelia/app/data/LocalStore.kt:93) | 每本新书先完整解析，再逐本读取现有文档比较哈希。文档缓存仅 4 本/32 MB，较大书库批量导入会重复反序列化全库。建立 sourceHash → BookRef 的轻量索引，在解析前查重；测 10/100/500 本书库的重复导入耗时、磁盘读取量和内存。 |
| F20 | P2 · [ReadAloudService.kt:80](/E:/Project/novelia/app/src/main/java/cc/novelia/app/reader/ReadAloudService.kt:80)、[ReadAloudService.kt:51](/E:/Project/novelia/app/src/main/java/cc/novelia/app/reader/ReadAloudService.kt:51) | 点击朗读时同步编码并写整章 JSON，Service 主线程读取、解码和拆分。长章有主线程阻塞风险。后台准备队列，使用独立请求 ID/文件，服务启动时仅接收轻量引用；用 StrictMode 和长章 trace 验证。 |
| F21 | P2 · [EInkPage.kt:86](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/EInkPage.kt:86)、[ReaderScreen.kt:430](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/ReaderScreen.kt:430) | 字号、行距、宽度滑块连续更新触发全章重新布局；旧同步测量循环没有取消检查，协程取消后仍可继续计算。对排版参数防抖或松手提交，每段/每批行检查取消。用超长章节连续拖动设置测 CPU、重排次数和响应时间。 |
| F22 | P2 · [DocumentTools.kt:126](/E:/Project/novelia/app/src/main/java/cc/novelia/app/files/DocumentTools.kt:126) | OCR 整理对长块用 fold 空字符串反复连接，持续复制已累计文本，最坏呈平方级工作量。改为 StringBuilder 和逐行迭代并支持取消；补长单换行文本基准。 |

建议增加专门的性能基线：冷启动、长目录滚动、超长章节切换、分页调参、批量导入和插图 EPUB。使用真实低端设备上的 Release 构建，记录 p50/p95 帧耗时、峰值内存、磁盘读取量和完成时间；参见 [Android Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)。本次源码搜索未发现 Macrobenchmark、Baseline Profile 或 JankStats 接入。

## 需要设备验证的交互项

- [DownloadScreens.kt:33](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/DownloadScreens.kt:33) 的下载面板使用不可滚动 Column。横屏、大字体和电子纸对话框限制高度时，“开始下载”有被挤出视口的风险。建议改用项目已有的可滚动容器，并在小屏横屏与 200% 字体下确认；本次未实际观察到裁剪。
- [MarkdownToolbar.kt:36](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/MarkdownToolbar.kt:36) 的格式按钮关闭了键盘焦点，外接键盘 Tab/D-pad 会跳过这些操作。应在保存编辑选区和软键盘体验的同时保留键盘操作路径。此结论不等同于 TalkBack 失效；还需独立检查无障碍语义与焦点顺序，参见 [Compose Semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)。
- [ToolScreens.kt:73](/E:/Project/novelia/app/src/main/java/cc/novelia/app/ui/ToolScreens.kt:73) 在输入时直接过滤术语集合，上限可达 5,000 项。先测长词条输入延迟，再决定是否复用项目现有的查询防抖和结果缓存。

## 工程维护与验证结果

已有合理基础：后台初始化、原子持久化及突发写合并、有界缓存、OkHttp 请求取消、列表稳定 key、减少动效与电子纸适配、Release R8/资源压缩。下一轮应集中补状态边界和量化基线。

本次执行：

```powershell
./build.ps1 -Tasks @('testDebugUnitTest', 'lintDebug', 'testReleaseUnitTest', 'lintRelease') -Offline
```

| 检查 | 本次结果 |
| --- | --- |
| Debug JVM | 18 个测试类，91 项测试，0 失败、0 错误、0 跳过 |
| Release JVM | 同一套 91 项测试，0 失败、0 错误、0 跳过 |
| Debug Lint | 0 错误，20 条 Warning |
| Release Lint | 0 错误，13 条 Warning |
| Markdown 独立探针 | 普通剧透通过；备用字符耗尽稳定触发 NoSuchElementException |
| 设备连接 | adb devices 为空，未运行设备测试、实机手势验证或性能采样 |

Lint 警告主要涉及依赖/工具版本、KTX 建议、Modifier 参数约定和 target API 提示。版本提示应结合目标发布环境核对，不能替代本报告中的行为验证。构建另有 Android analytics 设置不可写的非致命提示，不影响构建及测试结果。

现有单元测试通过不代表上述边界已覆盖。最值得补的是账号切换与排队顺序、异步请求期间编辑、草稿退出、配置重建、跨正文版本排版、大文件上限和大书库规模测试。

README 顶部仍写 0.1.4，而构建配置为 0.1.5；性能文档中的“42 项 JVM”是旧验证记录。本次结果应作为新的验证记录保留，不覆盖历史记录。

产物：[构建日志](/E:/Project/novelia/artifacts/project-audit-build.log)、[Debug 测试报告](/E:/Project/novelia/app/build/reports/tests/testDebugUnitTest/index.html)、[Release 测试报告](/E:/Project/novelia/app/build/reports/tests/testReleaseUnitTest/index.html)、[Debug Lint](/E:/Project/novelia/app/build/reports/lint-results-debug.html)、[Release Lint](/E:/Project/novelia/app/build/reports/lint-results-release.html)。

## 建议执行顺序

1. 修复 F01–F07：绑定账号、操作排序、分页版本隔离、解析回退、提交快照和导入前大小校验。每项补对应回归。
2. 修复草稿、状态恢复、筛选与更新覆盖范围，统一异步编辑的提交状态；保留未提交输入。
3. 优化导入索引、朗读队列、分页取消和 OCR 处理，并用 Release 设备基线验证收益。
4. 完成小屏/横屏/大字体/键盘/TalkBack/电子纸测试矩阵，再处理依赖升级和文档一致性。

本次只新增审查报告与忽略目录内的验证产物，未修改业务源码、依赖或应用数据，未执行真实账号写入。
