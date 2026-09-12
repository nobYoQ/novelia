# 小绿贴纸与插图交互

使用用户下载的 `resource/HoshikawaMidori-1789205072094` 原始贴纸，延续应用绿色 Material 3 界面。原图不修改，选中的 13 张 WebP 复制到 `drawable-nodpi`，保留透明边缘和比例，总计 417,912 字节。

## 素材映射

| 原文件 | 应用资源 | 场景 |
| --- | --- | --- |
| `file_2958651.webp` | `midori_neutral.webp` | 我的页常态、默认封面 |
| `file_2958650.webp` | `midori_happy.webp` | 点击开心笑脸 |
| `file_2958660.webp` | `midori_wink.webp` | 点击眨眼 |
| `file_2958664.webp` | `midori_love.webp` | 点击爱心眼 |
| `file_2958715.webp` | `midori_welcome.webp` | 空书架欢迎、默认封面 |
| `file_2958696.webp` | `midori_curious.webp` | 搜索无结果 |
| `file_2958717.webp` | `midori_reading.webp` | 默认封面：阅读 |
| `file_2958676.webp` | `midori_thinking.webp` | 默认封面：思考 |
| `file_2958709.webp` | `midori_celebrate.webp` | 下载完成庆祝 |
| `file_2958677.webp` | `midori_approve.webp` | 导入成功点赞 |
| `file_2958694.webp` | `midori_sleep.webp` | 朗读定时结束 |
| `file_2958652.webp` | `midori_concerned.webp` | 加载失败、刷新失败 |
| `file_2958714.webp` | `midori_wave.webp` | 关于页招手彩蛋 |

## 我的页与默认封面

我的页只显示互动贴纸，移除「你的阅读搭子」「戳戳我」及点击后的气泡文案。点击区域为 128 dp，贴纸从 96 dp 放大至 112 dp；登录、账号信息继续使用原来的独立区域。

待机左右摆动约 ±4°，每轮 4.4 秒，包含 1.6 秒休息。点击立即轮换开心、眨眼、爱心眼，缩至 88% 后弹至 112% 再回落，三颗爱心在约 950 ms 内向上散开。最后一次点击约 1.8 秒后恢复常态。连点只重启当前反馈，不累计粒子和任务。

缺封面、空地址或封面加载失败时，使用阅读、思考、常态、欢迎四张素材之一，加渐变底色、细书脊和书名。表情按作品标识固定选择，筛选、刷新不会随机换图。实际封面加载成功后覆盖默认封面；保留现有封面缓存和淡入逻辑。

## 场景动效

| 场景 | 触发 | 反馈与结束 |
| --- | --- | --- |
| 下载完成 | 当前会话中观察到已有任务从未完成变为已完成 | 完成提示配庆祝贴纸轻跳一次；同批合并，连续完成替换上一次庆祝 |
| 导入成功 | 文件解析、保存书籍成功后 | 完成提示配点赞贴纸轻弹；重复文件不触发新导入庆祝，失败保留错误提示 |
| 无搜索结果、空书架 | 空状态展示 | 疑问贴纸轻晃、欢迎贴纸轻弹，随后静止 |
| 网络/加载异常 | 首次加载失败或刷新失败 | 汗滴贴纸轻晃一次；保留准确错误原因和重试，刷新失败不移除已有内容 |
| 朗读定时结束 | `ReadAloudService` 的停止定时器到期 | 朗读按钮显示睡着贴纸，朗读面板显示结束状态和一次轻缓呼吸；不额外覆盖正文或播放提示音 |
| 关于页彩蛋 | 版本号连续点击 5 次，相邻间隔小于 800 ms | 原图标位置招手一次，2.4 秒后恢复，不增加提示标语 |

场景动画通常 900 ms，睡着贴纸为 1.6 秒。只在前台播放一次；后台中断后恢复静止。下载历史不重播庆祝，后台和阅读中完成的下载保留在下载管理中。

贴纸动画遵循「减少动态效果」和系统动画开关，开启时保留静态图案与必要状态。设置项只保留标题和开关，无具体描述。我的页另按卡片可见性暂停待机动画。

## 阅读插图查看器

- 单击插图继续控制阅读工具栏，长按或读屏的「放大查看插图」操作打开独立全屏弹层。
- 支持双指 1～5 倍缩放、单指拖动。缩放围绕触点，拖动按实际适配后的图像范围限制；窄图或横图不足以填满某个方向时，该方向保持居中。
- 双击在适配大小与 2.5 倍之间切换；底部提供放大、缩小、还原按钮与当前比例，顶部提供关闭按钮，系统返回也可关闭。
- 插图查看不调整正文列表位置；布局尺寸变化后恢复适配。网络插图与 EPUB 本地插图复用同一入口。
- 加载中显示进度，失败显示重试。解码尺寸上限 4096 × 4096，缩放不重复发起图片请求。

手势实现参考 Android 官方 [多点触控](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch) 与 [Dialog](https://developer.android.com/develop/ui/compose/components/dialog) 文档。前后台控制使用 [LifecycleResumeEffect](https://developer.android.com/topic/libraries/architecture/lifecycle#lifecycleresumeeffect)。

## 复现验证

运行 `./build.ps1 -Tasks @('assembleDebug', 'assembleDebugAndroidTest', 'testDebugUnitTest', 'lintDebug') -Offline`。

安装应用与测试 APK 后，通过 `adb shell am instrument -w -r -e class <测试类> cc.novelia.app.test/androidx.test.runner.AndroidJUnitRunner` 执行设备测试。相关类：`MidoriCompanionTest`、`ProfileStickerTest`、`IllustrationViewerTest`、`StickerFeaturesTest`、`StickerFallbackTest`、`AsyncContentTest`。系统动画关闭测试需设置测试设备动画时长为 0，完成后恢复。

测试使用临时本地文件、绘制资源和模拟下载/服务状态，不访问真实下载接口、不启动有声朗读。全流程测试结束时清理测试文件、恢复原有状态。JVM 测试额外覆盖缩放触点、横竖图拖动边界和下载完成事件去重。

## 验收记录（2026-09-12）

- Debug APK、测试 APK 构建成功；46 项 JVM 测试通过，Lint 为 0 错误、15 项现有警告。
- 13 项设备测试已覆盖贴纸点击、动效停止、减少动态效果、缺封面与坏封面兜底、插图长按/缩放/拖动/重试/关闭、阅读位置保持，以及完成提示、网络重试和关于页彩蛋。系统动画关闭的静止测试单独在动画时长为 0 时运行。
- Android 15 / API 35 模拟器检查了正常手机、约 375 dp 宽且 2 倍字号、横屏，以及浅色和深色。修复了提示框裁切贴纸和大字号/横屏朗读面板末尾按钮不可见的问题。
- 13 张资源的 SHA-256 均与原始素材一致。
- 验收图片和日志位于本地 `artifacts/sticker-review/v2-*`，辅助脚本位于 `artifacts/sticker-v2`；这些验收输出不纳入源码。
