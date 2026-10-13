package com.thorfeeds.catalog.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.thorfeeds.catalog.core.ArchiveLayout
import com.thorfeeds.catalog.core.LocatedArchive
import com.thorfeeds.catalog.core.Names
import com.thorfeeds.catalog.core.StoredEntry

class FolderScanner(private val context: Context) {
    fun hasAccess(treeUri: String): Boolean {
        val parsed = Uri.parse(treeUri)
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == parsed && permission.isReadPermission && permission.isWritePermission
        }
    }

    fun label(treeUri: String): String? {
        return DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.name
    }

    fun scan(treeUri: String): FolderSnapshot {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return FolderSnapshot(emptyList(), emptyList())
        if (!tree.canRead()) return FolderSnapshot(emptyList(), emptyList())
        val entries = mutableListOf<StoredEntry>()
        val archives = mutableListOf<LocatedArchive>()
        for (child in tree.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                val payloads = mutableListOf<String>()
                for (file in child.listFiles()) {
                    val fileName = file.name ?: continue
                    if (!file.isFile || Names.isPartialName(fileName)) continue
                    payloads += fileName
                    if (ArchiveLayout.formatOf(fileName) != null) {
                        archives += LocatedArchive(folderName = name, fileName = fileName)
                    }
                }
                entries += StoredEntry(name = name, isDirectory = true, hasPayload = payloads.isNotEmpty())
                payloads.forEach { fileName ->
                    entries += StoredEntry(name = fileName, isDirectory = false, hasPayload = true)
                }
            } else if (child.isFile && !Names.isPartialName(name)) {
                entries += StoredEntry(name = name, isDirectory = false, hasPayload = true)
                if (ArchiveLayout.formatOf(name) != null) {
                    archives += LocatedArchive(folderName = null, fileName = name)
                }
            }
        }
        return FolderSnapshot(entries, archives)
    }

    data class FolderSnapshot(
        val entries: List<StoredEntry>,
        val archives: List<LocatedArchive>,
    )

    companion object {
        const val TREE_FLAGS: Int =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
