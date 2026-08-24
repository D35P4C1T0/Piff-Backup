package com.d35p4c1t0.piffbackup.backup

import java.text.NumberFormat
import java.util.Locale

object UserFacingFormat {
    private val byteUnits = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")

    fun itemCount(count: Long, locale: Locale = Locale.getDefault()): String {
        require(count >= 0L) { "Item count must not be negative" }
        return NumberFormat.getIntegerInstance(locale).format(count)
    }

    fun bytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
        require(bytes >= 0L) { "Byte count must not be negative" }
        if (bytes < 1_000L) return "$bytes B"

        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1_000.0 && unitIndex < byteUnits.lastIndex) {
            value /= 1_000.0
            unitIndex++
        }
        val formatter = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = if (value < 10.0) 1 else 0
            maximumFractionDigits = 1
        }
        return "${formatter.format(value)} ${byteUnits[unitIndex]}"
    }
}
