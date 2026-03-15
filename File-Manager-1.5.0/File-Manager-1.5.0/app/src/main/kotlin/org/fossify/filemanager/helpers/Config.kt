package org.fossify.filemanager.helpers

import android.content.Context
import android.content.res.Configuration
import org.fossify.commons.extensions.getInternalStoragePath
import org.fossify.commons.helpers.BaseConfig
import org.json.JSONObject
import java.io.File
import java.util.Locale

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    var showHidden: Boolean
        get() = prefs.getBoolean(SHOW_HIDDEN, false)
        set(show) = prefs.edit().putBoolean(SHOW_HIDDEN, show).apply()

    var temporarilyShowHidden: Boolean
        get() = prefs.getBoolean(TEMPORARILY_SHOW_HIDDEN, false)
        set(temporarilyShowHidden) = prefs.edit().putBoolean(TEMPORARILY_SHOW_HIDDEN, temporarilyShowHidden).apply()

    fun shouldShowHidden() = showHidden || temporarilyShowHidden

    var pressBackTwice: Boolean
        get() = prefs.getBoolean(PRESS_BACK_TWICE, true)
        set(pressBackTwice) = prefs.edit().putBoolean(PRESS_BACK_TWICE, pressBackTwice).apply()

    var homeFolder: String
        get(): String {
            var path = prefs.getString(HOME_FOLDER, "")!!
            if (path.isEmpty() || !File(path).isDirectory) {
                path = context.getInternalStoragePath()
                homeFolder = path
            }
            return path
        }
        set(homeFolder) = prefs.edit().putString(HOME_FOLDER, homeFolder).apply()

    fun isFavorite(path: String): Boolean {
        return resolveStoredFavoritePath(path) != null
    }

    fun getFavoriteRemark(path: String): String? {
        return getFavoriteRemarkMap()[normalizeFavoritePath(path)]?.takeIf { it.isNotBlank() }
    }

    fun addFavorite(path: String, remark: String) {
        val normalizedPath = normalizeFavoritePath(path)
        val currFavorites = HashSet<String>(favorites)
        currFavorites.removeAll { normalizeFavoritePath(it) == normalizedPath }
        currFavorites.add(normalizedPath)
        favorites = currFavorites

        val currRemarks = getFavoriteRemarkMap()
        currRemarks[normalizedPath] = remark.trim()
        setFavoriteRemarkMap(currRemarks)
    }

    fun moveFavorite(oldPath: String, newPath: String) {
        val storedOldPath = resolveStoredFavoritePath(oldPath) ?: return
        val normalizedNewPath = normalizeFavoritePath(newPath)

        val currFavorites = HashSet<String>(favorites)
        currFavorites.remove(storedOldPath)
        currFavorites.add(normalizedNewPath)
        favorites = currFavorites

        val currRemarks = getFavoriteRemarkMap()
        val movedRemark = currRemarks.remove(normalizeFavoritePath(oldPath))
        if (!movedRemark.isNullOrBlank()) {
            currRemarks[normalizedNewPath] = movedRemark
        }
        setFavoriteRemarkMap(currRemarks)
    }

    fun removeFavorite(path: String) {
        val storedPath = resolveStoredFavoritePath(path) ?: return

        val currFavorites = HashSet<String>(favorites)
        currFavorites.remove(storedPath)
        favorites = currFavorites

        val currRemarks = getFavoriteRemarkMap()
        currRemarks.remove(normalizeFavoritePath(path))
        setFavoriteRemarkMap(currRemarks)
    }

    private fun resolveStoredFavoritePath(path: String): String? {
        val normalizedPath = normalizeFavoritePath(path)
        return favorites.firstOrNull { normalizeFavoritePath(it) == normalizedPath }
    }

    private fun normalizeFavoritePath(path: String): String {
        val normalized = path.trim().trimEnd('/')
        return normalized.ifEmpty { "/" }
    }

    private fun getFavoriteRemarkMap(): LinkedHashMap<String, String> {
        val rawValue = prefs.getString(FAVORITE_REMARKS, "")!!
        if (rawValue.isBlank()) {
            return LinkedHashMap()
        }

        return try {
            val map = LinkedHashMap<String, String>()
            val json = JSONObject(rawValue)
            val iterator = json.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                val value = json.optString(key).trim()
                if (value.isNotEmpty()) {
                    map[key] = value
                }
            }
            map
        } catch (_: Exception) {
            LinkedHashMap()
        }
    }

    private fun setFavoriteRemarkMap(remarks: Map<String, String>) {
        val json = JSONObject()
        remarks.forEach { (path, remark) ->
            val cleanedRemark = remark.trim()
            if (cleanedRemark.isNotEmpty()) {
                json.put(path, cleanedRemark)
            }
        }
        prefs.edit().putString(FAVORITE_REMARKS, json.toString()).apply()
    }

    var isRootAvailable: Boolean
        get() = prefs.getBoolean(IS_ROOT_AVAILABLE, false)
        set(isRootAvailable) = prefs.edit().putBoolean(IS_ROOT_AVAILABLE, isRootAvailable).apply()

    var enableRootAccess: Boolean
        get() = prefs.getBoolean(ENABLE_ROOT_ACCESS, false)
        set(enableRootAccess) = prefs.edit().putBoolean(ENABLE_ROOT_ACCESS, enableRootAccess).apply()

    var editorTextZoom: Float
        get() = prefs.getFloat(EDITOR_TEXT_ZOOM, 1.2f)
        set(editorTextZoom) = prefs.edit().putFloat(EDITOR_TEXT_ZOOM, editorTextZoom).apply()

    fun saveFolderViewType(path: String, value: Int) {
        if (path.isEmpty()) {
            viewType = value
        } else {
            prefs.edit().putInt(VIEW_TYPE_PREFIX + path.lowercase(Locale.getDefault()), value).apply()
        }
    }

    fun getFolderViewType(path: String) = prefs.getInt(VIEW_TYPE_PREFIX + path.lowercase(Locale.getDefault()), viewType)

    fun removeFolderViewType(path: String) {
        prefs.edit().remove(VIEW_TYPE_PREFIX + path.lowercase(Locale.getDefault())).apply()
    }

    fun hasCustomViewType(path: String) = prefs.contains(VIEW_TYPE_PREFIX + path.lowercase(Locale.getDefault()))

    var fileColumnCnt: Int
        get() = prefs.getInt(getFileColumnsField(), getDefaultFileColumnCount())
        set(fileColumnCnt) = prefs.edit().putInt(getFileColumnsField(), fileColumnCnt).apply()

    private fun getFileColumnsField(): String {
        val isPortrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        return if (isPortrait) {
            FILE_COLUMN_CNT
        } else {
            FILE_LANDSCAPE_COLUMN_CNT
        }
    }

    private fun getDefaultFileColumnCount(): Int {
        val isPortrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        return if (isPortrait) 4 else 8
    }

    var displayFilenames: Boolean
        get() = prefs.getBoolean(DISPLAY_FILE_NAMES, true)
        set(displayFilenames) = prefs.edit().putBoolean(DISPLAY_FILE_NAMES, displayFilenames).apply()

    var showTabs: Int
        get() = prefs.getInt(SHOW_TABS, ALL_TABS_MASK)
        set(showTabs) = prefs.edit().putInt(SHOW_TABS, showTabs).apply()

    var wasStorageAnalysisTabAdded: Boolean
        get() = prefs.getBoolean(WAS_STORAGE_ANALYSIS_TAB_ADDED, false)
        set(wasStorageAnalysisTabAdded) = prefs.edit().putBoolean(WAS_STORAGE_ANALYSIS_TAB_ADDED, wasStorageAnalysisTabAdded).apply()

    var preferTermuxStorage: Boolean
        get() = prefs.getBoolean(PREFER_TERMUX_STORAGE, false)
        set(preferTermuxStorage) = prefs.edit().putBoolean(PREFER_TERMUX_STORAGE, preferTermuxStorage).apply()

    var showTermuxSystemDirs: Boolean
        get() = prefs.getBoolean(SHOW_TERMUX_SYSTEM_DIRS, false)
        set(showTermuxSystemDirs) = prefs.edit().putBoolean(SHOW_TERMUX_SYSTEM_DIRS, showTermuxSystemDirs).apply()
}
