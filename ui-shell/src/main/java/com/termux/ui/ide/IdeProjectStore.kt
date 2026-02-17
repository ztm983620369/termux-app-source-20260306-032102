package com.termux.ui.ide

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import com.termux.ui.panel.ExecTarget
import com.termux.ui.panel.ProotDistroManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object IdeProjectStore {

    private fun indexFile(context: Context): File {
        val home = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
        val dir = File(home, ".termux-ide")
        dir.mkdirs()
        return File(dir, "projects.json")
    }

    fun loadProjects(context: Context): List<IdeProject> {
        val f = indexFile(context)
        if (!f.exists() || !f.isFile) return emptyList()
        val text = runCatching { f.readText() }.getOrNull() ?: return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("projects") ?: return emptyList()
        val list = ArrayList<IdeProject>(arr.length())
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val projRaw = parseProject(o) ?: continue
            val inferred = inferTargetFromPath(projRaw.rootDir)
            val proj = if (inferred != null && inferred != projRaw.target) {
                changed = true
                projRaw.copy(target = inferred)
            } else {
                projRaw
            }
            list.add(proj)
        }
        if (changed) saveProjects(context, list)
        return list
    }

    fun saveProjects(context: Context, projects: List<IdeProject>) {
        val root = JSONObject()
        val arr = JSONArray()
        for (p in projects) arr.put(serializeProject(p))
        root.put("version", 1)
        root.put("projects", arr)
        val f = indexFile(context)
        f.writeText(root.toString(2))
    }

    fun addProject(context: Context, project: IdeProject) {
        val list = loadProjects(context).toMutableList()
        list.removeAll { it.id == project.id || it.rootDir == project.rootDir }
        list.add(0, project)
        saveProjects(context, list)
    }

    fun removeProject(context: Context, projectId: String) {
        val list = loadProjects(context).filterNot { it.id == projectId }
        saveProjects(context, list)
    }

    fun createNewProject(
        context: Context,
        name: String,
        template: IdeTemplate,
        target: ExecTarget = ExecTarget.Host,
        toolVersions: List<IdeToolVersion> = emptyList(),
        runArgs: String = ""
    ): IdeProject {
        val home = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
        val workspace = when (target) {
            ExecTarget.Host -> File(home, "workspace")
            is ExecTarget.Proot -> File(ProotDistroManager.installedRootHomeDir(target.distro), "workspace")
        }.apply { mkdirs() }
        val safeName = sanitizeName(name).ifBlank { template.id }
        val root = uniqueDir(workspace, safeName)
        root.mkdirs()

        val runConfigs = defaultRunConfigs(template)
        val project = IdeProject(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { safeName },
            rootDir = root.absolutePath,
            templateId = template.id,
            target = target,
            toolVersions = toolVersions,
            runConfigs = runConfigs,
            runArgs = runArgs
        )

        IdeProjectTemplates.writeTemplateFiles(template, root)
        IdeProjectTemplates.writeProjectConfig(project, root)
        addProject(context, project)
        return project
    }

    fun importExistingProject(
        context: Context,
        name: String,
        rootDir: String,
        target: ExecTarget = ExecTarget.Host
    ): IdeProject {
        val root = File(rootDir)
        if (!root.exists() || !root.isDirectory) throw IllegalStateException("目录不可用: $rootDir")
        val template = IdeProjectTemplates.detectTemplate(root) ?: IdeTemplate.NOTE
        val inferredTarget = inferTargetFromPath(root.absolutePath) ?: target
        val project = IdeProject(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { root.name },
            rootDir = root.absolutePath,
            templateId = template.id,
            target = inferredTarget,
            toolVersions = emptyList(),
            runConfigs = defaultRunConfigs(template),
            runArgs = ""
        )
        IdeProjectTemplates.writeProjectConfig(project, root)
        addProject(context, project)
        return project
    }

    private fun defaultRunConfigs(template: IdeTemplate): List<IdeRunConfig> {
        return when (template) {
            IdeTemplate.CPP -> listOf(
                IdeRunConfig(
                    id = "run",
                    name = "Run",
                    command = "g++ main.cpp -O2 -std=c++17 -o app && ./app",
                    workdir = "."
                )
            )

            IdeTemplate.PYTHON -> listOf(
                IdeRunConfig(
                    id = "run",
                    name = "Run",
                    command = "python3 main.py",
                    workdir = "."
                )
            )

            IdeTemplate.NODE -> listOf(
                IdeRunConfig(
                    id = "run",
                    name = "Run",
                    command = "node index.js",
                    workdir = "."
                )
            )

            IdeTemplate.GO -> listOf(
                IdeRunConfig(
                    id = "run",
                    name = "Run",
                    command = "go run main.go",
                    workdir = "."
                )
            )

            IdeTemplate.NOTE -> listOf(
                IdeRunConfig(
                    id = "open",
                    name = "Open",
                    command = "ls -la",
                    workdir = "."
                )
            )
        }
    }

    private fun parseProject(o: JSONObject): IdeProject? {
        val id = o.optString("id").orEmpty()
        val name = o.optString("name").orEmpty()
        val rootDir = o.optString("rootDir").orEmpty()
        if (id.isBlank() || rootDir.isBlank()) return null
        val templateId = o.optString("templateId").orEmpty()
        val targetObj = o.optJSONObject("target")
        val target = parseTarget(targetObj) ?: ExecTarget.Host
        val pythonVenv = o.optBoolean("pythonVenv", false)
        val versionsArr = o.optJSONArray("toolVersions") ?: JSONArray()
        val toolVersions = ArrayList<IdeToolVersion>(versionsArr.length())
        for (i in 0 until versionsArr.length()) {
            val v = versionsArr.optJSONObject(i) ?: continue
            val toolId = v.optString("toolId").trim()
            val plugin = v.optString("plugin").trim()
            val ver = v.optString("version").trim()
            if (toolId.isBlank() || plugin.isBlank() || ver.isBlank()) continue
            toolVersions.add(IdeToolVersion(toolId = toolId, plugin = plugin, version = ver))
        }
        val runArgs = o.optString("runArgs").orEmpty()
        val runs = o.optJSONArray("runConfigs") ?: JSONArray()
        val runConfigs = ArrayList<IdeRunConfig>(runs.length())
        for (i in 0 until runs.length()) {
            val r = runs.optJSONObject(i) ?: continue
            val rc = IdeRunConfig(
                id = r.optString("id").ifBlank { "run" },
                name = r.optString("name").ifBlank { "Run" },
                command = r.optString("command").ifBlank { "" },
                workdir = r.optString("workdir").ifBlank { "." }
            )
            runConfigs.add(rc)
        }
        return IdeProject(
            id = id,
            name = name.ifBlank { File(rootDir).name },
            rootDir = rootDir,
            templateId = templateId,
            target = target,
            toolVersions = toolVersions,
            runConfigs = if (runConfigs.isEmpty()) listOf(IdeRunConfig("run", "Run", "ls -la", ".")) else runConfigs,
            runArgs = runArgs,
            pythonVenv = pythonVenv
        )
    }

    private fun serializeProject(p: IdeProject): JSONObject {
        val o = JSONObject()
        o.put("id", p.id)
        o.put("name", p.name)
        o.put("rootDir", p.rootDir)
        o.put("templateId", p.templateId)
        o.put("target", serializeTarget(p.target))
        o.put("pythonVenv", p.pythonVenv)
        val vers = JSONArray()
        for (v in p.toolVersions) {
            val vo = JSONObject()
            vo.put("toolId", v.toolId)
            vo.put("plugin", v.plugin)
            vo.put("version", v.version)
            vers.put(vo)
        }
        o.put("toolVersions", vers)
        o.put("runArgs", p.runArgs)
        val arr = JSONArray()
        for (r in p.runConfigs) {
            val ro = JSONObject()
            ro.put("id", r.id)
            ro.put("name", r.name)
            ro.put("command", r.command)
            ro.put("workdir", r.workdir)
            arr.put(ro)
        }
        o.put("runConfigs", arr)
        return o
    }

    private fun serializeTarget(t: ExecTarget): JSONObject {
        val o = JSONObject()
        when (t) {
            ExecTarget.Host -> {
                o.put("mode", "host")
            }
            is ExecTarget.Proot -> {
                o.put("mode", "proot")
                o.put("distro", t.distro)
            }
        }
        return o
    }

    private fun parseTarget(o: JSONObject?): ExecTarget? {
        if (o == null) return null
        val mode = o.optString("mode").lowercase()
        return if (mode == "proot") {
            val d = o.optString("distro").ifBlank { "ubuntu" }
            ExecTarget.Proot(d)
        } else if (mode == "host") {
            ExecTarget.Host
        } else {
            null
        }
    }

    private fun sanitizeName(name: String): String {
        val trimmed = name.trim()
        val sb = StringBuilder(trimmed.length)
        for (ch in trimmed) {
            val ok = ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.'
            sb.append(if (ok) ch else '-')
        }
        return sb.toString().trim('-').ifBlank { "project" }
    }

    private fun uniqueDir(parent: File, baseName: String): File {
        var f = File(parent, baseName)
        if (!f.exists()) return f
        for (i in 2..9999) {
            f = File(parent, "$baseName-$i")
            if (!f.exists()) return f
        }
        return File(parent, baseName + "-" + UUID.randomUUID().toString().take(8))
    }

    private fun inferTargetFromPath(path: String): ExecTarget? {
        val p = path.replace('\\', '/')
        val marker = "/usr/var/lib/proot-distro/installed-rootfs/"
        val idx = p.indexOf(marker)
        if (idx < 0) return ExecTarget.Host
        val after = p.substring(idx + marker.length)
        val distro = after.substringBefore('/').trim()
        if (distro.isBlank()) return ExecTarget.Host
        return ExecTarget.Proot(distro)
    }
}
