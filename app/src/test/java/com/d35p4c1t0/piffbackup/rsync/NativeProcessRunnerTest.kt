package com.d35p4c1t0.piffbackup.rsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread

class NativeProcessRunnerTest {
    private val workingDirectory = File(requireNotNull(System.getProperty("java.io.tmpdir")))

    @Test
    fun `captures stdout and stderr independently without shell interpolation`() {
        val marker = Files.createTempFile("piffbackup-marker", ".tmp").toFile().apply { delete() }
        val injectionShapedValue = "literal;touch ${marker.path}"

        val result = NativeProcessRunner().start(
            listOf("/usr/bin/printf", "%s", injectionShapedValue),
            workingDirectory,
        ).await()

        assertEquals(0, result.exitCode)
        assertEquals(injectionShapedValue, result.stdout)
        assertEquals("", result.stderr)
        assertFalse(marker.exists())
    }

    @Test
    fun `bounded capture continues draining after truncation`() {
        val result = NativeProcessRunner(captureLimitBytes = 4).start(
            listOf("/usr/bin/printf", "123456789"),
            workingDirectory,
        ).await()

        assertEquals("1234", result.stdout)
        assertTrue(result.stdoutTruncated)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `cancellation terminates a running process`() {
        val session = NativeProcessRunner().start(
            listOf("/bin/sleep", "30"),
            workingDirectory,
        )
        val cancellation = thread {
            Thread.sleep(100)
            session.cancel()
        }

        val result = session.await()
        cancellation.join()

        assertTrue(result.cancelled)
        assertTrue(result.exitCode != 0)
        assertTrue(result.durationMillis < 5_000)
    }
}
