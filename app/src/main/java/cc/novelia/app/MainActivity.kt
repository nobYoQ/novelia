package cc.novelia.app

import cc.novelia.app.ui.community.ForumStrikesScreen
import cc.novelia.app.ui.community.ForumRulesScreen
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.launcher.observeLauncherSplashScreen
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
import cc.novelia.app.ui.navigation.RootDestinationLayout
import cc.novelia.app.ui.navigation.switchRootTab
import cc.novelia.app.ui.navigation.ObserveClipboardLinks
import cc.novelia.app.ui.notes.NotesScreen
import cc.novelia.app.ui.reader.ReaderScreen
import cc.novelia.app.ui.settings.BlockedScreen
import cc.novelia.app.ui.settings.CloudSyncScreen
import cc.novelia.app.ui.settings.WebDavSyncScreen
import cc.novelia.app.ui.settings.WebDavServerScreen
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
import kotlinx.coroutines.delay
import cc.novelia.app.startup.StartupScreen

private data class AppAppearance(val theme: String, val reducedMotion: Boolean, val eInk: Boolean, val eInkBooks: Set<String>,
    val screenButtons: Boolean, val hideStatusBar: Boolean, val bookStatusBars: Map<String, Boolean>)
private fun LibraryState.appearance() = AppAppearance(theme, reducedMotion || reader.eInkMode, reader.eInkMode,
    bookSettings.filterValues { it.eInkMode }.keys, reader.showEInkScreenButtons, reader.hideStatusBar, bookSettings.mapValues { it.value.hideStatusBar })

/**
 * Android 生命周期与 Compose 界面的连接点：等待应用初始化，装配主题、控制器和导航图。
 * incoming 只保存尚未消费的外部链接；已完成的导航交给 NavController 恢复，避免旋转后重开书籍。
 * 根标签切换保存各自返回栈，详情页面则保留带不同参数的独立历史记录。
 */
class MainActivity : ComponentActivity() {
    private val incoming = MutableStateFlow<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeLauncherSplashScreen((application as NoveliaApplication).launcherIcons)
        enableEdgeToEdge()
        // 导航栈自行恢复，只重放尚未消费的外部意图。
        if (savedInstanceState == null) receive(intent)
        else incoming.value = savedInstanceState.getString("novelia.pendingLink")
        setContent {
            val app = application as NoveliaApplication
            val startup by app.startup.progress.collectAsStateWithLifecycle()
            val initialized = startup.ready
            ReportDrawnWhen { initialized }
            if (!initialized) {
                NoveliaTheme("system") { StartupScreen(startup, app.startup::retry) }
                return@setContent
            }
            // 将完整书库投影成外观状态，阅读进度等频繁更新不会使整个导航容器随之刷新。
            val appearance by remember(app) { app.store.state.map { it.appearance() }.distinctUntilChanged() }
                .collectAsStateWithLifecycle(initialValue = remember(app) { app.store.state.value.appearance() })
            val link by incoming.collectAsStateWithLifecycle()
            AppInteractionMode(appearance.eInk, appearance.reducedMotion) {
            NoveliaTheme(appearance.theme) {
                val nav = rememberNavController(); val scope = rememberCoroutineScope(); val snackbar = remember { SnackbarHostState() }
                val source by app.bookSources.state.collectAsStateWithLifecycle()
                val controller = remember(source.revision) { AppController(app, nav, scope, snackbar) }
                key(source.revision) {
                val bookSync = rememberBookSyncPresentation(controller)
                val bookList = rememberBookListPresentation(controller)
                CompositionLocalProvider(LocalBookSyncPresentation provides bookSync, LocalBookListPresentation provides bookList,
                    cc.novelia.app.ui.theme.LocalScreenPageButtons provides appearance.screenButtons) {
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
                LaunchedEffect(app) {
                    val binding = app.session.capture()
                    val token = app.session.tokenFor(binding)
                    // 游客启动不初始化 WebView Cookie；已有账号的续期让首屏先完成绘制。
                    if(token != null) {
                        delay(2_000)
                        runCatching { app.session.refreshIfCurrent(binding, token) }
                    }
                }
                val entry by nav.currentBackStackEntryAsState(); val route = entry?.destination?.route
                val readingKey = "${entry?.arguments?.getString("provider")}/${entry?.arguments?.getString("id")}"
                cc.novelia.app.ui.reader.ReadingStatusBar(route?.startsWith("reader/") == true &&
                    (appearance.bookStatusBars[readingKey] ?: appearance.hideStatusBar))
                ObserveDownloadCelebrations(controller, route)
                cc.novelia.app.ui.feedback.ObserveAppUpdates(controller, show = route?.startsWith("reader/") != true)
                ObserveClipboardLinks(controller, externalLinkPending = link != null)
                val roots = listOf("shelf", "discover?query={query}", "community", "profile")
                fun switchTab(target: String) { nav.switchRootTab(target) }
                var navigationBarHeight by remember { mutableStateOf(0.dp) }
                fun reportNavigationBarHeight(root: String): (Dp) -> Unit = { height ->
                    if(route?.startsWith(root) == true) navigationBarHeight = height
                }
                Scaffold(snackbarHost = {
                    Box(Modifier.padding(bottom = if(route in roots) navigationBarHeight else 0.dp)) {
                        StickerSnackbarHost(snackbar)
                    }
                }) { padding ->
                    // NavHost 的尺寸不随当前路由改变；各根页面自己持有侧栏/底栏。
                    val bottomPadding = PaddingValues(bottom = padding.calculateBottomPadding())
                    Box(Modifier.fillMaxSize().padding(bottomPadding).consumeWindowInsets(bottomPadding)) {
                        val duration = if(appReducedMotion()) 0 else AppMotion.Standard
                        val travel = with(LocalDensity.current) { 24.dp.roundToPx() }
                        fun staticReader(entry: androidx.navigation.NavBackStackEntry): Boolean = entry.destination.route?.startsWith("reader/") == true &&
                            "${entry.arguments?.getString("provider")}/${entry.arguments?.getString("id")}" in appearance.eInkBooks
                        NavHost(nav, startDestination = "shelf", modifier = Modifier.fillMaxSize(), enterTransition = {
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
                            composable("shelf") { entry ->
                                var compactShelfDetail by rememberSaveable { mutableStateOf(false) }
                                RootDestinationLayout("shelf", ::switchTab, hideBottomNavigation = compactShelfDetail,
                                    onNavigationBarHeightChanged = reportNavigationBarHeight("shelf")) {
                                    AdaptiveLibraryScreen(controller, entry.savedStateHandle) { compactShelfDetail = it }
                                }
                            }
                            composable("discover?query={query}") { entry ->
                                RootDestinationLayout("discover", ::switchTab,
                                    onNavigationBarHeightChanged = reportNavigationBarHeight("discover")) {
                                    DiscoverScreen(controller, entry.arguments?.getString("query").orEmpty())
                                }
                            }
                            composable("rank") { RankScreen(controller) }
                            composable("wenku-new") { WenkuEditorScreen(controller) }
                            composable("community") {
                                RootDestinationLayout("community", ::switchTab,
                                    onNavigationBarHeightChanged = reportNavigationBarHeight("community")) { CommunityScreen(controller) }
                            }
                            composable("profile") {
                                RootDestinationLayout("profile", ::switchTab,
                                    onNavigationBarHeightChanged = reportNavigationBarHeight("profile")) { ProfileScreen(controller) }
                            }
                            composable("book/{provider}/{id}") { BookScreen(controller, BookRef(it.arguments!!.getString("provider")!!, it.arguments!!.getString("id")!!)) }
                            composable("reader/{provider}/{id}/{chapter}") { ReaderScreen(controller, BookRef(it.arguments!!.getString("provider")!!, it.arguments!!.getString("id")!!), it.arguments!!.getString("chapter")!!) }
                            composable("article/{id}") { ArticleScreen(controller, it.arguments!!.getString("id")!!, it.savedStateHandle) }
                            composable("compose?article={article}&draft={draft}") { ComposeArticleScreen(controller, it.arguments?.getString("article"), it.arguments?.getString("draft")) }
                            composable("login") { LoginScreen(controller) }
                            composable("forum-login") { LoginScreen(controller, forum = true) }
                            composable("forum-strikes") { ForumStrikesScreen(controller) }
                            composable("forum-rules") { ForumRulesScreen(controller) }
                            composable("settings?section={section}") { entry -> SettingsScreen(controller, entry.arguments?.getString("section")) }
                            composable("backup") { LibraryBackupScreen(controller) }
                            composable("keywords") { KeywordLibraryScreen(controller) }
                            composable("sync") { CloudSyncScreen(controller) }
                            // 外层已留出导航栏空间；同步页面本身也可独立处理系统边距。
                            composable("webdav") { Box(Modifier.consumeWindowInsets(WindowInsets.navigationBars)) { WebDavSyncScreen(controller) } }
                            composable("webdav-server") { Box(Modifier.consumeWindowInsets(WindowInsets.navigationBars)) { WebDavServerScreen(controller) } }
                            composable("updates") { BookUpdatesScreen(controller) }
                            composable("downloads") { DownloadsScreen(controller) }
                            composable("tools") { ToolsScreen(controller) }
                            // 保留已移除功能的路由，使升级前保存的返回栈仍能恢复。
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
