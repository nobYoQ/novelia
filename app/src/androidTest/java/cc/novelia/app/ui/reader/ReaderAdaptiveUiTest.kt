package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.toReaderChapter
import cc.novelia.app.reader.ReadAloudService
import cc.novelia.app.reader.SpeechPlayback
import cc.novelia.app.reader.speechSegments
import cc.novelia.app.ui.saveTestScreenshot
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.reader.ReaderScreen
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderAdaptiveUiTest {
    @Test fun speechFollowsAutoPagesWithOptionsOpenAndWhilePaused() = withReader(390.dp) { app, ref, _ ->
        val oldPlayback = ReadAloudService.playback.value
        val oldStatus = ReadAloudService.status.value
        try {
            compose.runOnIdle { app.store.update { it.copy(reader = it.reader.withPaginationMode("auto"), readerTapTutorialSeen = true) } }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("正在分页…").fetchSemanticsNodes().isEmpty() && compose.onAllNodesWithTag("reader-page").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reader-page").performTouchInput { click(center) }
            val settings = app.store.state.value.reader
            val queue = speechSegments(app.store.documentChapter(ref.id, "first").toReaderChapter(), settings)
            fun playback(index: Int, playing: Boolean = true) = SpeechPlayback("ui-test", ref.key, "first", queue[index], index, queue.size, playing)
            compose.runOnIdle { ReadAloudService.playback.value = playback(0); ReadAloudService.status.value = "正在朗读：第一章 林间" }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader-speech-dock").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val dock = compose.onNodeWithTag("reader-speech-dock").getUnclippedBoundsInRoot()
            val status = compose.onNodeWithTag("reader-reading-status-bar").getUnclippedBoundsInRoot()
            assertTrue("朗读控制必须在阅读信息底栏上方", dock.bottom <= status.top)
            compose.onNodeWithContentDescription("朗读选项").performTouchInput { click() }
            compose.runOnIdle { assertTrue("点开朗读选项不能关闭跟随", ReadAloudService.playback.value!!.following) }
            val fifth = queue.indexOfFirst { it.paragraph == 4 }
            compose.runOnIdle { ReadAloudService.playback.value = playback(fifth) }
            compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.sourceParagraph in 3..4 }
            compose.onNodeWithText("边听边读").performScrollTo().performClick()
            compose.onNodeWithTag("reader-speech-panel").assertDoesNotExist()
            val ninth = queue.indexOfFirst { it.paragraph == 8 }
            compose.runOnIdle { ReadAloudService.playback.value = playback(ninth, playing = false) }
            compose.waitUntil(10_000) {
                app.store.state.value.positions[ref.key]?.sourceParagraph in 7..8 &&
                    compose.onAllNodes(hasText("第9段", substring = true) and hasAnyAncestor(hasTestTag("reader-page"))).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            val sentence = compose.onAllNodes(hasText("第9段", substring = true) and hasAnyAncestor(hasTestTag("reader-page"))).onFirst().getUnclippedBoundsInRoot()
            assertTrue("朗读控制不能遮住当前句", sentence.bottom <= compose.onNodeWithTag("reader-speech-dock").getUnclippedBoundsInRoot().top)
            saveTestScreenshot("speech-reader-auto-page.png")
            // 连续听书进入下一章时，新的 reader 也要承接同一会话和正文来源。
            val nextQueue = speechSegments(app.store.documentChapter(ref.id, "second").toReaderChapter(), settings)
            compose.runOnIdle { ReadAloudService.playback.value = SpeechPlayback("ui-test", ref.key, "second", nextQueue.first(), 0, nextQueue.size, true) }
            compose.waitUntil(15_000) { app.store.state.value.positions[ref.key]?.chapterId == "second" }
            compose.onNodeWithTag("reader-speech-dock").assertIsDisplayed()
        } finally {
            compose.runOnIdle { ReadAloudService.playback.value = oldPlayback; ReadAloudService.status.value = oldStatus }
        }
    }

    @Test fun touchingBodyPausesSpeechFollowButOpeningOptionsDoesNot() = withReader(390.dp) { app, ref, _ ->
        val oldPlayback = ReadAloudService.playback.value
        val oldStatus = ReadAloudService.status.value
        try {
            val queue = speechSegments(app.store.documentChapter(ref.id, "first").toReaderChapter(), app.store.state.value.reader)
            compose.runOnIdle { ReadAloudService.playback.value = SpeechPlayback("ui-test", ref.key, "first", queue.first(), 0, queue.size, true) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader-speech-dock").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val dock = compose.onNodeWithTag("reader-speech-dock").getUnclippedBoundsInRoot()
            val preferences = compose.onNodeWithContentDescription("阅读设置").getUnclippedBoundsInRoot()
            assertTrue("展开工具栏时，朗读控制也必须在底部操作区上方", dock.bottom <= preferences.top)
            saveTestScreenshot("speech-reader-toolbar-open.png")
            compose.onNodeWithContentDescription("朗读选项").performTouchInput { click() }
            compose.runOnIdle { assertTrue(ReadAloudService.playback.value!!.following) }
            compose.onNodeWithText("边听边读").performScrollTo().performClick()
            compose.onNodeWithTag("reader-scroll").performTouchInput { click(center) }
            compose.runOnIdle { assertFalse(ReadAloudService.playback.value!!.following) }
            compose.onNodeWithText("恢复跟随").performTouchInput { click() }
            compose.runOnIdle { assertTrue(ReadAloudService.playback.value!!.following) }
            saveTestScreenshot("speech-reader-scroll.png")
        } finally {
            compose.runOnIdle { ReadAloudService.playback.value = oldPlayback; ReadAloudService.status.value = oldStatus }
        }
    }

    @Test fun configurableFooterKeepsDefaultSettingsAndWorksInBothReadingModes() = withReader(390.dp) { app, ref, _ ->
        compose.onNodeWithContentDescription("阅读设置").performClick()
        compose.onNodeWithText("仅应用于这本书").performClick()
        compose.onNodeWithText("翻页").performClick()
        compose.onNodeWithText("底栏内容与顺序").performScrollTo().performClick()
        compose.onNodeWithTag("reader-status-option-battery").performScrollTo().performClick()
        compose.onNodeWithTag("reader-status-option-time").performScrollTo().performClick()
        compose.onNodeWithText("关闭面板").performClick()
        compose.onNodeWithTag("reader-status-clock").assertDoesNotExist()
        compose.onNodeWithTag("reader-status-battery").assertIsDisplayed()
        compose.runOnIdle {
            val library = app.store.state.value
            assertFalse("battery" in library.reader.readingStatusItems)
            assertTrue("battery" in library.bookSettings.getValue(ref.key).readingStatusItems)
            app.store.update { it.copy(bookSettings = it.bookSettings + (ref.key to it.bookSettings.getValue(ref.key).copy(paginationMode = "auto"))) }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reader-page-counter").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader-status-battery").assertIsDisplayed()
        compose.onNodeWithTag("reader-status-clock").assertDoesNotExist()
    }
    @Test fun readingTimePausesForPreferencesAndRespectsPausedHistory() = withReader(390.dp) { app, _, _ ->
        compose.runOnIdle { app.store.update { it.copy(readingStatistics = cc.novelia.app.data.model.ReadingStatistics(revision = it.readingStatistics.revision + 1)) } }
        compose.waitForIdle()
        android.os.SystemClock.sleep(1_100)
        compose.onNodeWithContentDescription("阅读设置").performClick()
        compose.waitForIdle()
        val first = app.store.state.value.readingStatistics.totalMillis
        assertTrue("前台正文应该累计时长", first >= 1_000)
        android.os.SystemClock.sleep(1_100)
        compose.runOnIdle { assertEquals(first, app.store.state.value.readingStatistics.totalMillis) }
        compose.onNodeWithText("关闭面板").performClick()
        compose.waitForIdle()
        android.os.SystemClock.sleep(1_100)
        compose.onNodeWithContentDescription("阅读设置").performClick()
        compose.waitForIdle()
        val second = app.store.state.value.readingStatistics.totalMillis
        assertTrue(second > first)
        compose.runOnIdle { app.store.update { it.copy(historyPaused = true) } }
        compose.onNodeWithText("关闭面板").performClick()
        compose.waitForIdle()
        android.os.SystemClock.sleep(1_100)
        compose.onNodeWithContentDescription("阅读设置").performClick()
        compose.runOnIdle { assertEquals(second, app.store.state.value.readingStatistics.totalMillis) }
    }
    @get:Rule val compose = createComposeRule()

    @Test fun perBookReplacementsApplyToScrollAndPagesWithoutChangingDefaults() = withReader(390.dp) { app, ref, _ ->
        compose.onNodeWithContentDescription("阅读设置").performClick()
        compose.onNodeWithText("更多").performClick()
        compose.onNodeWithText("本书文字替换", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("添加规则").performClick()
        compose.onNodeWithTag("reader-replacement-source").performTextInput("森林小路")
        compose.onNodeWithTag("reader-replacement-target").performTextInput("石板街道")
        compose.onNodeWithText("保存到本书").performClick()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("关闭面板").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("石板街道", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            assertTrue(app.store.state.value.reader.textReplacements.isEmpty())
            app.store.update { it.copy(bookSettings = it.bookSettings + (ref.key to it.bookSettings.getValue(ref.key).withPaginationMode("auto"))) }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader-page").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodes(hasText("石板街道", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun automaticScrollingAdvancesAndTouchPausesIt() = withReader(390.dp) { app, _, _ ->
        compose.runOnIdle { app.store.update { it.copy(reader = it.reader.copy(autoReadSpeed = 80f)) } }
        val before = scrollPosition()
        startAutomaticReading()
        compose.waitUntil(5_000) { scrollPosition() > before + .05f }
        compose.onNodeWithTag("reader-scroll").performTouchInput { click(center) }
        compose.onNodeWithTag("reader-auto-reading").assertDoesNotExist()
        val stopped = scrollPosition()
        Thread.sleep(250)
        assertEquals(stopped, scrollPosition(), .001f)
        assertEquals(80f, app.store.state.value.reader.autoReadSpeed)
    }

    @Test fun automaticPagesWaitThenAdvanceAndCanBePaused() = withReader(390.dp) { app, ref, _ ->
        compose.runOnIdle { app.store.update { it.copy(reader = it.reader.withPaginationMode("auto").copy(autoReadIntervalSeconds = 5)) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("正在分页…").fetchSemanticsNodes().isEmpty() }
        val before = app.store.state.value.positions.getValue(ref.key)
        startAutomaticReading()
        compose.runOnIdle { assertEquals(before.textOffset, app.store.state.value.positions.getValue(ref.key).textOffset) }
        compose.waitUntil(9_000) { app.store.state.value.positions[ref.key]?.textOffset != before.textOffset }
        compose.onNodeWithTag("reader-page").performTouchInput { click(center) }
        compose.onNodeWithTag("reader-auto-reading").assertDoesNotExist()
    }

    @Test fun automaticReadingContinuesIntoTheNextChapter() = withReader(390.dp, initialChapter = "second") { app, ref, _ ->
        compose.runOnIdle { app.store.update { it.copy(reader = it.reader.copy(autoReadIntervalSeconds = 5)) } }
        startAutomaticReading()
        compose.waitUntil(12_000) { app.store.state.value.positions[ref.key]?.chapterId == "extra-3" }
        compose.onNodeWithTag("reader-auto-reading").assertExists().performClick()
        compose.onNodeWithTag("reader-auto-reading").assertDoesNotExist()
    }

    @Test fun automaticContinuationFailureKeepsTheChapterAndRetryStaysPaused() = withReader(390.dp, initialChapter = "second") { app, ref, _ ->
        val index = app.store.documentIndex(ref.id)
        val file = File(app.store.documentsDir, "${ref.id}-chapters/${index.chapterFiles.getValue("extra-3")}.json")
        val original = file.readBytes()
        try {
            file.writeText("interrupted test fixture", Charsets.UTF_8)
            compose.runOnIdle { app.store.update { it.copy(reader = it.reader.copy(autoReadIntervalSeconds = 5)) } }
            startAutomaticReading()
            compose.waitUntil(12_000) { compose.onAllNodesWithText("章节加载失败，仍在当前章").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reader-auto-reading").assertDoesNotExist()
            assertEquals("second", app.store.state.value.positions[ref.key]?.chapterId)
            file.writeBytes(original)
            compose.onNodeWithText("重试加载章节").performClick()
            compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.chapterId == "extra-3" }
            compose.onNodeWithTag("reader-auto-reading").assertDoesNotExist()
        } finally { file.writeBytes(original) }
    }

    private fun startAutomaticReading() {
        compose.onNodeWithContentDescription("自动阅读").performClick()
        compose.onNodeWithText("开始自动阅读").performScrollTo().performClick()
        compose.onNodeWithTag("reader-auto-reading").assertExists()
    }

    @Test fun spokenSentenceFollowsInScrollModeAndManualTouchStopsFollowing() = verifySpeechFollowing(false)
    @Test fun spokenSentenceFollowsInPagesAndManualTouchStopsFollowing() = verifySpeechFollowing(true)

    private fun verifySpeechFollowing(paged: Boolean) = withReader(390.dp) { app, ref, _ ->
        val service = cc.novelia.app.reader.ReadAloudService
        val previous = service.playback.value
        try {
            if(paged) {
                compose.runOnIdle { app.store.update { it.copy(reader = it.reader.withPaginationMode("auto")) } }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("正在分页…").fetchSemanticsNodes().isEmpty() }
            }
            val tag = if(paged) "reader-page" else "reader-scroll"
            compose.onNodeWithTag(tag).performTouchInput { click(center) }
            compose.runOnIdle {
                service.playback.value = cc.novelia.app.reader.SpeechPlayback("test", ref.key, "first",
                    cc.novelia.app.reader.SpeechSegment("第5段，沿着森林小路走向远处的小镇。", 4, "original"), 0, 2, true)
            }
            compose.waitUntil(10_000) {
                (app.store.state.value.positions[ref.key]?.sourceParagraph ?: 0) > 0 &&
                    compose.onAllNodes(hasText("第5段", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(tag).performTouchInput { click(center) }
            compose.waitUntil(5_000) { service.playback.value?.following == false }
            val position = app.store.state.value.positions.getValue(ref.key)
            compose.runOnIdle {
                service.playback.value = service.playback.value?.copy(index = 1,
                    segment = cc.novelia.app.reader.SpeechSegment("第9段，沿着森林小路走向远处的小镇。", 8, "original"))
            }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(position.sourceParagraph, app.store.state.value.positions.getValue(ref.key).sourceParagraph) }
        } finally { service.playback.value = previous }
    }

    @Test fun wideDirectoryCanCollapseAndReopenWithoutLosingItsSession() = withReader(1024.dp) { app, ref, _ ->
        val initial = app.store.state.value.positions.getValue(ref.key)
        compose.onNodeWithTag("reader-hide-wide-toc").performClick()
        compose.onNodeWithTag("reader-toc-pane").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(app.store.state.value.reader.showWideToc)
            assertEquals(initial.sourceParagraph, app.store.state.value.positions.getValue(ref.key).sourceParagraph)
        }
        compose.onNodeWithText("目录", substring = true).performClick()
        compose.onNodeWithTag("reader-toc-pane").assertExists()
        compose.runOnIdle { assertTrue(app.store.state.value.reader.showWideToc) }
    }

    @Test fun sideMarginsHandleScrollingPagingAndTaps() = verifySideMarginGestures(false)

    @Test fun sideMarginsHandleScrollingPagingAndTapsOnEInk() = verifySideMarginGestures(true)

    @Test fun wideMarginsRemainInteractiveWithDirectoryOpenAndClosed() = withReader(1024.dp) { app, _, _ ->
        compose.runOnIdle { app.store.update { it.copy(reader = it.reader.copy(width = 400f), readerTapTutorialSeen = true) } }
        compose.onNodeWithTag("reader-scroll").performTouchInput { click(center) }
        val readingArea = compose.onNodeWithTag("reader-wide-layout")
        for(directoryOpen in listOf(true, false)) {
            if(!directoryOpen) compose.onNodeWithTag("reader-hide-wide-toc").performClick()
            for(x in listOf(if(directoryOpen) .32f else .04f, .96f)) {
                val before = scrollPosition()
                readingArea.performTouchInput { swipe(Offset(width * x, height * .65f), Offset(width * x, height * .4f), 500) }
                compose.waitUntil(5_000) { scrollPosition() > before + .05f }
            }
        }
    }

    private fun verifySideMarginGestures(eInk: Boolean) = withReader(390.dp, eInk) { app, _, _ ->
        // 缩窄正文在手机测试视口中复现宽屏留白，触摸从阅读区边缘发出，不能相对正文定位。
        compose.runOnIdle { app.store.update { it.copy(reader = it.reader.copy(width = 320f), readerTapTutorialSeen = true) } }
        val readingArea = compose.onNodeWithTag("reader-compact-layout")
        compose.onNodeWithTag("reader-scroll").performTouchInput { click(center) }
        for(x in listOf(.04f, .96f)) {
            val before = scrollPosition()
            readingArea.performTouchInput { swipe(Offset(width * x, height * .65f), Offset(width * x, height * .4f), 500) }
            compose.waitUntil(5_000) { scrollPosition() > before + .05f }
        }
        compose.runOnIdle { app.store.update { it.copy(reader = it.reader.copy(paginationMode = "auto",
            horizontalPageTurn = true, scrollPageTurn = true, tapPageTurn = true)) } }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reader-page").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithText("正在分页…").fetchSemanticsNodes().isEmpty() }
        fun pageCounter() = compose.onNodeWithTag("reader-page-counter").fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text.substringBefore('/').trim().toInt()
        var before = pageCounter()
        readingArea.performTouchInput { swipe(Offset(width * .04f, height * .65f), Offset(width * .04f, height * .4f), 500) }
        compose.waitUntil(5_000) { pageCounter() == before + 1 }
        before = pageCounter()
        readingArea.performTouchInput { swipe(Offset(width * .96f, centerY), Offset(width * .5f, centerY), 500) }
        compose.waitUntil(5_000) { pageCounter() == before + 1 }
        before = pageCounter()
        readingArea.performTouchInput { click(Offset(width * .96f, centerY)) }
        compose.waitUntil(5_000) { pageCounter() == before + 1 }
        readingArea.performTouchInput { click(Offset(width * .04f, centerY)) }
        compose.waitUntil(5_000) { pageCounter() == before }
        compose.onNodeWithContentDescription("阅读设置").assertDoesNotExist()
    }

    @Test fun marginChangesPreserveTheCharacterAnchorInBothReadingModes() = withReader(390.dp) { app, ref, _ ->
        compose.onNodeWithTag("reader-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 800f) }
        compose.waitUntil(10_000) { (app.store.state.value.positions[ref.key]?.textOffset ?: 0) > 0 }
        val before = app.store.state.value.positions.getValue(ref.key)
        compose.runOnIdle { app.store.update { it.copy(reader = it.reader.copy(horizontalMargin = 48f, verticalMargin = 36f)) } }
        compose.waitForIdle()
        compose.runOnIdle {
            val after = app.store.state.value.positions.getValue(ref.key)
            assertEquals(before.sourceParagraph, after.sourceParagraph)
            assertEquals(before.textOffset, after.textOffset)
            app.store.update { it.copy(reader = it.reader.withPaginationMode("auto")) }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("正在分页…").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        compose.runOnIdle { app.store.update { it.copy(reader = it.reader.copy(horizontalMargin = 8f, verticalMargin = 0f)) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("正在分页…").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        compose.runOnIdle {
            val after = app.store.state.value.positions.getValue(ref.key)
            assertEquals(before.sourceParagraph, after.sourceParagraph)
            assertTrue(after.textOffset <= before.textOffset)
        }
        val visible = compose.onNode(hasText("第1段", substring = true)).fetchSemanticsNode().config[SemanticsProperties.Text].sumOf { it.text.length }
        assertTrue(app.store.state.value.positions.getValue(ref.key).textOffset + visible >= before.textOffset)
    }

    @Test fun readerCreatesMultipleSelectionsAndCancellingANoteDoesNotSaveIt() = withReader(390.dp) { app, ref, _ ->
        compose.onNode(hasText("第1段", substring = true) and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader-selection-text").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader-selection-text").performTextInputSelection(androidx.compose.ui.text.TextRange(0, 3))
        compose.onNodeWithText("划线").performScrollTo().performClick()
        compose.waitUntil(5_000) { app.store.state.value.notes.any { it.key == ref.key && it.quote == "第1段" } }
        compose.onNodeWithTag("reader-selection-text").performScrollTo().performTextInputSelection(androidx.compose.ui.text.TextRange(4, 8))
        compose.onNodeWithText("划线").performScrollTo().performClick()
        compose.waitUntil(5_000) { app.store.state.value.notes.count { it.key == ref.key && it.selection != null } == 2 }
        compose.onNodeWithTag("reader-selection-text").performScrollTo().performTextInputSelection(androidx.compose.ui.text.TextRange(9, 12))
        compose.onNodeWithText("写批注").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(2, app.store.state.value.notes.count { it.key == ref.key && it.selection != null }) }
    }

    @Test fun nextChapterAndLayoutChangesReuseTheLoadedDirectory() = verifyDirectoryReuse(eInk = false)

    @Test fun eInkNextChapterAndLayoutChangesReuseTheLoadedDirectory() = verifyDirectoryReuse(eInk = true)

    @Test fun reopeningTheBookStartsANewDirectorySession() {
        lateinit var controller: AppController
        withReader(1024.dp, onController = { controller = it }) { _, ref, _ ->
            val previous = controller.readerToc(ref)
            compose.runOnIdle {
                controller.back()
                controller.read(ref, "second")
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader-toc-chapter-second").fetchSemanticsNodes().any { it.config[SemanticsProperties.Selected] } }
            compose.runOnIdle {
                assertNotSame(previous, controller.readerToc(ref))
                assertNotNull(controller.readerToc(ref).toc)
            }
        }
    }

    private fun verifyDirectoryReuse(eInk: Boolean) {
        lateinit var controller: AppController
        withReader(1024.dp, eInk, onController = { controller = it }) { app, ref, resize ->
            val directory = controller.readerToc(ref)
            compose.waitUntil(10_000) { directory.toc != null }
            val original = requireNotNull(directory.toc)
            compose.onNodeWithContentDescription("下一章").performClick()
            compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.chapterId == "second" }
            compose.onNodeWithTag("reader-toc-chapter-second").assertIsSelected()
            compose.onNodeWithTag("reader-toc-loading").assertDoesNotExist()
            compose.runOnIdle {
                assertSame(directory, controller.readerToc(ref))
                assertSame(original, directory.toc)
                assertEquals(100, app.store.state.value.positions[ref.key]?.chapterCount)
                resize(390.dp)
            }
            repeat(2) {
                compose.onNodeWithText("目录", substring = true).performClick()
                compose.onNodeWithTag("reader-toc-chapter-second").assertIsDisplayed().assertIsSelected()
                compose.onNodeWithTag("reader-toc-loading").assertDoesNotExist()
                compose.onNodeWithText("关闭面板").performClick()
            }
            compose.runOnIdle { resize(1024.dp) }
            compose.onNodeWithTag("reader-toc-chapter-second").assertIsSelected()
            compose.runOnIdle {
                assertSame(directory, controller.readerToc(ref))
                assertSame(original, directory.toc)
            }
        }
    }

    @Test fun directoryOpeningAndReopeningBothFindTheCurrentChapter() =
        withReader(390.dp, initialChapter = "extra-80") { _, _, _ ->
            compose.onNodeWithText("目录", substring = true).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader-toc-chapter-extra-80").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reader-toc-chapter-extra-80").assertIsDisplayed().assertIsSelected()
            compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("reader-toc-list"))).performScrollToIndex(0)
            compose.onNodeWithTag("reader-toc-chapter-first").assertIsDisplayed()
            compose.onNodeWithText("关闭面板").performClick()
            compose.onNodeWithText("目录", substring = true).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader-toc-chapter-extra-80").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reader-toc-chapter-extra-80").assertIsDisplayed()
        }

    @Test fun bookmarkSavesImmediatelyAndEditingOrClearingNotesPreservesItsIdentity() = withReader(390.dp) { app, ref, _ ->
        compose.onNodeWithContentDescription("保存书签").performClick()
        val bookmark = app.store.state.value.notes.single { it.key == ref.key }
        compose.onNodeWithText("编辑笔记").performClick()
        compose.onNodeWithText("笔记内容").performTextInput("回头再读这一段")
        compose.onNodeWithText("保存笔记").performClick()
        compose.runOnIdle { assertEquals("回头再读这一段", app.store.state.value.notes.single { it.id == bookmark.id }.text) }
        compose.onNodeWithContentDescription("已保存书签").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("编辑笔记").performClick()
        compose.onNodeWithText("笔记内容").assertTextContains("回头再读这一段").performTextClearance()
        compose.onNodeWithText("保存笔记").performClick()
        compose.runOnIdle {
            val saved = app.store.state.value.notes.single { it.key == ref.key }
            assertEquals(bookmark.id, saved.id)
            assertEquals("", saved.text)
            assertEquals(bookmark.paragraph, saved.paragraph)
        }
    }

    @Test fun directoryAndTextKeepTheirStateAcrossWideAndCompactLayouts() = withReader(1024.dp) { app, ref, resize ->
        compose.onNodeWithTag("reader-wide-layout").assertExists()
        compose.onNodeWithTag("reader-toc-chapter-first").assertIsSelected()
        compose.onNodeWithTag("reader-toc-query").performTextReplacement("第二")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader-toc-chapter-first").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("reader-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 950f) }
        compose.waitUntil(10_000) { (app.store.state.value.positions[ref.key]?.textOffset ?: 0) > 0 }
        val before = app.store.state.value.positions.getValue(ref.key)
        compose.runOnIdle { resize(390.dp) }
        compose.onNodeWithTag("reader-compact-layout").assertExists()
        compose.onNodeWithTag("reader-toc-pane").assertDoesNotExist()
        compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.let { it.index == before.index && it.textOffset == before.textOffset } == true }
        compose.onNodeWithText("目录", substring = true).performClick()
        compose.onNodeWithTag("reader-toc-query").assertTextContains("第二")
        compose.onNodeWithText("关闭面板").performClick()
        compose.runOnIdle { resize(1024.dp) }
        compose.onNodeWithTag("reader-toc-query").assertTextContains("第二")
        compose.onNodeWithTag("reader-toc-chapter-second").performClick()
        compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.chapterId == "second" }
        compose.onNodeWithTag("reader-toc-chapter-second").assertIsSelected()
        compose.onNodeWithTag("reader-toc-query").assertTextContains("第二")
        compose.onNodeWithText("定位当前").performClick()
        compose.onNodeWithTag("reader-toc-chapter-second").assertIsDisplayed()
    }

    @Test fun failedEndPullPreservesTheChapterAndRetriesAfterTheFileRecovers() = verifyFailedPull(eInk = false)

    @Test fun eInkFailedEndPullCanBeDismissedAndRetriedWithoutLosingText() = verifyFailedPull(eInk = true)

    private fun verifyFailedPull(eInk: Boolean) = withReader(390.dp, eInk) { app, ref, _ ->
        val index = app.store.documentIndex(ref.id)
        val file = File(app.store.documentsDir, "${ref.id}-chapters/${index.chapterFiles.getValue("second")}.json")
        val original = file.readBytes()
        try {
            file.writeText("interrupted test fixture", Charsets.UTF_8)
            compose.onNodeWithTag("reader-scroll").performTouchInput { click(center) }
            compose.onNodeWithTag("reader-top-toolbar").assertDoesNotExist()
            compose.onNodeWithTag("reader-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            compose.waitForIdle()
            val before = scrollPosition()
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            compose.onNodeWithTag("reader-scroll").performTouchInput {
                down(center); moveBy(Offset(0f, -100f * density), delayMillis = 250); up()
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("章节加载失败，仍在当前章").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("本章完").assertIsDisplayed()
            assertEquals(before, scrollPosition(), .001f)
            assertEquals("first", app.store.state.value.positions[ref.key]?.chapterId)
            if(eInk) {
                compose.onNodeWithText("继续阅读").performClick()
                compose.onNodeWithTag("reader-chapter-load-status").assertDoesNotExist()
                assertEquals(before, scrollPosition(), .001f)
                compose.onNodeWithText("阅读下一章").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("章节加载失败，仍在当前章").fetchSemanticsNodes().isNotEmpty() }
            }
            file.writeBytes(original)
            compose.onNodeWithText("重试加载章节").performClick()
            compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.chapterId == "second" }
            compose.onNodeWithText("恢复后的下一章正文。", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("reader-chapter-load-status").assertDoesNotExist()
            compose.onNodeWithTag("reader-top-toolbar").assertDoesNotExist()
        } finally { file.writeBytes(original) }
    }

    private fun scrollPosition(): Float = compose.onNodeWithTag("reader-scroll").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun withReader(initialWidth: Dp, eInk: Boolean = false, initialChapter: String = "first", onController: (AppController) -> Unit = {}, block: (NoveliaApplication, BookRef, (Dp) -> Unit) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val previous = app.store.state.value
        val ref = BookRef("local", "adaptive-reader-regression")
        var width by mutableStateOf(initialWidth)
        lateinit var controller: AppController
        try {
            app.store.saveDocument(LocalDocument(ref.id, "阅读布局测试", "txt", listOf(
                LocalChapter("first", "第一章 林间", List(12) { paragraph -> "第${paragraph + 1}段，沿着森林小路走向远处的小镇。".repeat(80) }),
                LocalChapter("second", "第二章 归途", listOf("恢复后的下一章正文。"))
            ) + (3..100).map { LocalChapter("extra-$it", "续章 $it", listOf("续章正文 $it")) }))
            app.store.update { it.copy(reader = ReaderSettings(paginationMode = "scroll", width = 900f, eInkMode = eInk, showPageButtons = false, prefetchChapters = 0),
                reducedMotion = true, historyPaused = false, notes = it.notes.filterNot { note -> note.key == ref.key }, positions = it.positions - ref.key, bookSettings = it.bookSettings - ref.key) }
            compose.setContent {
                NoveliaTheme("light") {
                    AppInteractionMode(eInk, true) {
                        val nav = rememberNavController()
                        val scope = rememberCoroutineScope()
                        val snackbar = remember { SnackbarHostState() }
                        controller = remember(nav, scope, snackbar) { AppController(app, nav, scope, snackbar) }
                        Box(Modifier.fillMaxSize().wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(width)) {
                            NavHost(nav, startDestination = "home") {
                                composable("home") { Text("测试书架") }
                                composable("reader/{provider}/{book}/{chapter}") { entry ->
                                    ReaderScreen(controller, BookRef(entry.arguments?.getString("provider").orEmpty(), entry.arguments?.getString("book").orEmpty()), entry.arguments?.getString("chapter").orEmpty())
                                }
                            }
                            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
            compose.runOnIdle { controller.read(ref, initialChapter) }
            compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.chapterId == initialChapter }
            compose.waitForIdle()
            onController(controller)
            block(app, ref) { width = it }
        } finally {
            compose.runOnIdle { controller.back() }
            app.store.update { previous }
        }
    }
}
