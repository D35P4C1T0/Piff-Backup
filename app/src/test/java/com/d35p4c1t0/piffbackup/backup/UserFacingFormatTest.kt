package com.d35p4c1t0.piffbackup.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Locale

class UserFacingFormatTest {
    @Test
    fun `formats zero small and maximum byte counts dynamically`() {
        assertEquals("0 B", UserFacingFormat.bytes(0L, Locale.US))
        assertEquals("999 B", UserFacingFormat.bytes(999L, Locale.US))
        assertEquals("1.0 KB", UserFacingFormat.bytes(1_000L, Locale.US))
        assertEquals("9.2 EB", UserFacingFormat.bytes(Long.MAX_VALUE, Locale.US))
    }

    @Test
    fun `formats long item counts without a fixed collection scale`() {
        assertEquals("9,223,372,036,854,775,807", UserFacingFormat.itemCount(Long.MAX_VALUE, Locale.US))
        assertThrows(IllegalArgumentException::class.java) { UserFacingFormat.itemCount(-1L, Locale.US) }
        assertThrows(IllegalArgumentException::class.java) { UserFacingFormat.bytes(-1L, Locale.US) }
    }
}
