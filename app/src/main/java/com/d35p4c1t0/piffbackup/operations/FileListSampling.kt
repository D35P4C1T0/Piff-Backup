package com.d35p4c1t0.piffbackup.operations

import java.io.File
import java.security.MessageDigest
import java.util.PriorityQueue
import com.d35p4c1t0.piffbackup.media.RelativeFileListPath

object FileListSampling {
    fun forEach(file: File, consumer: (String) -> Unit) {
        file.inputStream().buffered().use { input ->
            val entry = java.io.ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0) { require(entry.size() == 0) { "Truncated file list" }; break }
                if (value == 0) {
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    val path = decoder.decode(java.nio.ByteBuffer.wrap(entry.toByteArray())).toString()
                    consumer(RelativeFileListPath.create(path).value); entry.reset()
                } else { require(entry.size() < 65536); entry.write(value) }
            }
        }
    }
    /** O(limit) memory regardless of folder size. */
    fun sample(file: File, seed: String, limit: Int = 100) {
        require(limit in 1..1000)
        data class Entry(val rank: String, val path: String)
        val selected = PriorityQueue<Entry>(compareByDescending<Entry> { it.rank }.thenByDescending { it.path })
        val digest = MessageDigest.getInstance("SHA-256")
        forEach(file) { path ->
            val rank = digest.digest((seed + "\u0000" + path).toByteArray()).joinToString("") { "%02x".format(it) }
            selected.add(Entry(rank, path)); if (selected.size > limit) selected.poll()
        }
        file.outputStream().buffered().use { output -> selected.sortedBy { it.path }.forEach {
            output.write(it.path.toByteArray()); output.write(0)
        } }
    }
}
