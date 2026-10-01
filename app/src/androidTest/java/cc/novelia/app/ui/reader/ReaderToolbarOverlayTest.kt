@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package cc.novelia.app.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.storage.LocalStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderToolbarOverlayTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun automaticPagesKeepExactTextPageCountAndAnchorUnderOverlays() = verifyOverlay("auto")

    @Test fun continuousScrollingKeepsTextCoordinatesAndPositionUnderOverlays() = verifyOverlay("scroll")

    @Test fun continuousScrollingKeepsToolbarHiddenAcrossChapters() = verifyChapterNavigation(ReaderSettings(paginationMode = "scroll"))

    @Test fun automaticPagesKeepToolbarHiddenAcrossChapters() = verifyChapterNavigation(ReaderSettings(paginationMode = "auto"))

    @Test fun eInkPagesKeepToolbarHiddenAcrossChapters() = verifyChapterNavigation(ReaderSettings().withEInkMode(true))

    @Test fun eInkScrollingKeepsToolbarHiddenAcrossChapters() = verifyChapterNavigation(ReaderSettings().withEInkMode(true).withPaginationMode("scroll"))

    @Test fun scrollingSeekReachesLongParagraphsAndChapterBoundariesWithoutTurningChapters() = withReader(
        ReaderSettings(paginationMode = "scroll", showPageButtons = false),
        listOf(LocalChapter("first", "第一章 长段落", listOf("旅人沿着森林小路前行，寻找远处的小镇。".repeat(600))),
            LocalChapter("second", "第二章", listOf("下一章正文。")))
    ) { app, ref ->
        val slider = compose.onNodeWithTag("reader-seek-bar").assertHeightIsAtLeast(48.dp)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(.63f) }
        compose.waitUntil(10_000) { (app.store.state.value.positions[ref.key]?.textOffset ?: 0) > 6000 }
        screenshot("reader-seek-scroll")
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(.2f) }
        compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.textOffset?.let { it in 1000..4000 } == true }
        val anchor = app.store.state.value.positions.getValue(ref.key)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("工具栏覆盖测试").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reader-seek-bar").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertAnchorEquals(anchor, app.store.state.value.positions.getValue(ref.key))
        compose.onNodeWithTag("reader-seek-bar").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithText("上拉加载下一章").assertIsDisplayed()
        assertChapterUnchanged(app, ref, "first")
        compose.onNodeWithTag("reader-seek-preview").assertTextEquals("100%")
        compose.onNodeWithTag("reader-seek-bar").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.let { it.index == 0 && it.offset == 0 } == true }
    }

    @Test fun pagedSeekPreviewsWhileDraggingAndReducedMotionKeepsTheSelectedPage() = withReader(
        ReaderSettings(paginationMode = "auto", showPageButtons = false)
    ) { app, ref ->
        val total = pageNumbers().second
        val slider = compose.onNodeWithTag("reader-seek-bar")
        slider.performTouchInput { down(Offset(width * .2f, centerY)); moveTo(Offset(width * .75f, centerY), delayMillis = 300) }
        compose.waitUntil(10_000) { pageNumbers().first > total / 2 }
        slider.performTouchInput { up() }
        screenshot("reader-seek-paged")
        compose.runOnIdle { app.store.update { it.copy(reducedMotion = true) } }
        val targetIndex = total / 3
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(targetIndex.toFloat() / (total - 1)) }
        val expected = targetIndex + 1
        compose.waitUntil(10_000) { pageNumbers().first == expected }
        assertChapterProgress(expected, total)
        assertTrue(app.store.state.value.positions.getValue(ref.key).textOffset > 0 || app.store.state.value.positions.getValue(ref.key).index > 1)
        slider.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
        assertEquals(expected + 1, pageNumbers().first)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        assertChapterProgress(total, total)
        assertChapterUnchanged(app, ref, "first")
    }

    @Test fun eInkSeekDefersPageRefreshUntilReleaseAndCancelledPreviewDoesNotNavigate() = withReader(
        ReaderSettings().withEInkMode(true).copy(showPageButtons = false)
    ) { _, _ ->
        val slider = compose.onNodeWithTag("reader-seek-bar").assertHeightIsAtLeast(48.dp)
        val total = pageNumbers().second
        slider.performTouchInput { down(Offset(width * .2f, centerY)); moveTo(Offset(width * .8f, centerY), delayMillis = 300) }
        compose.onNodeWithText("松手跳转").assertIsDisplayed()
        assertEquals(1, pageNumbers().first)
        screenshot("reader-seek-eink-preview")
        slider.performTouchInput { cancel() }
        assertEquals(1, pageNumbers().first)
        compose.onNodeWithText("松手跳转").assertDoesNotExist()
        slider.performTouchInput { down(Offset(width * .2f, centerY)); moveTo(Offset(width * .8f, centerY), delayMillis = 300); up() }
        compose.waitUntil(10_000) { pageNumbers().first > total / 2 }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        assertEquals(1, pageNumbers().first)
    }

    @Test fun eInkScrollingSeekCommitsOnReleaseAndRetainsAnExactLongParagraphAnchor() = withReader(
        ReaderSettings().withEInkMode(true).withPaginationMode("scroll").copy(showPageButtons = false)
    ) { app, ref ->
        val before = app.store.state.value.positions.getValue(ref.key)
        val slider = compose.onNodeWithTag("reader-seek-bar")
        slider.performTouchInput { down(Offset(width * .1f, centerY)); moveTo(Offset(width * .65f, centerY), delayMillis = 300) }
        assertAnchorEquals(before, app.store.state.value.positions.getValue(ref.key))
        slider.performTouchInput { up() }
        compose.waitUntil(10_000) { (app.store.state.value.positions[ref.key]?.index ?: 0) >= 3 }
        assertTrue((app.store.state.value.positions[ref.key]?.textOffset ?: 0) > 0)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.let { it.index == 0 && it.offset == 0 } == true }
    }

    @Test fun progressBarPreferencePersistsPerBookWithoutChangingThePageOrOtherControls() = withReader(
        ReaderSettings().withEInkMode(true)
    ) { app, ref ->
        compose.onNodeWithText("下一页").performClick()
        val before = snapshot("reader-page")
        val counter = pageCounter()
        compose.onNodeWithContentDescription("阅读设置").performClick()
        compose.onNodeWithText("仅应用于这本书").performClick()
        compose.onNodeWithText("翻页").performClick()
        compose.onNodeWithText("阅读进度条").performScrollTo().performClick()
        screenshot("reader-progress-preference")
        compose.onNodeWithText("关闭面板").performClick()
        compose.onNodeWithTag("reader-seek-bar").assertDoesNotExist()
        compose.onNodeWithText("下一页").assertIsDisplayed()
        assertEquals(counter, pageCounter())
        assertEquals(before, snapshot("reader-page"))
        runBlocking { app.store.flush() }
        val saved = LocalStore(compose.activity).state.value
        assertTrue(saved.reader.showProgressBar)
        assertFalse(saved.bookSettings.getValue(ref.key).showProgressBar)
        compose.onNodeWithContentDescription("阅读设置").performClick()
        compose.onNodeWithText("仅应用于这本书").performScrollTo().performClick()
        compose.onNodeWithText("关闭面板").performClick()
        compose.onNodeWithTag("reader-seek-bar").assertIsDisplayed()
        compose.runOnIdle { app.store.update { it.copy(reader = it.reader.copy(showProgressBar = false)) } }
        compose.onNodeWithTag("reader-seek-bar").assertDoesNotExist()
        assertEquals(counter, pageCounter())
        assertEquals(before, snapshot("reader-page"))
    }

    @Test fun chapterEndButtonsFollowTheGlobalPreference() = verifyChapterEndButtons(eInk = false, perBook = false)

    @Test fun chapterEndButtonsFollowThePerBookPreferenceInEInkMode() = verifyChapterEndButtons(eInk = true, perBook = true)

    private fun verifyChapterEndButtons(eInk: Boolean, perBook: Boolean) = withReader(
        ReaderSettings(eInkMode = eInk, paginationMode = "scroll", showPageButtons = true),
        listOf(
            LocalChapter("first", "第一章 林间旅途", listOf("旅人沿着森林小路前行。")),
            LocalChapter("second", "第二章 归来", listOf("星光照亮归途。")),
            LocalChapter("third", "第三章 新的旅程", listOf("新的故事开始了。"))
        )
    ) { app, ref ->
        fun toggleChapterEndButtons() {
            compose.onNodeWithContentDescription("阅读设置").performClick()
            compose.onNodeWithText("翻页").performClick()
            compose.onNodeWithText("章节末尾按钮").performScrollTo().performClick()
            if(eInk) compose.onNodeWithText("关闭面板").performClick()
            else InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            scrollToChapterEnd()
        }
        scrollToChapterEnd()
        compose.onNodeWithText("阅读下一章").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        if(perBook) {
            compose.onNodeWithContentDescription("阅读设置").performClick()
            compose.onNodeWithText("仅应用于这本书").performScrollTo().performClick()
            compose.onNodeWithText("关闭面板").performClick()
        }
        toggleChapterEndButtons()
        compose.onNodeWithText("阅读下一章").assertDoesNotExist()
        compose.onNodeWithText("上拉加载下一章").assertExists()
        compose.onNodeWithText("下一屏").assertIsDisplayed()
        runBlocking { app.store.flush() }
        val saved = LocalStore(compose.activity).state.value
        assertFalse((if(perBook) saved.bookSettings.getValue(ref.key) else saved.reader).showScrollPageButtons)
        assertTrue(saved.reader.showPageButtons)
        if(perBook) assertTrue(saved.reader.showScrollPageButtons)

        toggleChapterEndButtons()
        compose.onNodeWithText("阅读下一章").assertIsDisplayed().performClick()
        waitForChapter(app, ref, "second")
        toggleChapterEndButtons()
        compose.onNodeWithText("阅读下一章").assertDoesNotExist()
        val density = compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag("reader-scroll").performTouchInput {
            down(center)
            moveBy(Offset(0f, -100f * density), delayMillis = 200)
            up()
        }
        waitForChapter(app, ref, "third")
        scrollToChapterEnd()
        compose.onNodeWithText("返回目录").assertDoesNotExist()
        toggleChapterEndButtons()
        compose.onNodeWithText("返回目录").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

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
        val pull = 80f * density
        compose.onNodeWithTag("reader-scroll").performTouchInput {
            down(center)
            moveBy(Offset(0f, -pull), delayMillis = 200)
            moveBy(Offset(0f, 48f * density), delayMillis = 200)
            up()
        }
        assertChapterUnchanged(app, ref, "first")
        scrollToChapterEnd()
        val footerTop = compose.onNodeWithText("本章完").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("reader-scroll").performTouchInput {
            down(center)
            moveBy(Offset(0f, -32f * density), delayMillis = 100)
        }
        compose.onNodeWithText("继续上拉加载下一章").assertExists()
        compose.onAllNodesWithTag("reader-chapter-pull-hint").assertCountEquals(1)
        compose.onNodeWithText("上拉加载下一章").assertDoesNotExist()
        compose.onNodeWithTag("reader-next-chapter-pull").assertDoesNotExist()
        compose.runOnIdle { assertEquals("first", app.store.state.value.positions[ref.key]?.chapterId) }
        compose.onNodeWithTag("reader-scroll").performTouchInput { moveBy(Offset(0f, -48f * density), delayMillis = 150) }
        compose.onNodeWithText("松手加载下一章").assertIsDisplayed()
        val pulledFooterTop = compose.onNodeWithText("本章完").fetchSemanticsNode().boundsInRoot.top
        assertTrue("短距离上拉时正文应跟随手指移动", footerTop - pulledFooterTop > 30f * density)
        compose.runOnIdle { assertEquals("达到阈值但未松手不能切章", "first", app.store.state.value.positions[ref.key]?.chapterId) }
        screenshot("reader-pull-ready")
        compose.onNodeWithTag("reader-scroll").performTouchInput { up() }
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
            moveBy(Offset(0f, -80f * density), delayMillis = 200)
            moveBy(Offset(0f, 40f * density), delayMillis = 200)
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

    @Test fun eInkScrollingShowsPullFeedbackAndLoadsOnReleaseWithMotionDisabled() = withReader(
        ReaderSettings().withEInkMode(true).withPaginationMode("scroll").copy(showPageButtons = false),
        listOf(
            LocalChapter("first", "第一章 短章", listOf("短章正文。")),
            LocalChapter("second", "第二章 终章", listOf("故事到这里结束。"))
        )
    ) { app, ref ->
        compose.runOnIdle { app.store.update { it.copy(reducedMotion = true) } }
        compose.onNodeWithText("短章正文。", substring = true).performClick()
        assertToolbarHidden()
        scrollToChapterEnd()
        val density = compose.activity.resources.displayMetrics.density
        val body = compose.onNodeWithTag("reader-scroll")
        val restingTop = compose.onNodeWithText("本章完").fetchSemanticsNode().boundsInRoot.top
        body.performTouchInput { down(center); moveBy(Offset(0f, -80f * density), delayMillis = 200) }
        compose.onNodeWithText("松手加载下一章").assertIsDisplayed()
        assertEquals("电子纸上拉反馈不连续移动正文", restingTop, compose.onNodeWithText("本章完").fetchSemanticsNode().boundsInRoot.top, .5f)
        compose.runOnIdle { assertEquals("first", app.store.state.value.positions[ref.key]?.chapterId) }
        body.performTouchInput { cancel() }
        compose.onNodeWithTag("reader-next-chapter-pull").assertDoesNotExist()
        assertChapterUnchanged(app, ref, "first")
        body.performTouchInput { down(center); moveBy(Offset(0f, -80f * density), delayMillis = 200); up() }
        waitForChapter(app, ref, "second")
    }

    @Test fun pullReturnStopsWhenMotionIsDisabledOrAnotherTouchBegins() = withReader(
        ReaderSettings(paginationMode = "scroll", showPageButtons = false),
        listOf(
            LocalChapter("first", "第一章 短章", listOf("短章正文。")),
            LocalChapter("second", "第二章 终章", listOf("故事到这里结束。"))
        )
    ) { app, ref ->
        compose.onNodeWithText("短章正文。", substring = true).performClick()
        assertToolbarHidden()
        scrollToChapterEnd()
        val density = compose.activity.resources.displayMetrics.density
        val body = compose.onNodeWithTag("reader-scroll")
        for(disableMotion in listOf(true, false)) {
            compose.runOnIdle { app.store.update { it.copy(reducedMotion = false) } }
            compose.waitForIdle()
            val restingTop = compose.onNodeWithText("本章完").fetchSemanticsNode().boundsInRoot.top
            compose.mainClock.autoAdvance = false
            try {
                body.performTouchInput { down(center); moveBy(Offset(0f, -80f * density), delayMillis = 200) }
                compose.mainClock.advanceTimeBy(64)
                body.performTouchInput { cancel() }
                compose.mainClock.advanceTimeBy(64)
                val returningTop = compose.onNodeWithText("本章完").fetchSemanticsNode().boundsInRoot.top
                assertTrue("应在回弹尚未完成时改变交互状态", restingTop - returningTop > density)
                if(disableMotion) compose.runOnIdle { app.store.update { it.copy(reducedMotion = true) } }
                else body.performTouchInput { down(center) }
                compose.mainClock.advanceTimeBy(32)
                compose.onNodeWithTag("reader-next-chapter-pull").assertDoesNotExist()
                assertEquals("关闭动画或再次按下应立即结束旧回弹", restingTop,
                    compose.onNodeWithText("本章完").fetchSemanticsNode().boundsInRoot.top, .5f)
                if(!disableMotion) body.performTouchInput { cancel() }
                compose.mainClock.advanceTimeBy(300)
                assertEquals(restingTop, compose.onNodeWithText("本章完").fetchSemanticsNode().boundsInRoot.top, .5f)
            } finally { compose.mainClock.autoAdvance = true }
        }
        assertChapterUnchanged(app, ref, "first")
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

        // 仍可显式打开工具栏，章节按钮也应保留这一可见状态。
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
        // 同时验证章首和长段落内部的位置。
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
            compose.onNodeWithText("翻页").performClick()
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
            compose.onNodeWithText("翻页").performClick()
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
        // LazyColumn 也会暴露已缓存但未放置的项，只比较屏幕上实际可见的文本。
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
