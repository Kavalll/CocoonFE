package com.thorfeeds.catalog.net

open class MirrorException(message: String) : Exception(message)

data class RemoteFile(
    val url: String,
    val fileName: String,
    val sizeBytes: Long?,
    val sourceKey: String,
    val headers: Map<String, String> = emptyMap(),
)

data class ResolvedMirror(
    val label: String,
    val files: List<RemoteFile>,
)

interface MirrorRouter {
    suspend fun resolve(uri: String): ResolvedMirror
}

interface DownloadListener {
    fun onMirrorStarting(index: Int, count: Int, label: String)
    fun onMirrorFailed(index: Int, message: String)
    fun onProgress(fileName: String, bytesRead: Long, totalBytes: Long?)
}

interface FileFetcher {
    suspend fun fetch(
        file: RemoteFile,
        sink: ByteSink,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): String
}

interface ByteSink {
    fun existing(partialName: String, sourceKey: String): ExistingPartial
    fun open(partialName: String, append: Boolean): java.io.OutputStream
    fun writeMeta(partialName: String, sourceKey: String, acceptsRange: Boolean)
    fun commit(partialName: String)
    fun discard(partialName: String)
}

data class ExistingPartial(
    val bytes: Long,
    val acceptsRange: Boolean,
)
