package com.d35p4c1t0.piffbackup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d35p4c1t0.piffbackup.rsync.NativeFeasibilityProbe
import com.d35p4c1t0.piffbackup.rsync.NativeTool
import com.d35p4c1t0.piffbackup.rsync.NativeToolLocator
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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
}
