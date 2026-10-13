package com.thorfeeds.catalog.download

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

object ZArchiverLaunch {
    const val PACKAGE = "ru.zdevs.zarchiver"

    fun installed(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun open(context: Context, archiveUri: Uri, displayName: String): Boolean {
        if (!installed(context)) return false
        val mime = if (displayName.lowercase().endsWith(".rar")) {
            "application/x-rar-compressed"
        } else {
            "application/zip"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setPackage(PACKAGE)
            setDataAndType(archiveUri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri(displayName, archiveUri)
        }
        return try {
            context.grantUriPermission(PACKAGE, archiveUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
