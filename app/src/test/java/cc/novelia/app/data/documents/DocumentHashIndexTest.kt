package cc.novelia.app.data.documents

import org.junit.Assert.*
import org.junit.Test

class DocumentHashIndexTest {
    @Test fun legacyLibraryIsReadOnceAcrossManyImports() {
        var reads = 0
        var saved = emptyMap<String, String>()
        val ids = (1..100).map(Int::toString)
        val index = DocumentHashIndex(readHash = { reads++; "hash-$it" }, persist = { saved = it })
        repeat(10) { assertEquals("93", index.find("hash-93", ids)) }
        assertEquals(100, reads)
        val restored = DocumentHashIndex(saved, { error("Must use persisted metadata") }, {})
        assertEquals("93", restored.find("hash-93", ids))
    }

    @Test fun removedBooksAreNotReturnedAndNewBooksDoNotRequireDecoding() {
        val index = DocumentHashIndex(readHash = { error("Unexpected body read") }, persist = {})
        index.record("new", "hash")
        assertEquals("new", index.find("hash", listOf("new")))
        assertNull(index.find("hash", emptyList()))
        index.remove("new")
        assertNull(index.find("hash", emptyList()))
        assertNull(index.find("", listOf("new")))
    }
}
