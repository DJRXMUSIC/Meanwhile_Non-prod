package app.meanwhile.domain.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UuidV7Test {
    @Test
    fun versionVariantAndTimestamp() {
        val ts = 1_791_000_000_123L
        val id = UuidV7.generate(ts)
        assertEquals(7, id.version())
        assertEquals(2, id.variant())
        assertEquals(ts, UuidV7.timestampOf(id))
    }

    @Test
    fun sortsByTime() {
        val a = UuidV7.string(1_000L)
        val b = UuidV7.string(2_000L)
        assertTrue(a < b)
    }
}
