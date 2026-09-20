package cc.novelia.app.ui.about

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.MenuRow
import cc.novelia.app.ui.components.MetaParagraph
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.feedback.AboutIdentity
import cc.novelia.app.ui.navigation.AppController

@Composable fun AboutScreen(c: AppController) {
    Screen("帮助与关于", c::back) { padding -> AppLazyColumn(Modifier.padding(padding)) {
        item { AboutIdentity() }
        item { MenuRow("原站使用教程", "账号规则、检索语法与资源说明", Icons.Outlined.HelpOutline, { c.go("article/64f3d63f794cbb1321145c07") }) }
        // item { MenuRow("反馈与建议", "在 GitHub 报告客户端问题", Icons.Outlined.Forum, { c.external("https://github.com/nobYoQ/novelia/issues") }) }
        // item { MenuRow("下载新版本", "查看 GitHub 发行版与更新说明", Icons.Outlined.Download, { c.external("https://github.com/nobYoQ/novelia/releases") }) }
        // item { MenuRow("项目源码", "查看源码与贡献指南", Icons.Outlined.Code, { c.external("https://github.com/nobYoQ/novelia") }) }
        // item { MenuRow("开源许可证", "离线查看项目许可与第三方声明", Icons.Outlined.Description, { c.go("licenses") }) }
        item { MenuRow("访问原站", "n.novelia.cc", Icons.Outlined.OpenInNew, { c.external("https://n.novelia.cc") }) }
        item { MetaParagraph("关于此版本", "使用 Kotlin、Jetpack Compose 和 Material 3 构建。保留原站绿色主题，提供原生阅读、本地文件与社区入口。此版本不包含翻译中心或生成译文的任务。原站内容与账号权限由 Novelia 服务提供。") }
        // item { MetaParagraph("源码授权", "项目原创代码采用 GPL-3.0。第三方组件与贴纸保留各自的许可和权利，详情见开源许可证。") }
    } }
}
