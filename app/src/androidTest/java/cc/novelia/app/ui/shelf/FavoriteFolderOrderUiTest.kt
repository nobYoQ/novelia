package cc.novelia.app.ui.shelf

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.CLOUD_DEFAULT_FOLDER_ID
import cc.novelia.app.data.model.DEFAULT_FOLDER
import cc.novelia.app.data.model.DEFAULT_FOLDER_ID
import cc.novelia.app.data.model.FavoriteFolderOrder
import cc.novelia.app.data.model.FavoriteFolderSort
import cc.novelia.app.data.model.Folder
import cc.novelia.app.data.model.createShelfFolder
import cc.novelia.app.data.network.CloudWebFilter
import cc.novelia.app.data.network.cloudFolderChoices
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FavoriteFolderOrderUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localFolderMenuAppliesCreationNameAndManualOrderWithoutChangingTheSelectedFolder() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val before = app.store.state.value
        try {
            val creation = listOf(DEFAULT_FOLDER, "Zeta", "Alpha", "Beta")
            app.store.update { state ->
                creation.drop(1).fold(state.copy(books = emptyList(), folders = listOf(DEFAULT_FOLDER),
                    folderIds = mapOf(DEFAULT_FOLDER to DEFAULT_FOLDER_ID), localFavoriteFolderOrder = FavoriteFolderOrder(),
                    rememberLastShelfFolder = false)) { current, name -> current.createShelfFolder(name) }
            }
            compose.setContent {
                AppInteractionMode(eInk = false, reducedMotion = true) { NoveliaTheme("light") {
                    val nav = rememberNavController()
                    val scope = rememberCoroutineScope()
                    val c = remember(nav, scope) { AppController(app, nav, scope, SnackbarHostState()) }
                    ShelfScreen(c)
                } }
            }
            val picker = compose.onNodeWithTag("local-folder-picker")
            fun menuItem(title: String) = compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("local-folder-menu")))
            fun assertMenuOrder(vararg titles: String) {
                val tops = titles.map { menuItem(it).getUnclippedBoundsInRoot().top }
                assertTrue(tops.zipWithNext().all { (first, second) -> first < second })
            }
            picker.performClick()
            assertMenuOrder("全部收藏夹", *creation.toTypedArray())
            menuItem("Beta").performClick()
            picker.performClick()
            menuItem("收藏夹排序").performClick()
            menuItem("按名称").performClick().assertIsSelected()
            assertMenuOrder("全部收藏夹", DEFAULT_FOLDER, "Alpha", "Beta", "Zeta")
            menuItem("手动排序").performClick().assertIsSelected()
            compose.onNode(hasContentDescription("上移 Beta") and hasAnyAncestor(hasTestTag("local-folder-menu")))
                .performScrollTo().performClick()
            assertMenuOrder("全部收藏夹", DEFAULT_FOLDER, "Beta", "Alpha", "Zeta")
            assertEquals(FavoriteFolderSort.Manual, app.store.state.value.localFavoriteFolderOrder.sort)
            menuItem("按创建顺序").performScrollTo().performClick().assertIsSelected()
            assertMenuOrder("全部收藏夹", *creation.toTypedArray())
            menuItem("收藏夹排序").performClick()
            menuItem("按名称").assertDoesNotExist()
            picker.assertTextContains("Beta")
            assertEquals(creation, app.store.state.value.folders)
        } finally { app.store.update { before } }
    }

    @Test fun cloudFolderMenuOffersServerOrderAndKeepsTheAllAndDefaultEntriesFirst() {
        val default = Folder(CLOUD_DEFAULT_FOLDER_ID, "默认收藏夹")
        val remote = listOf(default, Folder("z", "Zeta"), Folder("a", "Alpha"))
        compose.setContent {
            AppInteractionMode(eInk = false, reducedMotion = true) { NoveliaTheme("light") {
                var order by remember { mutableStateOf(FavoriteFolderOrder()) }
                val folders = cloudFolderChoices(order.ordered(remote, default.id))
                CompactCloudControls(folders, default, {}, "update", {}, kind = 0, filter = CloudWebFilter(), onApply = { _, _ -> },
                    query = "", onQuery = {}, onSubmit = {}, onClearQuery = {}, expanded = false, onExpanded = {},
                    countLabel = "本页 0 本", maxHeight = 450.dp, active = true, visible = true,
                    folderOrder = order, onOrderFolders = { order = it }) { }
            } }
        }
        val picker = compose.onNodeWithTag("cloud-folder-picker")
        fun menuItem(title: String) = compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("cloud-folder-menu")))
        fun assertBefore(first: String, second: String) {
            assertTrue(menuItem(first).getUnclippedBoundsInRoot().top < menuItem(second).getUnclippedBoundsInRoot().top)
        }
        picker.performClick()
        assertBefore("Zeta", "Alpha")
        menuItem("收藏夹排序").performClick()
        menuItem("原站顺序").assertIsSelected()
        menuItem("按名称").performClick().assertIsSelected()
        picker.assertTextContains(default.title)
        assertBefore("全部收藏", default.title)
        assertBefore(default.title, "Alpha")
        assertBefore("Alpha", "Zeta")
    }
}
