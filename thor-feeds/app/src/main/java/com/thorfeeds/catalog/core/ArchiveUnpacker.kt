package com.thorfeeds.catalog.core

import com.github.junrar.Archive
import com.github.junrar.exception.RarException
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class UnpackException(
    val format: String,
    val offerZArchiver: Boolean,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

interface UnpackOutput {
    fun create(relativePath: String): OutputStream
    fun exists(relativePath: String): Boolean
}

class DirectoryUnpackOutput(private val root: File) : UnpackOutput {
    override fun create(relativePath: String): OutputStream {
        val file = resolved(relativePath)
        file.parentFile?.mkdirs()
        return file.outputStream()
    }

    override fun exists(relativePath: String): Boolean = resolved(relativePath).isFile

    private fun resolved(relativePath: String): File {
        val rootPath = root.canonicalFile.toPath()
        val file = File(root, relativePath).canonicalFile
        if (!file.toPath().startsWith(rootPath)) {
            throw UnpackException(ArchiveFormat.ZIP.label, false, "The archive contains an unsafe path, so it was left in the game folder.")
        }
        return file
    }
}

object ArchiveUnpacker {
    fun unpack(
        archive: File,
        format: ArchiveFormat,
        output: UnpackOutput,
        cancelled: () -> Boolean,
        onProgress: (written: Long, total: Long?) -> Unit = { _, _ -> },
    ): List<String> {
        checkCancelled(cancelled)
        return when (format) {
            ArchiveFormat.ZIP -> unpackZip(archive, output, cancelled, onProgress)
            ArchiveFormat.RAR -> unpackRar(archive, output, cancelled, onProgress)
        }
    }

    private fun unpackZip(
        archive: File,
        output: UnpackOutput,
        cancelled: () -> Boolean,
        onProgress: (Long, Long?) -> Unit,
    ): List<String> {
        try {
            ZipFile(archive).use { zip ->
                val items = zip.entries().toList().map { entry ->
                    ArchiveItem(entry.name, entry.isDirectory, entry.size)
                }
                val plan = ArchiveLayout.plan(items)
                if (plan.isEmpty()) throw emptyArchive(ArchiveFormat.ZIP)
                val total = totalBytes(plan)
                var written = 0L
                val created = mutableListOf<String>()
                for (entry in plan) {
                    checkCancelled(cancelled)
                    val zipEntry = zip.getEntry(entry.sourceName) ?: zip.entries().toList().firstOrNull { it.name == entry.sourceName }
                    if (zipEntry == null || zipEntry.isDirectory) continue
                    output.create(entry.destination).use { out ->
                        zip.getInputStream(zipEntry).use { input ->
                            written = copy(input, out, written, total, cancelled, onProgress)
                        }
                    }
                    created += entry.destination
                }
                verify(created, output, ArchiveFormat.ZIP)
                return created
            }
        } catch (cancelledDownload: CancellationException) {
            throw cancelledDownload
        } catch (unsafe: UnsafeArchivePath) {
            throw UnpackException(ArchiveFormat.ZIP.label, false, unsafe.message ?: "The archive contains an unsafe path, so it was left in the game folder.")
        } catch (unpack: UnpackException) {
            throw unpack
        } catch (zip: ZipException) {
            val password = zip.message.orEmpty().contains("password", ignoreCase = true) ||
                zip.message.orEmpty().contains("encrypted", ignoreCase = true)
            val detail = if (password) {
                "This zip is password protected, so it was left in the game folder."
            } else {
                "This zip could not be opened, so it was left in the game folder."
            }
            throw UnpackException(ArchiveFormat.ZIP.label, false, detail, zip)
        } catch (error: IOException) {
            throw UnpackException(ArchiveFormat.ZIP.label, false, "This zip could not be opened, so it was left in the game folder.", error)
        }
    }

    fun unpackZipStream(
        open: () -> InputStream,
        output: UnpackOutput,
        cancelled: () -> Boolean,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): List<String> {
        val items = mutableListOf<ArchiveItem>()
        try {
            open().use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        checkCancelled(cancelled)
                        val entry = zip.nextEntry ?: break
                        items += ArchiveItem(entry.name, entry.isDirectory, entry.size)
                        zip.closeEntry()
                    }
                }
            }
            val plan = ArchiveLayout.plan(items)
            if (plan.isEmpty()) throw emptyArchive(ArchiveFormat.ZIP)
            val byName = plan.associateBy { it.sourceName }
            val total = totalBytes(plan)
            var written = 0L
            val created = mutableListOf<String>()
            open().use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        checkCancelled(cancelled)
                        val entry: ZipEntry = zip.nextEntry ?: break
                        val planned = byName[entry.name]
                        if (planned != null && !entry.isDirectory) {
                            output.create(planned.destination).use { out ->
                                written = copy(zip, out, written, total, cancelled, onProgress)
                            }
                            created += planned.destination
                        }
                        zip.closeEntry()
                    }
                }
            }
            verify(created, output, ArchiveFormat.ZIP)
            return created
        } catch (cancelledDownload: CancellationException) {
            throw cancelledDownload
        } catch (unsafe: UnsafeArchivePath) {
            throw UnpackException(ArchiveFormat.ZIP.label, false, unsafe.message ?: "The archive contains an unsafe path, so it was left in the game folder.")
        } catch (unpack: UnpackException) {
            throw unpack
        } catch (zip: ZipException) {
            throw UnpackException(ArchiveFormat.ZIP.label, false, "This zip could not be opened, so it was left in the game folder.", zip)
        } catch (error: IOException) {
            throw UnpackException(ArchiveFormat.ZIP.label, false, "This zip could not be opened, so it was left in the game folder.", error)
        }
    }

    private fun unpackRar(
        archive: File,
        output: UnpackOutput,
        cancelled: () -> Boolean,
        onProgress: (Long, Long?) -> Unit,
    ): List<String> {
        try {
            Archive(archive).use { rar ->
                if (rar.isPasswordProtected) {
                    throw UnpackException(ArchiveFormat.RAR.label, true, "This rar is password protected, so it was left in the game folder.")
                }
                val headers = rar.fileHeaders
                if (headers.any { it.isEncrypted }) {
                    throw UnpackException(ArchiveFormat.RAR.label, true, "This rar is password protected, so it was left in the game folder.")
                }
                if (headers.any { it.isSplitAfter || it.isSplitBefore }) {
                    throw UnpackException(ArchiveFormat.RAR.label, true, "This rar is split across volumes, so it was left in the game folder.")
                }
                val items = headers.filter { it.redirection == null }.map { header ->
                    val size = if (header.isUnpSizeUnknown) -1L else header.fullUnpackSize
                    ArchiveItem(header.fileName.orEmpty(), header.isDirectory, size)
                }
                val plan = ArchiveLayout.plan(items).associateBy { it.sourceName }
                if (plan.isEmpty()) throw emptyArchive(ArchiveFormat.RAR)
                val total = totalBytes(plan.values.toList())
                var written = 0L
                val created = mutableListOf<String>()
                for (header in headers) {
                    checkCancelled(cancelled)
                    if (header.redirection != null || header.isDirectory) continue
                    val planned = plan[header.fileName] ?: continue
                    output.create(planned.destination).use { out ->
                        val guarded = object : OutputStream() {
                            override fun write(b: Int) {
                                checkCancelled(cancelled)
                                out.write(b)
                                written += 1
                                onProgress(written, total)
                            }

                            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                                checkCancelled(cancelled)
                                out.write(buffer, offset, length)
                                written += length
                                onProgress(written, total)
                            }
                        }
                        rar.extractFile(header, guarded)
                    }
                    created += planned.destination
                }
                verify(created, output, ArchiveFormat.RAR)
                return created
            }
        } catch (cancelledDownload: CancellationException) {
            throw cancelledDownload
        } catch (unsafe: UnsafeArchivePath) {
            throw UnpackException(ArchiveFormat.RAR.label, true, unsafe.message ?: "The archive contains an unsafe path, so it was left in the game folder.")
        } catch (unpack: UnpackException) {
            throw unpack
        } catch (rar: RarException) {
            if (cancelled()) throw CancellationException("Download cancelled")
            throw UnpackException(
                ArchiveFormat.RAR.label,
                true,
                "This rar could not be opened, so it was left in the game folder.",
                rar,
            )
        } catch (error: IOException) {
            if (cancelled()) throw CancellationException("Download cancelled")
            throw UnpackException(
                ArchiveFormat.RAR.label,
                true,
                "This rar could not be opened, so it was left in the game folder.",
                error,
            )
        }
    }

    private fun copy(
        input: InputStream,
        output: OutputStream,
        already: Long,
        total: Long?,
        cancelled: () -> Boolean,
        onProgress: (Long, Long?) -> Unit,
    ): Long {
        val buffer = ByteArray(256 * 1024)
        var written = already
        while (true) {
            checkCancelled(cancelled)
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            written += count
            onProgress(written, total)
        }
        return written
    }

    private fun verify(created: List<String>, output: UnpackOutput, format: ArchiveFormat) {
        val missing = created.firstOrNull { !output.exists(it) }
        if (missing != null) {
            throw UnpackException(
                format.label,
                format == ArchiveFormat.RAR,
                "Extraction finished but $missing was not in the game folder. The archive was kept.",
            )
        }
    }

    private fun totalBytes(plan: List<PlannedEntry>): Long? {
        if (plan.any { it.size < 0L }) return null
        return plan.sumOf { it.size }
    }

    private fun emptyArchive(format: ArchiveFormat): UnpackException {
        return UnpackException(format.label, format == ArchiveFormat.RAR, "The ${format.label} archive did not contain any files, so it was left in the game folder.")
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("Download cancelled")
    }
}
