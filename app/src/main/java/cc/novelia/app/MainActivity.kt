package cc.novelia.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import cc.novelia.app.data.BookRef
import cc.novelia.app.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    private val incoming = MutableStateFlow<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); receive(intent)
        setContent {
            val app = application as NoveliaApplication
            val initialized by produceState(false, app) { app.initialization.await(); value = true }
            if (!initialized) {
                NoveliaTheme("system") { Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() } } }
                return@setContent
            }
            val appearance by remember(app) { app.store.state.map { it.theme to it.reducedMotion }.distinctUntilChanged() }
                .collectAsStateWithLifecycle(initialValue = remember(app) { app.store.state.value.let { it.theme to it.reducedMotion } })
            val link by incoming.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalReducedMotion provides appearance.second) {
            NoveliaTheme(appearance.first) {
                val nav = rememberNavController(); val scope = rememberCoroutineScope(); val snackbar = remember { SnackbarHostState() }
                val controller = remember { AppController(app, nav, scope, snackbar) }
                LaunchedEffect(app) { app.store.persistenceError.filterNotNull().collect { snackbar.showSnackbar(it) } }
                LaunchedEffect(link) { link?.let { controller.openLink(it); incoming.value = null } }
                LaunchedEffect(Unit) { runCatching { app.session.refresh() } }
                val entry by nav.currentBackStackEntryAsState(); val route = entry?.destination?.route
                ObserveDownloadCelebrations(controller, route)
                val roots = listOf("shelf", "discover?query={query}", "community", "profile")
                val tabs = listOf(Triple("shelf", "书架", Icons.Outlined.CollectionsBookmark), Triple("discover", "发现", Icons.Outlined.Explore), Triple("community", "社区", Icons.Outlined.Forum), Triple("profile", "我的", Icons.Outlined.PersonOutline))
                fun switchTab(target: String) { nav.navigate(target) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } }
                val showNavigation = route in roots
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 600.dp
                    Scaffold(snackbarHost = { StickerSnackbarHost(snackbar) }, bottomBar = {
                        if(showNavigation && !wide) NavigationBar { tabs.forEach { (target, label, icon) ->
                            val selected = route?.startsWith(target) == true
                            NavigationBarItem(selected, { if(!selected) switchTab(target) }, { NavigationIcon(icon, label, selected) }, label = { Text(label) })
                        } }
                    }) { padding ->
                        Row(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                            if(wide && showNavigation) NavigationRail { Spacer(Modifier.height(40.dp)); tabs.forEach { (target, label, icon) ->
                                val selected = route?.startsWith(target) == true
                                NavigationRailItem(selected, { if(!selected) switchTab(target) }, { NavigationIcon(icon, label, selected) }, label = { Text(label) })
                            } }
                            val duration = if(appearance.second) 0 else 220
                            val travel = with(LocalDensity.current) { 24.dp.roundToPx() }
                            NavHost(nav, startDestination = "shelf", modifier = Modifier.weight(1f), enterTransition = {
                                val from = roots.indexOf(initialState.destination.route)
                                val to = roots.indexOf(targetState.destination.route)
                                val direction = if(from >= 0 && to >= 0 && to < from) -1 else 1
                                fadeIn(tween(duration)) + slideInHorizontally(tween(duration, easing = FastOutSlowInEasing)) { direction * travel }
                            }, exitTransition = {
                                val from = roots.indexOf(initialState.destination.route)
                                val to = roots.indexOf(targetState.destination.route)
                                val direction = if(from >= 0 && to >= 0 && to < from) -1 else 1
                                fadeOut(tween(duration * 2 / 3)) + slideOutHorizontally(tween(duration, easing = FastOutSlowInEasing)) { -direction * travel / 2 }
                            }, popEnterTransition = {
                                fadeIn(tween(duration)) + slideInHorizontally(tween(duration, easing = FastOutSlowInEasing)) { -travel / 2 }
                            }, popExitTransition = {
                                fadeOut(tween(duration * 2 / 3)) + slideOutHorizontally(tween(duration, easing = FastOutSlowInEasing)) { travel }
                            }) {
                                composable("shelf") { ShelfScreen(controller) }
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
                                composable("downloads") { DownloadsScreen(controller) }
                                composable("tools") { ToolsScreen(controller) }
                                composable("notes") { NotesScreen(controller) }
                                composable("blocked") { BlockedScreen(controller) }
                                composable("history") { HistoryScreen(controller) }
                                composable("glossary/{provider}/{id}") { GlossaryScreen(controller, BookRef(it.arguments!!.getString("provider")!!, it.arguments!!.getString("id")!!)) }
                                composable("edit/{provider}/{id}") { EditBookScreen(controller, BookRef(it.arguments!!.getString("provider")!!, it.arguments!!.getString("id")!!)) }
                                composable("about") { AboutScreen(controller) }
                            }
                        }
                    }
                }
            }
            }
        }
    }
    override fun onStop() { super.onStop(); (application as NoveliaApplication).persistState() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); receive(intent) }
    private fun receive(intent: Intent) { incoming.value = intent.dataString ?: intent.getStringExtra(Intent.EXTRA_TEXT) }
}

@Composable private fun NavigationIcon(icon: ImageVector, label: String, selected: Boolean) {
    val reducedMotion = LocalReducedMotion.current
    val emphasis = animateFloatAsState(
        targetValue = if(selected) 1f else 0f,
        animationSpec = tween(if(reducedMotion) 0 else 180, easing = FastOutSlowInEasing),
        label = "navigation selection",
    )
    Icon(icon, label, Modifier.graphicsLayer {
        val scale = if(reducedMotion) 1f else 1f + .08f * emphasis.value
        scaleX = scale
        scaleY = scale
    })
}
