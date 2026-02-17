package io.github.rosemoe.sora.app.lsp

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import io.github.rosemoe.sora.app.BaseEditorActivity
import io.github.rosemoe.sora.app.R
import io.github.rosemoe.sora.app.switchThemeIfRequired
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.dsl.languages
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.lsp.client.connection.SocketStreamConnectionProvider
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition
import io.github.rosemoe.sora.lsp.client.languageserver.wrapper.EventHandler
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.lsp.events.EventType
import io.github.rosemoe.sora.lsp.events.code.codeAction
import io.github.rosemoe.sora.lsp.utils.asLspRange
import io.github.rosemoe.sora.lsp.utils.createTextDocumentIdentifier
import io.github.rosemoe.sora.lsp.utils.toFileUri
import io.github.rosemoe.sora.utils.toast
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.github.rosemoe.sora.widget.getComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.tm4e.core.registry.IThemeSource
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class PythonLspTestActivity : BaseEditorActivity() {

    private lateinit var lspProject: LspProject
    private var lspEditor: LspEditor? = null
    private lateinit var filePath: String

    private var rootMenu: Menu? = null
    private var formattingEnabled = false
    private var lastError: String? = null
    private var lastInitializeResult: InitializeResult? = null
    private var lastConnectedHost: String? = null
    private var lastConnectedPort: Int? = null

    private val prefs by lazy { getSharedPreferences("python_lsp_test", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "LSP Test - Python"

        val font = Typeface.createFromAsset(assets, "JetBrainsMono-Regular.ttf")
        editor.typefaceText = font
        editor.typefaceLineNumber = font

        ensureTextmateTheme()
        switchThemeIfRequired(this, editor)

        val (projectPath, filePath, initialText) = prepareProjectFiles()
        this.filePath = filePath

        editor.setText(initialText)
        editor.getComponent<EditorAutoCompletion>().setEnabledAnimation(true)
        editor.getComponent<EditorTextActionWindow>().isEnabled = false

        lspProject = LspProject(projectPath)

        promptAndConnect()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        switchThemeIfRequired(this, editor)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_python_lsp, menu)
        rootMenu = menu
        menu.findItem(R.id.code_format)?.isEnabled = formattingEnabled
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.code_format -> {
                val cursor = editor.text.cursor
                if (cursor.isSelected) {
                    editor.formatCodeAsync(cursor.left(), cursor.right())
                } else {
                    editor.formatCodeAsync()
                }
            }

            R.id.python_lsp_actions -> {
                showActionsDialog()
            }

            R.id.python_lsp_reconnect -> {
                val host = lastConnectedHost ?: (prefs.getString("host", "") ?: "")
                val port = lastConnectedPort ?: prefs.getInt("port", 4389)
                if (host.isBlank()) {
                    promptAndConnect()
                } else {
                    connectToServer(host, port)
                }
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        editor.release()
        lifecycleScope.launch {
            val editor = lspEditor
            if (editor != null) {
                runCatching { editor.dispose() }
            }
            runCatching { lspProject.dispose() }
        }
    }

    private fun prepareProjectFiles(): Triple<String, String, String> {
        val projectDir = File(externalCacheDir, "pythonProject").apply { mkdirs() }
        val file = File(projectDir, "main.py")
        val initialText = """
            def add(a: int, b: int) -> int:
                return a + b
            
            
            value = add(1, 2)
            print(value)
        """.trimIndent() + "\n"
        runCatching { file.writeText(initialText) }
        return Triple(projectDir.absolutePath, file.absolutePath, initialText)
    }

    private fun promptAndConnect() {
        val fallbackHost = "192.168.155.199"
        val defaultHost = (prefs.getString("host", "") ?: "").ifBlank { fallbackHost }
        val defaultPort = prefs.getInt("port", 4389)

        val statusText = TextView(this).apply {
            val summary = buildString {
                appendLine("电脑端 Node LSP：默认端口 4389")
                appendLine("热点模式：填电脑在手机热点下的 IPv4（通常是 192.168.43.x）")
                appendLine()
                appendLine(buildLocalNetworkSummary())
            }.trimEnd()
            text = summary
            setTextIsSelectable(true)
        }

        val hostInput = EditText(this).apply {
            hint = "电脑 IP（热点场景填电脑的 IPv4，例如 192.168.155.199）"
            setText(defaultHost)
        }
        val portInput = EditText(this).apply {
            hint = "端口（默认 4389）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(defaultPort.toString())
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(statusText)
            addView(hostInput)
            addView(portInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Python LSP 服务器地址")
            .setView(container)
            .setPositiveButton("连接") { _: DialogInterface, _: Int ->
                val host = hostInput.text?.toString()?.trim().orEmpty()
                val port = portInput.text?.toString()?.trim()?.toIntOrNull() ?: 4389
                prefs.edit().putString("host", host).putInt("port", port).apply()
                connectToServer(host, port)
            }
            .setNeutralButton("扫描") { _: DialogInterface, _: Int ->
                val port = portInput.text?.toString()?.trim()?.toIntOrNull() ?: 4389
                lifecycleScope.launch {
                    editor.editable = false
                    toast("正在扫描端口 $port（最多 10 秒）…")
                    val host = withContext(Dispatchers.IO) { scanForServerHost(port) }
                    editor.editable = true
                    if (host == null) {
                        showCopyableDialog(
                            "Python LSP 扫描失败",
                            buildString {
                                appendLine("未找到可连接的服务")
                                appendLine("port=$port")
                                appendLine()
                                appendLine(buildLocalNetworkSummary())
                            }.trimEnd()
                        )
                    } else {
                        prefs.edit().putString("host", host).putInt("port", port).apply()
                        connectToServer(host, port)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildLocalNetworkSummary(): String {
        val ips = NetworkUtils.getLocalIpv4Addresses()
        return buildString {
            appendLine("本机可用 IPv4：")
            if (ips.isEmpty()) {
                appendLine("- （未获取到）")
            } else {
                ips.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("扫描范围：")
            NetworkUtils.getScanPrefixes(ips).forEach { appendLine("- $it.0/24") }
        }.trimEnd()
    }

    private fun scanForServerHost(port: Int): String? {
        val ips = NetworkUtils.getLocalIpv4Addresses()
        val prefixes = NetworkUtils.getScanPrefixes(ips)
        val deadlineMs = System.currentTimeMillis() + 15_000L
        for (prefix in prefixes) {
            if (System.currentTimeMillis() > deadlineMs) {
                return null
            }
            if (NetworkUtils.canConnect("$prefix.1", port, 200)) {
                return "$prefix.1"
            }
            for (i in 2..254) {
                if (System.currentTimeMillis() > deadlineMs) {
                    return null
                }
                val octet = 256 - i
                val host = "$prefix.$octet"
                if (NetworkUtils.canConnect(host, port, 80)) return host
            }
        }
        return null
    }

    private fun connectToServer(host: String, port: Int) {
        lifecycleScope.launch {
            editor.editable = false
            toast("正在连接 Python LSP：$host:$port")
            lspProject.closeAllEditors()
            lspProject.removeServerDefinition("py")
            val serverDefinition =
                object : CustomLanguageServerDefinition(
                    "py",
                    mapOf("py" to "python"),
                    ServerConnectProvider {
                        SocketStreamConnectionProvider(port, host)
                    }
                ) {
                    private val listener = PythonLspEventListener(this@PythonLspTestActivity)
                    override val eventListener: EventHandler.EventListener
                        get() = listener
                }
            lspProject.addServerDefinition(serverDefinition)
            val editorInstance = lspProject.createEditor(filePath).also { created ->
                created.wrapperLanguage = createTextMateLanguage()
                created.editor = editor
                created.isEnableInlayHint = true
                LspEditorTextActionWindow(created).setOnMoreButtonClickListener { _, lspEditor ->
                    lspEditor.coroutineScope.launch {
                        lspEditor.eventManager.emitAsync(EventType.codeAction) {
                            put(editor.cursor.range.asLspRange())
                        }
                    }
                }
            }
            lspEditor = editorInstance
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    editorInstance.connectWithTimeout()
                }
            }
            editor.editable = true
            result.onSuccess {
                lastConnectedHost = host
                lastConnectedPort = port
                toast("已连接：$host:$port")
                val info = buildString {
                    appendLine("Python LSP connected")
                    appendLine("server=$host:$port")
                    appendLine("file=${editorInstance.uri.path}")
                }.trimEnd()
                showCopyableDialog("Python LSP 测试", info)
            }.onFailure { e ->
                val msg = buildString {
                    appendLine("Python LSP connect failed")
                    appendLine("server=$host:$port")
                    appendLine("file=${editorInstance.uri.path}")
                    appendLine()
                    appendLine(Log.getStackTraceString(e))
                }.trimEnd()
                lastError = msg
                showCopyableDialog("Python LSP 异常", msg)
            }
        }
    }

    private fun showActionsDialog() {
        val options = arrayOf(
            "显示能力",
            "显示最近异常/日志",
            "Hover（光标）",
            "SignatureHelp（光标）",
            "Definition（光标）",
            "跳到定义（同文件）",
            "References（光标）",
            "Document Symbols",
            "Workspace Symbols（query=add）",
            "Code Actions（光标）",
            "Formatting（仅请求，不改文本）",
            "插入语法错误并等待诊断",
            "清除语法错误并等待诊断清空",
            "检查全部（复制报告）"
        )
        AlertDialog.Builder(this)
            .setTitle("Python LSP 检查")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> runCheckCapabilities()
                    1 -> runShowLastError()
                    2 -> runCheckHover()
                    3 -> runCheckSignatureHelp()
                    4 -> runCheckDefinition()
                    5 -> runJumpToDefinition()
                    6 -> runCheckReferences()
                    7 -> runCheckDocumentSymbols()
                    8 -> runCheckWorkspaceSymbols()
                    9 -> runCheckCodeActions()
                    10 -> runCheckFormatting()
                    11 -> runCheckDiagnostics(insertError = true)
                    12 -> runCheckDiagnostics(insertError = false)
                    13 -> runCheckAll()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runShowLastError() {
        showCopyableDialog(
            "最近异常/日志",
            (lastError ?: "（空）").trimEnd()
        )
    }

    private fun requireConnectedEditor(): LspEditor? {
        val e = lspEditor
        if (e == null || !e.isConnected) {
            toast("未连接 LSP")
            return null
        }
        return e
    }

    private fun currentLspPosition(): Position {
        val cursor = editor.cursor
        return Position(cursor.leftLine, cursor.leftColumn)
    }

    private fun currentTextDocumentIdentifier(e: LspEditor): TextDocumentIdentifier {
        return e.uri.createTextDocumentIdentifier()
    }

    private fun runCheckCapabilities() {
        val e = requireConnectedEditor() ?: return
        val caps = lastInitializeResult?.capabilities
        val msg = buildString {
            appendLine("server=${lastConnectedHost}:${lastConnectedPort}")
            appendLine("file=${e.uri.path}")
            appendLine("connected=${e.isConnected}")
            appendLine()
            appendLine("capabilities:")
            appendLine(caps?.toString() ?: "null")
        }.trimEnd()
        showCopyableDialog("Python LSP 能力", msg)
    }

    private fun runCheckHover() {
        val e = requireConnectedEditor() ?: return
        lifecycleScope.launch {
            val pos = currentLspPosition()
            val td = currentTextDocumentIdentifier(e)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val future = e.requestManager?.hover(HoverParams(td, pos))
                    future?.get(3, TimeUnit.SECONDS)
                }
            }
            showCopyableDialog(
                "Hover 结果",
                buildString {
                    appendLine("pos=$pos")
                    appendLine(result.getOrNull()?.contents?.toString() ?: "null")
                    result.exceptionOrNull()?.let {
                        appendLine()
                        appendLine(Log.getStackTraceString(it))
                    }
                }.trimEnd()
            )
        }
    }

    private fun runCheckSignatureHelp() {
        val e = requireConnectedEditor() ?: return
        lifecycleScope.launch {
            val pos = currentLspPosition()
            val td = currentTextDocumentIdentifier(e)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val future = e.requestManager?.signatureHelp(SignatureHelpParams(td, pos))
                    future?.get(3, TimeUnit.SECONDS)
                }
            }
            showCopyableDialog(
                "SignatureHelp 结果",
                buildString {
                    appendLine("pos=$pos")
                    appendLine(result.getOrNull()?.toString() ?: "null")
                    result.exceptionOrNull()?.let {
                        appendLine()
                        appendLine(Log.getStackTraceString(it))
                    }
                }.trimEnd()
            )
        }
    }

    private fun runCheckDefinition() {
        val e = requireConnectedEditor() ?: return
        lifecycleScope.launch {
            val pos = currentLspPosition()
            val td = currentTextDocumentIdentifier(e)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val future = e.requestManager?.definition(DefinitionParams(td, pos))
                    future?.get(5, TimeUnit.SECONDS)
                }
            }
            showCopyableDialog(
                "Definition 结果",
                buildString {
                    appendLine("pos=$pos")
                    val either = result.getOrNull()
                    if (either == null) {
                        appendLine("null")
                    } else if (either.isLeft) {
                        val locations = either.left ?: emptyList()
                        appendLine("locations=${locations.size}")
                        locations.firstOrNull()?.let { appendLine(it.toString()) }
                    } else {
                        val links = either.right ?: emptyList()
                        appendLine("locationLinks=${links.size}")
                        links.firstOrNull()?.let { appendLine(it.toString()) }
                    }
                    result.exceptionOrNull()?.let {
                        appendLine()
                        appendLine(Log.getStackTraceString(it))
                    }
                }.trimEnd()
            )
        }
    }

    private fun runJumpToDefinition() {
        val e = requireConnectedEditor() ?: return
        lifecycleScope.launch {
            val pos = currentLspPosition()
            val td = currentTextDocumentIdentifier(e)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val future = e.requestManager?.definition(DefinitionParams(td, pos))
                    future?.get(5, TimeUnit.SECONDS)
                }
            }
            val either = result.getOrNull()
            val location: Location? = when {
                either == null -> null
                either.isLeft -> either.left?.firstOrNull()
                else -> either.right?.firstOrNull()?.targetSelectionRange?.let { range ->
                    Location().apply {
                        uri = either.right?.firstOrNull()?.targetUri
                        this.range = range
                    }
                }
            }
            if (location == null || location.uri.isNullOrBlank()) {
                showCopyableDialog(
                    "跳到定义",
                    buildString {
                        appendLine("pos=$pos")
                        appendLine("definition=null")
                        result.exceptionOrNull()?.let { appendLine(Log.getStackTraceString(it)) }
                    }.trimEnd()
                )
                return@launch
            }
            val currentUri = e.uri.toFileUri()
            if (location.uri != currentUri) {
                showCopyableDialog(
                    "跳到定义",
                    buildString {
                        appendLine("当前文件=$currentUri")
                        appendLine("目标文件=${location.uri}")
                        appendLine()
                        appendLine("暂不自动打开跨文件跳转（仅校验返回值）")
                    }.trimEnd()
                )
                return@launch
            }
            val range = location.range
            if (range == null) {
                toast("definition range=null")
                return@launch
            }
            editor.setSelection(range.start.line, range.start.character)
            toast("已跳转到定义：${range.start.line}:${range.start.character}")
        }
    }

    private fun runCheckReferences() {
        val e = requireConnectedEditor() ?: return
        lifecycleScope.launch {
            val pos = currentLspPosition()
            val td = currentTextDocumentIdentifier(e)
            val params = ReferenceParams(td, pos, ReferenceContext(true))
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val future = e.requestManager?.references(params)
                    future?.get(5, TimeUnit.SECONDS)
                }
            }
            showCopyableDialog(
                "References 结果",
                buildString {
                    appendLine("pos=$pos")
                    val refs = result.getOrNull()
                    appendLine("count=${refs?.size ?: 0}")
                    refs?.firstOrNull()?.let { appendLine(it.toString()) }
                    result.exceptionOrNull()?.let {
                        appendLine()
                        appendLine(Log.getStackTraceString(it))
                    }
                }.trimEnd()
            )
        }
    }

    private fun runCheckDocumentSymbols() {
        val e = requireConnectedEditor() ?: return
        lifecycleScope.launch {
            val td = currentTextDocumentIdentifier(e)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val future = e.requestManager?.documentSymbol(DocumentSymbolParams(td))
                    future?.get(5, TimeUnit.SECONDS)
                }
            }
            showCopyableDialog(
                "Document Symbols 结果",
                buildString {
                    val list = result.getOrNull()
                    appendLine("count=${list?.size ?: 0}")
                    list?.firstOrNull()?.let { appendLine(it.toString()) }
                    result.exceptionOrNull()?.let {
                        appendLine()
                        appendLine(Log.getStackTraceString(it))
                    }
                }.trimEnd()
            )
        }
    }

    private fun runCheckWorkspaceSymbols() {
        val e = requireConnectedEditor() ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val future = e.requestManager?.symbol(WorkspaceSymbolParams("add"))
                    future?.get(5, TimeUnit.SECONDS)
                }
            }
            showCopyableDialog(
                "Workspace Symbols 结果",
                buildString {
                    val data = result.getOrNull()
                    val list = data as? List<*>
                    appendLine("count=${list?.size ?: 0}")
                    list?.firstOrNull()?.let { appendLine(it.toString()) } ?: run {
                        if (data != null && list == null) {
                            appendLine(data.toString())
                        }
                    }
                    result.exceptionOrNull()?.let {
                        appendLine()
                        appendLine(Log.getStackTraceString(it))
                    }
                }.trimEnd()
            )
        }
    }

    private fun runCheckCodeActions() {
        val e = requireConnectedEditor() ?: return
        lifecycleScope.launch {
            val td = currentTextDocumentIdentifier(e)
            val cursor = editor.cursor
            val range = cursor.range.asLspRange()
            val params = CodeActionParams(td, range, CodeActionContext(emptyList()))
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val future = e.requestManager?.codeAction(params)
                    future?.get(5, TimeUnit.SECONDS)
                }
            }
            showCopyableDialog(
                "Code Actions 结果",
                buildString {
                    appendLine("range=$range")
                    val list = result.getOrNull()
                    appendLine("count=${list?.size ?: 0}")
                    list?.firstOrNull()?.let { appendLine(it.toString()) }
                    result.exceptionOrNull()?.let {
                        appendLine()
                        appendLine(Log.getStackTraceString(it))
                    }
                }.trimEnd()
            )
        }
    }

    private fun runCheckFormatting() {
        val e = requireConnectedEditor() ?: return
        lifecycleScope.launch {
            val td = currentTextDocumentIdentifier(e)
            val options = FormattingOptions().apply {
                tabSize = 4
                isInsertSpaces = true
            }
            val params = DocumentFormattingParams(td, options)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val future = e.requestManager?.formatting(params)
                    future?.get(10, TimeUnit.SECONDS)
                }
            }
            showCopyableDialog(
                "Formatting 结果",
                buildString {
                    val edits = result.getOrNull()
                    appendLine("edits=${edits?.size ?: 0}")
                    edits?.firstOrNull()?.let { appendLine(it.toString()) }
                    if (edits == null) {
                        appendLine()
                        appendLine("提示：pylsp 返回 null 通常表示未安装格式化插件（autopep8/yapf/black 等）或无可用 edits")
                    }
                    result.exceptionOrNull()?.let {
                        appendLine()
                        appendLine(Log.getStackTraceString(it))
                    }
                }.trimEnd()
            )
        }
    }

    private fun runCheckDiagnostics(insertError: Boolean) {
        val e = requireConnectedEditor() ?: return
        lifecycleScope.launch {
            if (insertError) {
                editor.setText("def broken(:\n    pass\n")
            } else {
                editor.setText(
                    """
                    def add(a: int, b: int) -> int:
                        return a + b
                    
                    
                    value = add(1, 2)
                    print(value)
                    """.trimIndent() + "\n"
                )
            }
            val ok: Boolean = withContext(Dispatchers.IO) {
                runCatching {
                    withTimeout(6_000) {
                        while (true) {
                            val has = editor.diagnostics != null
                            val matched = (insertError && has) || (!insertError && !has)
                            if (matched) break
                            kotlinx.coroutines.delay(150)
                        }
                        true
                    }
                }.getOrDefault(false)
            }
            showCopyableDialog(
                "Diagnostics 结果",
                buildString {
                    appendLine("insertError=$insertError")
                    appendLine("hasDiagnostics=${editor.diagnostics != null}")
                    appendLine("waitOk=$ok")
                    if (!ok) {
                        appendLine()
                        appendLine("提示：当前 pylsp capabilities 里 diagnosticProvider=null（不支持 pull diagnostics），需要 publishDiagnostics 推送 + 对应 lint 插件")
                    }
                }.trimEnd()
            )
        }
    }

    private fun runCheckAll() {
        val e = requireConnectedEditor() ?: return
        lifecycleScope.launch {
            val td = currentTextDocumentIdentifier(e)
            val pos = currentLspPosition()
            val cursorRange = editor.cursor.range.asLspRange()
            val report = withContext(Dispatchers.IO) {
                buildString {
                    appendLine("server=${lastConnectedHost}:${lastConnectedPort}")
                    appendLine("file=${e.uri.path}")
                    appendLine("pos=$pos")
                    appendLine("range=$cursorRange")
                    appendLine()
                    val caps = lastInitializeResult?.capabilities
                    appendLine("capabilities:")
                    appendLine(caps?.toString() ?: "null")
                    appendLine()
                    fun <T> run(name: String, block: () -> T?): String {
                        return runCatching { block() }
                            .fold(
                                onSuccess = { "$name: OK ${it?.toString() ?: "null"}" },
                                onFailure = { "$name: FAIL ${it::class.java.simpleName}: ${it.message}" }
                            )
                    }
                    fun summarizeList(value: Any?): String {
                        val list = value as? Collection<*>
                        return if (list != null) {
                            val first = list.firstOrNull()
                            "count=${list.size} first=${first?.toString() ?: "null"}"
                        } else {
                            value?.toString() ?: "null"
                        }
                    }
                    val rm = e.requestManager
                    appendLine(run("hover") { rm?.hover(HoverParams(td, pos))?.get(3, TimeUnit.SECONDS)?.contents?.toString() })
                    appendLine(run("signatureHelp") { rm?.signatureHelp(SignatureHelpParams(td, pos))?.get(3, TimeUnit.SECONDS)?.toString() })
                    appendLine(run("definition") { rm?.definition(DefinitionParams(td, pos))?.get(5, TimeUnit.SECONDS)?.toString() })
                    appendLine(run("references") { summarizeList(rm?.references(ReferenceParams(td, pos, ReferenceContext(true)))?.get(5, TimeUnit.SECONDS)) })
                    appendLine(run("documentSymbol") { summarizeList(rm?.documentSymbol(DocumentSymbolParams(td))?.get(5, TimeUnit.SECONDS)) })
                    appendLine(run("workspaceSymbol") { summarizeList(rm?.symbol(WorkspaceSymbolParams("add"))?.get(5, TimeUnit.SECONDS)) })
                    appendLine(run("codeAction") { summarizeList(rm?.codeAction(CodeActionParams(td, cursorRange, CodeActionContext(emptyList())))?.get(5, TimeUnit.SECONDS)) })
                    appendLine(run("formatting") { summarizeList(rm?.formatting(DocumentFormattingParams(td, FormattingOptions().apply { tabSize = 4; isInsertSpaces = true }))?.get(10, TimeUnit.SECONDS)) })
                }.trimEnd()
            }
            showCopyableDialog("Python LSP 检查报告", report)
        }
    }

    private fun showCopyableDialog(title: String, message: String) {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = message
            setTextIsSelectable(true)
            setPadding(padding, padding, padding, padding)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(tv)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(title, message))
                toast("已复制到剪贴板")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun createTextMateLanguage(): TextMateLanguage {
        GrammarRegistry.getInstance().loadGrammars(
            languages {
                language("python") {
                    grammar = "textmate/python/syntaxes/python.tmLanguage.json"
                    scopeName = "source.python"
                    languageConfiguration = "textmate/python/language-configuration.json"
                }
            }
        )
        return TextMateLanguage.create("source.python", false)
    }

    private fun ensureTextmateTheme() {
        val editorColorScheme = editor.colorScheme
        if (editorColorScheme is TextMateColorScheme) {
            return
        }

        FileProviderRegistry.getInstance().addFileProvider(
            AssetsFileResolver(assets)
        )

        val themeRegistry = ThemeRegistry.getInstance()
        val path = "textmate/ayu-dark.json"
        themeRegistry.loadTheme(
            ThemeModel(
                IThemeSource.fromInputStream(
                    FileProviderRegistry.getInstance().tryGetInputStream(path),
                    path,
                    null
                ),
                "ayu-dark"
            )
        )
        themeRegistry.setTheme("ayu-dark")
        editor.colorScheme = TextMateColorScheme.create(themeRegistry)
    }

    private class PythonLspEventListener(
        private val activity: PythonLspTestActivity
    ) : EventHandler.EventListener {
        override fun initialize(server: LanguageServer?, result: InitializeResult) {
            activity.lastInitializeResult = result
            val enabled = result.capabilities.documentFormattingProvider != null
            activity.formattingEnabled = enabled
            activity.runOnUiThread {
                activity.rootMenu?.findItem(R.id.code_format)?.isEnabled = enabled
            }
        }

        override fun onHandlerException(exception: Exception) {
            val msg = Log.getStackTraceString(exception)
            activity.lastError = msg
            activity.runOnUiThread {
                activity.showCopyableDialog("Python LSP Handler 异常", msg)
            }
        }

        override fun onShowMessage(messageParams: MessageParams?) {
            val text = messageParams?.message ?: return
            activity.runOnUiThread {
                activity.toast(text)
            }
        }

        override fun onLogMessage(messageParams: MessageParams?) {
            val text = messageParams?.message ?: return
            activity.lastError = (activity.lastError?.let { "$it\n\n" } ?: "") + text
        }
    }

    private object NetworkUtils {
        fun getLocalIpv4Addresses(): List<String> {
            val result = ArrayList<String>()
            runCatching {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr.isLoopbackAddress) continue
                        val host = addr.hostAddress ?: continue
                        if (!host.contains('.')) continue
                        if (host.startsWith("169.254.")) continue
                        result.add(host)
                    }
                }
            }
            return result.distinct()
        }

        fun getScanPrefixes(localIps: List<String>): List<String> {
            val prefixes = LinkedHashSet<String>()
            localIps.forEach { ip ->
                val idx = ip.lastIndexOf('.')
                if (idx > 0) {
                    prefixes.add(ip.substring(0, idx))
                }
            }
            if (prefixes.isEmpty()) {
                prefixes.addAll(
                    listOf(
                        "192.168.43",
                        "192.168.155",
                        "192.168.1",
                        "192.168.0",
                        "10.0.0",
                        "10.0.2"
                    )
                )
            }
            return prefixes.toList().sortedWith(
                compareBy<String> {
                    when {
                        it.startsWith("192.168.") -> 0
                        it.startsWith("10.") -> 1
                        it.startsWith("172.") -> 2
                        else -> 3
                    }
                }.thenBy { it }
            )
        }

        fun canConnect(host: String, port: Int, timeoutMs: Int): Boolean {
            return runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                }
                true
            }.getOrDefault(false)
        }
    }
}
