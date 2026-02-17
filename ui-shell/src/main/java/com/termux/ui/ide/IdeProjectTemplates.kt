package com.termux.ui.ide

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object IdeProjectTemplates {

    private const val ENV_FILE_REL = ".termuxide/project.env"
    private const val INIT_FILE_REL = ".termuxide/init.sh"

    fun writeTemplateFiles(template: IdeTemplate, root: File) {
        when (template) {
            IdeTemplate.CPP -> {
                File(root, "main.cpp").writeText(
                    """
                    #include <iostream>
                    int main() {
                      std::cout << "Hello C++" << std::endl;
                      return 0;
                    }
                    """.trimIndent() + "\n"
                )
                File(root, ".gitignore").writeText("app\n*.o\nbuild/\n")
            }

            IdeTemplate.PYTHON -> {
                File(root, "main.py").writeText(
                    """
                    def main():
                        print("Hello Python")

                    if __name__ == "__main__":
                        main()
                    """.trimIndent() + "\n"
                )
                File(root, ".gitignore").writeText("__pycache__/\n.venv/\n")
            }

            IdeTemplate.NODE -> {
                File(root, "index.js").writeText("console.log('Hello Node');\n")
                File(root, "package.json").writeText(
                    JSONObject()
                        .put("name", root.name.lowercase())
                        .put("private", true)
                        .put("type", "module")
                        .put("scripts", JSONObject().put("start", "node index.js"))
                        .toString(2) + "\n"
                )
                File(root, ".gitignore").writeText("node_modules/\n")
            }

            IdeTemplate.GO -> {
                File(root, "main.go").writeText(
                    """
                    package main

                    import "fmt"

                    func main() {
                        fmt.Println("Hello Go")
                    }
                    """.trimIndent() + "\n"
                )
                File(root, "go.mod").writeText("module ${root.name.lowercase()}\n\ngo 1.21\n")
                File(root, ".gitignore").writeText("${root.name}\n")
            }

            IdeTemplate.NOTE -> {
                File(root, "README.md").writeText("# ${root.name}\n")
            }
        }
        File(root, ".termuxide").mkdirs()
    }

    fun writeProjectConfig(project: IdeProject, root: File) {
        val dir = File(root, ".termuxide").apply { mkdirs() }
        val file = File(dir, "project.json")
        file.writeText(serializeProjectConfig(project).toString(2) + "\n")
        ensureShellConfig(project, root)
    }

    fun detectTemplate(root: File): IdeTemplate? {
        val f = root.listFiles()?.toList().orEmpty()
        if (f.any { it.name == "main.cpp" }) return IdeTemplate.CPP
        if (f.any { it.name == "main.py" }) return IdeTemplate.PYTHON
        if (f.any { it.name == "package.json" } || f.any { it.name == "index.js" }) return IdeTemplate.NODE
        if (f.any { it.name == "main.go" } || f.any { it.name == "go.mod" }) return IdeTemplate.GO
        if (f.any { it.name.equals("README.md", ignoreCase = true) }) return IdeTemplate.NOTE
        return null
    }

    private fun serializeProjectConfig(project: IdeProject): JSONObject {
        val o = JSONObject()
        o.put("version", 1)
        o.put("id", project.id)
        o.put("name", project.name)
        o.put("rootDir", project.rootDir)
        o.put("templateId", project.templateId)
        o.put("pythonVenv", project.pythonVenv)
        o.put("runArgs", project.runArgs)
        val arr = JSONArray()
        for (rc in project.runConfigs) {
            val r = JSONObject()
            r.put("id", rc.id)
            r.put("name", rc.name)
            r.put("command", rc.command)
            r.put("workdir", rc.workdir)
            arr.put(r)
        }
        o.put("runConfigs", arr)
        return o
    }

    fun ensureShellConfig(project: IdeProject, root: File) {
        writeProjectEnvConfig(project, root)
        writeInitScript(root)
    }

    private fun writeProjectEnvConfig(project: IdeProject, root: File) {
        val f = File(root, ENV_FILE_REL)
        if (f.exists()) return
        val template = IdeTemplate.values().firstOrNull { it.id == project.templateId } ?: IdeTemplate.NOTE
        val pythonVenv = template == IdeTemplate.PYTHON && (project.pythonVenv || File(root, ".venv").isDirectory)
        val text = buildString {
            appendLine("# Termux IDE project env config (bash)")
            appendLine("#")
            appendLine("# 说明：")
            appendLine("# - 这个文件会被 .termuxide/init.sh 读取，并影响终端/运行/预检行为。")
            appendLine("# - 只需要把 yes/no 改掉即可生效。")
            appendLine()
            appendLine("# 自动进入项目根目录")
            appendLine("TERMUX_IDE_AUTO_CD=yes")
            appendLine()
            appendLine("# Python 虚拟环境（venv）")
            appendLine("TERMUX_IDE_PY_VENV_ENABLE=${if (pythonVenv) "yes" else "no"}")
            appendLine("TERMUX_IDE_PY_VENV_DIR=.venv")
            appendLine("TERMUX_IDE_PY_VENV_CREATE_IF_MISSING=${if (pythonVenv) "yes" else "no"}")
        }
        f.parentFile?.mkdirs()
        f.writeText(text.trimEnd() + "\n")
    }

    private fun writeInitScript(root: File) {
        val f = File(root, INIT_FILE_REL)
        if (f.exists()) return
        val text = """
            #!/data/data/com.termux/files/usr/bin/bash
            set -e

            project_root="$(cd "$(dirname "${'$'}0")/.." && pwd)"
            env_file="${'$'}project_root/.termuxide/project.env"

            if [ -f "${'$'}env_file" ]; then
              # shellcheck disable=SC1090
              . "${'$'}env_file"
            fi

            case "${'$'}{TERMUX_IDE_AUTO_CD:-yes}" in
              yes|y|true|1) cd "${'$'}project_root" ;;
            esac

            case "${'$'}{TERMUX_IDE_PY_VENV_ENABLE:-no}" in
              yes|y|true|1)
                venv_dir="${'$'}{TERMUX_IDE_PY_VENV_DIR:-.venv}"
                if [ ! -d "${'$'}venv_dir" ]; then
                  case "${'$'}{TERMUX_IDE_PY_VENV_CREATE_IF_MISSING:-no}" in
                    yes|y|true|1) (python3 -m venv "${'$'}venv_dir" || python -m venv "${'$'}venv_dir") ;;
                  esac
                fi
                if [ -f "${'$'}venv_dir/bin/activate" ]; then
                  # shellcheck disable=SC1090
                  . "${'$'}venv_dir/bin/activate"
                fi
                ;;
            esac
        """.trimIndent()
        f.parentFile?.mkdirs()
        f.writeText(text.trimEnd() + "\n")
        runCatching { f.setExecutable(true, false) }
    }
}
