package org.fossify.filemanager.helpers

import android.content.Context
import com.termux.sessionsync.FileRootResolver
import com.termux.sessionsync.SessionFileCoordinator
import org.fossify.commons.extensions.humanizePath
import org.fossify.filemanager.extensions.config
import org.fossify.filemanager.models.ListItem
import java.util.LinkedHashSet

object NavigatorFolderHelper {

    private const val NAV_ROOT_RELATIVE_PATH = ".termux/.navigator"

    fun rootPath(context: Context): String {
        val root = context.filesDir.absolutePath.trimEnd('/')
        return "$root/$NAV_ROOT_RELATIVE_PATH"
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
        val termuxRoot = context.filesDir.absolutePath.trimEnd('/')
        val homePath = clampToRoot(context.config.homeFolder, termuxRoot)
        val favoritePaths = context.config.favorites
            .map { clampToRoot(it, termuxRoot) }
            .filter { it.startsWith(termuxRoot) }

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

        addFolder("Termux", termuxRoot, selected = selectedKey.isNullOrEmpty())
        addFolder("\u672c\u5730\u5de5\u4f5c\u76ee\u5f55", homePath, selected = selectedKey.isNullOrEmpty())

        favoritePaths.forEach { path ->
            val label = "\u6536\u85cf / ${context.humanizePath(path)}"
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

    private fun clampToRoot(rawPath: String?, root: String): String {
        val normalized = normalizePath(rawPath ?: "")
        return if (normalized == root || normalized.startsWith("$root/")) normalized else root
    }

    private fun normalizePath(rawPath: String): String {
        var path = rawPath.trim().replace('\\', '/')
        while (path.contains("//")) path = path.replace("//", "/")
        if (path.endsWith("/") && path.length > 1) path = path.substring(0, path.length - 1)
        return if (path.isEmpty()) "/" else path
    }
}

