package com.termux.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.AnnotatedString
import com.termux.bridge.FileOpenBridge
import com.termux.ui.files.FilesBrowserPage
import com.termux.ui.files.VsCodeFileIconTheme
import com.termux.ui.panel.SystemDashboardActivity
import com.termux.ui.ide.IdeProject
import com.termux.ui.ide.IdeProjectPreflight
import com.termux.ui.ide.IdeProjectStore
import com.termux.ui.ide.IdeTemplate
import com.termux.ui.nav.UiShellNavBridge
import com.termux.ui.selftest.FullChainSelfTest
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TermuxUiApp() {
    Surface(color = MaterialTheme.colorScheme.background) {
        TermuxUiScaffold()
    }
}

@Composable
private fun TermuxUiScaffold() {
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val stateHolder = rememberSaveableStateHolder()
    val items = listOf(
        BottomItem("文件", Icons.Filled.Description),
        BottomItem("终端", Icons.Filled.Code)
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selectedIndex == index,
                        onClick = {
                            selectedIndex = index
                        },
                        icon = { androidx.compose.material3.Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        when (selectedIndex) {
            0 -> stateHolder.SaveableStateProvider("tab_files") {
                FilesPage(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    requestedDirPath = null
                )
            }
            else -> stateHolder.SaveableStateProvider("tab_terminal") {
                TerminalPage(Modifier.fillMaxSize().padding(padding))
            }
        }
    }
}

@Composable
private fun FilesPage(
    modifier: Modifier = Modifier,
    requestedDirPath: String?
) {
    FilesBrowserPage(
        modifier = modifier,
        requestedDirPath = requestedDirPath,
        onOpenFile = { request ->
            FileOpenBridge.dispatch(request)
        }
    )
}

@Composable
private fun TerminalPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var terminalLaunchError by rememberSaveable { mutableStateOf<String?>(null) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (terminalLaunchError == null) {
            Button(
                onClick = {
                    terminalLaunchError = runCatching { openNativeTermuxTerminal(context) }
                        .exceptionOrNull()
                        ?.message
                }
            ) {
                Text("打开原生终端")
            }
        } else {
            Text("打开失败：$terminalLaunchError")
        }
    }
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

private data class BottomItem(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

object UiShellEmbed {
    @JvmStatic
    fun attachFilesPage(container: ViewGroup) {
        attach(container) { FilesPage(Modifier.fillMaxSize(), requestedDirPath = null) }
    }

    private fun attach(container: ViewGroup, content: @Composable () -> Unit) {
        container.removeAllViews()
        val composeView = ComposeView(container.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                Surface(color = MaterialTheme.colorScheme.background) {
                    content()
                }
            }
        }
        container.addView(
            composeView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }
}
