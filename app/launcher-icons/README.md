# 预置桌面图标

把准备好的图片放到 **[res/drawable-nodpi](res/drawable-nodpi)**，在 **[icons.json](icons.json)** 登记后重新构建 APK。用户从「设置 → 外观与操作 → 桌面图标」预览和选择。这里的图片随安装包发布，不是手机上的运行时导入目录。

## 添加图片

1. 推荐正方形 PNG 或 WebP（例如 512×512），也支持 Android drawable XML，包括 VectorDrawable 和自适应图标。图案留出边距，不要把主要内容贴边，实际桌面形状由系统决定。
2. 文件名使用小写英文字母、数字、下划线，以字母开头。例如 `launcher_sakura.png`。同一资源名只能有一个文件，不要同时保留同名 PNG 和 XML。
3. 在 `icons.json` 数组中增加一项，`drawable` 不带扩展名：

   ```json
   { "id": "sakura", "title": "樱花", "drawable": "launcher_sakura" }
   ```

4. 在仓库根目录运行 `./build-debug.ps1` 或 `./build-release.ps1`，安装新 APK。Gradle 会检查清单并自动生成桌面入口及对应的系统启动主题；不需要手工修改 Manifest、启动主题或 Kotlin。

`id` 是安装后保持稳定的标识，只能使用小写字母、数字和下划线，以字母开头。`title` 是设置中的名称，桌面应用名称保持 Novelia。默认图标 `default` 使用现有的 `ic_launcher`。

## 星川绿素材

`rawIcon/` 保存原始素材，不直接参与资源打包。三张图片均为带透明通道的正方形；正式资源保留原始像素，以 Android 合法文件名复制到 `res/drawable-nodpi/`，使用自适应图标 XML 包装：

| 原始素材 | 尺寸 | 设置中的名称 | 稳定 id / 图标 XML |
| --- | --- | --- | --- |
| `星川绿.webp` | 512×512 | 星川绿 | `xingchuan_green` / `launcher_xingchuan_green.xml` |
| `星川绿01.png` | 1254×1254 | 星川绿·心动 | `xingchuan_green_01` / `launcher_xingchuan_green_01.xml` |
| `星川绿02.png` | 1254×1254 | 星川绿·招手 | `xingchuan_green_02` / `launcher_xingchuan_green_02.xml` |

- 图标采用脸部特写构图，背景使用与「经典绿」相同的 `#006C4C`。108dp 前景图层中的人物画布为 97.2dp，相比最初保留全图的 54dp 方案放大 1.8 倍，并向上移动 10.8dp；允许耳朵、身体和装饰被系统遮罩裁切，优先突出眼睛与表情。
- 背景和取景参数统一定义在 `res/values/launcher_icon_styles.xml`：左右各 `5%`，顶部 `-5%`，底部 `15%`。上下与左右的边距总和相等，保持原图比例；负的上边距用于向上取景。圆形、圆角方形等遮罩仍由系统应用，参照 [Android 自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。
- 原始 WebP 的边缘清晰度弱于两张 PNG，保留其原画风格；位图启用缩放过滤。PNG 主体的 alpha 大多为 254，接近完全不透明，不需要重新抠图。
- 每张图片使用 `launcher_xingchuan_green*_artwork` 资源名，`icons.json` 登记对应的 XML 名称。替换图片时更新 `rawIcon/` 和对应的 `*_artwork` 文件，保持已发布的 id 和 XML 名称稳定。
- 项目最低支持 Android 8.0（API 26），可以直接使用自适应图标；设置页通过系统 Drawable 渲染预览，兼容位图、矢量及自适应图标。

**已经发布过的 id 不能删除或改名。** Android 会跨升级保留入口启停状态，移除正在使用的入口可能使桌面找不到应用。要下架旧图标，保留该行和资源，并增加 `"hidden": true`；它不再提供给新选择，正在使用它的用户仍可启动和换回其他图标。默认图标不能隐藏。

## 切换行为

- 在前台选择只保存偏好，显示「待退到后台时切换」，可以继续改选或取消。
- 整个应用进入后台后，使用 `ProcessLifecycleOwner.ON_STOP` 执行切换；仅打开面板、暂停 Activity 或旋转屏幕不会切换。
- 待处理的选择保存在本机，进程重启后继续保留；只有再次进入后台才会应用。不随普通设置或资料备份迁移。
- Android 13 及以上原子更新入口；旧版先启用新图标，再禁用旧图标。无界面的图标入口把启动交给独立、稳定的 MainActivity 任务，避免旧入口被禁用时系统把阅读/设置任务一起移除。MainActivity 一直启用，站内链接和分享入口不受图标选择影响。失败会保留选择，在下次进入后台时重试。
- Android 12 及以上的系统启动画面跟随**已生效**的桌面图标：主界面在后台仍监听切换结果，通过 `SplashScreen.setSplashScreenTheme` 将对应主题交给系统持久化，杀进程后冷启动也使用该图标。桌面入口在转交 MainActivity 前再次同步，覆盖升级后的首次启动。前台待切换或取消选择不会提前更换启动画面；Android 8–11 保持原有窗口启动行为。
- 桌面刷新速度、图标位置和已有固定快捷方式的处理取决于桌面应用，需在目标厂商桌面上验收。此功能切换启动器图标，通知图标仍沿用原有设置。

实现：`app/build.gradle.kts` 中的 `LauncherIconManifestTask`、`LauncherSplashResourcesTask` 和 `app/src/main/java/cc/novelia/app/launcher/`。启动主题名 `Theme.Novelia.Launcher.<id>` 也必须跨版本保持稳定；Manifest 元数据直接引用主题，Release 资源压缩会保留它。入口必须在安装时存在，参见 Android 官方的 [activity-alias](https://developer.android.com/guide/topics/manifest/activity-alias-element)、[启动主题持久化](https://developer.android.com/reference/android/window/SplashScreen#setSplashScreenTheme(int))、[批量切换组件](https://developer.android.com/reference/android/content/pm/PackageManager#setComponentEnabledSettings(java.util.List)) 与 [应用进程生命周期](https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner) 文档。
