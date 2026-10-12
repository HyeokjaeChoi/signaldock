package local.signaldock.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class KeyConfigTest {
    @Test
    fun `two different non-blank keys are accepted`() {
        assertEquals("c" to "q", resolveKeys("c", "q"))
    }

    @Test
    fun `startup fails when the query key is missing, blank, or equal to the collection key`() {
        for (query in listOf(null, "", "  ")) {
            val error = assertFailsWith<IllegalArgumentException> { resolveKeys("secret-collection", query) }
            assertEquals("SIGNALDOCK_QUERY_KEY is missing or blank", error.message)
        }
        val same = assertFailsWith<IllegalArgumentException> { resolveKeys("secret-same", "secret-same") }
        assertEquals("SIGNALDOCK_QUERY_KEY must differ from SIGNALDOCK_COLLECTION_KEY", same.message)
    }

    @Test
    fun `startup fails when the collection key is missing or blank and errors never contain key values`() {
        for (collection in listOf(null, "", " ")) {
            assertFailsWith<IllegalArgumentException> { resolveKeys(collection, "q") }
        }
        val error = assertFailsWith<IllegalArgumentException> { resolveKeys("secret-same", "secret-same") }
        assertFalse(error.message!!.contains("secret-same"))
    }
}
