package local.signaldock.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drift guard for the SDK/server wire contract (ADR-0027). The files in `fixtures/sdk-wire/` are
 * request bodies that the real SDK builds (the SDK test `WireCorpusTest` keeps them equal to its
 * output). The real request parser and the per-item rules must accept all of them: no
 * request-level failure and no rejected item.
 */
class SdkWireCorpusTest {
    private val files = File("../../fixtures/sdk-wire").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    @Test
    fun `corpus is present`() {
        assertTrue(files.size >= 9, "expected the SDK corpus in fixtures/sdk-wire, found ${files.size} files")
    }

    @Test
    fun `server accepts every request body the SDK builds`() {
        files.forEach { file ->
            val batch = try {
                parseBatch(file.readBytes(), "1")
            } catch (e: Exception) {
                throw AssertionError("${file.name}: request-level failure ${e}", e)
            }
            assertTrue(batch.items.isNotEmpty(), file.name)
            batch.items.forEach { item ->
                assertEquals(null, item.rejectionCode, "${file.name} item ${item.index}")
                assertNotNull(item.event, "${file.name} item ${item.index}")
            }
        }
    }
}
