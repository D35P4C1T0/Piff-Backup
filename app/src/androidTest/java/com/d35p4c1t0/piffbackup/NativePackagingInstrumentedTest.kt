package com.d35p4c1t0.piffbackup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d35p4c1t0.piffbackup.rsync.AdoptionPreviewSummary
import com.d35p4c1t0.piffbackup.rsync.NativeFeasibilityProbe
import com.d35p4c1t0.piffbackup.rsync.NativeProcessRunner
import com.d35p4c1t0.piffbackup.rsync.NativeTool
import com.d35p4c1t0.piffbackup.rsync.NativeToolLocator
import com.d35p4c1t0.piffbackup.rsync.RsyncCommandBuilder
import com.d35p4c1t0.piffbackup.rsync.RsyncOutputParser
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativePackagingInstrumentedTest {
    @Test
    fun packagedArm64ToolsExecuteFromNativeLibraryDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val locator = NativeToolLocator(context)

        NativeTool.entries.forEach { tool ->
            assertTrue("${tool.name} should be executable", locator.require(tool).canExecute())
        }
        val report = NativeFeasibilityProbe(context).runLocalOnly()
        assertTrue(report.summary, report.successful)
    }

    @Test
    fun adoptionPreviewOutputIsMachineParseableOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(context.cacheDir, "phase2-${System.nanoTime()}")
        val source = File(work, "source").apply { mkdirs() }
        val destination = File(work, "destination").apply { mkdirs() }
        File(source, "same-size.jpg").writeText("abc")
        File(destination, "same-size.jpg").writeText("xyz")
        File(source, "new-file.jpg").writeText("12345")

        try {
            val rsync = NativeToolLocator(context).require(NativeTool.RSYNC)
            val command = listOf(
                rsync.path,
                "-rlt",
                "--size-only",
                "--dry-run",
                "--itemize-changes",
                "--itemize-changes",
                "--out-format=${RsyncCommandBuilder.ITEM_RECORD_PREFIX}%i:%l",
                "--",
                source.path + "/",
                destination.path + "/",
            )
            val result = NativeProcessRunner().start(command, work).await()

            assertTrue(result.stderr, result.exitCode == 0)
            assertTrue(
                result.stdout,
                RsyncOutputParser.parseAdoptionPreview(result.stdout) == AdoptionPreviewSummary(1L, 1L, 5L),
            )
        } finally {
            work.deleteRecursively()
        }
    }
}
