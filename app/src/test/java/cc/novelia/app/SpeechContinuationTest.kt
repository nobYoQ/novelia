package cc.novelia.app

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechContinuationTest {
    @Test fun continuationSkipsIllustrationsAndKeepsChosenLanguageAndEngine() = runTest {
        val calls = mutableListOf<String>()
        val settings = ReaderSettings(mode = "jp", speechLanguage = "zh", engines = listOf("gpt", "sakura", "youdao"))
        val sequence = SpeechChapterSequence("first", "picture", settings) { id ->
            calls += id
            when(id) {
                "picture" -> Chapter(nextId = "second", paragraphs = listOf("<图片>https://example.com/image.png"))
                else -> Chapter(titleZh = "第二章", paragraphs = listOf("日本語"), sakuraParagraphs = listOf("另一个翻译"), gptParagraphs = listOf("中文正文。"))
            }
        }
        val next = sequence.next()
        assertEquals("second", next?.id)
        assertEquals(listOf("中文正文。"), next?.paragraphs)
        assertEquals(listOf("picture", "second"), calls)
        assertNull(sequence.next())
    }

    @Test fun failedLoadRetriesTheSameChapterWithoutSkippingIt() = runTest {
        var attempts = 0
        val sequence = SpeechChapterSequence("first", "second", ReaderSettings()) { id ->
            assertEquals("second", id)
            if(++attempts == 1) throw IOException("offline")
            Chapter(paragraphs = listOf("正文"))
        }
        try { sequence.next(); fail("Expected load failure") } catch(_: IOException) { }
        assertEquals("second", sequence.next()?.id)
        assertEquals(2, attempts)
        assertNull(sequence.next())
    }

    @Test fun cancelledLoadingDoesNotConsumeTheNextChapter() = runTest {
        val gate = CompletableDeferred<Unit>()
        var returned = false
        val sequence = SpeechChapterSequence("first", "second", ReaderSettings()) { gate.await(); Chapter(paragraphs = listOf("正文")) }
        val job = launch { sequence.next(); returned = true }
        runCurrent()
        job.cancel()
        gate.complete(Unit)
        runCurrent()
        assertFalse(returned)
        assertEquals("second", sequence.next()?.id)
    }

    @Test fun disabledContinuationDoesNotLoadAndCyclicChaptersCannotLoopForever() = runTest {
        val disabled = SpeechChapterSequence("first", "second", ReaderSettings(speechContinueChapters = false)) { error("Must not load") }
        assertNull(disabled.next())
        val cyclic = SpeechChapterSequence("first", "picture", ReaderSettings()) { Chapter(nextId = "first", paragraphs = emptyList()) }
        try { cyclic.next(); fail("Expected cycle detection") } catch(e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("章节顺序异常")) }
    }

    @Test fun localAndCachedChaptersNeverRequestNetwork() = runTest {
        val chapter = Chapter(paragraphs = listOf("正文"))
        val local = resolveSpeechChapter(BookRef("local", "book"), "1", false,
            local = { chapter }, cached = { error("Local must not use remote cache") }, network = { error("Must not request network") })
        assertSame(chapter, local)
        val cached = resolveSpeechChapter(BookRef("syosetu", "book"), "1", false,
            local = { error("Remote book") }, cached = { chapter }, network = { error("Must not request network") })
        assertSame(chapter, cached)
    }

    @Test fun missingCacheRequestsNetworkOnlyWhenAllowed() = runTest {
        val calls = mutableListOf<String>()
        suspend fun resolve(allow: Boolean) = resolveSpeechChapter(BookRef("syosetu", "book"), "2", allow,
            local = { error("Remote book") }, cached = { calls += "cache:$it"; null },
            network = { calls += "network:$it"; Chapter(paragraphs = listOf("网络正文")) })
        try { resolve(false); fail("Expected offline boundary") } catch(e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("尚未缓存")) }
        assertEquals(listOf("cache:2"), calls)
        assertEquals(listOf("网络正文"), resolve(true).paragraphs)
        assertEquals(listOf("cache:2", "cache:2", "network:2"), calls)
    }

    @Test fun initialAndFollowingChaptersUseTheSameLanguageProjectionAndSourceAnchor() {
        val chapter = Chapter(paragraphs = listOf("一", "二", "三"), gptParagraphs = listOf("中文一", "中文二", "中文三"))
        assertEquals(listOf("中文二", "中文三"), speechParagraphs(chapter, ReaderSettings(mode = "jp", speechLanguage = "zh"), 1))
        assertEquals(listOf("二", "三"), speechParagraphs(chapter, ReaderSettings(mode = "zh", speechLanguage = "jp"), 1))
    }
}
