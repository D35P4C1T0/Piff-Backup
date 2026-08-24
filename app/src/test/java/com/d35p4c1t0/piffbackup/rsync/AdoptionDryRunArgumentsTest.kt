package com.d35p4c1t0.piffbackup.rsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdoptionDryRunArgumentsTest {
    @Test
    fun `preserves source contents semantics and cannot delete`() {
        val arguments = AdoptionDryRunArguments.local(
            File("/storage/emulated/0/DCIM/Camera"),
            File("/data/user/0/pkg/cache/native-spike/destination"),
        )

        assertEquals("/storage/emulated/0/DCIM/Camera/", arguments[arguments.lastIndex - 1])
        assertEquals("/data/user/0/pkg/cache/native-spike/destination/", arguments.last())
        assertTrue("--dry-run" in arguments)
        assertTrue("--size-only" in arguments)
        assertFalse(arguments.any { it == "--delete" || it.startsWith("--delete-") })
    }
}
