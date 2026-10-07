package com.d35p4c1t0.piffbackup.backup
import org.junit.Assert.*
import org.junit.Test
class FileSelectionPolicyTest {
    @Test fun exclusionsApplyToNestedFilesAndHiddenComponents() {
        val policy = FileSelectionPolicy(listOf("Cache/", "**/*.tmp", "*.bak"),false)
        listOf("Cache/a.jpg","docs/a.tmp","root.tmp","a.bak","docs/.hidden/a.txt", ".rsync-partial/a").forEach { assertFalse(it,policy.includes(it)) }
        assertTrue(policy.includes("Trips/photo 😄.jpg"))
    }
    @Test fun invalidPatternsAreRejected() {
        listOf("/absolute","../outside","a/../b","a\u0000b", "a\\b", "").forEach { assertTrue(runCatching { FileSelectionPolicy(listOf(it)) }.isFailure) }
    }
}
