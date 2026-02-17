package com.termux.ui.nav

import android.content.Context
import android.content.SharedPreferences

object UiShellNavBridge {
    private const val PREFS = "ui_shell_nav"
    private const val KEY_FILES_REQUESTED_DIR = "files.requested_dir"

    @JvmStatic
    fun setRequestedFilesDir(context: Context, dirPath: String) {
        prefs(context).edit().putString(KEY_FILES_REQUESTED_DIR, dirPath).apply()
    }

    @JvmStatic
    fun consumeRequestedFilesDir(context: Context): String? {
        val p = prefs(context)
        val v = p.getString(KEY_FILES_REQUESTED_DIR, null)
        if (v != null) {
            p.edit().remove(KEY_FILES_REQUESTED_DIR).apply()
        }
        return v
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
