package com.thorfeeds.catalog.core

import kotlin.math.roundToLong

/**
 * [fileSize] on a feed is a display string. Sortable bytes, when they can be
 * read at all, come from the first number and the first unit after it.
 * Unparseable labels return null and never throw.
 */
object FileSizeParser {
    private val number = Regex("""\d+(?:\.\d+)?""")
    private val unit = Regex("""KB|MB|GB|TB""", RegexOption.IGNORE_CASE)
    private val thousands = Regex("""(?<=\d)[,_](?=\d)""")

    fun parseBytes(label: String?): Long? {
        if (label.isNullOrBlank()) return null
        val normalized = label.replace('\u00A0', ' ').replace(thousands, "")
        val numberMatch = number.find(normalized) ?: return null
        val value = numberMatch.value.toDoubleOrNull() ?: return null
        val after = normalized.substring(numberMatch.range.last + 1)
        val unitMatch = unit.find(after) ?: return null
        val multiplier = when (unitMatch.value.uppercase()) {
            "KB" -> 1024.0
            "MB" -> 1024.0 * 1024.0
            "GB" -> 1024.0 * 1024.0 * 1024.0
            "TB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> return null
        }
        val bytes = value * multiplier
        if (!bytes.isFinite() || bytes < 0.0 || bytes > Long.MAX_VALUE.toDouble()) return null
        return bytes.roundToLong()
    }
}
