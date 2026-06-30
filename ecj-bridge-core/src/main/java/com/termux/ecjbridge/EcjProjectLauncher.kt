package com.termux.ecjbridge

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import java.io.File

object EcjProjectLauncher {
    fun buildRunProjectIntent(context: Context, projectRoot: File): Intent {
        val archiveUri = EcjProjectArchiveProvider.authorizeProject(context, projectRoot)
        val intent = Intent(EcjBridgeContract.ACTION_RUN_PROJECT)
            .setPackage(EcjBridgeContract.ECJ_APP_PACKAGE)
            .putExtra(EcjBridgeContract.EXTRA_BRIDGE_VERSION, EcjBridgeContract.BRIDGE_VERSION)
            .putExtra(EcjBridgeContract.EXTRA_TEMPLATE_VERSION, EcjBridgeContract.TEMPLATE_VERSION)
            .putExtra(EcjBridgeContract.EXTRA_PROJECT_NAME, projectRoot.name)
            .putExtra(EcjBridgeContract.EXTRA_PROJECT_PATH, projectRoot.absolutePath)
            .putExtra(EcjBridgeContract.EXTRA_PROJECT_ARCHIVE_URI, archiveUri.toString())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        intent.clipData = ClipData.newRawUri("ECJ project archive", archiveUri)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.grantUriPermission(
            EcjBridgeContract.ECJ_APP_PACKAGE,
            archiveUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        return intent
    }

    @Throws(ActivityNotFoundException::class)
    fun launchProject(context: Context, projectRoot: File) {
        context.startActivity(buildRunProjectIntent(context, projectRoot))
    }
}
