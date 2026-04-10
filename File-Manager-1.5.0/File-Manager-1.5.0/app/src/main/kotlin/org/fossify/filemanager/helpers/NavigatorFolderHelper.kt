package org.fossify.filemanager.helpers

import android.content.Context
import com.termux.sessionsync.FileRootResolver
import com.termux.sessionsync.SessionFileCoordinator
import org.fossify.commons.extensions.humanizePath
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.filemanager.extensions.config
import org.fossify.filemanager.models.ListItem
import java.io.File
import java.util.LinkedHashSet

object NavigatorFolderHelper {

    fun rootPath(context: Context): String {
        return TermuxPathScope.navigatorRootPath(context)
    }

    fun displayTitle(): String {
        return "\u6838\u5fc3\u5bfc\u822a\u5668"
    }

    fun isNavigatorPath(context: Context, path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return normalizePath(path) == normalizePath(rootPath(context))
    }

    fun buildNavigatorItems(context: Context): ArrayList<ListItem> {
        val coordinator = SessionFileCoordinator.getInstance()
        val selectedKey = coordinator.getSelectedSessionKey(context)
        val homePath = TermuxPathScope.clampVisiblePath(
            context,
            context.config.homeFolder,
            TermuxPathScope.termuxHomePath(context)
        )
        val favoritePaths = context.config.favorites
            .map { TermuxPathScope.normalizePath(it) }
            .filter { TermuxPathScope.isVisibleInFileManager(context, it) }

        val now = System.currentTimeMillis()
        val usedTargets = LinkedHashSet<String>()
        val items = ArrayList<ListItem>()

        fun addFolder(title: String, targetPath: String, selected: Boolean = false) {
            val normalized = normalizePath(targetPath)
            if (!usedTargets.add(normalized)) return
            val name = if (selected) "\u3010\u5f53\u524d\u3011$title" else title
            items.add(
                ListItem(
                    mPath = normalized,
                    mName = name,
                    mIsDirectory = true,
                    mChildren = -1, // negative => navigator subtitle mode in adapter
                    mSize = 0L,
                    mModified = now,
                    isSectionTitle = false,
                    isGridTypeDivider = false
                )
            )
        }

        addFolder("\u672c\u5730\u5de5\u4f5c\u76ee\u5f55", homePath, selected = selectedKey.isNullOrEmpty())

        if (context.config.showTermuxSystemDirs) {
            buildAndroidDirectoryItems(context).forEach { (label, path) ->
                addFolder(label, path)
            }
        }

        favoritePaths.forEach { path ->
            val label = context.config.getFavoriteRemark(path)
                ?: "\u6536\u85cf / ${FavoriteHelper.displayPath(context, path)}"
            addFolder(label, path, selected = selectedKey.isNullOrEmpty() && normalizePath(path) == normalizePath(homePath))
        }

        coordinator.listTargets(context).forEach { target ->
            val virtualRoot = FileRootResolver.resolveVirtualRoot(context, target.entry)
            addFolder(
                title = "\u670d\u52a1\u5668 / ${target.entry.displayName}",
                targetPath = virtualRoot,
                selected = !selectedKey.isNullOrEmpty() && selectedKey == target.key
            )
        }

        return items
    }

    fun getItemSubtitle(context: Context, targetPath: String): String {
        val coordinator = SessionFileCoordinator.getInstance()
        return if (coordinator.isVirtualPath(context, targetPath)) {
            coordinator.getDisplayPath(context, targetPath)
        } else {
            context.humanizePath(targetPath)
        }
    }

    fun resolveSessionKeyForTargetPath(context: Context, targetPath: String): String? {
        val normalized = normalizePath(targetPath)
        val targets = SessionFileCoordinator.getInstance().listTargets(context)
        targets.forEach { target ->
            val root = normalizePath(FileRootResolver.resolveVirtualRoot(context, target.entry))
            if (normalized == root || normalized.startsWith("$root/")) {
                return target.key
            }
        }
        return null
    }

    private fun normalizePath(rawPath: String): String {
        return TermuxPathScope.normalizePath(rawPath)
    }

    private fun buildAndroidDirectoryItems(context: Context): List<Pair<String, String>> {
        val roots = TermuxPathScope.phoneStorageRoots(context)
        val internalRoot = normalizePath(context.internalStoragePath)
        val items = ArrayList<Pair<String, String>>()

        roots.forEach { root ->
            val prefix = if (root == internalRoot) {
                "\u624b\u673a\u5b58\u50a8"
            } else {
                "\u5916\u90e8\u5b58\u50a8"
            }

            items.add(prefix to root)

            linkedMapOf(
                "Download" to "\u4e0b\u8f7d",
                "Documents" to "\u6587\u6863",
                "DCIM" to "DCIM",
                "Pictures" to "\u56fe\u7247",
                "Movies" to "\u89c6\u9891",
                "Music" to "\u97f3\u4e50",
                "Android/data" to "Android/data",
                "Android/obb" to "Android/obb"
            ).forEach { (relativePath, displayName) ->
                val target = File(root, relativePath).absolutePath
                if (File(target).isDirectory) {
                    items.add("$prefix / $displayName" to normalizePath(target))
                }
            }
        }

        return items
    }
}
