package com.termux.ui.panel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SystemDashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SystemDashboardScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemDashboardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("环境", "进程", "端口", "镜像")

    var busy by remember { mutableStateOf(false) }
    var busyTitle by remember { mutableStateOf<String?>(null) }
    var progressLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var resultHint by remember { mutableStateOf<UiHint?>(null) }

    fun launchSelfTest() {
        if (busy) return
        busy = true
        val label = "一键自检"
        busyTitle = label
        progressLines = emptyList()
        scope.launch {
            val lines = ArrayList<String>(512)
            val result = runCatching {
                PanelSelfTest.run(
                    context = context,
                    onLine = { line ->
                        lines.add(line)
                        scope.launch {
                            progressLines = (progressLines + line).takeLast(200)
                        }
                    }
                )
            }.getOrElse { t ->
                PanelSelfTest.SelfTestResult(
                    ok = false,
                    title = "自检异常",
                    detail = t.stackTraceToString()
                )
            }
            resultHint = UiHint(
                title = result.title,
                detail = lines.joinToString("\n").ifBlank { result.detail },
                suggestion = if (result.ok) null else "把这段自检输出直接复制给我，我能定位到具体异常点"
            )
            busy = false
            busyTitle = null
            progressLines = emptyList()
        }
    }

    fun showCmdResult(label: String, r: TermuxCmdResult) {
        resultHint = if (r.ok) {
            UiHint(
                title = "成功：$label",
                detail = EnvironmentManager.mapResultToHint(label, r).detail,
                suggestion = null
            )
        } else {
            EnvironmentManager.mapResultToHint("失败：$label", r)
        }
    }

    fun launchCommandWithLiveLog(title: String, cmd: String) {
        if (busy) return
        busy = true
        val actualCmd = cmd
        val label = title
        busyTitle = label
        progressLines = emptyList()
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    TermuxCommandRunner.runBashWithLineProgress(
                        context = context,
                        bashCommand = actualCmd,
                        onLine = { line ->
                            scope.launch {
                                progressLines = (progressLines + line).takeLast(160)
                            }
                        }
                    )
                }
            }.getOrElse { t ->
                TermuxCmdResult(
                    command = actualCmd,
                    workingDirectory = TermuxCommandRunner.defaultWorkDir(),
                    exitCode = 1,
                    stdout = "",
                    stderr = t.stackTraceToString()
                )
            }
            showCmdResult(label, r)
            busy = false
            busyTitle = null
            progressLines = emptyList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Termux 管理面板") },
                actions = {
                    TextButton(onClick = { launchSelfTest() }, enabled = !busy) {
                        Text("自检")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { idx, label ->
                    Tab(
                        selected = tab == idx,
                        onClick = { tab = idx },
                        text = { Text(label) }
                    )
                }
            }
            when (tab) {
                0 -> EnvironmentTab(
                    modifier = Modifier.fillMaxSize(),
                    busy = busy,
                    onRunLive = { title, cmd -> launchCommandWithLiveLog(title, cmd) },
                    onShowHint = { hint -> resultHint = hint }
                )
                1 -> ProcessTab(
                    modifier = Modifier.fillMaxSize(),
                    busy = busy,
                    onRun = { title, cmd -> launchCommandWithLiveLog(title, cmd) }
                )
                2 -> PortsTab(
                    modifier = Modifier.fillMaxSize(),
                    busy = busy,
                    onRun = { title, cmd -> launchCommandWithLiveLog(title, cmd) }
                )
                3 -> MirrorTab(
                    modifier = Modifier.fillMaxSize(),
                    onOpenTerminal = {
                        openNativeTermuxTerminal(context)
                    }
                )
            }
        }
    }

    if (busy && busyTitle != null) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(busyTitle ?: "") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text("执行中…")
                    }
                    if (progressLines.isNotEmpty()) {
                        Divider(Modifier.padding(vertical = 12.dp))
                        SelectionContainer {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                for (line in progressLines) {
                                    Text(line, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    if (resultHint != null) {
        AlertDialog(
            onDismissRequest = { resultHint = null },
            confirmButton = {
                Button(onClick = { resultHint = null }) { Text("关闭") }
            },
            title = { Text(resultHint?.title ?: "执行结果") },
            text = {
                SelectionContainer {
                    val hint = resultHint
                    Text(
                        buildString {
                            if (hint?.suggestion != null) {
                                appendLine("建议：")
                                appendLine(hint.suggestion.trim())
                                appendLine()
                            }
                            appendLine(hint?.detail.orEmpty())
                        }.trimEnd()
                    )
                }
            }
        )
    }
}

@Composable
private fun EnvironmentTab(
    modifier: Modifier,
    busy: Boolean,
    onRunLive: (title: String, cmd: String) -> Unit,
    onShowHint: (UiHint) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshots by remember { mutableStateOf<List<ToolSnapshot>>(emptyList()) }
    var lastRefreshAt by remember { mutableStateOf<Long?>(null) }

    fun refresh() {
        if (busy) return
        scope.launch {
            val list = EnvironmentManager.tools.map { EnvironmentManager.detectTool(context, ExecTarget.Host, it) }
            snapshots = list
            lastRefreshAt = System.currentTimeMillis()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("环境", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (lastRefreshAt == null) "未刷新" else "已刷新：$lastRefreshAt",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(onClick = { refresh() }, enabled = !busy) { Text("刷新") }
        }

        Divider()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(snapshots) { snap ->
                val tool = snap.tool
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(tool.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        snap.detectedVersion ?: "未安装",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (snap.detectedPath != null) {
                        Text(
                            "来源：${snap.detectedSource ?: "-"}  路径：${snap.detectedPath}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (snap.message != null) {
                        Text(snap.message, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer12()

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val installCmd = EnvironmentManager.pkgInstallCmd(tool)
                        if (installCmd.isNotBlank()) {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    onRunLive("pkg 安装/更新：${tool.title}", installCmd)
                                }
                            ) { Text("pkg 安装") }
                        }
                        Button(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    val cmd = """
                                        echo "which:"
                                        command -v ${tool.binary} 2>/dev/null || true
                                        echo
                                        echo "path:"
                                        if command -v ${tool.binary} >/dev/null 2>&1; then
                                          p="${'$'}(command -v ${tool.binary})"
                                          readlink -f "${'$'}p" 2>/dev/null || echo "${'$'}p"
                                        fi
                                        echo
                                        echo "version:"
                                        if command -v ${tool.binary} >/dev/null 2>&1; then
                                          if [ "${tool.id}" = "java" ]; then
                                            java -version 2>&1 | head -n 40
                                          else
                                            ${tool.binary} ${tool.versionArgs} 2>&1 | head -n 40
                                          fi
                                        fi
                                    """.trimIndent()
                                    val r = withContext(Dispatchers.IO) {
                                        TermuxCommandRunner.runBash(context, cmd)
                                    }
                                    onShowHint(
                                        if (r.ok) {
                                            UiHint("详情：${tool.title}", EnvironmentManager.mapResultToHint(tool.title, r).detail, null)
                                        } else {
                                            EnvironmentManager.mapResultToHint("详情失败：${tool.title}", r)
                                        }
                                    )
                                }
                            }
                        ) { Text("详情") }
                    }
                }
                Divider()
            }
        }
    }
}

@Composable
private fun ProcessTab(
    modifier: Modifier,
    busy: Boolean,
    onRun: (title: String, cmd: String) -> Unit
) {
    Column(modifier.padding(12.dp)) {
        Text("进程列表", style = MaterialTheme.typography.titleMedium)
        Text("提示：默认只展示前 200 行", style = MaterialTheme.typography.bodySmall)
        Spacer12()
        Button(
            onClick = { onRun("刷新进程", "ps -A -o pid,user,args | head -n 200") },
            enabled = !busy
        ) { Text("刷新") }
        Spacer12()
        Button(
            onClick = {
                onRun("安装工具：procps", "pkg install -y procps")
            },
            enabled = !busy
        ) { Text("安装 ps 增强工具") }
        Spacer12()
        Text("更多操作（kill/top 等）建议在终端执行。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PortsTab(
    modifier: Modifier,
    busy: Boolean,
    onRun: (title: String, cmd: String) -> Unit
) {
    Column(modifier.padding(12.dp)) {
        Text("端口监听", style = MaterialTheme.typography.titleMedium)
        Text("优先 ss，其次 netstat", style = MaterialTheme.typography.bodySmall)
        Spacer12()
        Button(
            onClick = {
                onRun(
                    "刷新端口",
                    "command -v ss >/dev/null 2>&1 && ss -lntup || (command -v netstat >/dev/null 2>&1 && netstat -lntup) || echo '未找到 ss/netstat，建议安装 iproute2 或 net-tools'"
                )
            },
            enabled = !busy
        ) { Text("刷新") }
        Spacer12()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    onRun("安装 ss(iproute2)", "pkg install -y iproute2")
                },
                enabled = !busy
            ) { Text("安装 ss") }
            Button(
                onClick = {
                    onRun("安装 netstat(net-tools)", "pkg install -y net-tools")
                },
                enabled = !busy
            ) { Text("安装 netstat") }
        }
    }
}

@Composable
private fun MirrorTab(
    modifier: Modifier,
    onOpenTerminal: () -> Unit
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("镜像源管理", style = MaterialTheme.typography.titleMedium)
            Text(
                "镜像切换涉及交互式选择，建议在终端运行 termux-change-repo。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer12()
            Button(onClick = { onOpenTerminal() }) {
                Text("打开终端执行 termux-change-repo")
            }
        }
    }
}

@Composable
private fun Spacer12() {
    Spacer(modifier = Modifier.height(12.dp))
}

private fun openNativeTermuxTerminal(context: Context) {
    val packageName = context.packageName
    val activityClassName = "$packageName.app.TermuxActivity"
    val intent = Intent().apply {
        setClassName(packageName, activityClassName)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
