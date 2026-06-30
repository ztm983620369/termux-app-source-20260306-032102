package com.termux.ecjbridge

import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EcjTemplateCreateRequest(
    val parentDirectory: File,
    val projectName: String,
    val sourceAppPackage: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

data class EcjTemplateCreateResult(
    val projectRoot: File,
    val configFile: File,
    val entryFile: File
)

object EcjTemplateScaffold {
    private const val TEMPLATE_TITLE = "Termux ECJ Template"
    private val invalidNameChars = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    fun sanitizeProjectName(rawName: String, fallback: String = "ecj-template"): String {
        val compact = rawName.trim().replace(invalidNameChars, "_")
        return compact.take(64).trim('.', '_').ifBlank { fallback }
    }

    @Throws(IOException::class)
    fun createProject(request: EcjTemplateCreateRequest): EcjTemplateCreateResult {
        val parent = request.parentDirectory.canonicalFile
        if (!parent.exists() || !parent.isDirectory) {
            throw IOException("父目录不存在：${parent.absolutePath}")
        }
        if (!parent.canWrite()) {
            throw IOException("父目录不可写：${parent.absolutePath}")
        }

        val safeName = sanitizeProjectName(request.projectName)
        val root = File(parent, safeName).canonicalFile
        if (!isChildOf(parent, root)) {
            throw IOException("项目目录越界：${root.absolutePath}")
        }
        if (root.exists()) {
            throw IOException("项目已存在：${root.absolutePath}")
        }
        if (!root.mkdirs()) {
            throw IOException("无法创建项目目录：${root.absolutePath}")
        }

        try {
            writeDirectoryLayout(root)
            writeAtomic(File(root, EcjBridgeContract.HOPWEB_TITLE), "$safeName\n")
            writeAtomic(File(root, EcjBridgeContract.HOPWEB_TEMPLATE), "Termux ECJ 模板\n")
            writeAtomic(File(root, "README.md"), buildReadme(safeName))
            writeAtomic(File(root, "assets/data/config.json"), buildDemoConfig(safeName))
            writeAtomic(File(root, "assets/text/hello.txt"), buildHelloText(safeName))
            writeAtomic(File(root, "libs/README.md"), buildLibsReadme())

            val configFile = File(root, EcjBridgeContract.PROJECT_CONFIG)
            writeAtomic(configFile, buildProjectConfig(root, safeName, request))

            writeAtomic(
                File(root, EcjBridgeContract.TERMUX_LINK_CONFIG),
                buildTermuxLinkConfig(root, safeName, request)
            )

            val entryFile = File(root, "src/com/dynamic/RealAppScript.java")
            writeAtomic(entryFile, buildRealAppScript(safeName))

            return EcjTemplateCreateResult(
                projectRoot = root,
                configFile = configFile,
                entryFile = entryFile
            )
        } catch (t: Throwable) {
            runCatching { root.deleteRecursively() }
            throw t
        }
    }

    private fun writeDirectoryLayout(root: File) {
        listOf(
            EcjBridgeContract.CONFIG_DIR,
            "src/com/dynamic",
            "assets/data",
            "assets/text",
            "assets/images",
            "libs"
        ).forEach { relative ->
            val dir = File(root, relative)
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("无法创建目录：${dir.absolutePath}")
            }
        }
    }

    private fun buildProjectConfig(root: File, projectName: String, request: EcjTemplateCreateRequest): String {
        return """
            {
              "schema": "com.termux.ecjbridge.project",
              "schemaVersion": ${EcjBridgeContract.BRIDGE_VERSION},
              "templateVersion": "${EcjBridgeContract.TEMPLATE_VERSION}",
              "name": "${json(projectName)}",
              "rootPath": "${json(root.absolutePath)}",
              "entryClass": "${EcjBridgeContract.ENTRY_CLASS_NAME}",
              "entryMethod": "${EcjBridgeContract.ENTRY_METHOD_NAME}",
              "sourceLayout": "src",
              "assetsDir": "assets",
              "libsDir": "libs",
              "createdBy": "${json(request.sourceAppPackage)}",
              "createdAt": "${json(isoTime(request.createdAtMillis))}"
            }
        """.trimIndent() + "\n"
    }

    private fun buildTermuxLinkConfig(root: File, projectName: String, request: EcjTemplateCreateRequest): String {
        return """
            {
              "schema": "com.termux.ecjbridge.termuxLink",
              "schemaVersion": ${EcjBridgeContract.BRIDGE_VERSION},
              "projectName": "${json(projectName)}",
              "termuxProjectPath": "${json(root.absolutePath)}",
              "sourceAppPackage": "${json(request.sourceAppPackage)}",
              "archiveProvider": "${json(request.sourceAppPackage)}.ecjbridge",
              "runAction": "${EcjBridgeContract.ACTION_RUN_PROJECT}",
              "createdAt": "${json(isoTime(request.createdAtMillis))}"
            }
        """.trimIndent() + "\n"
    }

    private fun buildReadme(projectName: String): String {
        return """
            # $projectName

            这是一个标准 ECJ Android 运行项目，由 Termux ECJ Bridge 创建。

            ## 运行入口
            - `${EcjBridgeContract.ENTRY_CLASS_NAME}#${EcjBridgeContract.ENTRY_METHOD_NAME}(Activity)`

            ## 目录约定
            - `src/`：Java 源码
            - `assets/`：项目资源，运行时通过 `Host.readAssetText(...)` 等 API 读取
            - `libs/`：项目级 `*.jar` / `*.aar`
            - `.ecj/project.json`：跨 App 桥接配置
            - `.ecj/termux-link.json`：Termux 原生调用配置

            这个文件夹可以被 ECJ App 直接作为项目运行，也可以由 Termux 通过桥接模块授权给 ECJ App 运行。
        """.trimIndent() + "\n"
    }

    private fun buildDemoConfig(projectName: String): String {
        return """
            {
              "title": "${json(projectName)}",
              "message": "来自 Termux 创建的 ECJ 标准模板",
              "text": "text/hello.txt"
            }
        """.trimIndent() + "\n"
    }

    private fun buildHelloText(projectName: String): String {
        return """
            Hello from $projectName

            这个文本文件位于项目目录 assets/text/hello.txt。
            ECJ 运行时会通过 Host.readAssetText("text/hello.txt") 原生读取它。
        """.trimIndent() + "\n"
    }

    private fun buildLibsReadme(): String {
        return """
            # Project-local dependencies

            将 Android 可用的 `*.jar` 或 `*.aar` 放到这里。
            ECJ App 运行项目时会把 `libs/` 纳入编译和 D8 转换链路。
        """.trimIndent() + "\n"
    }

    private fun buildRealAppScript(projectName: String): String {
        val safeTitle = java(projectName)
        return """
            package com.dynamic;

            import android.app.Activity;
            import android.graphics.Color;
            import android.view.Gravity;
            import android.view.ViewGroup;
            import android.widget.LinearLayout;
            import android.widget.ScrollView;
            import android.widget.TextView;

            import com.dynamic.std.StdSuite;

            public class RealAppScript {
              public void launch(Activity activity) {
                StdSuite.attach(activity);

                String projectRoot = Host.getProjectRootPath();
                String hello = Host.readAssetText("text/hello.txt");

                LinearLayout content = new LinearLayout(activity);
                content.setOrientation(LinearLayout.VERTICAL);
                content.setGravity(Gravity.CENTER_HORIZONTAL);
                content.setPadding(dp(activity, 20), dp(activity, 22), dp(activity, 20), dp(activity, 22));
                content.setBackgroundColor(Color.rgb(15, 16, 20));

                TextView title = text(activity, "$safeTitle", 22, 0xFFFFFFFF);
                title.setGravity(Gravity.CENTER);
                content.addView(title);

                TextView subtitle = text(activity,
                    "Termux ECJ Bridge\\n" +
                    "Entry: com.dynamic.RealAppScript#launch\\n" +
                    "ProjectRoot: " + projectRoot,
                    12,
                    0xFF9AA4B2);
                subtitle.setGravity(Gravity.CENTER);
                subtitle.setPadding(0, dp(activity, 12), 0, dp(activity, 14));
                content.addView(subtitle);

                TextView body = text(activity, hello == null ? "" : hello, 14, 0xFFE7EAF0);
                body.setPadding(dp(activity, 14), dp(activity, 14), dp(activity, 14), dp(activity, 14));
                body.setBackgroundColor(0xFF1B1D24);
                content.addView(body, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ));

                ScrollView scroll = new ScrollView(activity);
                scroll.addView(content, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ));
                activity.setContentView(scroll);
              }

              private static TextView text(Activity activity, String value, int sp, int color) {
                TextView view = new TextView(activity);
                view.setText(value);
                view.setTextSize((float) sp);
                view.setTextColor(color);
                return view;
              }

              private static int dp(Activity activity, int dp) {
                float density = activity.getResources().getDisplayMetrics().density;
                return (int) (dp * density + 0.5f);
              }
            }
        """.trimIndent() + "\n"
    }

    private fun writeAtomic(file: File, text: String, charset: Charset = Charsets.UTF_8) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile ?: file.absoluteFile.parentFile, "${file.name}.tmp-${System.nanoTime()}")
        tmp.writeText(text, charset)
        if (file.exists() && !file.delete()) {
            tmp.delete()
            throw IOException("无法替换文件：${file.absolutePath}")
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("无法写入文件：${file.absolutePath}")
        }
    }

    private fun isChildOf(parent: File, child: File): Boolean {
        val parentPath = parent.canonicalPath.trimEnd(File.separatorChar)
        val childPath = child.canonicalPath
        return childPath == parentPath || childPath.startsWith("$parentPath${File.separator}")
    }

    private fun isoTime(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(timeMillis))
    }

    private fun json(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun java(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }
}
