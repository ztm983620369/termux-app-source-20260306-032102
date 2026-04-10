package org.fossify.filemanager.activities

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.extensions.toast
import org.fossify.filemanager.controllers.FileManagerController
import org.fossify.filemanager.databinding.FmActivityMainBinding
import org.fossify.filemanager.helpers.DownloadedApkInstallerSupport
import org.fossify.filemanager.interfaces.FileManagerExternalActions
import java.io.File
import java.util.LinkedHashMap

class MainActivity : SimpleActivity(), FileManagerExternalActions {
    override var isSearchBarEnabled = false

    private val binding by viewBinding(FmActivityMainBinding::inflate)
    private val pendingDownloadedApkDeletes = LinkedHashMap<String, LinkedHashSet<String>>()
    private val apkInstallCleanupReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val packageName = intent?.data?.schemeSpecificPart?.trim().orEmpty()
            if (packageName.isEmpty()) return
            val paths = pendingDownloadedApkDeletes.remove(packageName).orEmpty()
            paths.forEach { DownloadedApkInstallerSupport.deleteDownloadedApk(it) }
        }
    }
    private val fileManagerController by lazy {
        FileManagerController(
            activity = this,
            binding = binding,
            intentProvider = { intent },
            externalActions = this
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        registerApkInstallCleanupReceiver()
        fileManagerController.onCreate(null)
        if (savedInstanceState != null) {
            fileManagerController.onRestoreInstanceState(savedInstanceState)
        }
    }

    override fun onResume() {
        super.onResume()
        fileManagerController.onResume()
    }

    override fun onPause() {
        fileManagerController.onPause()
        super.onPause()
    }

    override fun onStop() {
        fileManagerController.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(apkInstallCleanupReceiver) }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        fileManagerController.onSaveInstanceState(outState)
    }

    override fun onBackPressedCompat(): Boolean {
        return fileManagerController.onBackPressedCompat()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun openInTerminal(path: String) {
        // Standalone file manager does not embed a terminal surface.
    }

    override fun installDownloadedApk(path: String, deleteAfterInstall: Boolean) {
        val apkFile = File(path)
        if (!apkFile.exists() || !apkFile.isFile) {
            toast("APK 文件不存在：$path")
            return
        }

        val apkInfo = DownloadedApkInstallerSupport.readArchiveInfo(this, path)
        if (deleteAfterInstall) {
            val packageName = apkInfo?.packageName.orEmpty()
            if (packageName.isNotBlank()) {
                pendingDownloadedApkDeletes.getOrPut(packageName) { LinkedHashSet() }.add(path)
            }
        }

        val installIntent = DownloadedApkInstallerSupport.createInstallIntent(this, path)
        if (installIntent == null) {
            removePendingDownloadedApkDelete(apkInfo?.packageName, path)
            toast("无法创建安装请求。")
            return
        }

        runCatching { startActivity(installIntent) }
            .onSuccess {
                if (deleteAfterInstall) {
                    toast("已打开安装器。安装成功后将自动删除 APK；取消安装则保留文件。")
                }
            }
            .onFailure {
                removePendingDownloadedApkDelete(apkInfo?.packageName, path)
                toast("启动安装器失败：${it.message ?: "unknown"}")
            }
    }

    private fun registerApkInstallCleanupReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            this,
            apkInstallCleanupReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun removePendingDownloadedApkDelete(packageName: String?, path: String) {
        if (packageName.isNullOrBlank()) return
        val paths = pendingDownloadedApkDeletes[packageName] ?: return
        paths.remove(path)
        if (paths.isEmpty()) {
            pendingDownloadedApkDeletes.remove(packageName)
        }
    }
}
