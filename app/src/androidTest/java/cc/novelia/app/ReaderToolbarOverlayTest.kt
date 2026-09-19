package cc.novelia.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderToolbarOverlayTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun automaticPagesKeepExactTextPageCountAndAnchorUnderOverlays() = verifyOverlay("auto")

    @Test fun continuousScrollingKeepsTextCoordinatesAndPositionUnderOverlays() = verifyOverlay("scroll")

    @Test fun continuousScrollingKeepsToolbarHiddenAcrossChapters() = verifyChapterNavigation(ReaderSettings(paginationMode = "scroll"))

    @Test fun automaticPagesKeepToolbarHiddenAcrossChapters() = verifyChapterNavigation(ReaderSettings(paginationMode = "auto"))

    @Test fun eInkPagesKeepToolbarHiddenAcrossChapters() = verifyChapterNavigation(ReaderSettings().withEInkMode(true))

    @Test fun eInkScrollingKeepsToolbarHiddenAcrossChapters() = verifyChapterNavigation(ReaderSettings().withEInkMode(true).withPaginationMode("scroll"))

    @Test fun automaticPageProgressStaysVisibleWithoutButtonsAndResetsAcrossChapters() = withReader(
        ReaderSettings(paginationMode = "auto", showPageButtons = false, scrollPageTurn = true),
        listOf(
            LocalChapter("first", "第一章 长途", listOf("旅人沿着森林小路前行，寻找远处的小镇。".repeat(100))),
            LocalChapter("second", "第二章 归来", listOf("星光照亮归途，旅人在小镇停下脚步。".repeat(40)))
        )
    ) { app, ref ->
        val firstPageCount = pageNumbers().second
        assertTrue("测试首章应超过一页", firstPageCount > 1)
        assertChapterProgress(1, firstPageCount)
        compose.onNodeWithText("下一页").assertDoesNotExist()
        compose.onNodeWithTag("reader-page").performTouchInput { click(center) }
        assertToolbarHidden()
        assertChapterProgress(1, firstPageCount)
        screenshot("reader-progress-hidden")

        for(expectedPage in 2..firstPageCount) {
            compose.onNodeWithTag("reader-page").performTouchInput {
                swipe(start = Offset(centerX, height * .7f), end = Offset(centerX, height * .3f))
            }
            compose.waitUntil(10_000) { pageNumbers().first == expectedPage }
            assertChapterProgress(expectedPage, firstPageCount)
            assertToolbarHidden()
        }
        compose.onNodeWithTag("reader-chapter-progress").assertTextEquals("本章 100%")
        compose.onNodeWithTag("reader-page").performTouchInput {
            swipe(start = Offset(centerX, height * .7f), end = Offset(centerX, height * .3f))
        }
        waitForChapter(app, ref, "second")
        compose.waitUntil(10_000) { pageNumbers().first == 1 }
        val secondPageCount = pageNumbers().second
        assertTrue("不同长度的章节应重新计算总页数", secondPageCount < firstPageCount)
        assertChapterProgress(1, secondPageCount)
        assertToolbarHidden()
        compose.onNodeWithText("下一页").assertDoesNotExist()
    }

    @Test fun continuousScrollingTurnsChapterOnlyAfterReleasingAnExtraPullAtTheEnd() = withReader(
        ReaderSettings(paginationMode = "scroll", showPageButtons = false),
        listOf(
            LocalChapter("first", "第一章 长途", List(30) { "第 ${it + 1} 段，旅人沿着森林小路前行。".repeat(8) }),
            LocalChapter("second", "第二章 归来", listOf("星光照亮归途。")),
            LocalChapter("third", "第三章 新的旅程", listOf("新的故事开始了。"))
        )
    ) { app, ref ->
        compose.onNodeWithTag("reader-scroll").performTouchInput { click(center) }
        assertToolbarHidden()
        scrollToChapterEnd()
        compose.onNodeWithText("阅读下一章").assertIsDisplayed()
        assertChapterUnchanged(app, ref, "first")

        val density = compose.activity.resources.displayMetrics.density
        val pull = 120f * density
        compose.onNodeWithTag("reader-scroll").performTouchInput {
            down(center)
            moveBy(Offset(0f, -pull), delayMillis = 200)
            moveBy(Offset(0f, 16f * density), delayMillis = 200)
            up()
        }
        assertChapterUnchanged(app, ref, "first")
        scrollToChapterEnd()
        compose.onNodeWithTag("reader-scroll").performTouchInput {
            down(center)
            moveBy(Offset(0f, -pull), delayMillis = 300)
            up()
        }
        waitForChapter(app, ref, "second")
        assertChapterUnchanged(app, ref, "second")
        assertToolbarHidden()
    }

    @Test fun shortChaptersIgnoreShortReverseAndCancelledPullsAndStopAtTheLastChapter() = withReader(
        ReaderSettings(paginationMode = "scroll", showPageButtons = false),
        listOf(
            LocalChapter("first", "第一章 短章", listOf("短章正文。")),
            LocalChapter("second", "第二章 终章", listOf("故事到这里结束。"))
        )
    ) { app, ref ->
        compose.onNodeWithText("短章正文。", substring = true).performClick()
        assertToolbarHidden()
        scrollToChapterEnd()
        assertChapterUnchanged(app, ref, "first")
        val density = compose.activity.resources.displayMetrics.density
        val body = compose.onNodeWithTag("reader-scroll")
        body.performTouchInput {
            down(center)
            moveBy(Offset(0f, -48f * density), delayMillis = 300)
            up()
        }
        assertChapterUnchanged(app, ref, "first")
        body.performTouchInput {
            down(center)
            moveBy(Offset(0f, 120f * density), delayMillis = 300)
            up()
        }
        assertChapterUnchanged(app, ref, "first")
        body.performTouchInput {
            down(center)
            moveBy(Offset(0f, -120f * density), delayMillis = 300)
            cancel()
        }
        assertChapterUnchanged(app, ref, "first")
        body.performTouchInput {
            down(center)
            moveBy(Offset(0f, -96f * density), delayMillis = 200)
            moveTo(center, delayMillis = 200)
            up()
        }
        assertChapterUnchanged(app, ref, "first")
        body.performTouchInput {
            down(center)
            moveBy(Offset(0f, -120f * density), delayMillis = 200)
            moveBy(Offset(0f, 16f * density), delayMillis = 200)
            up()
        }
        assertChapterUnchanged(app, ref, "first")
        body.performTouchInput {
            down(center)
            moveBy(Offset(0f, -120f * density), delayMillis = 200)
            down(pointerId = 1, position = center + Offset(40f * density, 0f))
            up(pointerId = 1)
            up()
        }
        assertChapterUnchanged(app, ref, "first")
        body.performTouchInput {
            down(center)
            moveBy(Offset(0f, -120f * density), delayMillis = 300)
            up()
        }
        waitForChapter(app, ref, "second")
        assertToolbarHidden()
        compose.onNodeWithTag("reader-scroll").performTouchInput {
            down(center)
            moveBy(Offset(0f, -120f * density), delayMillis = 300)
            up()
        }
        assertChapterUnchanged(app, ref, "second")
        compose.onNodeWithText("阅读下一章").assertDoesNotExist()
    }

    private fun verifyChapterNavigation(settings: ReaderSettings) = withReader(settings.copy(showPageButtons = settings.staticPagination), listOf(
        LocalChapter("first", "第一章 林间旅途", listOf("旅人沿着森林小路前行，寻找远处的小镇。".repeat(60))),
        LocalChapter("second", "第二章 归来", listOf("星光照亮归途。")),
        LocalChapter("third", "第三章 新的旅程", listOf("新的故事开始了。"))
    )) { app, ref ->
        val bodyTag = if(settings.staticPagination) "reader-page" else "reader-scroll"
        compose.onNodeWithContentDescription("阅读设置").assertIsDisplayed()
        compose.onNodeWithTag(bodyTag).performTouchInput { click(center) }
        assertToolbarHidden()

        if(settings.staticPagination) {
            val pageCount = pageCounter().substringAfter('/').trim().toInt()
            repeat(pageCount) { compose.onNodeWithText("下一页").performClick() }
        } else {
            compose.onNodeWithTag(bodyTag).performScrollToNode(hasText("阅读下一章"))
            compose.onNodeWithText("阅读下一章").performClick()
        }
        waitForChapter(app, ref, "second")
        assertToolbarHidden()

        if(settings.staticPagination) {
            compose.onNodeWithText("上一页").performClick()
            waitForChapter(app, ref, "first")
            assertToolbarHidden()
            val pages = pageCounter().split('/').map { it.trim().toInt() }
            assertEquals("向前跨章应落在上一章末页", pages[1], pages[0])
            compose.onNodeWithText("下一页").performClick()
            waitForChapter(app, ref, "second")
            assertToolbarHidden()
        }

        // Explicitly showing the toolbar still works, and chapter buttons retain that state.
        if(settings.staticPagination) compose.onNodeWithTag(bodyTag).performTouchInput { click(center) }
        else compose.onNodeWithText("星光照亮归途。", substring = true).performClick()
        compose.onNodeWithContentDescription("阅读设置").assertIsDisplayed()
        compose.onNodeWithContentDescription("上一章").performClick()
        waitForChapter(app, ref, "first")
        compose.onNodeWithContentDescription("阅读设置").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一章").performClick()
        waitForChapter(app, ref, "second")
        compose.onNodeWithContentDescription("阅读设置").assertIsDisplayed()
        compose.onNodeWithText("目录", substring = true).performClick()
        compose.onNodeWithText("第三章 新的旅程").performClick()
        waitForChapter(app, ref, "third")
        compose.onNodeWithContentDescription("阅读设置").assertIsDisplayed()
    }

    private fun assertToolbarHidden() {
        compose.waitForIdle()
        compose.onNodeWithTag("reader-top-toolbar").assertDoesNotExist()
        compose.onNodeWithContentDescription("阅读设置").assertDoesNotExist()
        compose.onNodeWithContentDescription("下一章").assertDoesNotExist()
    }

    private fun waitForChapter(app: NoveliaApplication, ref: BookRef, chapterId: String) {
        compose.waitUntil(15_000) { app.store.state.value.positions[ref.key]?.chapterId == chapterId }
        compose.waitForIdle()
    }

    private fun assertChapterUnchanged(app: NoveliaApplication, ref: BookRef, chapterId: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(chapterId, app.store.state.value.positions[ref.key]?.chapterId) }
    }

    private fun scrollToChapterEnd() {
        compose.onNodeWithTag("reader-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
    }

    private fun pageNumbers(): Pair<Int, Int> = pageCounter().split('/').map { it.trim().toInt() }.let { it[0] to it[1] }

    private fun assertChapterProgress(page: Int, total: Int) {
        compose.onNodeWithTag("reader-page-counter").assertIsDisplayed().assertTextEquals("$page / $total")
        compose.onNodeWithTag("reader-chapter-progress").assertIsDisplayed().assertTextEquals("本章 ${page * 100 / total}%")
    }

    private fun verifyOverlay(mode: String) = withReader(ReaderSettings(paginationMode = mode, showPageButtons = true)) { app, ref ->
        val bodyTag = if(mode == "auto") "reader-page" else "reader-scroll"
        val next = if(mode == "auto") "下一页" else "下一屏"
        // Verify both the chapter start and a position inside a long paragraph.
        repeat(2) { page ->
            if(page > 0) {
                compose.onNodeWithText(next).performClick()
                compose.waitUntil(10_000) { app.store.state.value.positions.getValue(ref.key).let { it.index > 0 && (mode == "scroll" || it.textOffset > 0) } }
            }
            val before = snapshot(bodyTag)
            val counter = if(mode == "auto") pageCounter() else null
            val anchor = app.store.state.value.positions.getValue(ref.key)
            for(reduced in listOf(false, true)) {
                compose.runOnIdle { app.store.update { it.copy(reducedMotion = reduced) } }
                repeat(2) {
                    compose.mainClock.autoAdvance = false
                    try {
                        compose.onNodeWithTag(bodyTag).performTouchInput { click(center) }
                        compose.mainClock.advanceTimeBy(96)
                        assertEquals("正文在工具栏动画途中不得移动或重新分页", before, snapshot(bodyTag))
                        compose.mainClock.advanceTimeBy(400)
                    } finally { compose.mainClock.autoAdvance = true }
                    assertEquals(before, snapshot(bodyTag))
                    if(counter != null) assertEquals(counter, pageCounter())
                    assertAnchorEquals(anchor, app.store.state.value.positions.getValue(ref.key))
                    if(it == 0) compose.onNodeWithContentDescription("阅读设置").assertDoesNotExist()
                    else compose.onNodeWithContentDescription("阅读设置").assertIsDisplayed()
                }
            }
            for(transparency in listOf(0f, .6f, 1f)) {
                compose.runOnIdle { app.store.update { it.copy(reader = it.reader.copy(toolbarTransparency = transparency)) } }
                assertEquals(before, snapshot(bodyTag))
                if(counter != null) assertEquals(counter, pageCounter())
            }
            compose.runOnIdle { app.store.update { it.copy(reader = it.reader.copy(showPageButtons = false)) } }
            assertEquals(before, snapshot(bodyTag))
            compose.onNodeWithText(next).assertDoesNotExist()
            compose.onNodeWithContentDescription("搜索本章").performClick()
            compose.onNodeWithText("搜索本章段落").assertIsDisplayed()
            assertEquals("展开搜索栏不得压缩正文", before, snapshot(bodyTag))
            compose.onNodeWithText("搜索本章段落").performClick().performTextInput("测试")
            assertEquals("搜索输入不得改变正文布局", before, snapshot(bodyTag))
            compose.onNodeWithContentDescription("搜索本章").performClick()
            compose.runOnIdle { app.store.update { it.copy(reader = it.reader.copy(showPageButtons = true, toolbarTransparency = .25f)) } }
            assertEquals(before, snapshot(bodyTag))
            assertAnchorEquals(anchor, app.store.state.value.positions.getValue(ref.key))
        }
        screenshot("toolbar-overlay-$mode")
    }

    @Test fun transparencySliderPersistsPerBookWithoutChangingGlobalDefaultsOrPage() =
        withReader(ReaderSettings().withEInkMode(true)) { app, ref ->
            compose.onNodeWithText("下一页").performClick()
            compose.waitUntil(10_000) { (app.store.state.value.positions[ref.key]?.textOffset ?: 0) > 0 }
            val before = snapshot("reader-page")
            val counter = pageCounter()
            compose.onNodeWithContentDescription("阅读设置").performClick()
            compose.onNodeWithText("仅应用于这本书").performClick()
            val slider = compose.onNodeWithTag("reader-toolbar-transparency")
            slider.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(.6f) }
            compose.onNodeWithText("工具栏透明度 60%").assertIsDisplayed()
            screenshot("toolbar-transparency-preference")
            compose.onNodeWithText("关闭面板").performClick()
            assertEquals(before, snapshot("reader-page"))
            assertEquals(counter, pageCounter())
            compose.runOnIdle {
                assertEquals(.25f, app.store.state.value.reader.toolbarTransparency, 0f)
                assertEquals(.6f, app.store.state.value.bookSettings.getValue(ref.key).toolbarTransparency, .001f)
            }
            runBlocking { app.store.flush() }
            val persisted = LocalStore(compose.activity).state.value
            assertEquals(.6f, persisted.bookSettings.getValue(ref.key).toolbarTransparency, .001f)
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("工具栏覆盖测试").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("reader-page-counter").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(before, snapshot("reader-page"))
            assertEquals(counter, pageCounter())
            compose.onNodeWithContentDescription("阅读设置").performClick()
            compose.onNodeWithTag("reader-toolbar-transparency").performScrollTo()
                .assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(.6f, 0f..1f))
            compose.onNodeWithText("仅应用于这本书").performScrollTo().performClick()
            compose.onNodeWithTag("reader-toolbar-transparency").performScrollTo()
                .assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(.25f, 0f..1f))
            compose.onNodeWithText("关闭面板").performClick()
        }

    private fun withReader(settings: ReaderSettings, chapters: List<LocalChapter> = listOf(
        LocalChapter("first", "第一章 林间旅途", List(6) { paragraph ->
            (1..80).joinToString("") { "第${paragraph + 1}段第${it}句，旅人沿着森林小路前行，寻找远处的小镇。" }
        })
    ), block: (NoveliaApplication, BookRef) -> Unit) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("本地文件").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as NoveliaApplication
        val previous = app.store.state.value
        val ref = BookRef("local", "toolbar-overlay-regression")
        try {
            compose.runOnIdle {
                app.store.update { it.copy(reader = settings, reducedMotion = false, bookSettings = it.bookSettings - ref.key,
                    positions = it.positions - ref.key, historyPaused = false) }
                app.store.saveDocument(LocalDocument(ref.id, "工具栏覆盖测试", "txt", chapters))
                app.store.saveBook(BookCard(ref, "工具栏覆盖测试"))
            }
            compose.onNodeWithText("本地文件").performClick()
            compose.onNodeWithText("工具栏覆盖测试").performClick()
            compose.waitUntil(15_000) { app.store.state.value.positions[ref.key]?.chapterId == "first" }
            compose.waitForIdle()
            block(app, ref)
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { app.store.update { previous } }
        }
    }

    private fun snapshot(tag: String): List<Pair<String, Rect>> {
        compose.waitForIdle()
        val matching = compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        // LazyColumn also exposes cached, unplaced items; compare only the text actually on screen.
        val nodes = matching.fetchSemanticsNodes().filterIndexed { index, _ -> matching[index].isDisplayed() }
        assertTrue("正文应已加载", nodes.isNotEmpty())
        return nodes.map { it.config[SemanticsProperties.Text].joinToString { text -> text.text } to it.boundsInRoot }
    }

    private fun pageCounter() = compose.onNodeWithTag("reader-page-counter").fetchSemanticsNode().config[SemanticsProperties.Text].single().text

    private fun assertAnchorEquals(expected: Position, actual: Position) {
        assertEquals(expected.chapterId, actual.chapterId)
        assertEquals(expected.index, actual.index)
        assertEquals(expected.offset, actual.offset)
        assertEquals(expected.textOffset, actual.textOffset)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir("screenshots"), "$name.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
