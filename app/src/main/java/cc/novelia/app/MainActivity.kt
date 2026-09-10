package cc.novelia.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import cc.novelia.app.data.BookRef
import cc.novelia.app.ui.*
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val incoming = MutableStateFlow<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); receive(intent)
        setContent {
            val app = application as NoveliaApplication
            val state by app.store.state.collectAsStateWithLifecycle()
            val link by incoming.collectAsStateWithLifecycle()
            NoveliaTheme(state.theme) {
                val nav = rememberNavController(); val scope = rememberCoroutineScope(); val snackbar = remember { SnackbarHostState() }
                val controller = remember { AppController(app, nav, scope, snackbar) }
                LaunchedEffect(link) { link?.let { controller.openLink(it); incoming.value = null } }
                LaunchedEffect(Unit) { runCatching { app.session.refresh() } }
                val entry by nav.currentBackStackEntryAsState(); val route = entry?.destination?.route
                val roots = listOf("shelf", "discover?query={query}", "community", "profile")
                val tabs = listOf(Triple("shelf", "书架", Icons.Outlined.CollectionsBookmark), Triple("discover", "发现", Icons.Outlined.Explore), Triple("community", "社区", Icons.Outlined.Forum), Triple("profile", "我的", Icons.Outlined.PersonOutline))
                fun switchTab(target: String) { nav.navigate(target) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } }
                val showNavigation = route in roots
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 600.dp
                    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
                        if(showNavigation && !wide) NavigationBar { tabs.forEach { (target, label, icon) -> NavigationBarItem(route?.startsWith(target) == true, { switchTab(target) }, { Icon(icon, label) }, label = { Text(label) }) } }
                    }) { padding ->
                        Row(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                            if(wide && showNavigation) NavigationRail { Spacer(Modifier.height(40.dp)); tabs.forEach { (target, label, icon) -> NavigationRailItem(route?.startsWith(target) == true, { switchTab(target) }, { Icon(icon, label) }, label = { Text(label) }) } }
                            val duration = if(state.reducedMotion) 0 else 220
                            NavHost(nav, startDestination = "shelf", modifier = Modifier.weight(1f), enterTransition = { fadeIn(tween(duration)) + slideInHorizontally(tween(duration)) { it / 12 } }, exitTransition = { fadeOut(tween(duration / 2)) }, popEnterTransition = { fadeIn(tween(duration)) }, popExitTransition = { fadeOut(tween(duration)) + slideOutHorizontally(tween(duration)) { it / 12 } }) {
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
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); receive(intent) }
    private fun receive(intent: Intent) { incoming.value = intent.dataString ?: intent.getStringExtra(Intent.EXTRA_TEXT) }
}
