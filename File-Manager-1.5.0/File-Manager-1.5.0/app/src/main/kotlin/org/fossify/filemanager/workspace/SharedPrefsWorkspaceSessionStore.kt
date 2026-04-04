package org.fossify.filemanager.workspace

import android.content.Context

class SharedPrefsWorkspaceSessionStore(context: Context) : WorkspaceSessionStore {

    private val sharedPreferences = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    override fun save(snapshot: WorkspaceSessionSnapshot) {
        sharedPreferences.edit().putString(KEY_SNAPSHOT, snapshot.toJson().toString()).apply()
    }

    override fun saveBlocking(snapshot: WorkspaceSessionSnapshot) {
        sharedPreferences.edit().putString(KEY_SNAPSHOT, snapshot.toJson().toString()).commit()
    }

    override fun load(): WorkspaceSessionSnapshot {
        return WorkspaceSessionSnapshot.fromJsonString(sharedPreferences.getString(KEY_SNAPSHOT, null))
    }

    companion object {
        private const val PREF_NAME = "termux.filemanager.workspace.session"
        private const val KEY_SNAPSHOT = "snapshot"
    }
}
