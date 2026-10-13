package com.thorfeeds.catalog.core

data class ArchiveItem(
    val name: String,
    val directory: Boolean,
    val size: Long,
)

data class PlannedEntry(
    val sourceName: String,
    val destination: String,
    val size: Long,
)

enum class ArchiveFormat(val label: String) {
    ZIP("zip"),
    RAR("rar"),
}

class UnsafeArchivePath(val entryName: String) : Exception("The archive contains an unsafe path, so it was left in the game folder.")

/**
 * Decides where each archive entry lands inside the game folder.
 * A single top-level directory is removed. Every other internal path stays.
 */
object ArchiveLayout {
    fun formatOf(fileName: String): ArchiveFormat? {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".zip") -> ArchiveFormat.ZIP
            lower.endsWith(".rar") -> ArchiveFormat.RAR
            else -> null
        }
    }

    fun plan(items: List<ArchiveItem>): List<PlannedEntry> {
        val parsed = items.map { item ->
            Parsed(item, segments(item.name) ?: throw UnsafeArchivePath(item.name))
        }
        val tops = parsed.map { it.segments.first() }.toSet()
        val fileAtTop = parsed.any { !it.item.directory && it.segments.size == 1 }
        val strip = if (tops.size == 1 && !fileAtTop) tops.single() else null
        return parsed.mapNotNull { entry ->
            if (entry.item.directory) return@mapNotNull null
            val relative = if (strip != null) entry.segments.drop(1) else entry.segments
            if (relative.isEmpty()) return@mapNotNull null
            PlannedEntry(
                sourceName = entry.item.name,
                destination = relative.joinToString("/"),
                size = entry.item.size,
            )
        }
    }

    private fun segments(raw: String): List<String>? {
        val slash = raw.replace('\\', '/').trim()
        if (slash.isEmpty() || slash.startsWith("/") || slash.startsWith("\\")) return null
        if (DRIVE.containsMatchIn(slash)) return null
        val parts = slash.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.map { safeSegment(it) }
    }

    private fun safeSegment(part: String): String {
        val cleaned = part.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trim('.')
        if (cleaned.isBlank()) return "_"
        return cleaned.take(160)
    }

    private data class Parsed(val item: ArchiveItem, val segments: List<String>)

    private val DRIVE = Regex("""^[A-Za-z]:""")
}
