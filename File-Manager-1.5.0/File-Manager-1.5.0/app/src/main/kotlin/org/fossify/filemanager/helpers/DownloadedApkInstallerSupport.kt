package org.fossify.filemanager.helpers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import org.fossify.filemanager.BuildConfig
import java.io.File

data class DownloadedApkArchiveInfo(
    val packageName: String,
    val applicationLabel: String,
    val versionName: String?,
    val versionCode: Long
)

object DownloadedApkInstallerSupport {

    @JvmStatic
    fun getSystemDownloadsPath(): String {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
    }

    @JvmStatic
    fun readArchiveInfo(context: Context, apkPath: String): DownloadedApkArchiveInfo? {
        val apkFile = File(apkPath)
        if (!apkFile.exists() || !apkFile.isFile) return null

        val packageManager = context.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_ACTIVITIES)
        } ?: return null

        val applicationInfo = packageInfo.applicationInfo ?: return null
        applicationInfo.sourceDir = apkFile.absolutePath
        applicationInfo.publicSourceDir = apkFile.absolutePath

        val label = runCatching {
            packageManager.getApplicationLabel(applicationInfo)?.toString()
        }.getOrNull().orEmpty().ifBlank { apkFile.nameWithoutExtension }

        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        return DownloadedApkArchiveInfo(
            packageName = packageInfo.packageName.orEmpty(),
            applicationLabel = label,
            versionName = packageInfo.versionName,
            versionCode = versionCode
        )
    }

    @JvmStatic
    fun loadArchiveIcon(context: Context, apkPath: String): Drawable? {
        val apkFile = File(apkPath)
        if (!apkFile.exists() || !apkFile.isFile) return null

        val packageManager = context.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_ACTIVITIES)
        } ?: return null

        val applicationInfo = packageInfo.applicationInfo ?: return null
        applicationInfo.sourceDir = apkFile.absolutePath
        applicationInfo.publicSourceDir = apkFile.absolutePath
        return runCatching { packageManager.getApplicationIcon(applicationInfo) }.getOrNull()
    }

    @JvmStatic
    fun createInstallIntent(context: Context, apkPath: String): Intent? {
        val apkFile = File(apkPath)
        if (!apkFile.exists() || !apkFile.isFile) return null

        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.provider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @JvmStatic
    fun deleteDownloadedApk(path: String): Boolean {
        return runCatching {
            val file = File(path)
            !file.exists() || file.delete()
        }.getOrDefault(false)
    }
}
