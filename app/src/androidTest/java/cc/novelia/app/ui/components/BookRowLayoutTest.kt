package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.catalog.formatApproximateCharacters
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.CloudReadingProgress
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.WebOutline
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.ui.shelf.MountedVolumeRow
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import cc.novelia.app.ui.components.book.BookListPresentation
import cc.novelia.app.ui.components.book.BookRow
import cc.novelia.app.ui.components.book.BookRowStatus
import cc.novelia.app.ui.components.book.LocalBookListPresentation
import cc.novelia.app.ui.components.book.bookRowStatus
import cc.novelia.app.ui.components.book.rememberCloudBookMetadata

class BookRowLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun favoriteMetadataSharesOneLineAndNeverShowsCachedOrLoadedCharacterCounts() {
        var book by mutableStateOf(WebOutline(providerId = "syosetu", novelId = "compact-favorite", titleJp = "风与书页",
            type = "连载中", total = 282, updateAt = 1704067200).card("alice"))
        val cached = SavedBook(book.copy(totalCharacters = 123_456))
        var width by mutableStateOf(412.dp)
        var fontScale by mutableStateOf(1f)
        var bookClicks = 0
        var menuClicks = 0
        compose.setContent {
            NoveliaTheme("dark") {
                CompositionLocalProvider(LocalDensity provides Density(1.5f, fontScale),
                    LocalBookListPresentation provides BookListPresentation(books = mapOf(book.ref.key to cached), account = "alice")) {
                    Surface(Modifier.requiredWidth(width).testTag("book-row-preview")) {
                        BookRow(book, { bookClicks++ }, status = BookRowStatus(.38f, "已读 38%", "更新 3 章"),
                            showCharacterCount = true, compactMetadata = true, trailing = {
                                IconButton(onClick = { menuClicks++ }) { Icon(Icons.Outlined.MoreVert, "管理小说") }
                            })
                    }
                }
            }
        }
        val metadata = compose.onNodeWithText("连载中 · 282 章", useUnmergedTree = true)
        val date = compose.onNodeWithTag("book-update-date-${book.ref.key}", useUnmergedTree = true)
        compose.onAllNodesWithText("连载中 · 282 章", useUnmergedTree = true).assertCountEquals(1)
        assertEquals(metadata.fetchSemanticsNode().boundsInRoot.top, date.fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText(formatApproximateCharacters(123_456)).assertDoesNotExist()
        compose.onNodeWithText("字数未知").assertDoesNotExist()
        compose.onNodeWithText("已读 38%").assertIsDisplayed()
        compose.onNodeWithText("更新 3 章").assertIsDisplayed()
        compose.onNodeWithTag("book-reading-progress-${book.ref.key}", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("管理小说").performClick()
        compose.runOnIdle { assertEquals(1, menuClicks); assertEquals(0, bookClicks) }
        captureRow("favorite-row-compact.png")

        compose.runOnIdle { book = book.copy(subtitle = "林间", authors = listOf("林间"), totalCharacters = 456_789) }
        compose.onNodeWithText("林间").assertIsDisplayed()
        compose.onNodeWithText(formatApproximateCharacters(456_789)).assertDoesNotExist()
        assertEquals(metadata.fetchSemanticsNode().boundsInRoot.top, date.fetchSemanticsNode().boundsInRoot.top)
        captureRow("favorite-row-with-author.png")

        compose.runOnIdle { width = 320.dp; fontScale = 1.5f }
        metadata.assertIsDisplayed()
        date.assertIsDisplayed()
        assertTrue("窄屏大字时应换行保留完整更新日期", date.fetchSemanticsNode().boundsInRoot.top > metadata.fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithContentDescription("管理小说").assertIsDisplayed()
        captureRow("favorite-row-large-font.png")
    }

    @Test fun discoveryAndWenkuRowsHideProgressWithoutHidingUpdateDates() {
        var book by mutableStateOf(BookCard(BookRef("syosetu", "visibility"), "可见性测试", updateAt = 1704067200))
        var showProgress by mutableStateOf(false)
        compose.setContent {
            NoveliaTheme("light") {
                CompositionLocalProvider(LocalDensity provides Density(1.5f)) {
                    Surface(Modifier.requiredWidth(600.dp)) {
                        BookRow(book, {}, status = BookRowStatus(.38f, "已读 38%", "更新 3 章"), showReadingProgress = showProgress)
                    }
                }
            }
        }
        compose.onNodeWithText("已读 38%").assertDoesNotExist()
        compose.onNodeWithTag("book-reading-progress-${book.ref.key}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("book-update-date-${book.ref.key}", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("更新 3 章").assertIsDisplayed()
        compose.runOnIdle { book = book.copy(ref = BookRef("wenku", "series")); showProgress = true }
        compose.onNodeWithText("已读 38%").assertDoesNotExist()
        compose.onNodeWithTag("book-reading-progress-${book.ref.key}", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aCloudHistoryMarkerNeverRendersAnEmptyProgressBarAndDoesNotLeakAcrossAccounts() {
        var account by mutableStateOf("alice")
        val book = BookCard(BookRef("syosetu", "cloud-only"), "云端在读", cloudReading = CloudReadingProgress("alice", lastReadAt = 1700000000))
        compose.setContent {
            NoveliaTheme("light") {
                CompositionLocalProvider(LocalBookListPresentation provides BookListPresentation(account = account)) { BookRow(book, {}) }
            }
        }
        compose.onNodeWithText("有阅读记录").assertIsDisplayed()
        compose.onNodeWithText("未读").assertDoesNotExist()
        compose.onNodeWithTag("book-reading-progress-${book.ref.key}", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { account = "bob" }
        compose.onNodeWithText("有阅读记录").assertDoesNotExist()
        compose.onNodeWithText("云端进度待同步").assertIsDisplayed()
    }

    @Test fun mountedVolumesShowTheirOwnProgressInsteadOfTheSeriesProgress() {
        val parent = SavedBook(BookCard(BookRef("wenku", "parent"), "文库系列"), status = "读完")
        val first = SavedBook(BookCard(BookRef("local", "one"), "第一卷", total = 2), parentWenkuKey = parent.book.ref.key)
        val second = SavedBook(BookCard(BookRef("local", "two"), "第二卷", total = 2), parentWenkuKey = parent.book.ref.key)
        compose.setContent {
            NoveliaTheme("light") {
                CompositionLocalProvider(LocalBookListPresentation provides BookListPresentation(books = mapOf(parent.book.ref.key to parent))) {
                    Column {
                        BookRow(parent.book, {})
                        // 进度按已到达的章节计；两章中的第一章才对应 50%。
                        MountedVolumeRow(first, Position("one", index = 1, chapterIndex = 0, chapterCount = 2, paragraphCount = 8), Modifier, {}, {})
                        MountedVolumeRow(second, null, Modifier, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("已读 100%", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("book-reading-progress-${parent.book.ref.key}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("已读 50%", substring = true).assertIsDisplayed()
        compose.onNodeWithText("未读", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("book-reading-progress-${first.book.ref.key}", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("book-reading-progress-${second.book.ref.key}", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun compactSplitPaneAndLargeFontRowsWrapDatesInsteadOfHidingThem() {
        var width by mutableStateOf(360.dp)
        var fontScale by mutableStateOf(1f)
        val book = BookCard(BookRef("syosetu", "n1"), "风与书页", subtitle = "林间", total = 10, translated = 8, updateAt = 1704067200)
        compose.setContent {
            AppInteractionMode(eInk = true, reducedMotion = true) {
                NoveliaTheme("light") {
                    CompositionLocalProvider(LocalDensity provides Density(1.5f, fontScale)) {
                        Surface(Modifier.requiredWidth(width).testTag("book-row-preview")) {
                            BookRow(book, {}, status = BookRowStatus(.38f, "已读 38%", "更新 3 章"))
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("风与书页").assertIsDisplayed()
        compose.onNodeWithText("林间").assertIsDisplayed()
        compose.onNodeWithText("已读 38%").assertIsDisplayed()
        compose.onNodeWithText("更新 3 章").assertIsDisplayed()
        assertCoverLeftOfTitle(book)
        compose.onNodeWithText("8 / 10 章有译文").assertDoesNotExist()
        compose.onNodeWithTag("book-update-date-${book.ref.key}", useUnmergedTree = true).assertIsDisplayed()
        captureRow("book-row-compact.png")
        compose.runOnIdle { width = 428.dp }
        compose.onNodeWithTag("book-update-date-${book.ref.key}", useUnmergedTree = true).assertIsDisplayed()
        captureRow("book-row-split-pane.png")
        compose.runOnIdle { width = 600.dp }
        assertCoverLeftOfTitle(book)
        compose.onNodeWithTag("book-update-date-${book.ref.key}", useUnmergedTree = true).assertIsDisplayed()
        captureRow("book-row-wide.png")
        compose.runOnIdle { fontScale = 1.5f }
        assertCoverLeftOfTitle(book)
        compose.onNodeWithTag("book-update-date-${book.ref.key}", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("已读 38%").assertIsDisplayed()
        compose.onNodeWithText("更新 3 章").assertIsDisplayed()
        captureRow("book-row-large-font.png")
    }

    @Test fun visibleCloudOnlyFavoriteUpdatesAfterItsDetailArrives() {
        val book = WebOutline(providerId = "syosetu", novelId = "cloud-favorite", titleJp = "只有云端收藏的作品", total = 4).card("alice")
        val response = kotlinx.coroutines.CompletableDeferred<BookCard>()
        var fillColor = Color.Unspecified
        compose.setContent {
            AppInteractionMode(eInk = true, reducedMotion = true) {
                NoveliaTheme("dark") {
                    fillColor = MaterialTheme.colorScheme.primary
                    CompositionLocalProvider(LocalBookListPresentation provides BookListPresentation(account = "alice"), LocalDensity provides Density(1.5f)) {
                        val resolved = rememberCloudBookMetadata(book, "alice", 0) { response.await() }
                        val localZero = Position("1", index = 1, chapterIndex = 0, chapterCount = 4, paragraphCount = 5)
                        Surface(Modifier.requiredWidth(428.dp).testTag("book-row-preview")) {
                            BookRow(resolved, {}, compactMetadata = true, status = bookRowStatus(resolved, null,
                                localZero.takeIf { resolved.cloudReading?.chapterResolved == true }, null, "alice", preferCloud = true))
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("未读").assertDoesNotExist()
        compose.onNodeWithTag("book-reading-progress-${book.ref.key}", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            response.complete(book.copy(cloudReading = WebDetail(lastReadChapterId = "3", toc = (1..4).map { TocItem(chapterId = "$it") })
                .card(book.ref, "alice").cloudReading, updateAt = 1704067200))
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("读到第 3 章").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("读到第 3 章").assertIsDisplayed()
        compose.onNodeWithText("云端读到", substring = true).assertDoesNotExist()
        val bar = compose.onNodeWithTag("book-reading-progress-${book.ref.key}", useUnmergedTree = true).assertIsDisplayed()
        bar.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(.75f, 0f..1f)))
        val pixels = bar.captureToImage().toPixelMap()
        assertEquals("第 3/4 章应填充到 75%，不能只更新文案", fillColor.toArgb(), pixels[pixels.width / 2, pixels.height / 2].toArgb())
        assertNotEquals("进度条末尾仍应保留 25% 轨道", fillColor.toArgb(), pixels[pixels.width * 7 / 8, pixels.height / 2].toArgb())
        compose.onNodeWithTag("book-update-date-${book.ref.key}", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("未读").assertDoesNotExist()
        captureRow("book-row-cloud-progress.png")
    }

    private fun assertCoverLeftOfTitle(book: BookCard) {
        val cover = compose.onNodeWithContentDescription("${book.title} 默认封面", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val title = compose.onNode(hasText(book.title) and !hasAnyAncestor(hasContentDescription("${book.title} 默认封面")), useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("书籍封面应保留在标题左侧，且不与文字重叠", cover.right <= title.left)
    }

    private fun captureRow(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onNodeWithTag("book-row-preview").captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
