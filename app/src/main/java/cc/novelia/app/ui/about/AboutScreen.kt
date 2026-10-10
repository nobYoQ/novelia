package cc.novelia.app.ui.about
import cc.novelia.app.BuildConfig

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.catalog.ForumLinks
import cc.novelia.app.data.updates.AppReleaseChannel
import cc.novelia.app.ui.feedback.appDownloadDescription
import cc.novelia.app.ui.account.ProfileDetailList
import cc.novelia.app.ui.account.ProfileDetailCard
import cc.novelia.app.ui.account.ProfileSectionTitle
import cc.novelia.app.ui.account.ProfileMenuRow
import cc.novelia.app.ui.account.ProfileSummary
import cc.novelia.app.ui.account.ProfileDetailScreen
import cc.novelia.app.ui.feedback.AboutIdentity
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.downloadAppRelease
import cc.novelia.app.ui.theme.MotionContent

@Composable fun AboutScreen(c: AppController) {
    val downloads by c.app.appReleaseDownloads.coordinator.states.collectAsStateWithLifecycle()
    ProfileDetailScreen("帮助与关于", c::back) { padding ->
    MotionContent(Unit, Modifier.padding(padding).fillMaxSize()) {
    ProfileDetailList(Modifier.fillMaxSize()) {
        item { ProfileDetailCard { AboutIdentity() } }
        item { ProfileSectionTitle("帮助与反馈") }
        item { ProfileMenuRow("Novelia 使用教程", "账号规则、检索语法与资源说明", Icons.Outlined.HelpOutline, { c.go("article/${ForumLinks.TUTORIAL_ID}") }) }
        item { ProfileMenuRow("反馈与建议", "在 GitHub 报告客户端问题", Icons.Outlined.Forum, { c.external("https://github.com/nobYoQ/novelia/issues") }) }
        item { ProfileSectionTitle("版本与更新") }
        item { ProfileMenuRow("检查应用更新", "当前版本 ${BuildConfig.VERSION_NAME} · 从 GitHub 检查正式版", Icons.Outlined.SystemUpdate, { c.action {
            if(c.app.appUpdates.check(force = true) == null) c.message("当前已是最新正式版")
        } }) }
        item { ProfileMenuRow("下载新版本", appDownloadDescription(downloads.getValue(AppReleaseChannel.Stable), "检查并下载最新正式版"), Icons.Outlined.Download, { c.downloadAppRelease() }) }
        item { ProfileMenuRow("下载预览包", appDownloadDescription(downloads.getValue(AppReleaseChannel.Preview), "检查预览更新 · 下载前需确认不稳定提示"), Icons.Outlined.Science, { c.downloadAppRelease(preview = true) }) }
        item { ProfileMenuRow("发行说明", "查看 GitHub 发行版与更新说明", Icons.Outlined.NewReleases, { c.external(cc.novelia.app.data.updates.APP_RELEASES_URL) }) }
        item { ProfileSectionTitle("项目与许可") }
        item { ProfileMenuRow("项目源码", "查看源码与贡献指南", Icons.Outlined.Code, { c.external("https://github.com/nobYoQ/novelia") }) }
        item { ProfileMenuRow("开源许可证", "离线查看项目许可与第三方声明", Icons.Outlined.Description, { c.go("licenses") }) }
        item { ProfileMenuRow("访问原站", "n.novelia.cc", Icons.Outlined.OpenInNew, { c.external("https://n.novelia.cc") }) }
        item { ProfileSummary("关于此版本", "使用 Kotlin、Jetpack Compose 和 Material 3 构建。提供原生阅读、本地文件与社区入口。内容与账号权限由 Novelia 服务提供。") }
        item { ProfileSummary("源码授权", "项目原创代码采用 GPL-3.0。第三方组件与素材保留各自的许可和权利，详情见开源许可证。") }
    } } }
}
