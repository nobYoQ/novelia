package cc.novelia.app.ui.theme

import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.community.ForumCategory
import cc.novelia.app.data.model.ThemePreset
import cc.novelia.app.data.model.ThemeBarGlass
import cc.novelia.app.data.model.ThemePresets
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class ThemeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun wallpaperRemainsVisibleBehindAllRootPagesAndAfterActivityRecreation() {
        val app = compose.activity.application as NoveliaApplication
        runBlocking { app.initialization.await() }
        val original = app.store.state.value
        val session = UUID.randomUUID().toString()
        val color = 0xFF37B9AE.toInt()
        val binding = app.forumSession.capture()
        val categoryKey = listOf("forum-categories", binding.source, binding.sourceRevision, app.store.cacheGeneration.value)
        val previousCategories = app.uiResults.categories.snapshot(categoryKey)
        fun awaitPaper(target: SemanticsNodeInteraction = compose.onRoot(), minimum: Float = .02f) {
            compose.waitUntil(10_000) {
                val image = target.captureToImage().toPixelMap()
                var visible = 0
                var samples = 0
                for (y in 0 until image.height step 24) for (x in 0 until image.width step 24) {
                    samples++
                    if (image[x, y].toArgb() == color) visible++
                }
                visible > samples * minimum
            }
        }
        try {
            app.uiResults.categories.put(categoryKey, listOf(ForumCategory(100, "novel")))
            app.store.update { it.copy(themePresets = ThemePresets(), theme = "light", reducedMotion = true,
                forumRulesReminderDismissed = true, reader = it.reader.copy(eInkMode = false)) }
            runBlocking {
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                bitmap.recycle()
                val hash = app.store.customThemes.pickImage(session) { bytes.inputStream() }
                val glass = ThemeBarGlass(enabled = true, opacity = 0)
                app.store.customThemes.save(ThemePreset(session, "导航背景", image = hash, overlay = 0, topBar = glass, bottomBar = glass), session)
            }
            awaitPaper()
            awaitPaper(compose.onNodeWithTag("page-top-bar"), .25f)
            awaitPaper(compose.onAllNodesWithTag("page-tabs").onFirst(), .25f)
            awaitPaper(compose.onNodeWithTag("root-navigation"), .25f)
            listOf("发现", "社区", "我的").forEach { label ->
                compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(hasTestTag("root-navigation"))).performClick()
                compose.waitForIdle()
                awaitPaper()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("page-top-bar").fetchSemanticsNodes().size == 1 }
                awaitPaper(compose.onNodeWithTag("page-top-bar"), .15f)
                awaitPaper(compose.onNodeWithTag("root-navigation"), .25f)
            }
            compose.activityRule.scenario.recreate()
            awaitPaper()
        } finally {
            if (previousCategories == null) app.uiResults.categories.discard(categoryKey)
            else app.uiResults.categories.put(categoryKey, previousCategories.value, fetchedAt = previousCategories.fetchedAt)
            runBlocking {
                app.store.customThemes.change {
                    app.store.update { it.copy(themePresets = original.themePresets, reader = original.reader,
                        reducedMotion = original.reducedMotion, theme = original.theme,
                        forumRulesReminderDismissed = original.forumRulesReminderDismissed) }
                }
                app.store.customThemes.discardDraft(session)
            }
        }
    }
}
