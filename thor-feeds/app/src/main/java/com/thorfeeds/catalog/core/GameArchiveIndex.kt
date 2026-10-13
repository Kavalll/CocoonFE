package com.thorfeeds.catalog.core

data class LocatedArchive(
    val folderName: String?,
    val fileName: String,
)

object GameArchiveIndex {
    fun forTitle(canonicalTitle: String, archives: List<LocatedArchive>): List<LocatedArchive> {
        val key = TitleCanonicalizer.matchKey(canonicalTitle)
        if (key.length < 2) return emptyList()
        val folder = Names.sanitizeFolder(canonicalTitle)
        return archives.filter { archive ->
            if (Names.isPartialName(archive.fileName) || ArchiveLayout.formatOf(archive.fileName) == null) return@filter false
            val parent = archive.folderName
            if (parent == null) {
                TitleCanonicalizer.matchKey(stripExtension(archive.fileName)) == key
            } else {
                parent.equals(folder, ignoreCase = true) || TitleCanonicalizer.matchKey(parent) == key
            }
        }
    }

    private fun stripExtension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) return fileName
        return fileName.substring(0, dot)
    }
}
