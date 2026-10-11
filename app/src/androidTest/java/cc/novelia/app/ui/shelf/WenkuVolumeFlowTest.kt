package cc.novelia.app.ui.shelf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.library.withVolumeParent
import cc.novelia.app.data.library.withWenkuVolumeOrder
import cc.novelia.app.data.library.withWenkuVolumes
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.files.importing.importDownloadedDocument
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WenkuVolumeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val parentRef = BookRef("wenku", "mount-series")
    private val otherRef = BookRef("wenku", "other-series")
    private val volumeRef = BookRef("local", "mount-volume-1")
    private val secondRef = BookRef("local", "mount-volume-2")
    private val disclosure = "wenku-volumes-${parentRef.key}"

    @Test fun legacyMountedVolumeProgressReturnsWithoutOpeningTheReaderAndSurvivesReload() = withShelf { app ->
        val document = LocalDocument(volumeRef.id, "第1卷 春日启程", "txt", listOf(
            LocalChapter("first", "第一章 启程", listOf("春日的旅途从这里开始。")),
            LocalChapter("second", "第二章 重逢", listOf("", "街角传来熟悉的声音。", " ", "旅人停下脚步。", "故人正在等候。", "故事仍在继续。"))
        ))
        val legacy = Position("second", index = 3, offset = 24, title = "第二章 重逢", updatedAt = 123456789L, textOffset = 8)
        val expected = legacy.copy(chapterIndex = 1, chapterCount = 2, paragraphCount = 4)
        compose.runOnIdle {
            app.store.saveDocument(document)
            app.store.update { it.withWenkuVolumes(parentRef.key, setOf(volumeRef.key))
                .copy(positions = mapOf(volumeRef.key to legacy)) }
        }
        compose.waitUntil(10_000) { app.store.state.value.positions[volumeRef.key] == expected }
        scrollTo(hasTestTag("shelf-volume-${volumeRef.key}"))
        // 书架按已到达的章节显示进度；旧锚点补齐为第 2 / 2 章后应为 100%。
        compose.onNodeWithTag("book-reading-progress-${volumeRef.key}", useUnmergedTree = true)
            .assertExists().assertContentDescriptionEquals("已读 100%")
        compose.onNodeWithText("已读 100% · 第二章 重逢").assertIsDisplayed()
        assertEquals(document, app.store.document(volumeRef.id))

        compose.onNodeWithText("本地文件").performClick()
        compose.onNodeWithText("我的书架").performClick()
        scrollTo(hasTestTag("shelf-volume-${volumeRef.key}"))
        compose.onNodeWithText("已读 100% · 第二章 重逢").assertIsDisplayed()
        runBlocking { app.store.flush() }
        val reloaded = LocalStore(compose.activity)
        assertEquals(expected, reloaded.state.value.positions[volumeRef.key])
        assertEquals(document, reloaded.document(volumeRef.id))
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        scrollTo(hasTestTag("shelf-volume-${volumeRef.key}"))
        compose.onNodeWithTag("book-reading-progress-${volumeRef.key}", useUnmergedTree = true)
            .assertExists().assertContentDescriptionEquals("已读 100%")
        assertEquals(expected, app.store.state.value.positions[volumeRef.key])
    }

    @Test fun mountReadCollapseReloadAndReassignExistingVolumes() = withShelf { app ->
        openManager()
        compose.onNodeWithTag("wenku-volume-picker").performScrollToNode(hasTestTag("mount-volume-${volumeRef.key}"))
        compose.onNodeWithTag("mount-volume-${volumeRef.key}").performClick()
        compose.onNodeWithTag("wenku-volume-picker").performScrollToNode(hasTestTag("mount-volume-${secondRef.key}"))
        compose.onNodeWithTag("mount-volume-${secondRef.key}").performClick()
        screenshot("wenku-volume-manager")
        compose.onNodeWithText("保存挂载（2）").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(2, app.store.state.value.books.count { it.parentWenkuKey == parentRef.key })
        screenshot("wenku-volumes-expanded-light")
        scrollTo(hasTestTag(disclosure))
        compose.onNodeWithTag(disclosure).assertIsDisplayed().assertTextContains("收起 2 个分卷")
        compose.onNodeWithTag(disclosure).performClick()
        compose.onNodeWithTag("shelf-volume-${volumeRef.key}").assertDoesNotExist()
        runBlocking { app.store.flush() }
        val restored = LocalStore(compose.activity).state.value
        assertFalse(restored.books.first { it.book.ref == parentRef }.volumesExpanded)
        assertEquals(parentRef.key, restored.books.first { it.book.ref == volumeRef }.parentWenkuKey)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        scrollTo(hasTestTag(disclosure))
        compose.onNodeWithTag(disclosure).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已折叠"))
        compose.onNodeWithTag(disclosure).performClick()
        scrollTo(hasTestTag("shelf-volume-${volumeRef.key}"))
        compose.onNodeWithTag("shelf-volume-${volumeRef.key}").performClick()
        compose.waitUntil(15_000) { app.store.state.value.positions[volumeRef.key]?.chapterId == "chapter" }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        scrollTo(hasContentDescription("管理 第1卷 春日启程"))
        compose.onNodeWithContentDescription("管理 第1卷 春日启程").performClick()
        compose.onNodeWithText("更换或取消挂载").performScrollTo().performClick()
        compose.onNodeWithTag("volume-parent-picker").performScrollToNode(hasTestTag("volume-parent-${otherRef.key}"))
        compose.onNodeWithTag("volume-parent-${otherRef.key}").performClick()
        compose.waitForIdle()
        assertEquals(otherRef.key, app.store.state.value.books.first { it.book.ref == volumeRef }.parentWenkuKey)
        assertEquals("chapter", app.store.state.value.positions.getValue(volumeRef.key).chapterId)

        compose.onNodeWithText("本地文件").performClick()
        scrollTo(hasContentDescription("管理 第1卷 春日启程"))
        compose.onNodeWithContentDescription("管理 第1卷 春日启程").performClick()
        compose.onNodeWithText("更换或取消挂载").performScrollTo().performClick()
        compose.onNodeWithTag("volume-parent-picker").performScrollToNode(hasText("取消挂载"))
        compose.onNodeWithText("取消挂载").performClick()
        compose.waitForIdle()
        assertNull(app.store.state.value.books.first { it.book.ref == volumeRef }.parentWenkuKey)
        assertEquals("chapter", app.store.document(volumeRef.id).chapters.single().id)
    }

    @Test fun searchFindsCollapsedVolumesAndUncheckingKeepsLocalFiles() = withShelf { app ->
        compose.runOnIdle { app.store.update { state -> state.withWenkuVolumes(parentRef.key, setOf(volumeRef.key, secondRef.key)).let { mounted ->
            mounted.copy(books = mounted.books.map { if(it.book.ref == parentRef) it.copy(volumesExpanded = false) else it }, theme = "dark")
        } } }
        compose.onNodeWithTag("local-search-toggle").performClick()
        compose.onNodeWithText("搜索书名或作者").performTextInput("春日")
        compose.onNodeWithText("搜索书名或作者").performImeAction()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("shelf-volume-${volumeRef.key}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("青空物语").assertExists()
        compose.onNodeWithTag("volume-drag-${volumeRef.key}").assertDoesNotExist()
        screenshot("wenku-volumes-search-dark")
        compose.onNodeWithTag("local-search-clear").performClick()
        compose.onNodeWithTag("local-search-toggle").performClick()
        compose.waitForIdle()
        openManager()
        compose.onNodeWithTag("wenku-volume-picker").performScrollToNode(hasTestTag("mount-volume-${secondRef.key}"))
        compose.onNodeWithTag("mount-volume-${secondRef.key}").performClick()
        compose.onNodeWithText("保存挂载（1）").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertNull(app.store.state.value.books.first { it.book.ref == secondRef }.parentWenkuKey)
        assertEquals(1, app.store.document(secondRef.id).chapters.size)
        compose.onNodeWithText("本地文件").performClick()
        scrollTo(hasText("第2卷 夏日约定"))
        compose.onNodeWithText("第2卷 夏日约定").assertIsDisplayed()
    }

    @Test fun expansionAnimatesAndReducedMotionMakesBothDirectionsImmediate() = withShelf { app ->
        compose.runOnIdle { app.store.update { state ->
            val mounted = state.withWenkuVolumes(parentRef.key, setOf(volumeRef.key))
            mounted.copy(books = mounted.books.filterNot { it.book.ref == secondRef })
        } }
        scrollTo(hasTestTag(disclosure))
        for(reduced in listOf(false, true)) {
            compose.runOnIdle { app.store.update { it.copy(reducedMotion = reduced) } }
            compose.waitForIdle()
            repeat(2) {
                val before = otherTop()
                compose.mainClock.autoAdvance = false
                try {
                    compose.onNodeWithTag(disclosure).performClick()
                    compose.mainClock.advanceTimeBy(96)
                    val during = otherTop()
                    compose.mainClock.advanceTimeBy(400)
                    val after = otherTop()
                    assertNotEquals("展开或收起应改变下方书籍位置", before, after)
                    if(reduced) assertEquals("减少动态效果应立即完成布局变化", after, during, .5f)
                    else assertTrue("下方书籍应在动画过程中平滑移动", during > minOf(before, after) && during < maxOf(before, after))
                } finally { compose.mainClock.autoAdvance = true }
            }
        }
    }

    @Test fun downloadedVolumeRetainsSourceAndImportIsIdempotent() = withShelf { app ->
        val entry = DownloadEntry("mount-import", "第3卷 秋日回信", "mount-import.txt", "https://example.com/volume", status = "已完成", sourceBook = parentRef)
        val file = File(app.store.downloadsDir, entry.fileName)
        file.writeText("第一章 回信\n窗边传来了秋天的消息。", Charsets.UTF_8)
        try {
            val ref = runBlocking { importDownloadedDocument(app.store, entry) }
            assertEquals(parentRef.key, app.store.state.value.books.first { it.book.ref == ref }.parentWenkuKey)
            app.store.savePosition(ref, Position("0", index = 1))
            assertEquals(ref, runBlocking { importDownloadedDocument(app.store, entry) })
            assertEquals(1, app.store.state.value.books.count { it.book.ref == ref })
            app.store.update { it.withVolumeParent(ref.key, null) }
            assertEquals(ref, runBlocking { importDownloadedDocument(app.store, entry) })
            assertEquals(parentRef.key, app.store.state.value.books.first { it.book.ref == ref }.parentWenkuKey)
            assertEquals(1, app.store.state.value.positions.getValue(ref.key).index)
            assertTrue(app.store.documentSource(ref.id, "txt").exists())
        } finally { file.delete() }
    }

    @Test fun dragVolumesBothWaysPersistsWithAndWithoutReducedMotion() = withShelf { app ->
        for(reduced in listOf(false, true)) {
            compose.runOnIdle { app.store.update { it.withWenkuVolumes(parentRef.key, setOf(volumeRef.key, secondRef.key))
                .withWenkuVolumeOrder(parentRef.key, listOf(volumeRef.key, secondRef.key)).copy(reducedMotion = reduced, theme = if(reduced) "dark" else "light") } }
            scrollTo(hasTestTag("volume-drag-${volumeRef.key}"))
            compose.waitForIdle()
            dragSibling(volumeRef.key, 1)
            compose.waitForIdle()
            assertEquals(listOf(secondRef.key, volumeRef.key), volumeOrder(app))
            assertTrue(app.store.state.value.positions.isEmpty())
            assertTrue(compose.onNodeWithTag("shelf-volume-${secondRef.key}").fetchSemanticsNode().boundsInRoot.top <
                compose.onNodeWithTag("shelf-volume-${volumeRef.key}").fetchSemanticsNode().boundsInRoot.top)
            screenshot(if(reduced) "wenku-volume-reorder-dark" else "wenku-volume-reorder-light")
            runBlocking { app.store.flush() }
            assertEquals(listOf(secondRef.key, volumeRef.key), LocalStore(compose.activity).state.value.books.first { it.book.ref == parentRef }.volumeOrder)
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            scrollTo(hasTestTag("volume-drag-${volumeRef.key}"))
            dragSibling(volumeRef.key, -1)
            compose.waitForIdle()
            assertEquals(listOf(volumeRef.key, secondRef.key), volumeOrder(app))
        }
        compose.onNodeWithTag("volume-drag-${volumeRef.key}").performClick()
        compose.onNodeWithText("下移一位").performClick()
        compose.waitForIdle()
        assertEquals(listOf(secondRef.key, volumeRef.key), volumeOrder(app))
    }

    @Test fun draggingAtEdgeScrollsLongListsAndCancelKeepsSavedOrder() = withShelf { app ->
        val keys = (1..18).map { "local/reorder-$it" }
        compose.runOnIdle { app.store.update { state ->
            state.copy(books = state.books.filterNot { it.book.ref.isLocal } + keys.mapIndexed { index, key ->
                SavedBook(BookCard(BookRef("local", key.substringAfter('/')), "第${index + 1}卷 长篇故事"), parentWenkuKey = parentRef.key)
            }).withWenkuVolumes(parentRef.key, keys.toSet()).withWenkuVolumeOrder(parentRef.key, keys)
        } }
        repeat(2) { attempt ->
            scrollTo(hasTestTag("volume-drag-${keys.first()}"))
            val list = compose.onNodeWithTag("shelf-books")
            val bounds = list.fetchSemanticsNode().boundsInRoot
            val handle = compose.onNodeWithTag("volume-drag-${keys.first()}").fetchSemanticsNode().boundsInRoot.center
            compose.mainClock.autoAdvance = false
            try {
                list.performTouchInput {
                    val start = handle - bounds.topLeft
                    down(start)
                    repeat(20) { step -> moveTo(Offset(start.x, start.y + (height - 20f - start.y) * (step + 1) / 20), delayMillis = 16) }
                }
                compose.mainClock.advanceTimeBy(3200)
                if(attempt == 0) {
                    list.performTouchInput { cancel() }
                    compose.mainClock.advanceTimeBy(400)
                    assertEquals(keys, volumeOrder(app))
                } else {
                    list.performTouchInput { up() }
                    compose.mainClock.advanceTimeBy(400)
                    val ordered = volumeOrder(app)
                    assertTrue("停留在边缘应自动滚动并越过初始屏幕的分卷", ordered.indexOf(keys.first()) > 6)
                    assertEquals(keys.toSet(), ordered.toSet())
                    assertEquals(keys.size, ordered.size)
                    assertTrue(app.store.state.value.books.first { it.book.ref == otherRef }.volumeOrder.isEmpty())
                }
            } finally { compose.mainClock.autoAdvance = true }
        }
        assertTrue(app.store.state.value.positions.isEmpty())
        scrollTo(hasTestTag("volume-drag-${keys.first()}"))
        val list = compose.onNodeWithTag("shelf-books")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithTag("volume-drag-${keys.first()}").fetchSemanticsNode().boundsInRoot.center
        compose.mainClock.autoAdvance = false
        try {
            list.performTouchInput {
                val start = handle - bounds.topLeft
                down(start)
                repeat(20) { step -> moveTo(Offset(start.x, start.y + (20f - start.y) * (step + 1) / 20), delayMillis = 16) }
            }
            compose.mainClock.advanceTimeBy(3600)
            list.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(400)
            assertEquals("向上拖回列表顶部应恢复原始顺序", keys, volumeOrder(app))
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun volumeOrder(app: NoveliaApplication) = app.store.state.value.books.first { it.book.ref == parentRef }.volumeOrder

    @Test fun globalEInkModeUsesVisibleOrderButtonsAndSurvivesRestart() = withShelf { app ->
        if(InstrumentationRegistry.getArguments().getString("einkLandscape") == "true") {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        }
        compose.runOnIdle { app.store.update { it.withWenkuVolumes(parentRef.key, setOf(volumeRef.key, secondRef.key))
            .copy(reader = it.reader.withEInkMode(true)) } }
        pageTo(hasTestTag("volume-down-${volumeRef.key}"))
        compose.onNodeWithTag("volume-drag-${volumeRef.key}").assertDoesNotExist()
        compose.onNodeWithTag("volume-up-${volumeRef.key}").assertIsNotEnabled()
        compose.onNodeWithTag("volume-down-${volumeRef.key}").performClick()
        compose.waitForIdle()
        assertEquals(listOf(secondRef.key, volumeRef.key), volumeOrder(app))
        assertTrue(app.store.state.value.positions.isEmpty())
        screenshot("eink-shelf-order-buttons")
        runBlocking { app.store.flush() }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertTrue(app.store.state.value.reader.eInkMode)
        assertEquals(listOf(secondRef.key, volumeRef.key), volumeOrder(app))
        pageTo(hasTestTag("volume-up-${volumeRef.key}"))
        compose.onNodeWithTag("volume-up-${volumeRef.key}").performClick()
        compose.waitForIdle()
        assertEquals(listOf(volumeRef.key, secondRef.key), volumeOrder(app))
        compose.runOnIdle { app.store.update { it.copy(reader = it.reader.withEInkMode(false)) } }
        compose.onNodeWithTag("volume-up-${volumeRef.key}").assertDoesNotExist()
        scrollTo(hasTestTag("volume-drag-${volumeRef.key}"))
        compose.onNodeWithTag("volume-drag-${volumeRef.key}").assertIsDisplayed()
    }

    private fun pageTo(matcher: SemanticsMatcher) {
        // 操作实际可见的电子纸控件；惰性预取可能提前暴露嵌套按钮的语义，
        // 但按钮尚未进入较矮的横屏视口，因此还须检查可见性。
        repeat(20) {
            if(compose.onAllNodes(hasText("上一屏") and isEnabled()).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("上一屏").performClick()
        }
        repeat(20) {
            if(compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() && compose.onNode(matcher).isDisplayed()) {
                return
            }
            compose.onNodeWithText("下一屏").assertIsEnabled().performClick()
        }
        error("翻屏后仍未找到目标分卷按钮")
    }

    private fun dragSibling(key: String, direction: Int) {
        val list = compose.onNodeWithTag("shelf-books")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithTag("volume-drag-$key").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        val distance = compose.onNodeWithTag("shelf-volume-$key").fetchSemanticsNode().size.height + 48f
        // 矮屏中让手指留在列表内，再通过边缘滚动显露相邻分卷。
        val end = (start.y + direction * distance).coerceIn(20f, bounds.height - 20f)
        compose.mainClock.autoAdvance = false
        try {
            list.performTouchInput {
                down(start)
                repeat(20) { step -> moveTo(Offset(start.x, start.y + (end - start.y) * (step + 1) / 20), delayMillis = 16) }
            }
            compose.mainClock.advanceTimeBy(1200)
            list.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(400)
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun openManager() {
        scrollTo(hasContentDescription("管理 青空物语"))
        compose.onNodeWithContentDescription("管理 青空物语").performClick()
        compose.onNodeWithText("管理挂载分卷").performScrollTo().performClick()
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onNodeWithTag("shelf-books").performScrollToNode(matcher)
        // 父书目及其展开内容可能超出较矮的横屏视口。
        compose.onNode(matcher).performScrollTo()
    }

    private fun otherTop() = compose.onNodeWithTag("shelf-book-${otherRef.key}").fetchSemanticsNode().boundsInRoot.top

    private fun withShelf(block: (NoveliaApplication) -> Unit) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("本地文件").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as NoveliaApplication
        val previous = app.store.state.value
        try {
            compose.runOnIdle {
                app.store.saveDocument(LocalDocument(volumeRef.id, "第1卷 春日启程", "txt", listOf(LocalChapter("chapter", "第一章 启程", listOf("春日的旅途从这里开始。")))))
                app.store.saveDocument(LocalDocument(secondRef.id, "第2卷 夏日约定", "txt", listOf(LocalChapter("chapter", "第一章 约定", listOf("夏日的约定仍在继续。")))))
                app.store.update { it.copy(books = listOf(
                    SavedBook(BookCard(parentRef, "青空物语", subtitle = "文库小说"), pinned = true, addedAt = 4),
                    SavedBook(BookCard(otherRef, "远山来信", subtitle = "文库小说"), addedAt = 3),
                    SavedBook(BookCard(volumeRef, "第1卷 春日启程"), addedAt = 2),
                    SavedBook(BookCard(secondRef, "第2卷 夏日约定"), addedAt = 1)
                ), positions = emptyMap(), folders = listOf("默认收藏"), theme = "light", reducedMotion = true, reader = ReaderSettings(), historyPaused = false) }
            }
            block(app)
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { app.store.update { previous } }
        }
    }

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir("screenshots"), "$name.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
