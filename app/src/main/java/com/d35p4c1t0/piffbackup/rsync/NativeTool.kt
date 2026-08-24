package com.d35p4c1t0.piffbackup.rsync

import android.content.Context
import java.io.File
import java.io.IOException

enum class NativeTool(val packagedFileName: String) {
    RSYNC("libpiffbackup_rsync.so"),
    SSH_CLIENT("libpiffbackup_dbclient.so"),
    SSH_KEYGEN("libpiffbackup_dropbearkey.so"),
    SSH_KEY_CONVERTER("libpiffbackup_dropbearconvert.so"),
}

class NativeToolLocator(context: Context) {
    private val nativeLibraryDirectory = File(context.applicationInfo.nativeLibraryDir)

    @Throws(IOException::class)
    fun require(tool: NativeTool): File {
        val directory = nativeLibraryDirectory.canonicalFile
        val candidate = File(directory, tool.packagedFileName).canonicalFile
        if (candidate.parentFile != directory) {
            throw IOException("Native tool resolved outside nativeLibraryDir")
        }
        if (!candidate.isFile) {
            throw IOException("Packaged native tool is missing: ${tool.name}")
        }
        if (!candidate.canExecute()) {
            throw IOException("Packaged native tool is not executable: ${tool.name}")
        }
        return candidate
    }
}
