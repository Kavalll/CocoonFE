package com.thorfeeds.catalog.data

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import com.thorfeeds.catalog.net.ByteSink
import com.thorfeeds.catalog.net.ExistingPartial
import com.thorfeeds.catalog.net.MirrorException
import org.json.JSONObject
import java.io.OutputStream

class SafByteSink(
    private val resolver: ContentResolver,
    private val directory: DocumentFile,
) : ByteSink {
    override fun existing(partialName: String, sourceKey: String): ExistingPartial {
        val file = directory.findFile(partialName) ?: return ExistingPartial(0, false)
        val meta = readMeta(partialName) ?: return ExistingPartial(0, false)
        if (meta.sourceKey != sourceKey || !meta.acceptsRange) return ExistingPartial(0, false)
        val length = file.length()
        if (length <= 0L) return ExistingPartial(0, false)
        return ExistingPartial(length, true)
    }

    override fun open(partialName: String, append: Boolean): OutputStream {
        val file = directory.findFile(partialName)
            ?: directory.createFile("application/octet-stream", partialName)
            ?: throw MirrorException("Couldn't create the download file")
        val mode = if (append) "wa" else "wt"
        return resolver.openOutputStream(file.uri, mode)
            ?: throw MirrorException("Couldn't open the download file")
    }

    override fun writeMeta(partialName: String, sourceKey: String, acceptsRange: Boolean) {
        val name = metaName(partialName)
        val file = directory.findFile(name)
            ?: directory.createFile("application/json", name)
            ?: return
        val payload = JSONObject()
            .put("sourceKey", sourceKey)
            .put("acceptsRange", acceptsRange)
            .toString()
        resolver.openOutputStream(file.uri, "wt")?.use { stream ->
            stream.write(payload.toByteArray(Charsets.UTF_8))
        }
    }

    override fun commit(partialName: String) {
        val partial = directory.findFile(partialName) ?: return
        val finalName = partialName.removeSuffix(".partial")
        directory.findFile(finalName)?.delete()
        partial.renameTo(finalName)
        directory.findFile(metaName(partialName))?.delete()
    }

    override fun discard(partialName: String) {
        directory.findFile(partialName)?.delete()
        directory.findFile(metaName(partialName))?.delete()
    }

    private fun readMeta(partialName: String): Meta? {
        val file = directory.findFile(metaName(partialName)) ?: return null
        val text = resolver.openInputStream(file.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        return try {
            val json = JSONObject(text)
            Meta(json.getString("sourceKey"), json.optBoolean("acceptsRange"))
        } catch (_: Exception) {
            null
        }
    }

    private fun metaName(partialName: String) = "$partialName.meta"

    private data class Meta(val sourceKey: String, val acceptsRange: Boolean)
}
