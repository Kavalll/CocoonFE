package com.thorfeeds.catalog.data

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import com.thorfeeds.catalog.core.ArchiveFormat
import com.thorfeeds.catalog.core.ArchiveUnpacker
import com.thorfeeds.catalog.core.UnpackException
import com.thorfeeds.catalog.core.UnpackOutput
import java.io.File
import java.io.OutputStream

class DocumentUnpackOutput(
    private val resolver: ContentResolver,
    private val root: DocumentFile,
) : UnpackOutput {
    private val children = mutableMapOf<String, MutableMap<String, DocumentFile>>()

    override fun create(relativePath: String): OutputStream {
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            throw UnpackException(ArchiveFormat.ZIP.label, false, "The archive contains an unsafe path, so it was left in the game folder.")
        }
        val directory = ensureDirectory(parts.dropLast(1))
        val name = parts.last()
        val listing = listing(directory, parts.dropLast(1).joinToString("/"))
        listing[name]?.takeIf { !it.isDirectory }?.delete()
        val file = directory.createFile("application/octet-stream", name)
            ?: throw UnpackException(ArchiveFormat.ZIP.label, false, "Couldn't create $name, so the archive was left in the game folder.")
        listing[name] = file
        return resolver.openOutputStream(file.uri, "wt")
            ?: throw UnpackException(ArchiveFormat.ZIP.label, false, "Couldn't open $name, so the archive was left in the game folder.")
    }

    override fun exists(relativePath: String): Boolean {
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        val directory = findDirectory(parts.dropLast(1)) ?: return false
        val file = listing(directory, parts.dropLast(1).joinToString("/"))[parts.last()]
        return file != null && file.isFile
    }

    private fun ensureDirectory(parts: List<String>): DocumentFile {
        var directory = root
        var walked = ""
        for (part in parts) {
            val listing = listing(directory, walked)
            val existing = listing[part]
            directory = if (existing?.isDirectory == true) {
                existing
            } else {
                directory.createDirectory(part)?.also { created -> listing[part] = created }
                    ?: throw UnpackException(
                        ArchiveFormat.ZIP.label,
                        false,
                        "Couldn't create a folder for $part, so the archive was left in the game folder.",
                    )
            }
            walked = if (walked.isEmpty()) part else "$walked/$part"
        }
        return directory
    }

    private fun findDirectory(parts: List<String>): DocumentFile? {
        var directory = root
        var walked = ""
        for (part in parts) {
            directory = listing(directory, walked)[part]?.takeIf { it.isDirectory } ?: return null
            walked = if (walked.isEmpty()) part else "$walked/$part"
        }
        return directory
    }

    private fun listing(directory: DocumentFile, key: String): MutableMap<String, DocumentFile> {
        return children.getOrPut(key) {
            directory.listFiles().mapNotNull { file -> file.name?.let { it to file } }.toMap().toMutableMap()
        }
    }
}

object GameArchiveExtractor {
    fun extract(
        resolver: ContentResolver,
        archive: DocumentFile,
        format: ArchiveFormat,
        cancelled: () -> Boolean,
        onProgress: (Long, Long?) -> Unit,
    ): List<String> {
        val parent = archive.parentFile
            ?: throw UnpackException(
                format.label,
                format == ArchiveFormat.RAR,
                "The game folder disappeared, so the archive was left in place.",
            )
        val descriptor = resolver.openFileDescriptor(archive.uri, "r")
        if (descriptor != null) {
            descriptor.use { opened ->
                val file = File("/proc/self/fd/${opened.fd}")
                if (file.canRead()) {
                    try {
                        return ArchiveUnpacker.unpack(file, format, DocumentUnpackOutput(resolver, parent), cancelled, onProgress)
                    } catch (error: UnpackException) {
                        if (format != ArchiveFormat.ZIP || error.message.orEmpty().contains("unsafe") || error.message.orEmpty().contains("did not contain")) {
                            throw error
                        }
                    }
                }
            }
        }
        if (format == ArchiveFormat.ZIP) {
            return ArchiveUnpacker.unpackZipStream(
                open = {
                    resolver.openInputStream(archive.uri)
                        ?: throw UnpackException(ArchiveFormat.ZIP.label, false, "The zip archive could not be opened, so it was left in the game folder.")
                },
                output = DocumentUnpackOutput(resolver, parent),
                cancelled = cancelled,
                onProgress = onProgress,
            )
        }
        throw UnpackException(format.label, true, "This rar could not be opened, so it was left in the game folder.")
    }
}
