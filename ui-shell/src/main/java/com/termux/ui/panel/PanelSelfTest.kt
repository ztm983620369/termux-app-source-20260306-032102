package com.termux.ui.panel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PanelSelfTest {

    data class SelfTestResult(
        val ok: Boolean,
        val title: String,
        val detail: String
    )

    suspend fun run(
        context: Context,
        target: ExecTarget = ExecTarget.Host,
        onLine: (String) -> Unit
    ): SelfTestResult = withContext(Dispatchers.IO) {
        val script = buildScript(target)
        val r = TermuxCommandRunner.runBashWithLineProgress(
            context = context,
            bashCommand = target.wrapBashCommand(script),
            onLine = onLine
        )

        val output = (r.stdout + "\n" + r.stderr).trim()
        val ok = r.ok && output.contains("[OK]")
        SelfTestResult(
            ok = ok,
            title = if (ok) "自检通过" else "自检发现问题",
            detail = EnvironmentManager.mapResultToHint("自检", r).detail
        )
    }

    private fun buildScript(target: ExecTarget): String {
        val prootNote = if (target is ExecTarget.Proot) {
            "echo \"NOTE: proot-distro 属于宿主 Termux，在 PRoot 内显示 not-found 是正常的\""
        } else {
            ""
        }
        return """
            set +e
            echo "=== PANEL SELFTEST ==="
            echo "time: ${'$'}(date 2>/dev/null || true)"
            echo "whoami: ${'$'}(whoami 2>/dev/null || true)"
            echo "uname: ${'$'}(uname -a 2>/dev/null || true)"
            echo "cwd: ${'$'}(pwd 2>/dev/null || true)"
            echo "PATH: ${'$'}PATH"
            echo
            echo "--- proot-distro ---"
            $prootNote
            command -v proot-distro >/dev/null 2>&1 && proot-distro --version 2>/dev/null || echo "proot-distro: not-found"
            echo
            echo "--- mise ---"
            if command -v mise >/dev/null 2>&1; then
              mise --version 2>&1 | head -n 1
            elif [ -x "${'$'}HOME/.local/bin/mise" ]; then
              "${'$'}HOME/.local/bin/mise" --version 2>&1 | head -n 1
            else
              echo "mise: not-found"
            fi
            if command -v mise >/dev/null 2>&1; then
              echo "mise shims: ${'$'}HOME/.local/share/mise/shims"
              echo "mise bin: ${'$'}HOME/.local/share/mise/bin"
            fi
            echo
            echo "--- tool versions ---"
            extract_ver() {
              if command -v grep >/dev/null 2>&1; then
                echo "${'$'}1" | grep -oE '[0-9]+(\\.[0-9]+){1,3}' | head -n 1
              else
                echo ""
              fi
            }
            for b in node npm python python3 pip pip3 go java gcc g++ clang clang++; do
              if command -v "${'$'}b" >/dev/null 2>&1; then
                echo "BIN ${'$'}b: ${'$'}(command -v "${'$'}b")"
                raw=""
                case "${'$'}b" in
                  java) raw="${'$'}(java -version 2>&1 | head -n 2 | tr '\n' ' ')" ;;
                  go) raw="${'$'}(go env GOVERSION 2>/dev/null | head -n 1)"; [ -z "${'$'}raw" ] && raw="${'$'}(go version 2>&1 | head -n 1)" ;;
                  gcc|g++) raw="${'$'}("${'$'}b" -dumpfullversion -dumpversion 2>/dev/null | head -n 1)"; [ -z "${'$'}raw" ] && raw="${'$'}("${'$'}b" --version 2>&1 | head -n 1)" ;;
                  clang|clang++) raw="${'$'}("${'$'}b" --version 2>&1 | head -n 1)" ;;
                  node) raw="${'$'}(node -p 'process.versions.node' 2>/dev/null | head -n 1)"; [ -z "${'$'}raw" ] && raw="${'$'}(node -v 2>&1 | head -n 1)" ;;
                  npm) raw="${'$'}(npm -v 2>&1 | head -n 1)" ;;
                  python|python3) raw="${'$'}("${'$'}b" -c 'import sys; print("{}.{}.{}".format(sys.version_info[0],sys.version_info[1],sys.version_info[2]))' 2>/dev/null | head -n 1)"; [ -z "${'$'}raw" ] && raw="${'$'}("${'$'}b" --version 2>&1 | head -n 1)" ;;
                  pip|pip3) raw="${'$'}("${'$'}b" --version 2>&1 | head -n 1)" ;;
                  *) raw="${'$'}("${'$'}b" --version 2>&1 | head -n 1)" ;;
                esac
                ver="${'$'}(extract_ver "${'$'}raw")"
                [ -z "${'$'}ver" ] && ver="${'$'}raw"
                echo "RAW: ${'$'}raw"
                echo "VER: ${'$'}ver"
              else
                echo "BIN ${'$'}b: not-found"
              fi
              echo
            done
            echo
            echo "--- mise plugins ---"
            if command -v mise >/dev/null 2>&1; then
              mise plugins 2>/dev/null | head -n 80 || true
            fi
            echo
            echo "--- mise ls-remote sanity ---"
            if command -v mise >/dev/null 2>&1; then
              for p in node nodejs python go golang java temurin; do
                echo "PLUGIN ${'$'}p:"
                mise ls-remote "${'$'}p" 2>&1 | head -n 20
                echo
              done
              echo "[OK] mise ls-remote executed"
            else
              echo "[WARN] mise not available, skip ls-remote"
            fi
            echo "=== SELFTEST END ==="
        """.trimIndent()
    }
}
