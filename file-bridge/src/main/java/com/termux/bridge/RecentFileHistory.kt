package com.termux.bridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class RecentFileEntry(
    val path: String,
    val displayName: String,
    val openedAtMs: Long
)

object RecentFileHistory {
    private const val PREFS_NAME = "Prefs"
    private const val KEY_LEGACY_RECENT_FILES = "recent_opened_files_v1"
    private const val KEY_DB_MIGRATED = "recent_opened_files_db_migrated_v1"

    private const val KEY_PATH = "path"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_OPENED_AT_MS = "opened_at_ms"

    private const val DB_NAME = "recent_files.db"
    private const val DB_VERSION = 1
    private const val TABLE_RECENT_FILES = "recent_files"

    private const val MAX_ENTRIES = 300
    private const val TRIM_EVERY_WRITES = 8

    private val dbLock = Any()
    @Volatile
    private var dbHelper: RecentFilesDbHelper? = null

    private val pendingWrites = ConcurrentHashMap<String, RecentFileEntry>()
    private val writeCounter = AtomicInteger(0)

    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "recent-files-writer").apply {
            isDaemon = true
        }
    }

    @JvmStatic
    fun recordOpenedFile(context: Context, path: String, displayName: String? = null) {
        val normalizedPath = path.trim()
        if (normalizedPath.isEmpty()) return

        val now = System.currentTimeMillis()
        val resolvedName = displayName?.trim().takeUnless { it.isNullOrEmpty() }
            ?: File(normalizedPath).name.ifBlank { normalizedPath.substringAfterLast('/') }
        val entry = RecentFileEntry(normalizedPath, resolvedName, now)

        pendingWrites[normalizedPath] = entry

        val appContext = context.applicationContext
        writerExecutor.execute {
            runCatching {
                val db = getDb(appContext)
                db.beginTransactionNonExclusive()
                try {
                    db.execSQL(
                        "INSERT OR REPLACE INTO $TABLE_RECENT_FILES($KEY_PATH, $KEY_DISPLAY_NAME, $KEY_OPENED_AT_MS) VALUES (?, ?, ?)",
                        arrayOf<Any>(entry.path, entry.displayName, entry.openedAtMs)
                    )
                    if (writeCounter.incrementAndGet() % TRIM_EVERY_WRITES == 0) {
                        trimToMaxEntries(db)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                markPersisted(entry)
            }
        }
    }

    @JvmStatic
    fun getRecentFiles(context: Context, limit: Int): List<RecentFileEntry> {
        val normalizedLimit = limit.coerceAtLeast(0)
        if (normalizedLimit == 0) return emptyList()

        val dbEntries = runCatching {
            queryRecentFiles(context.applicationContext, normalizedLimit + pendingWrites.size + 16)
        }.getOrDefault(emptyList())

        if (pendingWrites.isEmpty()) {
            return dbEntries.take(normalizedLimit)
        }

        val merged = LinkedHashMap<String, RecentFileEntry>(dbEntries.size + pendingWrites.size)
        dbEntries.forEach { merged[it.path] = it }
        pendingWrites.values.forEach { merged[it.path] = it }

        return merged.values
            .sortedByDescending { it.openedAtMs }
            .take(normalizedLimit)
    }

    @JvmStatic
    fun removePath(context: Context, path: String) {
        val normalizedPath = path.trim()
        if (normalizedPath.isEmpty()) return
        pendingWrites.remove(normalizedPath)

        val appContext = context.applicationContext
        writerExecutor.execute {
            runCatching {
                val db = getDb(appContext)
                db.execSQL(
                    "DELETE FROM $TABLE_RECENT_FILES WHERE $KEY_PATH = ?",
                    arrayOf(normalizedPath)
                )
            }
        }
    }

    private fun queryRecentFiles(context: Context, limit: Int): List<RecentFileEntry> {
        val db = getDb(context)
        val results = ArrayList<RecentFileEntry>(limit.coerceAtLeast(8))
        db.rawQuery(
            "SELECT $KEY_PATH, $KEY_DISPLAY_NAME, $KEY_OPENED_AT_MS FROM $TABLE_RECENT_FILES ORDER BY $KEY_OPENED_AT_MS DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            val pathIndex = cursor.getColumnIndexOrThrow(KEY_PATH)
            val nameIndex = cursor.getColumnIndexOrThrow(KEY_DISPLAY_NAME)
            val openedAtIndex = cursor.getColumnIndexOrThrow(KEY_OPENED_AT_MS)
            while (cursor.moveToNext()) {
                val path = cursor.getString(pathIndex)
                val displayName = cursor.getString(nameIndex)
                val openedAtMs = cursor.getLong(openedAtIndex)
                results.add(RecentFileEntry(path, displayName, openedAtMs))
            }
        }
        return results
    }

    private fun trimToMaxEntries(db: SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM $TABLE_RECENT_FILES WHERE $KEY_PATH NOT IN " +
                "(SELECT $KEY_PATH FROM $TABLE_RECENT_FILES ORDER BY $KEY_OPENED_AT_MS DESC LIMIT ?)",
            arrayOf<Any>(MAX_ENTRIES)
        )
    }

    private fun markPersisted(persisted: RecentFileEntry) {
        pendingWrites.compute(persisted.path) { _, current ->
            if (current == null) {
                null
            } else if (current.openedAtMs <= persisted.openedAtMs) {
                null
            } else {
                current
            }
        }
    }

    private fun getDb(context: Context): SQLiteDatabase {
        return getDbHelper(context).writableDatabase
    }

    private fun getDbHelper(context: Context): RecentFilesDbHelper {
        dbHelper?.let { return it }
        synchronized(dbLock) {
            dbHelper?.let { return it }
            val helper = RecentFilesDbHelper(context.applicationContext)
            val db = helper.writableDatabase
            migrateLegacyPrefsIfNeeded(context.applicationContext, db)
            dbHelper = helper
            return helper
        }
    }

    private fun migrateLegacyPrefsIfNeeded(context: Context, db: SQLiteDatabase) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DB_MIGRATED, false)) {
            return
        }

        val raw = prefs.getString(KEY_LEGACY_RECENT_FILES, "[]") ?: "[]"
        val parsed = ArrayList<RecentFileEntry>()
        try {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val path = obj.optString(KEY_PATH).trim()
                if (path.isEmpty()) continue
                val displayName = obj.optString(KEY_DISPLAY_NAME).trim()
                    .ifEmpty { File(path).name.ifBlank { path.substringAfterLast('/') } }
                val openedAtMs = obj.optLong(KEY_OPENED_AT_MS, 0L)
                parsed.add(RecentFileEntry(path, displayName, openedAtMs))
            }
        } catch (_: Exception) {
            prefs.edit().putBoolean(KEY_DB_MIGRATED, true).apply()
            return
        }

        val migratedEntries = parsed
            .distinctBy { it.path }
            .sortedByDescending { it.openedAtMs }

        if (migratedEntries.isNotEmpty()) {
            db.beginTransactionNonExclusive()
            try {
                migratedEntries.take(MAX_ENTRIES).forEach { item ->
                    db.execSQL(
                        "INSERT OR REPLACE INTO $TABLE_RECENT_FILES($KEY_PATH, $KEY_DISPLAY_NAME, $KEY_OPENED_AT_MS) VALUES (?, ?, ?)",
                        arrayOf<Any>(item.path, item.displayName, item.openedAtMs)
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        prefs
            .edit()
            .putBoolean(KEY_DB_MIGRATED, true)
            .apply()
    }

    private class RecentFilesDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE_RECENT_FILES (" +
                    "$KEY_PATH TEXT PRIMARY KEY NOT NULL, " +
                    "$KEY_DISPLAY_NAME TEXT NOT NULL, " +
                    "$KEY_OPENED_AT_MS INTEGER NOT NULL" +
                    ")"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_recent_files_opened_at ON $TABLE_RECENT_FILES($KEY_OPENED_AT_MS DESC)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // No-op for v1.
        }
    }
}
