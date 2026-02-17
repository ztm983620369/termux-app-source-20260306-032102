package com.termux.ui.panel

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ToolDescriptor(
    val id: String,
    val title: String,
    val binary: String,
    val versionArgs: String,
    val pkgNames: List<String>,
    val aptPackages: List<String>,
    val misePlugins: List<String>
)

data class ToolSnapshot(
    val tool: ToolDescriptor,
    val detectedVersion: String?,
    val detectedBy: String?,
    val message: String?,
    val detectedPath: String?,
    val detectedSource: String?
)

data class MiseSnapshot(
    val installed: Boolean,
    val version: String?,
    val message: String?
)

data class UiHint(
    val title: String,
    val detail: String,
    val suggestion: String?
)

object EnvironmentManager {

    val tools: List<ToolDescriptor> = listOf(
        ToolDescriptor("node", "Node.js", "node", "-v", listOf("nodejs", "nodejs-lts"), listOf("nodejs", "npm"), listOf("node", "nodejs")),
        ToolDescriptor("npm", "npm", "npm", "-v", listOf("nodejs", "nodejs-lts"), listOf("nodejs", "npm"), listOf("node", "nodejs")),
        ToolDescriptor("python", "Python", "python", "--version", listOf("python"), listOf("python3"), listOf("python")),
        ToolDescriptor("pip", "pip", "pip", "--version", listOf("python"), listOf("python3", "python3-pip"), listOf("python")),
        ToolDescriptor("go", "Go", "go", "version", listOf("golang"), listOf("golang-go"), listOf("go", "golang")),
        ToolDescriptor("java", "Java", "java", "-version", listOf("openjdk-21", "openjdk-17"), listOf("default-jdk"), listOf("java", "temurin")),
        ToolDescriptor("clang", "C/C++ clang", "clang", "--version", listOf("clang"), listOf("clang"), emptyList()),
        ToolDescriptor("gcc", "C/C++ gcc", "gcc", "--version", listOf("gcc"), listOf("gcc", "g++"), emptyList())
    )

    suspend fun detectMise(context: Context, target: ExecTarget): MiseSnapshot = withContext(Dispatchers.IO) {
        if (target is ExecTarget.Proot) {
            if (!File(ProotDistroManager.prootDistroBinPath()).exists()) {
                return@withContext MiseSnapshot(false, null, "未安装 proot-distro，无法进入 PRoot 检测（先在宿主执行：pkg install -y proot-distro）")
            }
            val script = """
                if [ -x "${'$'}HOME/.local/bin/mise" ]; then
                  "${'$'}HOME/.local/bin/mise" --version 2>&1 | head -n 1
                elif command -v mise >/dev/null 2>&1; then
                  mise --version 2>&1 | head -n 1
                else
                  echo not-installed
                fi
            """.trimIndent()
            val r = TermuxCommandRunner.runBash(context, target.wrapBashCommand(script))
            val text = (r.stdout.ifBlank { r.stderr }).trim()
            if (text == "not-installed" || text.isBlank()) {
                return@withContext MiseSnapshot(false, null, "未检测到 mise（多版本管理器）")
            }
            return@withContext MiseSnapshot(true, text.lines().firstOrNull()?.trim(), null)
        }

        val home = TermuxConstants.TERMUX_HOME_DIR_PATH
        val candidates = listOf(
            "$home/.local/bin/mise",
            "$home/.local/share/mise/bin/mise",
            File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "mise").absolutePath
        )
        val exe = candidates.firstOrNull { File(it).exists() && File(it).canExecute() }
        if (exe != null) {
            val r = TermuxCommandRunner.runBash(context, "\"$exe\" --version 2>&1 | head -n 1")
            val text = (r.stdout.ifBlank { r.stderr }).trim()
            if (text.isNotBlank()) {
                return@withContext MiseSnapshot(installed = true, version = text.lines().firstOrNull()?.trim(), message = null)
            }
        }

        val r = TermuxCommandRunner.runBash(context, "command -v mise >/dev/null 2>&1 && mise --version || echo not-installed")
        val text = (r.stdout.ifBlank { r.stderr }).trim()
        if (text == "not-installed" || text.isBlank()) {
            return@withContext MiseSnapshot(
                installed = false,
                version = null,
                message = "未检测到 mise（多版本管理器）"
            )
        }
        MiseSnapshot(
            installed = true,
            version = text.lines().firstOrNull()?.trim(),
            message = null
        )
    }

    fun miseInstallScript(): String {
        return """
            set -e
            export HOME="${'$'}{HOME:-/data/data/com.termux/files/home}"
            export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
            export MISE_INSTALL_EXT="${'$'}{MISE_INSTALL_EXT:-tar.gz}"
            mkdir -p "${'$'}HOME/.local/bin"

            if command -v pkg >/dev/null 2>&1; then
              pkg install -y curl ca-certificates tar zstd coreutils git >/dev/null 2>&1 || true
            fi

            installer="${'$'}(mktemp)"
            cleanup() { rm -f "${'$'}installer"; }
            trap cleanup EXIT

            if command -v curl >/dev/null 2>&1; then
              curl -fsSL https://mise.jdx.dev/install.sh -o "${'$'}installer"
            elif command -v wget >/dev/null 2>&1; then
              wget -qO "${'$'}installer" https://mise.jdx.dev/install.sh
            else
              echo "need curl or wget" 1>&2
              exit 2
            fi

            sh "${'$'}installer"

            if [ ! -x "${'$'}HOME/.local/bin/mise" ]; then
              echo "[ERROR] mise install finished but binary missing: ${'$'}HOME/.local/bin/mise" 1>&2
              ls -la "${'$'}HOME/.local/bin" 1>&2 || true
              for b in tar gzip xz zstd sha256sum shasum; do
                if command -v "${'$'}b" >/dev/null 2>&1; then
                  echo "ok: ${'$'}b=${'$'}(command -v "${'$'}b")" 1>&2
                else
                  echo "missing: ${'$'}b" 1>&2
                fi
              done
              exit 127
            fi

            "${'$'}HOME/.local/bin/mise" --version
        """.trimIndent()
    }

    fun miseInstallScript(target: ExecTarget): String {
        return if (target is ExecTarget.Proot) {
            """
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -y
            apt-get install -y curl ca-certificates git tar xz-utils zstd coreutils
            ${miseInstallScript()}
            """.trimIndent()
        } else {
            miseInstallScript()
        }
    }

    fun miseEnvExportLine(): String {
        return "export PATH=\"\$HOME/.local/share/mise/shims:\$HOME/.local/bin:\$HOME/.local/share/mise/bin:\$PATH\""
    }

    suspend fun detectTool(context: Context, target: ExecTarget, tool: ToolDescriptor): ToolSnapshot = withContext(Dispatchers.IO) {
        if (target is ExecTarget.Proot) {
            return@withContext detectToolInProot(context, target.distro, tool)
        }

        val cmd = buildDetectScript(tool)
        val r = TermuxCommandRunner.runBash(context, cmd)
        val parsed = parseDetectOutput(r.stdout.ifBlank { r.stderr })
        val path = parsed["PATH"]?.takeIf { it.isNotBlank() }
        val ver = parsed["VER"]?.takeIf { it.isNotBlank() }
        if (path != null && ver != null) {
            return@withContext ToolSnapshot(
                tool = tool,
                detectedVersion = ver,
                detectedBy = "PATH",
                message = null,
                detectedPath = path,
                detectedSource = sourceForPath(path)
            )
        }

        val prefixCandidate = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, tool.binary).absolutePath
        if (File(prefixCandidate).exists() && File(prefixCandidate).canExecute()) {
            val check = TermuxCommandRunner.runBash(
                context,
                when (tool.id) {
                    "java" -> "\"$prefixCandidate\" -version 2>&1 | head -n 1"
                    else -> "\"$prefixCandidate\" ${tool.versionArgs} 2>&1 | head -n 1"
                }
            )
            val v = (check.stdout.ifBlank { check.stderr }).trim()
            if (v.isNotBlank()) {
                return@withContext ToolSnapshot(
                    tool = tool,
                    detectedVersion = v,
                    detectedBy = "PREFIX",
                    message = null,
                    detectedPath = prefixCandidate,
                    detectedSource = "termux"
                )
            }
        }

        val home = TermuxConstants.TERMUX_HOME_DIR_PATH
        val shimCandidate = "$home/.local/share/mise/shims/${tool.binary}"
        if (File(shimCandidate).exists() && File(shimCandidate).canExecute()) {
            val check = TermuxCommandRunner.runBash(
                context,
                when (tool.id) {
                    "java" -> "\"$shimCandidate\" -version 2>&1 | head -n 1"
                    else -> "\"$shimCandidate\" ${tool.versionArgs} 2>&1 | head -n 1"
                }
            )
            val v = (check.stdout.ifBlank { check.stderr }).trim()
            if (v.isNotBlank()) {
                return@withContext ToolSnapshot(
                    tool = tool,
                    detectedVersion = v,
                    detectedBy = "MISE_SHIM",
                    message = null,
                    detectedPath = shimCandidate,
                    detectedSource = "mise"
                )
            }
        }

        val pkgInfo = queryAnyInstalledPkg(context, tool.pkgNames)
        if (pkgInfo != null) {
            return@withContext ToolSnapshot(
                tool = tool,
                detectedVersion = pkgInfo.second,
                detectedBy = "PKG",
                message = "已安装（包：${pkgInfo.first}），但当前 PATH 未检测到可执行文件",
                detectedPath = null,
                detectedSource = "termux"
            )
        }

        ToolSnapshot(
            tool = tool,
            detectedVersion = null,
            detectedBy = null,
            message = "未安装（Termux 宿主环境）",
            detectedPath = null,
            detectedSource = null
        )
    }

    suspend fun detectToolInProot(context: Context, distro: String, tool: ToolDescriptor): ToolSnapshot =
        withContext(Dispatchers.IO) {
            if (!File(ProotDistroManager.prootDistroBinPath()).exists()) {
                return@withContext ToolSnapshot(
                    tool = tool,
                    detectedVersion = null,
                    detectedBy = "proot:$distro",
                    message = "未安装 proot-distro（请在宿主执行：pkg install -y proot-distro）",
                    detectedPath = null,
                    detectedSource = "proot"
                )
            }
            val cmd = ProotDistroManager.execInDistroCmd(distro, buildDetectScript(tool))
            val r = TermuxCommandRunner.runBash(context, cmd)
            val parsed = parseDetectOutput(r.stdout.ifBlank { r.stderr })
            val path = parsed["PATH"]?.takeIf { it.isNotBlank() }
            val ver = parsed["VER"]?.takeIf { it.isNotBlank() }
            if (path != null && ver != null) {
                return@withContext ToolSnapshot(
                    tool = tool,
                    detectedVersion = ver,
                    detectedBy = "proot:$distro",
                    message = null,
                    detectedPath = path,
                    detectedSource = "proot"
                )
            }
            ToolSnapshot(
                tool = tool,
                detectedVersion = null,
                detectedBy = "proot:$distro",
                message = "未安装（PRoot：$distro）",
                detectedPath = null,
                detectedSource = "proot"
            )
        }

    fun pkgInstallCmd(tool: ToolDescriptor): String {
        val names = tool.pkgNames.filter { it.isNotBlank() }
        if (names.isEmpty()) return ""
        val chain = names.joinToString(" || ") { "pkg install -y $it" }
        return chain
    }

    fun aptInstallCmd(tool: ToolDescriptor): String {
        val names = tool.aptPackages.filter { it.isNotBlank() }
        if (names.isEmpty()) return ""
        val joined = names.joinToString(" ")
        return """
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -y
            apt-get install -y $joined
        """.trimIndent()
    }

    data class RemoteVersions(
        val plugin: String,
        val versions: List<String>
    )

    suspend fun listRemoteVersions(
        context: Context,
        target: ExecTarget,
        toolId: String,
        plugins: List<String>,
        limit: Int = 60
    ): Pair<RemoteVersions?, UiHint?> = withContext(Dispatchers.IO) {
        val tried = LinkedHashMap<String, List<String>>()
        var best: RemoteVersions? = null
        var bestScore = -1
        var firstError: UiHint? = null

        for (plugin in plugins.distinct().filter { it.isNotBlank() }) {
            val headCmd = """
                ${miseEnvExportLine()}
                mise ls-remote $plugin 2>/dev/null | head -n ${limit * 20}
            """.trimIndent()
            val tailCmd = """
                ${miseEnvExportLine()}
                mise ls-remote $plugin 2>/dev/null | tail -n ${limit * 20}
            """.trimIndent()

            val headR = TermuxCommandRunner.runBash(context, target.wrapBashCommand(headCmd))
            val tailR = TermuxCommandRunner.runBash(context, target.wrapBashCommand(tailCmd))

            if (!headR.ok && !tailR.ok) {
                if (firstError == null) firstError = mapResultToHint("拉取可用版本失败：$plugin", headR)
                continue
            }

            fun parse(r: TermuxCmdResult): List<String> {
                val rawLines = r.stdout
                    .split('\n')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                val normalized = normalizeRemoteVersions(rawLines)
                return filterRemoteVersions(toolId, normalized)
            }

            val headV = if (headR.ok) parse(headR) else emptyList()
            val tailV = if (tailR.ok) parse(tailR) else emptyList()

            val chosen = if (scoreRemoteVersions(toolId, headV) >= scoreRemoteVersions(toolId, tailV)) headV else tailV
            val filtered = chosen.take(limit)

            tried[plugin] = filtered
            val score = scoreRemoteVersions(toolId, filtered)
            if (score > bestScore) {
                bestScore = score
                best = RemoteVersions(plugin = plugin, versions = filtered)
            }
        }

        if (best == null) {
            return@withContext Pair(null, firstError ?: UiHint("拉取可用版本失败", "mise ls-remote 无可用输出", "检查 mise 是否可用，或切换网络后重试"))
        }

        val versions = best.versions
        if (versions.isEmpty() || bestScore <= 0) {
            val detail = buildString {
                appendLine("目标：${target.displayName()}")
                appendLine("工具：$toolId")
                appendLine("候选插件：${plugins.distinct().joinToString(", ")}")
                appendLine()
                for ((k, v) in tried) {
                    appendLine("插件：$k")
                    appendLine(v.take(20).joinToString(", "))
                    appendLine()
                }
            }.trimEnd()
            return@withContext Pair(best, UiHint("版本列表疑似异常", detail, "可能插件名不匹配；可尝试切换到另一个候选插件或更新 mise"))
        }

        Pair(best, null)
    }

    private fun normalizeRemoteVersions(lines: List<String>): List<String> {
        val semver = Regex("^[vV]?\\d+(?:\\.\\d+){1,3}([+~\\-_.].*)?$")
        val raw = lines
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.removePrefix("v").removePrefix("V") }
            .filter { semver.matches(it) || it.firstOrNull()?.isDigit() == true }
            .map { it.split(' ', '\t').firstOrNull().orEmpty() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return raw
            .sortedWith { a, b ->
                val pa = parseSemVer(a)
                val pb = parseSemVer(b)
                when {
                    pa != null && pb != null -> compareSemVerDesc(pa, pb)
                    pa != null -> -1
                    pb != null -> 1
                    else -> b.compareTo(a)
                }
            }
    }

    private data class SemVerParts(val major: Int, val minor: Int, val patch: Int, val extra: String)

    private fun parseSemVer(v: String): SemVerParts? {
        val s = v.trim()
        val m = Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?(.*)$").find(s) ?: return null
        val major = m.groupValues[1].toIntOrNull() ?: return null
        val minor = m.groupValues[2].toIntOrNull() ?: return null
        val patch = m.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        val extra = m.groupValues.getOrNull(4).orEmpty()
        return SemVerParts(major, minor, patch, extra)
    }

    private fun compareSemVerDesc(a: SemVerParts, b: SemVerParts): Int {
        if (a.major != b.major) return b.major.compareTo(a.major)
        if (a.minor != b.minor) return b.minor.compareTo(a.minor)
        if (a.patch != b.patch) return b.patch.compareTo(a.patch)
        return b.extra.compareTo(a.extra)
    }

    private fun filterRemoteVersions(toolId: String, versions: List<String>): List<String> {
        val majors = versions.map { it to (parseMajor(it) ?: -1) }
        return when (toolId) {
            "node", "npm" -> majors.filter { it.second >= 4 }.map { it.first }
            "python", "pip" -> majors.filter { it.second >= 3 }.map { it.first }
            "java" -> majors.filter { it.second >= 8 }.map { it.first }
            "go" -> majors.filter { it.second >= 1 }.map { it.first }
            else -> versions
        }
    }

    private fun scoreRemoteVersions(toolId: String, versions: List<String>): Int {
        if (versions.isEmpty()) return 0
        val majors = versions.mapNotNull { parseMajor(it) }
        val maxMajor = majors.maxOrNull() ?: 0
        val base = when (toolId) {
            "node", "npm" -> if (maxMajor >= 4) 1000 + maxMajor else 0
            "python", "pip" -> if (maxMajor >= 3) 1000 + maxMajor else 0
            "go" -> if (maxMajor >= 1) 800 + maxMajor else 0
            "java" -> if (maxMajor >= 8) 800 + maxMajor else 0
            else -> maxMajor
        }
        if (base <= 0) return 0
        return base + versions.size.coerceAtMost(100)
    }

    private fun parseMajor(v: String): Int? {
        val s = v.trim()
        val m = Regex("^\\d+").find(s)?.value ?: return null
        return m.toIntOrNull()
    }

    suspend fun miseInstall(context: Context, plugin: String, version: String): Pair<TermuxCmdResult, UiHint?> =
        withContext(Dispatchers.IO) {
            val cmd = """
                ${miseEnvExportLine()}
                mise install $plugin@$version
            """.trimIndent()
            val r = TermuxCommandRunner.runBash(context, cmd)
            Pair(r, if (r.ok) null else mapResultToHint("安装失败：$plugin@$version", r))
        }

    suspend fun miseUseGlobal(context: Context, plugin: String, version: String): Pair<TermuxCmdResult, UiHint?> =
        withContext(Dispatchers.IO) {
            val cmd = """
                ${miseEnvExportLine()}
                mise use -g $plugin@$version
                mise current $plugin || true
            """.trimIndent()
            val r = TermuxCommandRunner.runBash(context, cmd)
            Pair(r, if (r.ok) null else mapResultToHint("切换失败：$plugin@$version", r))
        }

    suspend fun pkgInstall(context: Context, pkgName: String): Pair<TermuxCmdResult, UiHint?> = withContext(Dispatchers.IO) {
        val r = TermuxCommandRunner.runBash(context, "pkg install -y $pkgName")
        Pair(r, if (r.ok) null else mapResultToHint("pkg 安装失败：$pkgName", r))
    }

    private fun buildDetectScript(tool: ToolDescriptor): String {
        val candidates = when (tool.id) {
            "python" -> listOf("python3", "python")
            "pip" -> listOf("pip3", "pip")
            "node" -> listOf("node", "nodejs")
            else -> listOf(tool.binary)
        }.distinct()

        return when (tool.id) {
            "java" -> """
                if command -v java >/dev/null 2>&1; then
                  p="${'$'}(command -v java)"
                  rp="${'$'}(readlink -f "${'$'}p" 2>/dev/null || echo "${'$'}p")"
                  raw="${'$'}(java -version 2>&1 | head -n 2 | tr '\n' ' ')"
                  if command -v grep >/dev/null 2>&1; then
                    v="${'$'}(echo "${'$'}raw" | grep -oE '[0-9]+(\\.[0-9]+){1,3}' | head -n 1)"
                  else
                    v="${'$'}raw"
                  fi
                  echo "PATH=${'$'}rp"
                  echo "VER=${'$'}v"
                  echo "RAW=${'$'}raw"
                fi
            """.trimIndent()

            else -> """
                extract_ver() {
                  if command -v grep >/dev/null 2>&1; then
                    echo "${'$'}1" | grep -oE '[0-9]+(\\.[0-9]+){1,3}' | head -n 1
                  else
                    echo ""
                  fi
                }
                for b in ${candidates.joinToString(" ") { it }}; do
                  if command -v "${'$'}b" >/dev/null 2>&1; then
                    p="${'$'}(command -v "${'$'}b")"
                    rp="${'$'}(readlink -f "${'$'}p" 2>/dev/null || echo "${'$'}p")"
                    raw=""
                    case "${tool.id}" in
                      node)
                        raw="${'$'}("${'$'}b" -p 'process.versions.node' 2>/dev/null | head -n 1)"
                        [ -z "${'$'}raw" ] && raw="${'$'}("${'$'}b" -v 2>&1 | head -n 1)"
                        ;;
                      npm)
                        raw="${'$'}("${'$'}b" -v 2>&1 | head -n 1)"
                        ;;
                      python)
                        raw="${'$'}("${'$'}b" -c 'import sys; print("{}.{}.{}".format(sys.version_info[0],sys.version_info[1],sys.version_info[2]))' 2>/dev/null | head -n 1)"
                        [ -z "${'$'}raw" ] && raw="${'$'}("${'$'}b" --version 2>&1 | head -n 1)"
                        ;;
                      pip)
                        raw="${'$'}("${'$'}b" --version 2>&1 | head -n 1)"
                        ;;
                      go)
                        raw="${'$'}("${'$'}b" env GOVERSION 2>/dev/null | head -n 1)"
                        [ -z "${'$'}raw" ] && raw="${'$'}("${'$'}b" version 2>&1 | head -n 1)"
                        ;;
                      gcc)
                        raw="${'$'}("${'$'}b" -dumpfullversion -dumpversion 2>/dev/null | head -n 1)"
                        [ -z "${'$'}raw" ] && raw="${'$'}("${'$'}b" --version 2>&1 | head -n 1)"
                        ;;
                      clang)
                        raw="${'$'}("${'$'}b" --version 2>&1 | head -n 1)"
                        ;;
                      *)
                        raw="${'$'}("${'$'}b" ${tool.versionArgs} 2>&1 | head -n 1)"
                        ;;
                    esac
                    v="${'$'}(extract_ver "${'$'}raw")"
                    [ -z "${'$'}v" ] && v="${'$'}raw"
                    echo "PATH=${'$'}rp"
                    echo "VER=${'$'}v"
                    echo "RAW=${'$'}raw"
                    break
                  fi
                done
            """.trimIndent()
        }
    }

    private fun parseDetectOutput(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (raw in text.split('\n')) {
            val line = raw.trim()
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim()
            if (k.isNotBlank()) map[k] = v
        }
        return map
    }

    private fun sourceForPath(path: String): String {
        val p = path.lowercase()
        return when {
            "/.local/share/mise/" in p || "/.local/bin/mise" in p || "/.local/share/mise/shims" in p -> "mise"
            p.startsWith("/data/data/com.termux/files/usr/") -> "termux"
            else -> "unknown"
        }
    }

    private suspend fun queryAnyInstalledPkg(context: Context, pkgNames: List<String>): Pair<String, String>? {
        val names = pkgNames.filter { it.isNotBlank() }
        for (name in names) {
            val cmd = "dpkg-query -W -f='${'$'}{Status} ${'$'}{Version}\\n' $name 2>/dev/null | head -n 1"
            val r = TermuxCommandRunner.runBash(context, cmd)
            val line = r.stdout.trim()
            if (line.contains("install ok installed")) {
                val ver = line.split(' ').lastOrNull()?.trim().orEmpty()
                return Pair(name, ver.ifEmpty { line })
            }
        }
        return null
    }

    fun mapResultToHint(title: String, r: TermuxCmdResult): UiHint {
        val stdoutClean = stripAnsi(r.stdout).trimEnd()
        val stderrClean = stripAnsi(r.stderr).trimEnd()
        val combined = (stderrClean + "\n" + stdoutClean).lowercase()
        val suggestion = when {
            ("/.local/bin/mise" in combined || "mise.jdx.dev/install.sh" in combined) &&
                ("no such file or directory" in combined || "command not found" in combined || "exit code: 127" in combined) ->
                "mise 安装未产出可执行文件：先装依赖（pkg install -y curl ca-certificates tar zstd coreutils），并设置 MISE_INSTALL_EXT=tar.gz 后重试；如果脚本下载失败，先切换网络/DNS"
            "downloading rootfs archive" in combined || ("proot-distro" in combined && "url:" in combined) ->
                "rootfs 下载失败：检查网络/代理/DNS；建议先执行 termux-change-repo 切换镜像源，再安装 ca-certificates/curl 后重试"
            "could not resolve host" in combined || "name or service not known" in combined ->
                "DNS 解析失败：切换镜像源（termux-change-repo）或配置代理/私人 DNS"
            "connection timed out" in combined || "operation timed out" in combined || "connection reset" in combined ->
                "网络连接不稳定或被拦截：切换镜像源/使用代理后重试"
            "ssl" in combined || "certificate" in combined || "x509" in combined ->
                "TLS/证书失败：安装 ca-certificates/openssl 后重试"
            "http" in combined && (" 404" in combined || " 403" in combined) ->
                "下载地址不可用（403/404）：更新 proot-distro 或切换网络/镜像源后重试"
            "permission denied" in combined -> "检查存储权限/目录权限，或尝试在 Termux HOME 下执行"
            "network is unreachable" in combined -> "网络不可达：检查网络/飞行模式/代理配置"
            "not found" in combined && "pkg" in combined -> "Termux 基础组件缺失，先更新：pkg update && pkg upgrade"
            "no such file or directory" in combined -> "路径不存在或工具未安装（建议先 command -v 检查）"
            "termux-change-repo" in combined -> "运行 termux-change-repo 切换镜像源后再试"
            "cannot execute" in combined -> "可执行文件损坏或架构不匹配，尝试重新安装"
            else -> "打开终端复现命令，复制 stderr 进一步定位"
        }
        val detail = buildString {
            appendLine("命令：bash -lc ${r.command}")
            appendLine("工作目录：${r.workingDirectory}")
            appendLine("退出码：${r.exitCode ?: "-"}")
            if (stdoutClean.isNotBlank()) appendLine().appendLine("stdout:").appendLine(stdoutClean)
            if (stderrClean.isNotBlank()) appendLine().appendLine("stderr:").appendLine(stderrClean)
        }.trimEnd()
        return UiHint(title = title, detail = detail, suggestion = suggestion)
    }

    private fun stripAnsi(text: String): String {
        if (text.isEmpty()) return text
        val s = text.replace("\u001B(B", "")
        val csi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        val osc = Regex("\u001B\\][^\u0007]*(\u0007|\u001B\\\\)")
        return s.replace(osc, "").replace(csi, "")
    }
}
