package com.clipsync.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** The preference-page seam: check / download / install permission. Tests fake this. */
interface AppUpdater {
    suspend fun check(currentVersion: String): UpdateCheckResult

    suspend fun download(
        check: UpdateCheckResult,
        onProgress: (received: Long, total: Long) -> Unit,
    ): File

    fun canRequestInstall(): Boolean
}

/**
 * Downloads the signed APK into the app cache and hands it to the system
 * installer. Never writes to shared storage. SHA-256 is verified before the
 * FileProvider URI is minted.
 */
class AppUpdateInstaller(
    private val context: Context,
    private val client: GitHubReleaseClient,
) : AppUpdater {
    override suspend fun check(currentVersion: String): UpdateCheckResult {
        val latest = client.fetchLatest()
        return UpdateCheckResult.from(currentVersion, latest, UpdatePlatform.ANDROID)
    }

    /**
     * @return the verified APK file, ready for [installIntent].
     */
    override suspend fun download(
        check: UpdateCheckResult,
        onProgress: (received: Long, total: Long) -> Unit,
    ): File {
        val payload =
            check.payload
                ?: error("Latest release has no Android APK.")
        val expectedSha = client.resolveSha256(check.latest, payload)
        val dir = File(context.cacheDir, UPDATES_DIR).apply { mkdirs() }
        val file = File(dir, payload.name)
        if (file.exists()) {
            file.delete()
        }
        client.download(payload, file, onProgress)
        GitHubReleaseClient.verifySha256(file, expectedSha)
        return file
    }

    override fun canRequestInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun manageUnknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    fun installIntent(apk: File): Intent {
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.clipboard",
                apk,
            )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        const val UPDATES_DIR = "updates"
    }
}

fun readAppVersionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: "0.0.0"
