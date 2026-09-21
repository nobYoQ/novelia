package cc.novelia.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.ui.about.AboutScreen
import cc.novelia.app.ui.about.OpenSourceLicensesScreen
import cc.novelia.app.ui.account.LoginScreen
import cc.novelia.app.ui.account.ProfileScreen
import cc.novelia.app.ui.book.BookScreen
import cc.novelia.app.ui.book.EditBookScreen
import cc.novelia.app.ui.book.GlossaryScreen
import cc.novelia.app.ui.book.WenkuEditorScreen
import cc.novelia.app.ui.community.ArticleScreen
import cc.novelia.app.ui.community.CommunityScreen
import cc.novelia.app.ui.community.ComposeArticleScreen
import cc.novelia.app.ui.components.LocalBookSyncPresentation
import cc.novelia.app.ui.components.rememberBookSyncPresentation
import cc.novelia.app.ui.components.LocalBookListPresentation
import cc.novelia.app.ui.components.rememberBookListPresentation
import cc.novelia.app.ui.discover.DiscoverScreen
import cc.novelia.app.ui.discover.RankScreen
import cc.novelia.app.ui.downloads.DownloadsScreen
import cc.novelia.app.ui.feedback.ObserveDownloadCelebrations
import cc.novelia.app.ui.feedback.StickerSnackbarHost
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.notes.NotesScreen
import cc.novelia.app.ui.reader.ReaderScreen
import cc.novelia.app.ui.settings.BlockedScreen
import cc.novelia.app.ui.settings.CloudSyncScreen
import cc.novelia.app.ui.settings.LibraryBackupScreen
import cc.novelia.app.ui.settings.SettingsScreen
import cc.novelia.app.ui.discover.KeywordLibraryScreen
import cc.novelia.app.ui.shelf.AdaptiveLibraryScreen
import cc.novelia.app.ui.shelf.BookUpdatesScreen
import cc.novelia.app.ui.shelf.FavoriteSheet
import cc.novelia.app.ui.shelf.HistoryScreen
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.NoveliaTheme
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.tools.ToolsScreen
import cc.novelia.app.ui.web.SiteWebScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

private data class AppAppearance(val theme: String, val reducedMotion: Boolean, val eInk: Boolean, val eInkBooks: Set<String>, val showScrollPageButtons: Boolean)
private fun LibraryState.appearance() = AppAppearance(theme, reducedMotion || reader.eInkMode, reader.eInkMode, bookSettings.filterValues { it.eInkMode }.keys, reader.showScrollPageButtons)

/**
 * Android 生命周期与 Compose 界面的连接点：等待应用初始化，装配主题、控制器和导航图。
 * incoming 只保存尚未消费的外部链接；已完成的导航交给 NavController 恢复，避免旋转后重开书籍。
 * 根标签切换保存各自返回栈，详情页面则保留带不同参数的独立历史记录。
 */
class MainActivity : ComponentActivity() {
    private val incoming = MutableStateFlow<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        // Navigation restores itself. Only replay an intent that had not yet been consumed.
        if (savedInstanceState == null) receive(intent)
        else incoming.value = savedInstanceState.getString("novelia.pendingLink")
        setContent {
            val app = application as NoveliaApplication
            val initialized by produceState(false, app) { app.initialization.await(); value = true }
            ReportDrawnWhen { initialized }
            if (!initialized) {
                NoveliaTheme("system") { Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("正在打开书库…") } } }
                return@setContent
            }
            // 将完整书库投影成外观状态，阅读进度等频繁更新不会使整个导航容器随之刷新。
            val appearance by remember(app) { app.store.state.map { it.appearance() }.distinctUntilChanged() }
                .collectAsStateWithLifecycle(initialValue = remember(app) { app.store.state.value.appearance() })
            val link by incoming.collectAsStateWithLifecycle()
            AppInteractionMode(appearance.eInk, appearance.reducedMotion, appearance.showScrollPageButtons) {
            NoveliaTheme(appearance.theme) {
                val nav = rememberNavController(); val scope = rememberCoroutineScope(); val snackbar = remember { SnackbarHostState() }
                val controller = remember { AppController(app, nav, scope, snackbar) }
                val bookSync = rememberBookSyncPresentation(controller)
                val bookList = rememberBookListPresentation(controller)
                CompositionLocalProvider(LocalBookSyncPresentation provides bookSync, LocalBookListPresentation provides bookList) {
                controller.pendingFavorite?.let { book ->
                    FavoriteSheet(controller, book, initialCloud = controller.pendingFavoriteCloud) { controller.pendingFavorite = null }
                }
                val recoveryIssue by app.store.recoveryIssue.collectAsStateWithLifecycle()
                LaunchedEffect(app) { app.store.persistenceError.filterNotNull().collect { snackbar.showSnackbar(it) } }
                // 恢复保护优先于普通导航，避免损坏状态下继续执行会修改书库的页面操作。
                if(recoveryIssue != null) {
                    Scaffold(snackbarHost = { StickerSnackbarHost(snackbar) }) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) { LibraryBackupScreen(controller) }
                    }
                    return@CompositionLocalProvider
                }
                LaunchedEffect(link) { link?.let { controller.openLink(it); incoming.value = null } }
                LaunchedEffect(Unit) { runCatching { app.session.refresh() } }
                val entry by nav.currentBackStackEntryAsState(); val route = entry?.destination?.route
                ObserveDownloadCelebrations(controller, route)
                val roots = listOf("shelf", "discover?query={query}", "community", "profile")
                val tabs = listOf(Triple("shelf", "书架", Icons.Outlined.CollectionsBookmark), Triple("discover", "发现", Icons.Outlined.Explore), Triple("community", "社区", Icons.Outlined.Forum), Triple("profile", "我的", Icons.Outlined.PersonOutline))
                fun switchTab(target: String) { nav.navigate(target) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } }
                val showNavigation = route in roots
                var compactShelfDetail by remember { mutableStateOf(false) }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 600.dp
                    val compactRail = maxHeight < 480.dp
                    Scaffold(snackbarHost = { StickerSnackbarHost(snackbar) }, bottomBar = {
                        if(showNavigation && !wide && !(route == "shelf" && compactShelfDetail)) NavigationBar { tabs.forEach { (target, label, icon) ->
                            val selected = route?.startsWith(target) == true
                            NavigationBarItem(selected, { if(!selected) switchTab(target) }, { NavigationIcon(icon, label, selected) }, label = { Text(label) })
                        } }
                    }) { padding ->
                        Row(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                            if(wide && showNavigation) NavigationRail { Spacer(Modifier.height(if(compactRail) 12.dp else 40.dp)); tabs.forEach { (target, label, icon) ->
                                val selected = route?.startsWith(target) == true
                                NavigationRailItem(selected, { if(!selected) switchTab(target) }, { NavigationIcon(icon, label, selected) }, label = if(compactRail) null else { { Text(label) } })
                            } }
                            val duration = if(appReducedMotion()) 0 else AppMotion.Standard
                            val travel = with(LocalDensity.current) { 24.dp.roundToPx() }
                            fun staticReader(entry: androidx.navigation.NavBackStackEntry): Boolean = entry.destination.route?.startsWith("reader/") == true &&
                                "${entry.arguments?.getString("provider")}/${entry.arguments?.getString("id")}" in appearance.eInkBooks
                            NavHost(nav, startDestination = "shelf", modifier = Modifier.weight(1f), enterTransition = {
                                if(duration == 0 || staticReader(initialState) || staticReader(targetState)) return@NavHost EnterTransition.None
                                val from = roots.indexOf(initialState.destination.route)
                                val to = roots.indexOf(targetState.destination.route)
                                val direction = if(from >= 0 && to >= 0 && to < from) -1 else 1
                                fadeIn(tween(duration)) + slideInHorizontally(tween(duration, easing = FastOutSlowInEasing)) { direction * travel }
                            }, exitTransition = {
                                if(duration == 0 || staticReader(initialState) || staticReader(targetState)) return@NavHost ExitTransition.None
                                val from = roots.indexOf(initialState.destination.route)
                                val to = roots.indexOf(targetState.destination.route)
                                val direction = if(from >= 0 && to >= 0 && to < from) -1 else 1
                                fadeOut(tween(AppMotion.Exit)) + slideOutHorizontally(tween(duration, easing = FastOutSlowInEasing)) { -direction * travel / 2 }
                            }, popEnterTransition = {
                                if(duration == 0 || staticReader(initialState) || staticReader(targetState)) return@NavHost EnterTransition.None
                                fadeIn(tween(duration)) + slideInHorizontally(tween(duration, easing = FastOutSlowInEasing)) { -travel / 2 }
                            }, popExitTransition = {
                                if(duration == 0 || staticReader(initialState) || staticReader(targetState)) return@NavHost ExitTransition.None
                                fadeOut(tween(AppMotion.Exit)) + slideOutHorizontally(tween(duration, easing = FastOutSlowInEasing)) { travel }
                            }) {
                                composable("shelf") { AdaptiveLibraryScreen(controller) { compactShelfDetail = it } }
                                composable("discover?query={query}") { DiscoverScreen(controller, it.arguments?.getString("query").orEmpty()) }
                                composable("rank") { RankScreen(controller) }
                                composable("wenku-new") { WenkuEditorScreen(controller) }
                                composable("community") { CommunityScreen(controller) }
                                composable("profile") { ProfileScreen(controller) }
                                composable("book/{provider}/{id}") { BookScreen(controller, BookRef(it.arguments!!.getString("provider")!!, it.arguments!!.getString("id")!!)) }
                                composable("reader/{provider}/{id}/{chapter}") { ReaderScreen(controller, BookRef(it.arguments!!.getString("provider")!!, it.arguments!!.getString("id")!!), it.arguments!!.getString("chapter")!!) }
                                composable("article/{id}") { ArticleScreen(controller, it.arguments!!.getString("id")!!) }
                                composable("compose?article={article}") { ComposeArticleScreen(controller, it.arguments?.getString("article")) }
                                composable("login") { LoginScreen(controller) }
                                composable("settings") { SettingsScreen(controller) }
                                composable("backup") { LibraryBackupScreen(controller) }
                                composable("keywords") { KeywordLibraryScreen(controller) }
                                composable("sync") { CloudSyncScreen(controller) }
                                composable("updates") { BookUpdatesScreen(controller) }
                                composable("downloads") { DownloadsScreen(controller) }
                                composable("tools") { ToolsScreen(controller) }
                                // Keep saved back stacks from the removed feature restorable after an update.
                                composable("ocr") {
                                    LaunchedEffect(Unit) {
                                        nav.navigate("tools") { popUpTo("ocr") { inclusive = true }; launchSingleTop = true }
                                    }
                                }
                                composable("notes") { NotesScreen(controller) }
                                composable("blocked") { BlockedScreen(controller) }
                                composable("history") { HistoryScreen(controller) }
                                composable("glossary/{provider}/{id}") { GlossaryScreen(controller, BookRef(it.arguments!!.getString("provider")!!, it.arguments!!.getString("id")!!)) }
                                composable("edit/{provider}/{id}") { EditBookScreen(controller, BookRef(it.arguments!!.getString("provider")!!, it.arguments!!.getString("id")!!)) }
                                composable("about") { AboutScreen(controller) }
                                composable("licenses") { OpenSourceLicensesScreen(controller) }
                                composable("web?url={url}") { SiteWebScreen(controller, it.arguments?.getString("url").orEmpty()) }
                            }
                        }
                    }
                }
                }
            }
            }
        }
    }
    override fun onStop() { super.onStop(); (application as NoveliaApplication).persistState() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("novelia.pendingLink", incoming.value)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    private fun receive(intent: Intent) { incoming.value = intent.dataString ?: intent.getStringExtra(Intent.EXTRA_TEXT) }
}

@Composable private fun NavigationIcon(icon: ImageVector, label: String, selected: Boolean) {
    val reducedMotion = appReducedMotion()
    val emphasis = animateFloatAsState(
        targetValue = if(selected) 1f else 0f,
        animationSpec = tween(if(reducedMotion) 0 else AppMotion.Release, easing = FastOutSlowInEasing),
        label = "navigation selection",
    )
    Icon(icon, label, Modifier.graphicsLayer {
        val scale = if(reducedMotion) 1f else 1f + .08f * emphasis.value
        scaleX = scale
        scaleY = scale
    })
}
