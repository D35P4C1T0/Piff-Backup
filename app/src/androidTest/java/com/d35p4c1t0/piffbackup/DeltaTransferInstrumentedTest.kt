package com.d35p4c1t0.piffbackup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d35p4c1t0.piffbackup.rsync.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeltaTransferInstrumentedTest {
    @Test fun deltaTransferBenchmarksPreserveContentAndReuseExistingOrPartialBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir,"delta-${System.nanoTime()}").apply { mkdir() }
        val random = java.util.Random(17)
        try {
            for (scenario in listOf("mutable", "new-media", "many-small", "partial")) {
                val work = root.resolve(scenario).apply { mkdir() }
                val source = work.resolve("source").apply { mkdir() }
                val whole = work.resolve("whole").apply { mkdir() }
                val delta = work.resolve("delta").apply { mkdir() }
                val count = if (scenario == "many-small") 100 else 1
                repeat(count) { index ->
                    val size = when (scenario) { "many-small" -> 4096; "partial" -> 8*1024*1024; else -> 1024*1024 }
                    val bytes = ByteArray(size).also(random::nextBytes)
                    val name = "file-$index.bin"
                    if (scenario in setOf("mutable", "many-small")) {
                        whole.resolve(name).writeBytes(bytes); delta.resolve(name).writeBytes(bytes)
                        bytes[size/2] = (bytes[size/2].toInt() xor 1).toByte()
                    } else if (scenario == "partial") {
                        for (destination in listOf(whole,delta)) destination.resolve(".rsync-partial").apply { mkdir() }
                            .resolve(name).writeBytes(bytes.copyOf(size/2))
                    }
                    source.resolve(name).writeBytes(bytes)
                }
                fun transfer(destination: File, option: String): NativeProcessResult {
                    val result = NativeProcessRunner().start(listOf(
                        NativeToolLocator(context).require(NativeTool.RSYNC).path,"-rt","--checksum",option,
                        "--partial","--partial-dir=.rsync-partial","--stats","--",source.path+"/",destination.path+"/"),work).await(30000)
                    assertEquals(result.stderr,0,result.exitCode)
                    source.listFiles()!!.forEach { assertArrayEquals(it.name,it.readBytes(),destination.resolve(it.name).readBytes()) }
                    return result
                }
                val full = transfer(whole,"--whole-file")
                val incremental = transfer(delta,"--no-whole-file")
                fun literal(result: NativeProcessResult): Long = Regex("Literal data: ([0-9,]+) bytes")
                    .find(result.stdout)!!.groupValues[1].replace(",","").toLong()
                if (scenario != "new-media") assertTrue(scenario, literal(incremental) < literal(full))
                else assertEquals(literal(full),literal(incremental))
                android.util.Log.i("PiffBackupBenchmark", "$scenario whole=${literal(full)} delta=${literal(incremental)} wholeMs=${full.durationMillis} deltaMs=${incremental.durationMillis}")
            }
        } finally { root.deleteRecursively() }
    }
}
