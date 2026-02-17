package io.github.rosemoe.sora.app

import android.os.Bundle
import android.util.Log
import androidx.lifecycle.lifecycleScope
import io.github.rosemoe.sora.editor.CodeEditor
import io.github.rosemoe.sora.editor.event.ContentChangeEvent
import io.github.rosemoe.sora.editor.event.EditorCreateEvent
import io.github.rosemoe.sora.editor.event.EditorFocusEvent
import io.github.rosemoe.sora.editor.event.EditorReleaseEvent
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.lsp.editor.event.LspEditorCreateEvent
import io.github.rosemoe.sora.lsp.editor.event.LspEditorReleaseEvent
import io.github.rosemoe.sora.lsp.event.LanguageServerWrapper
import io.github.rosemoe.sora.lsp.server.LanguageServerDefinition
import io.github.rosemoe.sora.lsp.server.CustomLanguageServerDefinition
import io.github.rosemoe.sora.lsp.server.SocketStreamConnectionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PythonLspTestActivity : LspTestActivity() {
    
    companion object {
        private const val TAG = "PythonLspTest"
        private const val PYTHON_SERVER_HOST = "127.0.0.1"
        private const val PYTHON_SERVER_PORT = 4389
    }
    
    private var lspWrapper: LanguageServerWrapper? = null
    private var lspEditor: LspEditor? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "Creating Python LSP Test Activity")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                setupLspServer()
                Log.i(TAG, "Python LSP Server setup completed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup Python LSP Server", e)
                withContext(Dispatchers.Main) {
                    showError("Failed to connect to Python LSP Server: ${e.message}")
                }
            }
        }
    }
    
    private suspend fun setupLspServer() {
        val serverDefinition = CustomLanguageServerDefinition(
            languageId = "python",
            languageName = "Python",
            extension = listOf("py", "pyw"),
            connectionProvider = SocketStreamConnectionProvider(PYTHON_SERVER_HOST, PYTHON_SERVER_PORT)
        )
        
        lspWrapper = LanguageServerWrapper(serverDefinition, lifecycleScope)
        
        lspWrapper?.start()?.let { capabilities ->
            Log.i(TAG, "LSP Server capabilities received")
            Log.d(TAG, "Text Document Sync: ${capabilities.textDocumentSync}")
            Log.d(TAG, "Completion Provider: ${capabilities.completionProvider}")
            Log.d(TAG, "Hover Provider: ${capabilities.hoverProvider}")
            Log.d(TAG, "Definition Provider: ${capabilities.definitionProvider}")
            Log.d(TAG, "References Provider: ${capabilities.referencesProvider}")
            Log.d(TAG, "Document Symbol Provider: ${capabilities.documentSymbolProvider}")
            Log.d(TAG, "Workspace Symbol Provider: ${capabilities.workspaceSymbolProvider}")
            Log.d(TAG, "Code Action Provider: ${capabilities.codeActionProvider}")
            
            withContext(Dispatchers.Main) {
                showCapabilities(capabilities)
            }
        }
    }
    
    private fun showCapabilities(capabilities: Any) {
        val message = """
            Python LSP Server Connected!
            
            Capabilities:
            - Text Document Sync: Supported
            - Completion: ${if (hasCapability(capabilities, "completionProvider")) "✓" else "✗"}
            - Hover: ${if (hasCapability(capabilities, "hoverProvider")) "✓" else "✗"}
            - Definition: ${if (hasCapability(capabilities, "definitionProvider")) "✓" else "✗"}
            - References: ${if (hasCapability(capabilities, "referencesProvider")) "✓" else "✗"}
            - Document Symbols: ${if (hasCapability(capabilities, "documentSymbolProvider")) "✓" else "✗"}
            - Workspace Symbols: ${if (hasCapability(capabilities, "workspaceSymbolProvider")) "✓" else "✗"}
            - Code Actions: ${if (hasCapability(capabilities, "codeActionProvider")) "✓" else "✗"}
        """.trimIndent()
        
        showMessage(message)
    }
    
    private fun hasCapability(capabilities: Any, name: String): Boolean {
        try {
            val field = capabilities.javaClass.getDeclaredField(name)
            field.isAccessible = true
            val value = field.get(capabilities)
            return value != null
        } catch (e: Exception) {
            return false
        }
    }
    
    override fun onEditorCreate(event: EditorCreateEvent) {
        super.onEditorCreate(event)
        Log.i(TAG, "Editor created: ${event.editor.uri}")
        
        if (event is LspEditorCreateEvent) {
            lspEditor = event.editor
            Log.i(TAG, "LSP Editor attached")
        }
    }
    
    override fun onEditorRelease(event: EditorReleaseEvent) {
        super.onEditorRelease(event)
        Log.i(TAG, "Editor released")
        
        if (event is LspEditorReleaseEvent) {
            lspEditor = null
        }
    }
    
    override fun onContentChange(event: ContentChangeEvent) {
        super.onContentChange(event)
        Log.d(TAG, "Content changed in ${event.editor.uri}")
    }
    
    override fun onEditorFocus(event: EditorFocusEvent) {
        super.onEditorFocus(event)
        Log.d(TAG, "Editor focus changed: ${event.hasFocus}")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                lspWrapper?.stop()
                Log.i(TAG, "LSP Server stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop LSP Server", e)
            }
        }
    }
    
    private fun showMessage(message: String) {
    }
    
    private fun showError(message: String) {
        Log.e(TAG, message)
    }
}
