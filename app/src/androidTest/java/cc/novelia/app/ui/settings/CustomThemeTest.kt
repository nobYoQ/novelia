package cc.novelia.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.navigation.compose.*
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.ui.components.media.RecoveringAsyncImage
import cc.novelia.app.data.backup.LibraryBackupService
import cc.novelia.app.data.catalog.KeywordStore
import cc.novelia.app.data.model.ThemePreset
import cc.novelia.app.data.model.ThemeBarGlass
import cc.novelia.app.data.model.ThemePresets
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CustomThemeTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun monochromeDisplaysGrayArtworkAndRestoresTheOriginalColors() {
        val file = File.createTempFile("mono-color-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.RED)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            var theme by mutableStateOf("light")
            compose.setContent {
                NoveliaTheme(theme) { RecoveringAsyncImage(file, null, Modifier.size(48.dp).testTag("color-artwork")) }
            }
            fun awaitColor(match: (Color) -> Boolean) = compose.waitUntil(10_000) {
                val image = compose.onNodeWithTag("color-artwork").captureToImage().toPixelMap()
                match(image[image.width / 2, image.height / 2])
            }
            awaitColor { it == Color.Red }
            compose.runOnIdle { theme = "monochrome" }
            awaitColor { it.red in 0.1f..0.5f && it.red == it.green && it.green == it.blue }
            compose.runOnIdle { theme = "light" }
            awaitColor { it == Color.Red }
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun monochromeAppModeOverridesWallpaperAndWorksInEInk() {
        val app = context.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val original = app.store.state.value
        val preset = ThemePreset("mono-test", "彩色方案", accent = 0xAF2255, image = "a".repeat(64))
        val file = File.createTempFile("mono-wallpaper-", ".png", context.cacheDir)
        image(file)
        var eInk by mutableStateOf(false)
        var scheme = MonochromeColors
        var hasPaper = false
        var follow = Color.Transparent
        fun choose(label: String) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performClick()
        }
        try {
            app.store.update { it.copy(theme = "dark", amoledBlack = true, themePresets = ThemePresets().saveAndSelect(preset)) }
            compose.setContent {
                val state by app.store.state.collectAsState()
                AppInteractionMode(eInk, true) {
                    NoveliaTheme(state.theme, amoledBlack = state.amoledBlack, preset = state.themePresets.active, imageFile = file) {
                        val rendered = MaterialTheme.colorScheme
                        val wallpaper = LocalThemeWallpaper.current
                        SideEffect {
                            scheme = rendered
                            hasPaper = wallpaper != null
                            follow = readerColors("system", rendered).background
                        }
                        val nav = rememberNavController()
                        val c = AppController(app, nav, rememberCoroutineScope(), remember { SnackbarHostState() })
                        NavHost(nav, startDestination = "settings") {
                            composable("settings") { SettingsScreen(c, "APPEARANCE") }
                            composable("custom-themes") { CustomThemesScreen(c) }
                        }
                    }
                }
            }
            choose("黑白")
            compose.onNodeWithText("黑白").assertIsSelected()
            compose.runOnIdle {
                assertEquals(Color.White, scheme.background)
                assertEquals(Color.Black, scheme.onSurface)
                assertEquals(Color.White, follow)
                assertFalse(hasPaper)
                assertEquals(preset, app.store.state.value.themePresets.active)
                eInk = true
            }
            compose.runOnIdle { assertEquals(MonochromeColors, scheme); eInk = false }
            choose("浅色")
            compose.runOnIdle { assertTrue(hasPaper) }
            choose("自定义主题")
            choose("黑白外观")
            compose.runOnIdle { assertEquals(MonochromeColors, scheme) }
            choose("彩色方案")
            compose.runOnIdle { assertEquals("system", app.store.state.value.theme); assertNotEquals(MonochromeColors, scheme) }
        } finally {
            app.store.update { it.copy(theme = original.theme, amoledBlack = original.amoledBlack, themePresets = original.themePresets) }
            file.delete()
        }
    }

    private fun image(file: File) {
        val pixels = IntArray(128 * 128) { if (it % 128 < 64) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        val bitmap = Bitmap.createBitmap(pixels, 128, 128, Bitmap.Config.ARGB_8888)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun pictureThemeCanBeCreatedEditedCopiedAndSelectedFromSettings() {
        val app = context.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val initial = app.store.state.value.themePresets
        val file = File.createTempFile("theme-", ".png", context.cacheDir)
        image(file)
        val picker = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
                }
            }
        }
        fun listItem(label: String) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performClick()
        }
        fun waitActive(name: String) = compose.waitUntil(10_000) { app.store.state.value.themePresets.active?.name == name }
        fun slider(label: String, value: Float) {
            compose.onNodeWithTag("theme-editor-controls").performScrollToNode(hasContentDescription(label))
            compose.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        }
        try {
            app.store.update { it.copy(themePresets = ThemePresets()) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                val library by app.store.state.collectAsState()
                val preset = library.themePresets.active
                NoveliaTheme("light", preset = preset, imageFile = preset?.image?.let(app.store.customThemes::imageFile)) {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                        val nav = rememberNavController()
                        val c = AppController(app, nav, rememberCoroutineScope(), remember { SnackbarHostState() })
                        NavHost(nav, startDestination = "settings") {
                            composable("settings") { SettingsScreen(c, "APPEARANCE") }
                            composable("custom-themes") { CustomThemesScreen(c) }
                        }
                    }
                }
            }
            listItem("自定义主题")
            listItem("新建主题")
            compose.onNodeWithTag("theme-editor-controls").performScrollToNode(hasSetTextAction())
            compose.onNode(hasSetTextAction()).performTextReplacement("雾林")
            listItem("主题色")
            compose.onNodeWithTag("reader-color-input-mode").performClick()
            compose.onNodeWithTag("reader-color-input").performTextReplacement("#8B3962")
            compose.onNodeWithText("应用颜色").performClick()
            compose.onNodeWithText("背景").performClick()
            listItem("图片背景")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("更换背景图片").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("保存并使用").assertIsDisplayed().assertIsEnabled()
            slider("图片模糊", 65f)
            if (!compose.onNodeWithContentDescription("编辑顶栏毛玻璃").isDisplayed()) {
                compose.onNodeWithTag("theme-editor-controls").performScrollToNode(hasContentDescription("编辑顶栏毛玻璃"))
            }
            compose.onNodeWithContentDescription("编辑顶栏毛玻璃").performClick()
            slider("顶栏模糊", 85f)
            slider("顶栏不透明度", 30f)
            listItem("底栏")
            slider("底栏模糊", 20f)
            slider("底栏不透明度", 75f)
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("保存并使用").performClick()
            waitActive("雾林")
            val original = app.store.state.value.themePresets.active!!
            assertEquals(0x8B3962L, original.accent)
            assertEquals(65, original.blur)
            assertEquals(ThemeBarGlass(true, 85, 30), original.topBar)
            assertEquals(ThemeBarGlass(true, 20, 75), original.bottomBar)
            assertTrue(app.store.customThemes.imageFile(original.image!!).isFile)
            assertTrue(file.delete())
            compose.onNodeWithContentDescription("管理雾林").performClick()
            compose.onNodeWithText("复制").performClick()
            compose.onNodeWithText("背景").performClick()
            listItem("纯色背景")
            compose.onNodeWithText("保存并使用").performClick()
            waitActive("雾林 副本")
            assertNull(app.store.state.value.themePresets.active?.image)
            compose.onNodeWithContentDescription("管理雾林 副本").performClick()
            compose.onNodeWithText("编辑").performClick()
            compose.onNodeWithTag("theme-editor-controls").performScrollToNode(hasSetTextAction())
            compose.onNode(hasSetTextAction()).performTextReplacement("午后")
            compose.onNodeWithText("保存并使用").performClick()
            waitActive("午后")
            assertEquals(2, app.store.state.value.themePresets.items.size)
            compose.onNodeWithText("雾林").performClick()
            waitActive("雾林")
            runBlocking { app.store.flush() }
            assertEquals(original, LocalStore(app).state.value.themePresets.active)
            compose.onNodeWithContentDescription("管理雾林").performClick()
            compose.onNodeWithText("删除").performClick()
            compose.onNodeWithText("删除").performClick()
            compose.waitUntil(10_000) { app.store.state.value.themePresets.items.size == 1 }
            assertNull(app.store.state.value.themePresets.activeId)
        } finally {
            runBlocking { app.store.customThemes.change { app.store.update { it.copy(themePresets = initial) } } }
            file.delete()
        }
    }

    @Test fun blurSoftensOnlyTheImageAndAmoledUsesSolidBlack() {
        val file = File.createTempFile("wallpaper-", ".png", context.cacheDir)
        image(file)
        val base = ThemePreset("test", "测试", image = "a".repeat(64), overlay = 0)
        var blur by mutableIntStateOf(0)
        var black by mutableStateOf(false)
        try {
            compose.setContent {
                NoveliaTheme(if (black) "dark" else "light", amoledBlack = black, preset = base.copy(blur = blur), imageFile = file) {
                    Box(Modifier.size(120.dp).testTag("paper").themePaper(androidx.compose.material3.MaterialTheme.colorScheme.background,
                        LocalThemeWallpaper.current)) {
                        Box(Modifier.align(Alignment.TopStart).size(12.dp).background(Color.Red))
                    }
                }
            }
            fun sample(x: Float, y: Float): Color {
                val pixels = compose.onNodeWithTag("paper").captureToImage().toPixelMap()
                return pixels[(x * pixels.width).toInt(), (y * pixels.height).toInt()]
            }
            compose.waitUntil(10_000) { sample(.48f, .5f).red < .02f && sample(.75f, .5f).red > .98f }
            compose.runOnIdle { blur = 100 }
            compose.waitUntil(10_000) { sample(.48f, .5f).red in .10f.. .70f }
            assertEquals(Color.Red, sample(.05f, .05f))
            compose.runOnIdle { black = true }
            compose.waitUntil(5_000) { sample(.75f, .5f) == Color.Black }
        } finally { file.delete() }
    }

    @Test fun backupRestoresSharedWallpaperAndDeletingLastPresetReclaimsIt() = runBlocking {
        val root = File(context.cacheDir, "theme-test-${UUID.randomUUID()}").apply { mkdirs() }
        fun storeAt(name: String) = LocalStore(object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "$name/files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "$name/cache").apply { mkdirs() }
        })
        try {
            val source = storeAt("source")
            val file = File(root, "original.png").also(::image)
            val hash = source.customThemes.pickImage("draft") { file.inputStream() }
            val preset = ThemePreset("one", "原图", image = hash, blur = 65,
                topBar = ThemeBarGlass(true, 80, 35), bottomBar = ThemeBarGlass(true, 30, 60))
            source.customThemes.save(preset, "draft")
            source.customThemes.discardDraft("draft")
            source.customThemes.save(preset.copy(id = "two", name = "副本"), "copy")
            val bytes = ByteArrayOutputStream()
            LibraryBackupService(source, KeywordStore(source.context)).export(bytes, includeOriginals = false)

            val restored = storeAt("target")
            assertNull(restored.customThemes.withAvailableImages(source.state.value.themePresets).active?.image)
            val backup = LibraryBackupService(restored, KeywordStore(restored.context))
            val preview = backup.prepare(bytes.toByteArray().inputStream())
            assertEquals(2, preview.themes)
            assertEquals(1, preview.themeImages)
            assertNull(backup.restore(preview.stagingId))
            assertEquals("two", storeAt("target").state.value.themePresets.activeId)
            assertEquals(preset.topBar, restored.state.value.themePresets.active?.topBar)
            assertEquals(preset.bottomBar, restored.state.value.themePresets.active?.bottomBar)
            val installed = restored.customThemes.imageFile(hash)
            assertArrayEquals(file.readBytes(), installed.readBytes())
            restored.customThemes.delete("one")
            assertTrue(installed.isFile)
            restored.customThemes.delete("two")
            assertFalse(installed.exists())
        } finally { root.deleteRecursively() }
        Unit
    }
}
