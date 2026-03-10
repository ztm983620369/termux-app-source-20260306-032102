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
    val openedAtMs: Long,
    val originType: String? = null,
    val originPath: String? = null,
    val originDisplayPath: String? = null
)

object RecentFileHistory {
    private const val PREFS_NAME = "Prefs"
    private const val KEY_LEGACY_RECENT_FILES = "recent_opened_files_v1"
    private const val KEY_DB_MIGRATED = "recent_opened_files_db_migrated_v1"

    private const val KEY_PATH = "path"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_OPENED_AT_MS = "opened_at_ms"
    private const val KEY_ORIGIN_TYPE = "origin_type"
    private const val KEY_ORIGIN_PATH = "origin_path"
    private const val KEY_ORIGIN_DISPLAY_PATH = "origin_display_path"

    private const val DB_NAME = "recent_files.db"
    private const val DB_VERSION = 2
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
    fun recordOpenedFile(
        context: Context,
        path: String,
        displayName: String? = null,
        originType: String? = null,
        originPath: String? = null,
        originDisplayPath: String? = null
    ) {
        val normalizedPath = path.trim()
        if (normalizedPath.isEmpty()) return

        val now = System.currentTimeMillis()
        val resolvedName = displayName?.trim().takeUnless { it.isNullOrEmpty() }
            ?: File(normalizedPath).name.ifBlank { normalizedPath.substringAfterLast('/') }
        val entry = RecentFileEntry(
            path = normalizedPath,
            displayName = resolvedName,
            openedAtMs = now,
            originType = originType?.trim().takeUnless { it.isNullOrEmpty() },
            originPath = originPath?.trim().takeUnless { it.isNullOrEmpty() },
            originDisplayPath = originDisplayPath?.trim().takeUnless { it.isNullOrEmpty() }
        )

        pendingWrites[normalizedPath] = entry

        val appContext = context.applicationContext
        writerExecutor.execute {
            runCatching {
                val db = getDb(appContext)
                db.beginTransactionNonExclusive()
                try {
                    insertOrReplaceEntry(db, entry)
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
            "SELECT $KEY_PATH, $KEY_DISPLAY_NAME, $KEY_OPENED_AT_MS, " +
                "$KEY_ORIGIN_TYPE, $KEY_ORIGIN_PATH, $KEY_ORIGIN_DISPLAY_PATH " +
                "FROM $TABLE_RECENT_FILES ORDER BY $KEY_OPENED_AT_MS DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            val pathIndex = cursor.getColumnIndexOrThrow(KEY_PATH)
            val nameIndex = cursor.getColumnIndexOrThrow(KEY_DISPLAY_NAME)
            val openedAtIndex = cursor.getColumnIndexOrThrow(KEY_OPENED_AT_MS)
            val originTypeIndex = cursor.getColumnIndex(KEY_ORIGIN_TYPE)
            val originPathIndex = cursor.getColumnIndex(KEY_ORIGIN_PATH)
            val originDisplayPathIndex = cursor.getColumnIndex(KEY_ORIGIN_DISPLAY_PATH)
            while (cursor.moveToNext()) {
                val path = cursor.getString(pathIndex)
                val displayName = cursor.getString(nameIndex)
                val openedAtMs = cursor.getLong(openedAtIndex)
                val originType = originTypeIndex.takeIf { it >= 0 }?.let(cursor::getString)
                val originPath = originPathIndex.takeIf { it >= 0 }?.let(cursor::getString)
                val originDisplayPath = originDisplayPathIndex.takeIf { it >= 0 }?.let(cursor::getString)
                results.add(
                    RecentFileEntry(
                        path = path,
                        displayName = displayName,
                        openedAtMs = openedAtMs,
                        originType = originType,
                        originPath = originPath,
                        originDisplayPath = originDisplayPath
                    )
                )
            }
        }
        return results
    }

    private fun insertOrReplaceEntry(db: SQLiteDatabase, entry: RecentFileEntry) {
        db.execSQL(
            "INSERT OR REPLACE INTO $TABLE_RECENT_FILES(" +
                "$KEY_PATH, $KEY_DISPLAY_NAME, $KEY_OPENED_AT_MS, " +
                "$KEY_ORIGIN_TYPE, $KEY_ORIGIN_PATH, $KEY_ORIGIN_DISPLAY_PATH" +
                ") VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                entry.path,
                entry.displayName,
                entry.openedAtMs,
                entry.originType,
                entry.originPath,
                entry.originDisplayPath
            )
        )
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
                val originType = obj.optString(KEY_ORIGIN_TYPE).trim().ifEmpty { null }
                val originPath = obj.optString(KEY_ORIGIN_PATH).trim().ifEmpty { null }
                val originDisplayPath = obj.optString(KEY_ORIGIN_DISPLAY_PATH).trim().ifEmpty { null }
                parsed.add(
                    RecentFileEntry(
                        path = path,
                        displayName = displayName,
                        openedAtMs = openedAtMs,
                        originType = originType,
                        originPath = originPath,
                        originDisplayPath = originDisplayPath
                    )
                )
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
                    insertOrReplaceEntry(db, item)
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
                    "$KEY_OPENED_AT_MS INTEGER NOT NULL, " +
                    "$KEY_ORIGIN_TYPE TEXT, " +
                    "$KEY_ORIGIN_PATH TEXT, " +
                    "$KEY_ORIGIN_DISPLAY_PATH TEXT" +
                    ")"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_recent_files_opened_at ON $TABLE_RECENT_FILES($KEY_OPENED_AT_MS DESC)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE $TABLE_RECENT_FILES ADD COLUMN $KEY_ORIGIN_TYPE TEXT")
                db.execSQL("ALTER TABLE $TABLE_RECENT_FILES ADD COLUMN $KEY_ORIGIN_PATH TEXT")
                db.execSQL("ALTER TABLE $TABLE_RECENT_FILES ADD COLUMN $KEY_ORIGIN_DISPLAY_PATH TEXT")
            }
        }
    }
}
