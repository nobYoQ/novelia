# 帖子与评论 Markdown

- 帖子、评论和嵌套回复统一支持 `!!剧透文字!!`：默认黑色遮盖，点击以白底黑字显示（明暗主题一致），再点普通剧透文字收起。剧透中的链接先展开，再点击访问。代码和转义的标记保留原文。
- 帖子正文输入框有随键盘可用空间调整的高度上限，长正文在框内滚动。页面处理键盘边距，聚焦正文及键盘大小变化时将编辑区域滚动到可视范围。
- 帖子正文、评论与嵌套回复的输入框上方共用快捷工具栏：粗体、斜体、删除线、链接、剧透、评分、折叠和格式帮助。模板与原站 `MarkdownToolbar.vue` 一致，选区可套用/取消格式，空选区插入原站占位文字，块模板补齐换行；操作后选中待修改部分并保留输入焦点。按钮触摸区为 48dp，窄屏可横向滑动；帖子编辑区域为工具栏预留键盘上方空间。
- 已移除段落实时预览和设置开关；正文统一使用完整 Markdown 源码输入，右上角保留手动「预览／编辑」。输入法组合输入、光标和滚动区域在键盘变化时保留，发布时使用原始 Markdown。
- 旧状态和设置备份中的 `postLivePreview` 字段会忽略，其他设置及原有草稿正常读取。
- `::: star 4.5` 渲染五颗星中的四颗半绿色星，支持 0–5 星；越界值限制在此范围，无效数字视为 0。兼容单行写法和可选的 `:::` 结束行。
- `::: details 自定义标题` 开始折叠块，`:::` 结束。默认收起，点击标题展开，再次点击收起；支持嵌套、内部 Markdown、剧透和星级。也兼容外面单独用两行 `!!` 包裹的写法。未闭合折叠延续到文档末尾，围栏代码和缩进代码中的语法示例不转换。
- 每个 Markdown 图片节点保留对应的图片对象及已加载尺寸。切换折叠不会重置其他图片，也不会在再次展开时重新下载已加载图片；阅读用 TextView 不因图片异步刷新而自动把选区滚动到屏幕中。
- 支持原站的软换行、`~~删除线~~`、裸网址自动识别（含中文标点分隔），以及已有的标题、列表、表格、代码、粗体、斜体、图片和 Markdown 链接。
- 正文图片长按进入与小说插图相同的全屏查看器，支持双指缩放、拖动、双击及缩放按钮。带链接的图片短按访问链接，长按只放大图片；剧透中的图片需要先揭开。
- 链接支持完整 URL、站内相对路径及 `//` 路径。站内书籍、章节、帖子使用应用页面；无对应原生页面的站内链接通过内置 WebView 打开，外部链接使用系统浏览器。WebView 中的后续跳转沿用同一规则。
- 相对链接使用当前帖子/小说地址作为基址。帖子正文中的 `#标题`、中文编码锚点和指向本帖的完整链接，直接在当前原生页面滚到对应标题，不新增返回记录。标题 ID 按原站规则生成，支持重复标题的 `-1`、`-2` 后缀；目标位于收起的折叠块时，先展开各级折叠，布局完成后再定位。空锚点 `#` 返回正文顶部。
- 其他页面的锚点、当前正文中无法匹配的锚点保留完整 URL，由 WebView 打开。首次加载时等待原站异步正文出现后再滚到标题，观察最多持续 15 秒，用户开始操作或页面改变后停止，避免抢夺滚动位置。WebView 顶部和系统返回均优先查询实际网页历史，包括 SPA 的页内跳转。
- 详情页之间跳转保留每一级返回记录，包括帖子→帖子、书籍→书籍和章节链接；根页面标签切换仍采用独立的状态恢复策略。编辑保存回到同一帖子时才显式替换顶部条目。

原站依据：`.reference/auto-novel/web/src/components/markdown/MarkdownView.vue`、`MarkdownToolbar.vue` 和 `MarkdownGuideModal.vue`。

标题锚点与原站使用的 [markdown-it-anchor 默认规则](https://github.com/valeriangalliat/markdown-it-anchor/blob/master/index.js) 对齐：提取标题中的文字和行内代码，转小写，以连字符替换空白，并为重复 ID 递增编号。

2026-09-13 通过原站 `/forum-edit` 的实际工具栏和帮助弹窗核对模板，并读取用户提供的两篇示例帖。教程帖 `/forum/64f3d63f794cbb1321145c07` 的目录使用 `#%E5%A6%82...` 页内锚点；图片帖 `/forum/6a4ba6ed447e2e413279011d` 使用多个 `pic` 折叠块。

验证入口：`MarkdownTest`（剧透解析）、`SiteMarkdownTest`（原站扩展、链接分类、旧设置兼容）、`MarkdownFeaturesTest`（明暗主题真实剧透点击与像素检查）、`SiteMarkdownInteractionTest`（折叠、半星、真实链接点击、图片长按与缩放）、`ArticleEditorLayoutTest`（真实编辑页与系统键盘布局）。测试使用本地内容，浏览器 Intent 在测试中拦截，不向原站发布帖子或评论。

新增回归入口：`MarkdownTemplatesTest`（模板、反向选区、取消格式、长度限制）、`MarkdownToolbarInteractionTest`（帖子/评论选区、焦点、帮助和清空草稿）、`SiteWebNavigationTest`（连续详情跳转逐级返回）。`SiteMarkdownInteractionTest` 另覆盖中文目录链接和延迟图片加载后的折叠复用。原站网络验证需显式传入 instrumentation 参数 `-e liveSite true`，默认跳过：直达原站发帖页、教程的中文锚点、示例帖真实图片展开/收起，不提交任何内容。

原生页内定位回归入口：`MarkdownAnchorsTest`（标题 ID、重复标题、中文编码、同文档识别）；`SiteMarkdownInteractionTest.localDirectoryLinksScrollWithinTheSameArticleIncludingClosedSections`（真实触摸、完整本帖链接、反复返回目录、重复标题及关闭的折叠）；`SiteWebNavigationTest.liveTutorialDirectoryLinksScrollInsideTheNativeArticle`（读取原站真实教程，逐一触发页内链接并验证目标位置、页面未变更）。

剧透点击回归修复：展开/收起时重新通知文本样式变化，刷新可选择文本的绘制缓存；展开时显式使用白底黑字。新增浅色、深色主题中的真实触摸与屏幕像素检查，覆盖展开、再次收起及其他剧透块保持隐藏。

2026-09-13 验证：Debug APK 构建成功，60 项 JVM 测试通过，Lint 为 0 错误、19 条建议/警告。15 项相关界面用例均已验证通过，另有横屏键盘布局检查。原连接设备上 14 项通过，但搜狗输入法未弹出；其临时键盘设置已还原，键盘用例改在项目的独立 Google Android 模拟器上验证，竖屏、横屏及手动预览往返均通过。原站教程冷启动验证同时断言完整 URL、正文标题及目标锚点进入视口；真实图片帖验证折叠后重用已加载图片。最终原站/导航复测为 4/4 通过。

日志：`artifacts/followup-final-build.log`、`artifacts/followup-ui-tests.log`、`artifacts/followup-emulator-tests.log`、`artifacts/followup-web-final-tests.log`、`artifacts/followup-landscape-test.log`。前两轮界面日志保留了发现问题的记录，最终 WebView 修复的结果以 `followup-web-final-tests.log` 为准。键盘截图：`artifacts/article-toolbar-keyboard.png`、`artifacts/article-toolbar-landscape.png`。测试使用临时草稿，未向原站发布帖子或评论。

2026-09-13 原生锚点补充验证：Debug APK 和测试 APK 构建成功，63 项 JVM 测试通过，Lint 为 0 错误、19 条建议/警告。14 项相关界面用例中 13 项首轮通过（含真实教程全部页内链接及原生真实触摸）；真实图片帖一项因网络图片 30 秒内未加载完成而超时，单独复测通过。折叠展开后将定位操作安排在布局回调之外，避免列表在布局过程中再次测量。构建和验证日志：`artifacts/native-anchor-final-build.log`、`artifacts/native-anchor-final-ui-tests.log`、`artifacts/native-anchor-image-recheck.log`。

0.1.4 Release 验证：版本码为 5，启用 R8 混淆和资源压缩；`assembleRelease`、`testReleaseUnitTest`、`lintRelease` 均通过，63 项单元测试无失败，Lint 为 0 错误、12 条建议/警告。`releases/Novelia-0.1.4-release.apk` 使用与 0.1.3 相同的本地测试证书，已验证签名、版本信息及 16KB 页面兼容的 ZIP 对齐；同目录保留未签名包、SHA-256 校验文件和混淆映射。日志：`artifacts/release-0.1.4-build.log`、`artifacts/release-0.1.4-signature.log`。
