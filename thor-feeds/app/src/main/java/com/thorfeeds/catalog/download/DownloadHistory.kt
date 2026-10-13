package com.thorfeeds.catalog.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DownloadRecord(
    val releaseId: String,
    val canonicalTitle: String,
    val phase: String,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val fileName: String? = null,
    val message: String? = null,
    val mirrorLabel: String = "",
    val mirrorIndex: Int = 0,
    val mirrorCount: Int = 1,
    val updatedAt: Long = 0,
    val acceptsResume: Boolean = false,
    val kind: String = KIND_DOWNLOAD,
    val offerZArchiver: Boolean = false,
    val archiveUri: String? = null,
) {
    fun toPhase(): DownloadPhase {
        return DownloadPhase.entries.firstOrNull { it.name == phase } ?: DownloadPhase.Failed
    }

    companion object {
        const val KIND_DOWNLOAD = "download"
        const val KIND_EXTRACT = "extract"
    }
}

object DownloadHistoryRules {
    fun interrupt(record: DownloadRecord, acceptsResume: Boolean): DownloadRecord {
        val phase = record.toPhase()
        if (phase == DownloadPhase.Completed || phase == DownloadPhase.Failed ||
            phase == DownloadPhase.Cancelled || phase == DownloadPhase.Interrupted
        ) {
            return record
        }
        val message = when {
            record.kind == DownloadRecord.KIND_EXTRACT ->
                "Extraction was interrupted. Retry will extract the downloaded file."
            acceptsResume -> "Interrupted. Retry will continue this file."
            else -> "Interrupted. Retry will start this file over."
        }
        return record.copy(
            phase = DownloadPhase.Interrupted.name,
            acceptsResume = acceptsResume && record.kind != DownloadRecord.KIND_EXTRACT,
            message = message,
        )
    }
}

class DownloadHistoryStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(): List<DownloadRecord> {
        val text = try {
            file.takeIf { it.isFile }?.readText()
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return try {
            json.decodeFromString<List<DownloadRecord>>(text)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun write(records: List<DownloadRecord>) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(records))
    }
}
