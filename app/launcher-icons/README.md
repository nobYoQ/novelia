# 预置桌面图标

把准备好的图片放到 **[res/drawable-nodpi](res/drawable-nodpi)**，在 **[icons.json](icons.json)** 登记后重新构建 APK。用户从「设置 → 外观与操作 → 桌面图标」预览和选择。这里的图片随安装包发布，不是手机上的运行时导入目录。

## 添加图片

1. 推荐正方形 PNG 或 WebP（例如 512×512），也支持 Android VectorDrawable XML。图案留出边距，不要把主要内容贴边，实际桌面形状由系统决定。
2. 文件名使用小写英文字母、数字、下划线，以字母开头。例如 `launcher_sakura.png`。同一资源名只能有一个文件，不要同时保留同名 PNG 和 XML。
3. 在 `icons.json` 数组中增加一项，`drawable` 不带扩展名：

   ```json
   { "id": "sakura", "title": "樱花", "drawable": "launcher_sakura" }
   ```

4. 在仓库根目录运行 `./build-debug.ps1` 或 `./build-release.ps1`，安装新 APK。Gradle 会检查清单并自动生成桌面入口；不需要手工修改 Manifest 或 Kotlin。

`id` 是安装后保持稳定的标识，只能使用小写字母、数字和下划线，以字母开头。`title` 是设置中的名称，桌面应用名称保持 Novelia。默认图标 `default` 使用现有的 `ic_launcher`；示例的水墨黑、夜读蓝可以直接替换图片内容，保持资源名即可。

**已经发布过的 id 不能删除或改名。** Android 会跨升级保留入口启停状态，移除正在使用的入口可能使桌面找不到应用。要下架旧图标，保留该行和资源，并增加 `"hidden": true`；它不再提供给新选择，正在使用它的用户仍可启动和换回其他图标。默认图标不能隐藏。

## 切换行为

- 在前台选择只保存偏好，显示「待退到后台时切换」，可以继续改选或取消。
- 整个应用进入后台后，使用 `ProcessLifecycleOwner.ON_STOP` 执行切换；仅打开面板、暂停 Activity 或旋转屏幕不会切换。
- 待处理的选择保存在本机，进程重启后继续保留；只有再次进入后台才会应用。不随普通设置或资料备份迁移。
- Android 13 及以上原子更新入口；旧版先启用新图标，再禁用旧图标。无界面的图标入口把启动交给独立、稳定的 MainActivity 任务，避免旧入口被禁用时系统把阅读/设置任务一起移除。MainActivity 一直启用，站内链接和分享入口不受图标选择影响。失败会保留选择，在下次进入后台时重试。
- 桌面刷新速度、图标位置和已有固定快捷方式的处理取决于桌面应用，需在目标厂商桌面上验收。此功能切换启动器图标，通知图标仍沿用原有设置。

实现：`app/build.gradle.kts` 中的 `LauncherIconManifestTask`、`app/src/main/java/cc/novelia/app/launcher/`。入口必须在安装时存在，参见 Android 官方的 [activity-alias](https://developer.android.com/guide/topics/manifest/activity-alias-element)、[批量切换组件](https://developer.android.com/reference/android/content/pm/PackageManager#setComponentEnabledSettings(java.util.List)) 与 [应用进程生命周期](https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner) 文档。
