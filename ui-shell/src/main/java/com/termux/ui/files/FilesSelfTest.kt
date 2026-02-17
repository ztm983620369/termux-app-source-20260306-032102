package com.termux.ui.files

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.os.FileObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object FilesSelfTest {
    suspend fun run(context: Context, filesRoot: File, homeDir: File, currentDir: File? = null): String =
        withContext(Dispatchers.IO) {
        val sb = StringBuilder()

        fun line(key: String, value: Any?) {
            sb.append(key).append(": ").append(value ?: "null").append('\n')
        }

        sb.append("==== FilesSelfTest ====\n")

        val packageName = context.packageName
        val filesDir = context.filesDir

        line("packageName", packageName)
        line("appVersion", getAppVersion(context))
        line("time", now())
        line("sdkInt", Build.VERSION.SDK_INT)
        line("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        line("fingerprint", Build.FINGERPRINT)
        line("abis", Build.SUPPORTED_ABIS.joinToString())
        line("locale", Locale.getDefault().toLanguageTag())
        line("timezone", TimeZone.getDefault().id)
        line("filesDir", filesDir.absolutePath)
        line("filesDirTotalSpace", filesDir.totalSpace)
        line("filesDirFreeSpace", filesDir.freeSpace)
        line("filesDirUsableSpace", filesDir.usableSpace)
        line("externalFilesDir", runCatching { context.getExternalFilesDir(null)?.absolutePath }.getOrNull())

        val filesRootPath = filesRoot.absolutePath
        line("filesRoot", filesRootPath)
        line("filesRootCanonical", runCatching { filesRoot.canonicalPath }.getOrDefault(filesRootPath))
        line("filesRootExists", filesRoot.exists())
        line("filesRootIsDirectory", filesRoot.isDirectory)
        line("filesRootCanRead", filesRoot.canRead())
        line("filesRootCanWrite", filesRoot.canWrite())
        line("filesRootCanExecute", filesRoot.canExecute())

        val homePath = homeDir.absolutePath
        val homeCanonical = runCatching { homeDir.canonicalPath }.getOrDefault(homePath)
        line("homeDir", homePath)
        line("homeCanonical", homeCanonical)
        line("homeExists", homeDir.exists())
        line("homeIsDirectory", homeDir.isDirectory)
        line("homeCanRead", homeDir.canRead())
        line("homeCanWrite", homeDir.canWrite())
        line("homeCanExecute", homeDir.canExecute())
        line("homeTotalSpace", homeDir.totalSpace)
        line("homeFreeSpace", homeDir.freeSpace)
        line("homeUsableSpace", homeDir.usableSpace)

        val homeList = runCatching { homeDir.listFiles() }.getOrNull()
        line("homeListFilesNull", homeList == null)
        if (homeList != null) line("homeChildrenCount", homeList.size)
        if (!homeList.isNullOrEmpty()) {
            sb.append("homeChildrenSample").append(":\n")
            for (f in homeList.sortedBy { it.name.lowercase(Locale.ROOT) }.take(80)) {
                sb.append("- ")
                    .append(if (f.isDirectory) "D " else "F ")
                    .append(f.name)
                    .append(" size=").append(if (f.isFile) f.length() else "-")
                    .append(" mtime=").append(runCatching { f.lastModified() }.getOrDefault(0L))
                    .append('\n')
            }
            if (homeList.size > 80) sb.append("- ...\n")
        }

        if (currentDir != null) {
            val currentPath = currentDir.absolutePath
            line("currentDir", currentPath)
            line("currentCanonical", runCatching { currentDir.canonicalPath }.getOrDefault(currentPath))
            line("currentExists", currentDir.exists())
            line("currentIsDirectory", currentDir.isDirectory)
            line("currentWithinFilesRoot", isWithinRoot(currentDir, filesRoot))
            val currentList = runCatching { currentDir.listFiles() }.getOrNull()
            line("currentListFilesNull", currentList == null)
            if (!currentList.isNullOrEmpty()) {
                line("currentChildrenCount", currentList.size)
                sb.append("currentChildrenSample").append(":\n")
                for (f in currentList.sortedBy { it.name.lowercase(Locale.ROOT) }.take(80)) {
                    sb.append("- ")
                        .append(if (f.isDirectory) "D " else "F ")
                        .append(f.name)
                        .append(" size=").append(if (f.isFile) f.length() else "-")
                        .append(" mtime=").append(runCatching { f.lastModified() }.getOrDefault(0L))
                        .append('\n')
                }
                if (currentList.size > 80) sb.append("- ...\n")
            }
        }

        val workspaceDir = File(homeDir, "workspace")
        runCatching { workspaceDir.mkdirs() }
        line("workspaceDir", workspaceDir.absolutePath)
        line("workspaceExists", workspaceDir.exists())
        line("workspaceIsDirectory", workspaceDir.isDirectory)

        val escapeAttempt = File(homeDir, "..")
        line("escapeAttemptPath", escapeAttempt.absolutePath)
        line("escapeAttemptWithinFilesRoot", isWithinRoot(escapeAttempt, filesRoot))

        line("procMeminfoReadable", File("/proc/meminfo").canRead())
        val meminfo = readHead(File("/proc/meminfo"))
        if (meminfo != null) {
            sb.append("procMeminfoHead").append(":\n").append(meminfo).append('\n')
        }
        val mounts = readHead(File("/proc/mounts"))
        if (mounts != null) {
            sb.append("procMountsHead").append(":\n").append(mounts).append('\n')
        }

        val iconTheme = runCatching { VsCodeFileIconTheme.load(context) }.getOrNull()
        line("iconThemeLoaded", iconTheme != null)
        if (iconTheme != null) {
            line("iconThemeIconDefinitions", iconTheme.iconDefinitionCount)
            line("iconThemeAssetCount", iconTheme.assetCount)
            val samples = listOf(
                Pair(false, "a.txt"),
                Pair(false, "a.ts"),
                Pair(false, "a.js"),
                Pair(false, "a.html"),
                Pair(false, "a.yaml"),
                Pair(false, "a.yml"),
                Pair(false, "a.php"),
                Pair(false, "README.md"),
                Pair(false, ".gitignore"),
                Pair(false, "build.gradle"),
                Pair(false, "package.json"),
                Pair(false, "style.css.map"),
                Pair(false, "test.spec.tsx"),
                Pair(false, "go.mod"),
                Pair(false, "Cargo.toml"),
                Pair(false, "Dockerfile"),
                Pair(true, "node_modules"),
                Pair(true, "src"),
                Pair(true, ".git")
            )
            var okCount = 0
            var missingCount = 0
            sb.append("iconThemeSamples").append(":\n")
            for ((isDir, name) in samples) {
                val r = iconTheme.resolve(name, isDir, preferLight = false)
                val asset = r.assetName
                val exists = if (asset == null) {
                    false
                } else {
                    runCatching { context.assets.open(asset).use { } }.isSuccess
                }
                if (exists) okCount++ else missingCount++
                sb.append("- ")
                    .append(if (isDir) "D " else "F ")
                    .append(name)
                    .append(" => asset=").append(asset ?: "null")
                    .append(" exists=").append(exists)
                    .append(" strategy=").append(r.strategy)
                    .append(" iconId=").append(r.iconId ?: "null")
                    .append(" attempted=").append(r.attempted.take(12).joinToString(","))
                    .append('\n')
            }
            line("iconThemeSampleOk", okCount)
            line("iconThemeSampleMissing", missingCount)
        }

        val tempDir = File(homeDir, ".ui_shell_selftest_${System.currentTimeMillis()}")
        if (!tempDir.mkdirs()) {
            line("tempDirCreateFailed", tempDir.absolutePath)
            return@withContext sb.toString()
        }
        line("tempDir", tempDir.absolutePath)

        val events = ArrayList<String>(64)
        val observer = DirectoryObserver(tempDir) { event, path ->
            events.add("${eventName(event)} ${path ?: ""}".trim())
        }
        observer.startWatching()

        suspend fun afterOp() {
            delay(80)
        }

        val a = File(tempDir, "a.txt")
        FileOutputStream(a).use { it.write("hello".toByteArray()) }
        afterOp()
        line("createFileA", a.exists())
        line("fileASize", a.length())

        val contentA = FileInputStream(a).use { it.readBytes().toString(Charsets.UTF_8) }
        line("fileAContent", contentA)

        val b = File(tempDir, "b.txt")
        val renamed = a.renameTo(b)
        afterOp()
        line("renameAToB", renamed)
        line("fileBExists", b.exists())

        val sub = File(tempDir, "sub")
        val subCreated = sub.mkdirs()
        afterOp()
        line("createSubDir", subCreated)

        val moved = b.renameTo(File(sub, "b.txt"))
        afterOp()
        line("moveBToSub", moved)

        val movedFile = File(sub, "b.txt")
        val deletedFile = movedFile.delete()
        afterOp()
        line("deleteMovedFile", deletedFile)

        val deletedSub = sub.delete()
        afterOp()
        line("deleteSubDir", deletedSub)

        observer.stopWatching()
        afterOp()

        line("observerEventCount", events.size)
        if (events.isNotEmpty()) {
            sb.append("observerEvents").append(":\n")
            for (e in events.take(40)) sb.append("- ").append(e).append('\n')
            if (events.size > 40) sb.append("- ...\n")
        }

        val deletedTemp = deleteRecursively(tempDir)
        line("cleanupTempDir", deletedTemp)

        sb.toString()
    }

    private fun eventName(event: Int): String {
        val masked = event and FileObserver.ALL_EVENTS
        return when (masked) {
            FileObserver.CREATE -> "CREATE"
            FileObserver.DELETE -> "DELETE"
            FileObserver.MOVED_FROM -> "MOVED_FROM"
            FileObserver.MOVED_TO -> "MOVED_TO"
            FileObserver.CLOSE_WRITE -> "CLOSE_WRITE"
            FileObserver.DELETE_SELF -> "DELETE_SELF"
            FileObserver.MOVE_SELF -> "MOVE_SELF"
            FileObserver.ATTRIB -> "ATTRIB"
            else -> "EVENT_$masked"
        }
    }

    private fun deleteRecursively(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (c in children) {
                    if (!deleteRecursively(c)) return false
                }
            }
        }
        return runCatching { file.delete() }.getOrDefault(false)
    }

    private fun isWithinRoot(file: File, root: File): Boolean {
        val rootCanonical = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        val fileCanonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        if (fileCanonical == rootCanonical) return true
        return fileCanonical.startsWith(rootCanonical.trimEnd(File.separatorChar) + File.separator)
    }

    private fun now(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return fmt.format(Date())
    }

    private fun getAppVersion(context: Context): String? {
        val pm = context.packageManager ?: return null
        val info: PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        val versionName = info.versionName ?: ""
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return "${versionName} (${versionCode})"
    }

    private fun readHead(file: File, maxBytes: Int = 8192): String? {
        if (!file.exists() || !file.canRead() || file.isDirectory) return null
        return runCatching {
            FileInputStream(file).use { input ->
                val buf = ByteArray(maxBytes)
                val n = input.read(buf)
                if (n <= 0) return@use ""
                String(buf, 0, n, Charsets.UTF_8)
            }
        }.getOrNull()
    }
}
