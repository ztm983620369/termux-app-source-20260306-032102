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
    private const val TERMUX_GROUP = "termux"
    private const val PHONE_STORAGE_GROUP = "phone-storage"

    fun rootPath(context: Context): String {
        return TermuxPathScope.navigatorRootPath(context)
    }

    fun displayTitle(): String {
        return "\u6838\u5fc3\u5bfc\u822a\u5668"
    }

    fun displayTitleForPath(context: Context, path: String?): String {
        val normalized = normalizePath(path.orEmpty())
        return when {
            isTermuxGroupPath(context, normalized) -> "Termux \u73af\u5883"
            isPhoneStorageGroupPath(context, normalized) -> "\u624b\u673a\u5b58\u50a8"
            else -> displayTitle()
        }
    }

    fun isNavigatorPath(context: Context, path: String?): Boolean {
        return TermuxPathScope.isNavigatorPath(context, path)
    }

    fun buildNavigatorItems(context: Context, path: String = rootPath(context)): ArrayList<ListItem> {
        val normalized = normalizePath(path)
        return when {
            isTermuxGroupPath(context, normalized) -> buildTermuxEnvironmentItems(context)
            isPhoneStorageGroupPath(context, normalized) -> {
                if (context.config.showTermuxSystemDirs) buildPhoneStorageItems(context) else ArrayList()
            }
            else -> buildRootItems(context)
        }
    }

    private fun buildRootItems(context: Context): ArrayList<ListItem> {
        val coordinator = SessionFileCoordinator.getInstance()
        val selectedKey = coordinator.getSelectedSessionKey(context)
        val homePath = TermuxPathScope.preferredTermuxWorkPath(context, context.config.homeFolder)
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
        addFolder("Termux \u73af\u5883", navigatorGroupPath(context, TERMUX_GROUP))

        if (context.config.showTermuxSystemDirs) {
            if (TermuxPathScope.phoneStorageRoots(context).isNotEmpty()) {
                addFolder("\u624b\u673a\u5b58\u50a8", navigatorGroupPath(context, PHONE_STORAGE_GROUP))
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
        val normalized = normalizePath(targetPath)
        val coordinator = SessionFileCoordinator.getInstance()
        return when {
            isTermuxGroupPath(context, normalized) -> {
                if (context.config.showTermuxSystemDirs) "HOME / PREFIX / .termux" else "HOME / .termux / projects"
            }
            isPhoneStorageGroupPath(context, normalized) -> "Download / Documents / DCIM / Android"
            coordinator.isVirtualPath(context, normalized) -> coordinator.getDisplayPath(context, normalized)
            else -> context.humanizePath(normalized)
        }
    }

    fun resolveSessionKeyForTargetPath(context: Context, targetPath: String): String? {
        val normalized = normalizePath(targetPath)
        if (isNavigatorPath(context, normalized)) return null
        val targets = SessionFileCoordinator.getInstance().listTargets(context)
        targets.forEach { target ->
            val root = normalizePath(FileRootResolver.resolveVirtualRoot(context, target.entry))
            if (normalized == root || normalized.startsWith("$root/")) {
                return target.key
            }
        }
        return null
    }

    private fun buildTermuxEnvironmentItems(context: Context): ArrayList<ListItem> {
        val now = System.currentTimeMillis()
        val usedTargets = LinkedHashSet<String>()
        val items = ArrayList<ListItem>()
        val homePath = TermuxPathScope.termuxHomePath(context)
        val filesRoot = TermuxPathScope.termuxRootPath(context)
        val prefixPath = normalizePath(File(filesRoot, "usr").absolutePath)

        fun addFolder(title: String, targetPath: String, requireExisting: Boolean = true) {
            val normalized = normalizePath(targetPath)
            if (!usedTargets.add(normalized)) return
            if (requireExisting && !File(normalized).isDirectory) return
            items.add(
                ListItem(
                    mPath = normalized,
                    mName = title,
                    mIsDirectory = true,
                    mChildren = -1,
                    mSize = 0L,
                    mModified = now,
                    isSectionTitle = false,
                    isGridTypeDivider = false
                )
            )
        }

        addFolder("Termux / HOME", homePath, requireExisting = false)
        addFolder("Termux / projects", File(homePath, "projects").absolutePath)
        addFolder("Termux / .termux", File(homePath, ".termux").absolutePath)

        if (context.config.showTermuxSystemDirs) {
            addFolder("Termux / files", filesRoot)
            addFolder("Termux / PREFIX", prefixPath)
            addFolder("Termux / tmp", File(prefixPath, "tmp").absolutePath)
        }

        return items
    }

    private fun buildPhoneStorageItems(context: Context): ArrayList<ListItem> {
        val now = System.currentTimeMillis()
        val usedTargets = LinkedHashSet<String>()
        val internalRoot = normalizePath(context.internalStoragePath)
        val items = ArrayList<ListItem>()

        fun addFolder(title: String, targetPath: String) {
            val normalized = normalizePath(targetPath)
            if (!File(normalized).isDirectory) return
            if (!usedTargets.add(normalized)) return
            items.add(
                ListItem(
                    mPath = normalized,
                    mName = title,
                    mIsDirectory = true,
                    mChildren = -1,
                    mSize = 0L,
                    mModified = now,
                    isSectionTitle = false,
                    isGridTypeDivider = false
                )
            )
        }

        TermuxPathScope.phoneStorageRoots(context).forEach { root ->
            val prefix = if (root == internalRoot) {
                "\u624b\u673a\u5b58\u50a8"
            } else {
                "\u5916\u90e8\u5b58\u50a8"
            }

            addFolder("$prefix / \u6839\u76ee\u5f55", root)

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
                addFolder("$prefix / $displayName", File(root, relativePath).absolutePath)
            }
        }

        return items
    }

    private fun normalizePath(rawPath: String): String {
        return TermuxPathScope.normalizePath(rawPath)
    }

    private fun navigatorGroupPath(context: Context, group: String): String {
        return normalizePath("${rootPath(context)}/$group")
    }

    private fun isTermuxGroupPath(context: Context, path: String): Boolean {
        return normalizePath(path) == navigatorGroupPath(context, TERMUX_GROUP)
    }

    private fun isPhoneStorageGroupPath(context: Context, path: String): Boolean {
        return normalizePath(path) == navigatorGroupPath(context, PHONE_STORAGE_GROUP)
    }
}
