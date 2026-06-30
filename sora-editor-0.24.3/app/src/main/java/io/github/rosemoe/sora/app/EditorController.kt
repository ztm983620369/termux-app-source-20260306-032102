/*******************************************************************************
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2024  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 ******************************************************************************/
package io.github.rosemoe.sora.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.termux.bridge.FileEditorContract
import com.termux.bridge.FileOpenEvent
import com.termux.bridge.FileOpenBridge
import com.termux.bridge.FileOpenListener
import com.termux.bridge.FileOpenRequest
import com.termux.bridge.RecentFileHistory
import com.termux.editorsync.EditorDocumentSyncManager
import com.termux.editorsync.EditorSaveTrigger
import com.termux.editorsync.EditorSyncTarget
import com.termux.editorsync.EditorSyncTargetKind
import com.termux.sessionsync.SessionFileCoordinator
import com.termux.shared.view.KeyboardUtils
import io.github.rosemoe.sora.app.databinding.ActivityMainBinding
import io.github.rosemoe.sora.app.lsp.LspTestActivity
import io.github.rosemoe.sora.app.lsp.LspTestJavaActivity
import io.github.rosemoe.sora.app.lsp.PythonLspTestActivity
import io.github.rosemoe.sora.app.tests.TestActivity
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorKeyEvent
import io.github.rosemoe.sora.event.KeyBindingEvent
import io.github.rosemoe.sora.event.SideIconClickEvent
import io.github.rosemoe.sora.event.TextSizeChangeEvent
import io.github.rosemoe.sora.graphics.inlayHint.ColorInlayHintRenderer
import io.github.rosemoe.sora.graphics.inlayHint.TextInlayHintRenderer
import io.github.rosemoe.sora.lang.styling.color.ConstColor
import io.github.rosemoe.sora.lang.styling.inlayHint.ColorInlayHint
import io.github.rosemoe.sora.lang.styling.inlayHint.InlayHintsContainer
import io.github.rosemoe.sora.lang.styling.inlayHint.TextInlayHint
import io.github.rosemoe.sora.langs.java.JavaLanguage as SoraJavaLanguage
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentIO
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import io.github.rosemoe.sora.utils.CrashHandler
import io.github.rosemoe.sora.utils.toast
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions
import io.github.rosemoe.sora.widget.SelectionMovement
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.component.Magnifier
import io.github.rosemoe.sora.widget.ext.EditorSpanInteractionHandler
import io.github.rosemoe.sora.widget.getComponent
import io.github.rosemoe.sora.widget.subscribeAlways
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.tm4e.core.internal.oniguruma.Oniguruma
import java.io.File
import java.io.FileInputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.PatternSyntaxException
import kotlin.math.abs
import kotlin.math.max

/**
 * Demo and debug Activity for the code editor
 */
class EditorController(
    private val activity: AppCompatActivity,
    private val intentProvider: () -> Intent,
    private val loadTMLLauncher: ActivityResultLauncher<String>,
    private val loadTMTLauncher: ActivityResultLauncher<String>,
    private val embeddedTerminalHost: EditorEmbeddedTerminalHost? = null
) {

    companion object {
        init {
            // Load tree-sitter libraries
            System.loadLibrary("android-tree-sitter")
            System.loadLibrary("tree-sitter-java")
        }

        private const val TAG = "EditorController"
        const val LOG_FILE = "crash-journal.log"
        private const val PREFS_NAME = "sora_editor_prefs"
        private const val FILE_RELOAD_DEBOUNCE_MS = 240L
        private val OBSERVED_FILE_EVENTS =
            FileObserver.CLOSE_WRITE or
                FileObserver.MODIFY or
                FileObserver.MOVED_TO or
                FileObserver.MOVED_FROM or
                FileObserver.DELETE or
                FileObserver.ATTRIB

        /**
         * Symbols to be displayed in symbol input view
         */
        val SYMBOLS = arrayOf(
            "->", "{", "}", "(", ")",
            ",", ".", ";", "\"", "?",
            "+", "-", "*", "/", "<",
            ">", "[", "]", ":"
        )

        /**
         * Texts to be committed to editor for symbols above
         */
        val SYMBOL_INSERT_TEXT = arrayOf(
            "\t", "{}", "}", "(", ")",
            ",", ".", ";", "\"", "?",
            "+", "-", "*", "/", "<",
            ">", "[", "]", ":"
        )
    }

    private val binding: ActivityMainBinding = ActivityMainBinding.inflate(activity.layoutInflater)

    val rootView: View
        get() = binding.root

    val embeddedWorkspaceHost: ViewGroup
        get() = binding.editorWorkspaceHost

    private val resources get() = activity.resources
    private val window get() = activity.window
    private val lifecycle get() = activity.lifecycle
    private val lifecycleScope get() = activity.lifecycleScope
    private val menuInflater get() = activity.menuInflater
    private val intent get() = intentProvider()
    private val assets get() = activity.assets
    private val isFinishing get() = activity.isFinishing
    private val isDestroyed get() = activity.isDestroyed
    private val MODE_PRIVATE = Context.MODE_PRIVATE

    private var title: CharSequence?
        get() = activity.title
        set(value) {
            activity.title = value
        }

    private fun openFileInput(name: String) = activity.openFileInput(name)

    private fun openFileOutput(name: String, mode: Int) = activity.openFileOutput(name, mode)

    private fun runOnUiThread(block: () -> Unit) = activity.runOnUiThread(block)

    private fun getSystemService(name: String) = activity.getSystemService(name)

    private fun <T> getSystemService(serviceClass: Class<T>): T? = activity.getSystemService(serviceClass)

    private fun toast(text: String) = activity.toast(text)

    private fun toast(textId: Int) = activity.toast(textId)

    private fun toast(text: String, duration: Int) = activity.toast(text, duration)

    private fun toast(textId: Int, duration: Int) = activity.toast(textId, duration)

    private fun getString(textId: Int) = activity.getString(textId)

    private fun startActivity(intent: Intent) = activity.startActivity(intent)

    private inline fun <reified T : Activity> startActivity() = activity.startActivity<T>()

    private fun sendBroadcast(intent: Intent) = activity.sendBroadcast(intent)

    fun attachTo(container: ViewGroup) {
        if (binding.root.parent != container) {
            (binding.root.parent as? ViewGroup)?.removeView(binding.root)
            container.removeAllViews()
            container.addView(
                binding.root,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
    }
    private lateinit var searchMenu: PopupMenu
    private var searchOptions = SearchOptions(false, false)
    private var undo: MenuItem? = null
    private var redo: MenuItem? = null

    private lateinit var editorEnv: EditorEnvironment
    private lateinit var vscode: VSCodeIntegration
    private lateinit var documentSync: EditorDocumentSyncManager
    private val saveStatusUi = EditorSaveStatusUi()

    private data class FileDiskState(
        val path: String,
        val lastModified: Long,
        val length: Long,
        val sha256: String
    )

    private data class DiskFileContent(
        val content: Content,
        val diskState: FileDiskState
    )

    private data class MaterializedOpenRequest(
        val request: FileOpenRequest,
        val file: File,
        val remoteRefreshed: Boolean
    )

    private data class OpenFileSnapshot(
        val diskState: FileDiskState,
        val editorSha256: String
    )

    private val openFileSnapshotLock = Any()
    private var lastBridgeSeqHandled: Long = 0L
    private var lastOpenRequest: FileOpenRequest? = null
    private var lastOpenAttemptAtMs: Long = 0L
    private var lastOpenOkAtMs: Long = 0L
    private var lastOpenError: String? = null
    private val openGeneration = AtomicLong(0L)
    @Volatile
    private var loadedPath: String? = null
    @Volatile
    private var loadedLastModified: Long = -1L
    @Volatile
    private var loadedSize: Long = -1L
    @Volatile
    private var openFileSnapshot: OpenFileSnapshot? = null
    @Volatile
    private var mutedExternalConflictHash: String? = null
    @Volatile
    private var allowOverwriteExternalHashOnce: String? = null
    @Volatile
    private var observedFilePath: String? = null
    private var observedFileName: String? = null
    private var observedFileParent: String? = null
    private var fileObserver: FileObserver? = null
    private var fileRefreshJob: Job? = null
    private var externalConflictDialog: AlertDialog? = null
    private var titlebarSearchModeActive: Boolean = false
    private var embeddedTerminalImeActive = false
    private var restoreEditorImeAfterTerminal = false

    private val prefs by lazy { activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private var applyToolbarTopInsetFromSystemBars: Boolean = true
    private var suppressContentChangeCallbacks: Boolean = false

    // ---------------- IME accessory bar (FULL REFACTOR) ----------------
    private var barFullHeightPx: Int = 0

    private var imeAnimCount: Int = 0
    private var imeMaxBottomPx: Int = 0
    private var lastImeBottomPx: Int = 0

    private var currentFraction: Float = 0f
    private var settlePosted: Boolean = false

    private var fractionAnimator: ValueAnimator? = null
    private var barHwLayerEnabled: Boolean = false

    // ------------------------------------------------------------------

    fun onCreate(savedInstanceState: Bundle?) {
        CrashHandler.INSTANCE.init(activity)

        activity.setSupportActionBar(binding.activityToolbar)
        syncEmbeddedTerminalWorkspaceUi()
        if (applyToolbarTopInsetFromSystemBars) {
            applyEdgeToEdgeForViews(binding.toolbarContainer, binding.root)
        }

        setupPersistentSymbolBar()
        setupTerminalWorkspaceButton()

        documentSync = EditorDocumentSyncManager(
            context = activity,
            prefs = prefs,
            scope = activity.lifecycleScope,
            textSnapshotProvider = { binding.editor.text.toString() },
            beforeSaveGuard = ::detectExternalSaveConflictBeforePersist,
            afterLocalPersist = ::recordEditorManagedLocalPersist
        )
        lifecycleScope.launch {
            documentSync.state.collect { snapshot ->
                saveStatusUi.render(snapshot)
            }
        }

        val typeface = Typeface.createFromAsset(activity.assets, "JetBrainsMono-Regular.ttf")

        // Setup Listeners
        binding.apply {
            btnGotoPrev.setOnClickListener(::gotoPrev)
            btnGotoNext.setOnClickListener(::gotoNext)
            btnReplace.setOnClickListener(::replace)
            btnReplaceAll.setOnClickListener(::replaceAll)
            titlebarBtnGotoPrev.setOnClickListener(::gotoPrev)
            titlebarBtnGotoNext.setOnClickListener(::gotoNext)
            titlebarSearchOptionsButton.setOnClickListener(::showSearchOptions)
            titlebarSearchCloseButton.setOnClickListener { closeTitlebarSearchMode(returnFocusToEditor = true) }
            searchOptions.setOnClickListener(::showSearchOptions)
        }

        // Configure symbol input view
        val inputView = binding.symbolInput
        inputView.bindEditor(binding.editor)
        inputView.setOnSymbolClickListener { _, insertText, editor ->
            if (embeddedTerminalImeActive) {
                embeddedTerminalHost?.sendTextToEmbeddedTerminal(insertText)
                showEmbeddedTerminalIme()
                return@setOnSymbolClickListener
            }
            if (editor == null || !editor.isEditable) {
                return@setOnSymbolClickListener
            }
            if ("\t" == insertText) {
                if (editor.snippetController.isInSnippet()) {
                    editor.snippetController.shiftToNextTabStop()
                } else {
                    editor.indentOrCommitTab()
                }
            } else {
                editor.insertText(insertText, 1)
            }
        }
        inputView.addSymbols(SYMBOLS, SYMBOL_INSERT_TEXT)
        inputView.forEachButton { it.typeface = typeface }

        binding.sharedImeHost.apply {
            callbacks = object : SharedImeHostEditText.Callbacks {
                override fun onBackPressedPreIme(event: KeyEvent): Boolean {
                    if (!embeddedTerminalImeActive) return false
                    if (event.action == KeyEvent.ACTION_DOWN) return true
                    if (event.action != KeyEvent.ACTION_UP) return true
                    return embeddedTerminalHost?.hideEmbeddedTerminalWorkspace() == true
                }
            }
            setSingleLine(true)
            maxLines = 1
            setRawInputType(EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, actionId, event ->
                if (!embeddedTerminalImeActive) return@setOnEditorActionListener false
                val isEnterEvent = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
                if (actionId != EditorInfo.IME_ACTION_SEND && !isEnterEvent) {
                    return@setOnEditorActionListener false
                }
                embeddedTerminalHost?.sendTextToEmbeddedTerminal("\r")
                true
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(editable: Editable) {
                    if (!embeddedTerminalImeActive) return
                    if (editable.isEmpty()) return
                    val text = editable.toString().replace("\n", "")
                    editable.clear()
                    if (text.isEmpty()) return
                    embeddedTerminalHost?.sendTextToEmbeddedTerminal(text)
                }
            })
            setOnKeyListener { _, keyCode, event ->
                if (!embeddedTerminalImeActive) return@setOnKeyListener false
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                if (keyCode != KeyEvent.KEYCODE_DEL) return@setOnKeyListener false
                if (text?.isNotEmpty() == true) return@setOnKeyListener false
                embeddedTerminalHost?.sendBackspaceToEmbeddedTerminal()
                true
            }
        }

        // Commit search when text changed
        binding.searchEditor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
            override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
            override fun afterTextChanged(editable: Editable) {
                tryCommitSearch()
            }
        })
        binding.titlebarSearchEditor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
            override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
            override fun afterTextChanged(editable: Editable) {
                if (titlebarSearchModeActive) {
                    tryCommitTitlebarSearch()
                }
            }
        })
        binding.titlebarSearchEditor.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                gotoNext(binding.titlebarBtnGotoNext)
                true
            } else {
                false
            }
        }
        // Search options
        searchMenu = PopupMenu(activity, binding.searchOptions)
        searchMenu.inflate(R.menu.menu_search_options)
        searchMenu.setOnMenuItemClickListener {
            // Update option states
            it.isChecked = !it.isChecked
            if (it.isChecked) {
                // Regex and whole word mode can not be both chose
                when (it.itemId) {
                    R.id.search_option_regex -> {
                        searchMenu.menu.findItem(R.id.search_option_whole_word)!!.isChecked = false
                    }

                    R.id.search_option_whole_word -> {
                        searchMenu.menu.findItem(R.id.search_option_regex)!!.isChecked = false
                    }
                }
            }
            // Update search options and commit search with the new options
            computeSearchOptions()
            tryCommitSearch()
            true
        }

        // Configure editor
        binding.editor.apply {
            registerInlayHintRenderers(
                TextInlayHintRenderer.DefaultInstance,
                ColorInlayHintRenderer.DefaultInstance
            )
            typefaceText = typeface
            props.stickyScroll = true
            setLineSpacing(2f, 1.1f)
            nonPrintablePaintingFlags =
                CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
                    CodeEditor.FLAG_DRAW_LINE_SEPARATOR or
                    CodeEditor.FLAG_DRAW_WHITESPACE_IN_SELECTION or
                    CodeEditor.FLAG_DRAW_SOFT_WRAP
            subscribeAlways<ContentChangeEvent> {
                postDelayedInLifecycle(
                    ::updateBtnState,
                    50
                )
                if (!suppressContentChangeCallbacks) {
                    documentSync.onContentChanged()
                }
            }
            subscribeAlways<SideIconClickEvent> {
                toast(R.string.tip_side_icon)
            }
            subscribeAlways<TextSizeChangeEvent> { event ->
                Log.d(
                    TAG,
                    "TextSizeChangeEvent onReceive() called with: oldTextSize = [${event.oldTextSize}], newTextSize = [${event.newTextSize}]"
                )
            }

            subscribeAlways<KeyBindingEvent> { event ->
                if (event.eventType == EditorKeyEvent.Type.DOWN) {
                    toast(
                        "Keybinding event: " + generateKeybindingString(event),
                        Toast.LENGTH_LONG
                    )
                }
            }

            // Handle span interactions
            EditorSpanInteractionHandler(this)
            getComponent<EditorAutoCompletion>()
                .setEnabledAnimation(true)
        }

        // ---- Split: editor environment + vscode integration ----
        editorEnv = EditorEnvironment(activity, binding, prefs)
        editorEnv.setupTextmate()
        editorEnv.setupMonarch()
        editorEnv.ensureTextmateTheme()

        // Default language: Java (native)
        binding.editor.setEditorLanguage(SoraJavaLanguage())
        editorEnv.applyUserPreferredTheme()

        // VS Code integration (syntax/theme/typeface/auto highlight)
        vscode = VSCodeIntegration(
            activity = activity,
            binding = binding,
            prefs = prefs,
            env = editorEnv,
            currentFilePathProvider = { lastOpenRequest?.path },
            onAutoHighlightFailure = { msg -> lastOpenError = msg }
        )

        // ---- File open bridge ----
        FileOpenBridge.addListener(fileOpenListener)
        applyOpenRequest(FileEditorContract.fromIntent(intent), "intent.onCreate")
        applyPendingBridgeRequests("bridge.onCreate")

        updateBtnState()
        editorEnv.applyUserPreferredTheme()
    }

    /**
     * When embedded in a host that already handles status-bar insets, disable
     * editor toolbar top inset to avoid duplicated top spacing.
     */
    fun setHostHandlesStatusBarInsets(hostHandlesInsets: Boolean) {
        applyToolbarTopInsetFromSystemBars = !hostHandlesInsets
    }

    private fun setupPersistentSymbolBar() {
        KeyboardUtils.setSoftInputModeAdjustResize(activity)
        fractionAnimator?.cancel()
        fractionAnimator = null
        currentFraction = 1f
        barFullHeightPx = 0
        barHwLayerEnabled = false
        binding.mainBottomBar.apply {
            visibility = View.VISIBLE
            alpha = 1f
            translationY = 0f
            isEnabled = true
            scaleX = 1f
            scaleY = 1f
            setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    private fun setupTerminalWorkspaceButton() {
        binding.openTerminalWorkspaceButton.visibility = if (embeddedTerminalHost != null) View.VISIBLE else View.GONE
        binding.openTerminalWorkspaceButton.setOnClickListener {
            toggleEmbeddedTerminalWorkspace()
        }
        syncTerminalWorkspaceButtonState()
    }

    private fun toggleEmbeddedTerminalWorkspace(): Boolean {
        val host = embeddedTerminalHost ?: return true
        if (host.isEmbeddedTerminalWorkspaceVisible()) {
            host.hideEmbeddedTerminalWorkspace()
        } else {
            host.showEmbeddedTerminalWorkspace(lastOpenRequest?.path)
        }
        syncEmbeddedTerminalWorkspaceUi()
        activity.invalidateOptionsMenu()
        return true
    }

    private fun syncTerminalWorkspaceButtonState() {
        val visible = embeddedTerminalHost?.isEmbeddedTerminalWorkspaceVisible() == true
        binding.openTerminalWorkspaceButton.isSelected = visible
        binding.openTerminalWorkspaceButton.isActivated = visible
        binding.openTerminalWorkspaceButton.alpha = if (embeddedTerminalHost != null) 1f else 0f
    }

    // ---------------- IME controller implementation ----------------

    /**
     * Ultra-smooth IME accessory bar controller:
     * - Never animates height per-frame (no relayout thrash -> no ghosting/jitter)
     * - Uses IME animation progress on Android 11+ (WindowInsetsAnimationCompat)
     * - Fallback animator for Android 10-
     * - Uses a tiny state machine to avoid flashing
     */
    private fun setupImeAccessoryBarController() {
        // Ensure we know bar height after first layout
        binding.root.doOnLayout {
            ensureBarMeasured()
            syncToCurrentImeStateNoAnimation()
        }

        val decor = window.decorView

        // 1) Listen IME animation (Android 11+ will call this; older might be no-op)
        ViewCompat.setWindowInsetsAnimationCallback(
            decor,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {

                private fun isImeAnim(animation: WindowInsetsAnimationCompat): Boolean {
                    return (animation.typeMask and WindowInsetsCompat.Type.ime()) != 0
                }

                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (!isImeAnim(animation)) return
                    imeAnimCount++
                    fractionAnimator?.cancel()
                    setBarHardwareLayer(true)
                }

                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    if (!isImeAnim(animation)) return bounds

                    imeMaxBottomPx = max(bounds.lowerBound.bottom, bounds.upperBound.bottom)

                    // Get current insets snapshot as "start"
                    val nowInsets = ViewCompat.getRootWindowInsets(decor)
                    val nowImeBottom = nowInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: lastImeBottomPx
                    lastImeBottomPx = nowImeBottom

                    // Ensure bar is attached early (prevents 1-frame pop)
                    val startFraction = if (imeMaxBottomPx > 0) {
                        (nowImeBottom.toFloat() / imeMaxBottomPx.toFloat()).coerceIn(0f, 1f)
                    } else {
                        if (nowImeBottom > 0) 1f else 0f
                    }
                    applyFraction(startFraction, animating = true)

                    return bounds
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    // Track current IME bottom
                    val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    lastImeBottomPx = imeBottom

                    // If an IME animation is running, drive our fraction from real insets.
                    if (imeAnimCount > 0) {
                        val f = if (imeMaxBottomPx > 0) {
                            (imeBottom.toFloat() / imeMaxBottomPx.toFloat()).coerceIn(0f, 1f)
                        } else {
                            if (imeBottom > 0) 1f else 0f
                        }
                        applyFraction(f, animating = true)
                    }

                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (!isImeAnim(animation)) return

                    imeAnimCount = (imeAnimCount - 1).coerceAtLeast(0)

                    if (imeAnimCount == 0) {
                        // Settle to final state (no flashing)
                        val target = if (lastImeBottomPx > 0) 1f else 0f
                        applyFraction(target, animating = false)
                        setBarHardwareLayer(false)
                    }
                }
            }
        )

        // 2) Insets listener: for fallback animation + "no-animation" cases.
        //    IMPORTANT: on Android 11+, IME animation callback drives progress.
        ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            lastImeBottomPx = imeBottom

            if (Build.VERSION.SDK_INT < 30) {
                // Pre-Android 11: no system IME animation progress -> we do our own animator.
                if (imeAnimCount == 0) {
                    val target = if (imeBottom > 0) 1f else 0f
                    animateFractionTo(target)
                }
            } else {
                // Android 11+: avoid early-settle flashes by posting a settle to next frame
                // ONLY when no IME animation is running.
                if (imeAnimCount == 0 && !settlePosted) {
                    settlePosted = true
                    decor.post {
                        settlePosted = false
                        if (imeAnimCount == 0) {
                            val finalInsets = ViewCompat.getRootWindowInsets(decor)
                            val finalImeBottom =
                                finalInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: lastImeBottomPx
                            val target = if (finalImeBottom > 0) 1f else 0f
                            applyFraction(target, animating = false)
                        }
                    }
                }
            }

            insets
        }

        ViewCompat.requestApplyInsets(decor)
    }

    private fun ensureBarMeasured(): Int {
        if (barFullHeightPx > 0) return barFullHeightPx
        val width = binding.root.width
        if (width <= 0) return 0

        val bar = binding.mainBottomBar
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        barFullHeightPx = bar.measuredHeight.coerceAtLeast(1)
        return barFullHeightPx
    }

    private fun setBarHardwareLayer(enabled: Boolean) {
        if (enabled == barHwLayerEnabled) return
        barHwLayerEnabled = enabled
        binding.mainBottomBar.setLayerType(
            if (enabled) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE,
            null
        )
    }

    /**
     * Core: apply fraction [0..1] with NO per-frame relayout.
     * It only sets translation/alpha/scale.
     */
    private fun applyFraction(fractionRaw: Float, animating: Boolean) {
        val barH = ensureBarMeasured()
        if (barH <= 0) return

        val fraction = fractionRaw.coerceIn(0f, 1f)
        currentFraction = fraction

        if (fraction > 0f || animating) {
            binding.mainBottomBar.visibility = View.VISIBLE
            setBarHardwareLayer(true)
        }

        val offset = barH.toFloat() * (1f - fraction)
        binding.mainBottomBar.apply {
            translationY = offset
            alpha = fraction
            isEnabled = fraction > 0f
            val s = 0.98f + 0.02f * fraction
            scaleX = s
            scaleY = s
        }

        if (!animating) {
            if (fraction <= 0f) {
                binding.mainBottomBar.apply {
                    visibility = View.INVISIBLE
                    alpha = 0f
                    translationY = barH.toFloat()
                    isEnabled = false
                    scaleX = 0.98f
                    scaleY = 0.98f
                }
                setBarHardwareLayer(false)
            } else if (fraction >= 1f) {
                binding.mainBottomBar.apply {
                    visibility = View.VISIBLE
                    alpha = 1f
                    translationY = 0f
                    isEnabled = true
                    scaleX = 1f
                    scaleY = 1f
                }
                setBarHardwareLayer(false)
            }
        }
    }

    /**
     * For cases where IME is already shown/hidden (no animation callbacks)
     */
    private fun syncToCurrentImeStateNoAnimation() {
        val insets = ViewCompat.getRootWindowInsets(window.decorView)
        val imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        lastImeBottomPx = imeBottom
        applyFraction(if (imeBottom > 0) 1f else 0f, animating = false)
    }

    private fun animateFractionTo(target: Float) {
        val t = target.coerceIn(0f, 1f)
        val start = currentFraction
        if (abs(start - t) < 0.0005f) {
            applyFraction(t, animating = false)
            return
        }

        fractionAnimator?.cancel()

        // Physical-ish curve: fast out, slow in (very smooth)
        val interpolator = PathInterpolator(0.18f, 0f, 0f, 1f)

        val duration = (180L + (abs(start - t) * 120L)).toLong().coerceIn(180L, 320L)

        setBarHardwareLayer(true)

        fractionAnimator = ValueAnimator.ofFloat(start, t).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { va ->
                val f = va.animatedValue as Float
                applyFraction(f, animating = true)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    applyFraction(t, animating = false)
                    setBarHardwareLayer(false)
                }

                override fun onAnimationCancel(animation: Animator) {
                    // don't force settle here; caller will settle via insets
                }
            })
            start()
        }
    }

    // ---------------- END IME controller ----------------

    fun onNewIntent(intent: Intent) {
        applyOpenRequest(FileEditorContract.fromIntent(intent), "intent.onNewIntent")
    }

    fun onOpenRequest(request: FileOpenRequest?, source: String = "bridge.external") {
        applyOpenRequest(request, source)
    }

    fun onResume() {
        applyPendingBridgeRequests("bridge.onResume")
        scheduleObservedFileCheck("lifecycle.resume")
    }

    fun onPause() {
        flushPendingEditsOnVisibilityLoss()
    }

    /**
     * Generate new [SearchOptions] for text searching in editor
     */
    private fun computeSearchOptions() {
        val caseInsensitive = !searchMenu.menu.findItem(R.id.search_option_match_case)!!.isChecked
        var type = SearchOptions.TYPE_NORMAL
        val regex = searchMenu.menu.findItem(R.id.search_option_regex)!!.isChecked
        if (regex) {
            type = SearchOptions.TYPE_REGULAR_EXPRESSION
        }
        val wholeWord = searchMenu.menu.findItem(R.id.search_option_whole_word)!!.isChecked
        if (wholeWord) {
            type = SearchOptions.TYPE_WHOLE_WORD
        }
        searchOptions = SearchOptions(type, caseInsensitive, RegexBackrefGrammar.DEFAULT)
    }

    /**
     * Commit a text search to editor
     */
    private fun tryCommitSearch() {
        commitSearchQuery(binding.searchEditor.editableText)
    }

    private fun tryCommitTitlebarSearch() {
        commitSearchQuery(binding.titlebarSearchEditor.editableText)
    }

    private fun commitSearchQuery(query: CharSequence) {
        if (query.isNotEmpty()) {
            try {
                binding.editor.searcher.search(
                    query.toString(),
                    searchOptions
                )
            } catch (_: PatternSyntaxException) {
                // Regex error
            }
        } else {
            binding.editor.searcher.stopSearch()
        }
    }

    private fun generateKeybindingString(event: KeyBindingEvent): String {
        val sb = StringBuilder()
        if (event.isCtrlPressed) sb.append("Ctrl + ")
        if (event.isAltPressed) sb.append("Alt + ")
        if (event.isShiftPressed) sb.append("Shift + ")
        sb.append(KeyEvent.keyCodeToString(event.keyCode))
        return sb.toString()
    }

    /**
     * Open file from assets, and set editor text
     */
    private fun openAssetsFile(name: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val text = ContentIO.createFrom(assets.open(name))
            withContext(Dispatchers.Main) {
                binding.editor.setText(text, null)

                updateBtnState()

                if ("big_sample" !in name) {
                    binding.editor.inlayHints = InlayHintsContainer().also {
                        it.add(TextInlayHint(28, 0, "unit:"))
                        it.add(TextInlayHint(28, 7, "open"))
                        it.add(TextInlayHint(28, 22, "^class"))
                        it.add(ColorInlayHint(30, 30, ConstColor("#f44336")))
                    }
                }
            }
        }
    }

    private fun readDiskFileForEditorStable(file: File): DiskFileContent {
        repeat(2) { attempt ->
            val beforeModified = file.lastModified()
            val beforeLength = file.length()
            val digest = MessageDigest.getInstance("SHA-256")
            val content = DigestInputStream(FileInputStream(file).buffered(), digest).use { stream ->
                ContentIO.createFrom(stream)
            }
            val diskState = FileDiskState(
                path = file.absolutePath,
                lastModified = file.lastModified(),
                length = file.length(),
                sha256 = toHex(digest.digest())
            )
            if (attempt > 0 ||
                (beforeModified == diskState.lastModified && beforeLength == diskState.length)
            ) {
                return DiskFileContent(content = content, diskState = diskState)
            }
        }
        throw IllegalStateException("file changed while reading: ${file.absolutePath}")
    }

    private fun readDiskStateStable(file: File): FileDiskState {
        repeat(2) { attempt ->
            val beforeModified = file.lastModified()
            val beforeLength = file.length()
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val diskState = FileDiskState(
                path = file.absolutePath,
                lastModified = file.lastModified(),
                length = file.length(),
                sha256 = toHex(digest.digest())
            )
            if (attempt > 0 ||
                (beforeModified == diskState.lastModified && beforeLength == diskState.length)
            ) {
                return diskState
            }
        }
        throw IllegalStateException("file changed while hashing: ${file.absolutePath}")
    }

    private fun sha256(bytes: ByteArray): String {
        return toHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun sha256Text(text: String): String {
        return sha256(text.toByteArray(Charsets.UTF_8))
    }

    private fun currentEditorSha256(): String {
        return sha256Text(binding.editor.text.toString())
    }

    private fun materializeOpenRequestForRead(request: FileOpenRequest, forceRemoteRefresh: Boolean): MaterializedOpenRequest {
        if (request.originType == FileOpenRequest.ORIGIN_SFTP_VIRTUAL &&
            !request.originPath.isNullOrBlank() &&
            (forceRemoteRefresh || request.path.isBlank())
        ) {
            val result = SessionFileCoordinator.getInstance()
                .materializeVirtualFile(activity.applicationContext, request.originPath)
            if (!result.success) {
                throw IllegalStateException(result.messageCn.ifBlank { "remote refresh failed" })
            }
            val localPath = result.localPath
            if (localPath.isBlank()) {
                throw IllegalStateException("remote refresh returned empty local path")
            }
            return MaterializedOpenRequest(
                request = request.copy(
                    path = localPath,
                    originModifiedMs = result.remoteModifiedMs.takeIf { it >= 0L },
                    originSize = result.remoteSize.takeIf { it >= 0L },
                    originSha256 = result.remoteSha256.takeIf { it.isNotBlank() },
                    originFingerprintLevel = result.remoteSha256.takeIf { it.isNotBlank() }?.let { "STRONG_CONTENT" },
                    originFingerprintMethod = result.remoteSha256.takeIf { it.isNotBlank() }?.let { "remote-native-or-sftp-sha256" }
                ),
                file = File(localPath),
                remoteRefreshed = true
            )
        }
        return MaterializedOpenRequest(
            request = request,
            file = File(request.path),
            remoteRefreshed = false
        )
    }

    private fun isSameOpenRequestSource(a: FileOpenRequest, b: FileOpenRequest): Boolean {
        val aOrigin = a.originPath?.takeIf { it.isNotBlank() }
        val bOrigin = b.originPath?.takeIf { it.isNotBlank() }
        if (a.originType == FileOpenRequest.ORIGIN_SFTP_VIRTUAL || b.originType == FileOpenRequest.ORIGIN_SFTP_VIRTUAL) {
            return aOrigin != null && aOrigin == bOrigin
        }
        return File(a.path).absolutePath == File(b.path).absolutePath
    }

    private fun requireReadableFile(file: File, rawPath: String = file.path) {
        if (!file.exists()) {
            throw IllegalStateException("文件不存在: $rawPath")
        }
        if (!file.isFile) {
            throw IllegalStateException("路径不是文件: $rawPath")
        }
        if (!file.canRead()) {
            throw IllegalStateException("文件不可读: $rawPath")
        }
    }

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        val alphabet = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = alphabet[v ushr 4]
            out[i * 2 + 1] = alphabet[v and 0x0f]
        }
        return String(out)
    }

    private fun updateLoadedDiskState(diskState: FileDiskState) {
        loadedPath = diskState.path
        loadedLastModified = diskState.lastModified
        loadedSize = diskState.length
    }

    private fun updateOpenFileSnapshot(diskState: FileDiskState, editorSha256: String = currentEditorSha256()) {
        synchronized(openFileSnapshotLock) {
            updateLoadedDiskState(diskState)
            openFileSnapshot = OpenFileSnapshot(diskState = diskState, editorSha256 = editorSha256)
            mutedExternalConflictHash = null
            allowOverwriteExternalHashOnce = null
        }
    }

    private fun updateOpenFileDiskMetadata(diskState: FileDiskState) {
        synchronized(openFileSnapshotLock) {
            updateLoadedDiskState(diskState)
            val snapshot = openFileSnapshot
            if (snapshot != null && snapshot.diskState.path == diskState.path) {
                openFileSnapshot = snapshot.copy(diskState = diskState)
            }
        }
    }

    private fun latestOpenFileSnapshot(): OpenFileSnapshot? {
        return synchronized(openFileSnapshotLock) {
            openFileSnapshot
        }
    }

    private fun markReloadedFileBaseline(
        request: FileOpenRequest,
        diskState: FileDiskState,
        absolutePath: String,
        editorSha256: String
    ) {
        val file = File(absolutePath)
        lastOpenRequest = request.copy(path = absolutePath)
        title = request.displayName ?: file.name
        documentSync.bindDocument(
            buildSyncTarget(lastOpenRequest ?: request, file),
            binding.editor.text.toString()
        )
        updateOpenFileSnapshot(diskState, editorSha256)
        updateBtnState()
        lastOpenOkAtMs = System.currentTimeMillis()
        lastOpenError = null
        externalConflictDialog?.dismiss()
        externalConflictDialog = null
        activity.invalidateOptionsMenu()
        startWatchingOpenFile(absolutePath)
    }

    private fun updateReloadedFileMetadataOnly(
        request: FileOpenRequest,
        diskState: FileDiskState,
        absolutePath: String
    ) {
        val file = File(absolutePath)
        lastOpenRequest = request.copy(path = absolutePath)
        title = request.displayName ?: file.name
        documentSync.updateTargetMetadata(buildSyncTarget(lastOpenRequest ?: request, file))
        updateOpenFileDiskMetadata(diskState)
        lastOpenOkAtMs = System.currentTimeMillis()
        lastOpenError = null
        activity.invalidateOptionsMenu()
        startWatchingOpenFile(absolutePath)
    }

    private fun recordEditorManagedLocalPersist(
        target: EditorSyncTarget,
        _trigger: EditorSaveTrigger,
        payload: ByteArray
    ) {
        val targetPath = File(target.localPath).absolutePath
        val activePath = observedFilePath ?: loadedPath
        if (activePath != null && activePath != targetPath) return

        val file = File(targetPath)
        val payloadHash = sha256(payload)
        val diskState = FileDiskState(
            path = targetPath,
            lastModified = runCatching { file.lastModified() }.getOrDefault(System.currentTimeMillis()),
            length = runCatching { file.length() }.getOrDefault(payload.size.toLong()),
            sha256 = payloadHash
        )
        updateOpenFileSnapshot(diskState, payloadHash)

        runOnUiThread {
            if (targetPath == observedFilePath && externalConflictDialog?.isShowing == true) {
                externalConflictDialog?.dismiss()
                externalConflictDialog = null
            }
        }
    }

    private fun refreshSnapshotAfterSuccessfulSave(path: String?) {
        val targetPath = path?.takeIf { it.isNotBlank() } ?: observedFilePath ?: loadedPath ?: return
        lifecycleScope.launch {
            val diskState = withContext(Dispatchers.IO) {
                runCatching { readDiskStateStable(File(targetPath)) }.getOrNull()
            } ?: return@launch
            val editorHash = currentEditorSha256()
            if (diskState.sha256 == editorHash || diskState.path == observedFilePath) {
                updateOpenFileSnapshot(diskState, editorHash)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startWatchingOpenFile(path: String) {
        val file = File(path)
        val parent = file.parentFile ?: return
        val absolutePath = file.absolutePath
        val parentPath = parent.absolutePath
        val fileName = file.name
        if (observedFilePath == absolutePath && observedFileParent == parentPath) return

        stopWatchingOpenFile()
        observedFilePath = absolutePath
        observedFileName = fileName
        observedFileParent = parentPath
        fileObserver = object : FileObserver(parentPath, OBSERVED_FILE_EVENTS) {
            override fun onEvent(event: Int, pathName: String?) {
                val changedName = pathName ?: return
                if (changedName != observedFileName) return
                val cleanEvent = event and FileObserver.ALL_EVENTS
                if ((cleanEvent and OBSERVED_FILE_EVENTS) == 0) return
                scheduleObservedFileCheck("observer:$cleanEvent")
            }
        }.also { observer ->
            runCatching { observer.startWatching() }
        }
    }

    private fun stopWatchingOpenFile() {
        fileRefreshJob?.cancel()
        fileRefreshJob = null
        runCatching { fileObserver?.stopWatching() }
        fileObserver = null
        observedFilePath = null
        observedFileName = null
        observedFileParent = null
        externalConflictDialog?.dismiss()
        externalConflictDialog = null
    }

    private fun scheduleObservedFileCheck(reason: String) {
        val path = observedFilePath ?: return
        fileRefreshJob?.cancel()
        fileRefreshJob = lifecycleScope.launch {
            delay(FILE_RELOAD_DEBOUNCE_MS)
            inspectObservedFile(path, reason)
        }
    }

    private suspend fun inspectObservedFile(path: String, reason: String) {
        if (path != observedFilePath) return
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            handleObservedFileMissing(path)
            return
        }

        val diskState = withContext(Dispatchers.IO) {
            runCatching { readDiskStateStable(file) }
        }.getOrElse {
            Log.w(TAG, "Unable to inspect observed file: $path", it)
            return
        }
        if (path != observedFilePath) return

        val snapshot = openFileSnapshot
        if (snapshot?.diskState?.sha256 == diskState.sha256) {
            updateOpenFileDiskMetadata(diskState)
            return
        }

        val editorHash = currentEditorSha256()
        if (diskState.sha256 == editorHash) {
            updateOpenFileSnapshot(diskState, editorHash)
            return
        }

        val editorHasLocalChanges = documentSync.hasUnsavedChanges() ||
            (snapshot != null && editorHash != snapshot.editorSha256)
        if (!editorHasLocalChanges) {
            reloadOpenFileFromDisk(path, reason = reason, notify = reason.startsWith("observer"))
            return
        }

        if (mutedExternalConflictHash != diskState.sha256) {
            showExternalFileConflictDialog(path, diskState)
        }
    }

    private fun handleObservedFileMissing(path: String) {
        val snapshot = openFileSnapshot ?: return
        val editorHash = currentEditorSha256()
        val editorHasLocalChanges = documentSync.hasUnsavedChanges() || editorHash != snapshot.editorSha256
        if (editorHasLocalChanges && mutedExternalConflictHash != "deleted:$path") {
            mutedExternalConflictHash = "deleted:$path"
            toast("文件已在外部删除，当前未保存内容已保留")
        } else if (!editorHasLocalChanges && mutedExternalConflictHash != "deleted:$path") {
            mutedExternalConflictHash = "deleted:$path"
            toast("文件已在外部删除，编辑器内容已保留")
        }
    }

    private fun refreshCurrentEditorFromSource() {
        val request = lastOpenRequest
        if (request == null) {
            toast("当前没有可刷新的文件")
            return
        }

        val refreshToken = openGeneration.incrementAndGet()
        lifecycleScope.launch {
            val result = runCatching {
                documentSync.runExclusiveExternalReload {
                    val beforeSnapshot = latestOpenFileSnapshot()
                    val (materialized, fileContent, absolutePath) = withContext(Dispatchers.IO) {
                        val materialized = materializeOpenRequestForRead(request, forceRemoteRefresh = true)
                        val file = materialized.file
                        requireReadableFile(file, request.path)
                        val fileContent = readDiskFileForEditorStable(file)
                        Triple(materialized, fileContent, file.absolutePath)
                    }

                    if (refreshToken != openGeneration.get()) return@runExclusiveExternalReload
                    val activeRequest = lastOpenRequest ?: return@runExclusiveExternalReload
                    if (!isSameOpenRequestSource(activeRequest, request)) return@runExclusiveExternalReload

                    if (beforeSnapshot?.diskState?.sha256 == fileContent.diskState.sha256) {
                        val editorHash = currentEditorSha256()
                        if (editorHash == beforeSnapshot.editorSha256) {
                            markReloadedFileBaseline(
                                request = materialized.request,
                                diskState = fileContent.diskState,
                                absolutePath = absolutePath,
                                editorSha256 = editorHash
                            )
                        } else {
                            updateReloadedFileMetadataOnly(
                                request = materialized.request,
                                diskState = fileContent.diskState,
                                absolutePath = absolutePath
                            )
                        }
                        return@runExclusiveExternalReload
                    }

                    val editorHash = currentEditorSha256()
                    if (editorHash == fileContent.diskState.sha256) {
                        markReloadedFileBaseline(
                            request = materialized.request,
                            diskState = fileContent.diskState,
                            absolutePath = absolutePath,
                            editorSha256 = editorHash
                        )
                        return@runExclusiveExternalReload
                    }

                    applyReloadedFileContent(
                        request = materialized.request,
                        fileContent = fileContent,
                        absolutePath = absolutePath,
                        reason = if (materialized.remoteRefreshed) "manual.remote.refresh" else "manual.local.refresh",
                        notify = true,
                        forceMessage = "已刷新最新内容"
                    )
                }
            }

            val failure = result.exceptionOrNull()
            if (failure != null) {
                if (refreshToken == openGeneration.get()) {
                    toast("刷新失败：${failure.message ?: failure::class.java.name}")
                    activity.invalidateOptionsMenu()
                }
                return@launch
            }
        }
    }

    private fun reloadOpenFileFromDisk(path: String, reason: String, notify: Boolean) {
        val request = lastOpenRequest ?: return
        val expectedPath = File(path).absolutePath
        lifecycleScope.launch {
            val fileContent = withContext(Dispatchers.IO) {
                runCatching { readDiskFileForEditorStable(File(expectedPath)) }
            }.getOrElse {
                toast("刷新失败：${it.message ?: it::class.java.name}")
                return@launch
            }
            if (expectedPath != observedFilePath && expectedPath != File(request.path).absolutePath) return@launch

            applyReloadedFileContent(
                request = request.copy(path = expectedPath),
                fileContent = fileContent,
                absolutePath = expectedPath,
                reason = reason,
                notify = notify
            )
        }
    }

    private fun applyReloadedFileContent(
        request: FileOpenRequest,
        fileContent: DiskFileContent,
        absolutePath: String,
        reason: String,
        notify: Boolean,
        forceMessage: String? = null
    ) {
        val editor = binding.editor
        val line = editor.cursor.leftLine
        val column = editor.cursor.leftColumn
        val scrollX = editor.offsetX
        val scrollY = editor.offsetY
        val file = File(absolutePath)

        suppressContentChangeCallbacks = true
        editor.setText(fileContent.content, null)
        lastOpenRequest = request.copy(path = absolutePath)
        title = request.displayName ?: file.name
        documentSync.bindDocument(
            buildSyncTarget(lastOpenRequest ?: request, file),
            editor.text.toString()
        )
        updateOpenFileSnapshot(fileContent.diskState, currentEditorSha256())
        updateBtnState()
        lastOpenOkAtMs = System.currentTimeMillis()
        lastOpenError = null
        externalConflictDialog?.dismiss()
        externalConflictDialog = null
        activity.invalidateOptionsMenu()
        startWatchingOpenFile(absolutePath)
        restoreEditorViewport(line, column, scrollX, scrollY)
        editor.post {
            suppressContentChangeCallbacks = false
            if (absolutePath == observedFilePath) {
                vscode.maybeAutoApplyVSCodeSyntaxByFileName(absolutePath)
            }
        }
        if (notify) {
            toast(forceMessage ?: "已刷新外部修改")
        }
        Log.d(TAG, "Reloaded file content reason=$reason path=$absolutePath")
    }

    private fun restoreEditorViewport(line: Int, column: Int, scrollX: Int, scrollY: Int) {
        val editor = binding.editor
        editor.post {
            val lineCount = editor.text.lineCount
            if (lineCount > 0) {
                val safeLine = line.coerceIn(0, lineCount - 1)
                val safeColumn = column.coerceIn(0, editor.text.getColumnCount(safeLine))
                runCatching { editor.setSelection(safeLine, safeColumn, false) }
            }
            val scroller = editor.scroller
            scroller.forceFinished(true)
            scroller.startScroll(
                scrollX.coerceIn(0, editor.scrollMaxX),
                scrollY.coerceIn(0, editor.scrollMaxY),
                0,
                0,
                0
            )
            scroller.abortAnimation()
        }
    }

    private fun detectExternalSaveConflictBeforePersist(
        target: EditorSyncTarget,
        trigger: EditorSaveTrigger,
        payload: ByteArray
    ): String? {
        val snapshot = openFileSnapshot ?: return null
        val targetPath = File(target.localPath).absolutePath
        if (snapshot.diskState.path != targetPath) return null
        val file = File(targetPath)
        if (!file.exists() || !file.isFile) return null

        val diskState = runCatching { readDiskStateStable(file) }.getOrNull() ?: return null
        val payloadHash = sha256(payload)
        if (diskState.sha256 == snapshot.diskState.sha256 || diskState.sha256 == payloadHash) {
            return null
        }

        if (trigger == EditorSaveTrigger.MANUAL && allowOverwriteExternalHashOnce == diskState.sha256) {
            allowOverwriteExternalHashOnce = null
            return null
        }

        if (trigger != EditorSaveTrigger.AUTO || mutedExternalConflictHash != diskState.sha256) {
            runOnUiThread {
                showExternalFileConflictDialog(targetPath, diskState)
            }
        }
        return "文件已被外部修改，已阻止覆盖保存"
    }

    private fun showExternalFileConflictDialog(path: String, diskState: FileDiskState) {
        if (path != observedFilePath && path != loadedPath) return
        if (externalConflictDialog?.isShowing == true) return

        val fileName = File(path).name
        val dialog = AlertDialog.Builder(activity)
            .setTitle("文件已在外部修改")
            .setMessage("磁盘上的 $fileName 已变化，当前编辑器也有未保存内容。为避免互相覆盖，已暂停自动刷新。")
            .setPositiveButton("重新载入") { _, _ ->
                mutedExternalConflictHash = null
                reloadOpenFileFromDisk(path, reason = "external.conflict.reload", notify = true)
            }
            .setNeutralButton("覆盖保存") { _, _ ->
                mutedExternalConflictHash = null
                allowOverwriteExternalHashOnce = diskState.sha256
                lifecycleScope.launch {
                    val result = documentSync.saveNow(EditorSaveTrigger.MANUAL)
                    if (result.ok) {
                        refreshSnapshotAfterSuccessfulSave(result.targetPath)
                        toast("已覆盖保存")
                    } else {
                        toast("保存失败：${result.error ?: "unknown"}")
                    }
                }
            }
            .setNegativeButton("稍后处理") { _, _ ->
                mutedExternalConflictHash = diskState.sha256
            }
            .create()
        dialog.setOnCancelListener {
            mutedExternalConflictHash = diskState.sha256
        }
        externalConflictDialog = dialog
        dialog.show()
    }

    private fun openDiskFile(request: FileOpenRequest) {
        val path = request.path
        val openToken = openGeneration.incrementAndGet()
        lifecycleScope.launch(Dispatchers.IO) {
            val materialized = runCatching { materializeOpenRequestForRead(request, forceRemoteRefresh = false) }
                .getOrElse {
                    if (openToken == openGeneration.get()) {
                        lastOpenError = it.message ?: it.toString()
                        withContext(Dispatchers.Main) {
                            if (openToken == openGeneration.get()) toast("打开失败：${lastOpenError ?: "unknown"}")
                        }
                    }
                    return@launch
                }
            val normalizedRequest = materialized.request
            val file = materialized.file
            val absolutePath = file.absolutePath
            lastOpenAttemptAtMs = System.currentTimeMillis()

            val readError = runCatching { requireReadableFile(file, path) }.exceptionOrNull()
            if (readError != null) {
                val error = readError.message ?: readError.toString()
                if (openToken == openGeneration.get()) {
                    lastOpenError = error
                    withContext(Dispatchers.Main) {
                        if (openToken == openGeneration.get()) toast(error)
                    }
                }
                return@launch
            }

            val fileModified = file.lastModified()
            val fileSize = file.length()

            // Skip parsing if the same file is already loaded and unchanged.
            if (loadedPath == absolutePath && loadedLastModified == fileModified && loadedSize == fileSize && lastOpenError == null) {
                if (openToken == openGeneration.get()) {
                    withContext(Dispatchers.Main) {
                        if (openToken != openGeneration.get()) return@withContext
                        lastOpenOkAtMs = System.currentTimeMillis()
                        lastOpenRequest = normalizedRequest.copy(path = absolutePath)
                        documentSync.bindDocument(
                            buildSyncTarget(normalizedReq = lastOpenRequest ?: normalizedRequest, file = file),
                            binding.editor.text.toString()
                        )
                        startWatchingOpenFile(absolutePath)
                        if (openFileSnapshot?.diskState?.path != absolutePath) {
                            scheduleObservedFileCheck("open.same-file")
                        }
                        activity.invalidateOptionsMenu()
                        recordRecentFileOpen(lastOpenRequest ?: normalizedRequest, file)
                    }
                }
                return@launch
            }

            val diskFile = runCatching { readDiskFileForEditorStable(file) }
                .getOrElse {
                    if (openToken == openGeneration.get()) {
                        lastOpenError = it.toString()
                        withContext(Dispatchers.Main) {
                            if (openToken == openGeneration.get()) toast(it.toString())
                        }
                    }
                    return@launch
                }

            withContext(Dispatchers.Main) {
                if (openToken != openGeneration.get()) return@withContext
                suppressContentChangeCallbacks = true
                binding.editor.setText(diskFile.content, null)
                lastOpenRequest = normalizedRequest.copy(path = absolutePath)
                documentSync.bindDocument(
                    buildSyncTarget(normalizedReq = lastOpenRequest ?: normalizedRequest, file = file),
                    binding.editor.text.toString()
                )
                updateBtnState()
                lastOpenOkAtMs = System.currentTimeMillis()
                lastOpenError = null
                updateOpenFileSnapshot(diskFile.diskState, currentEditorSha256())
                startWatchingOpenFile(absolutePath)
                activity.invalidateOptionsMenu()
                recordRecentFileOpen(lastOpenRequest ?: normalizedRequest, file)

                // Apply syntax on next frame so text becomes visible earlier.
                binding.editor.post {
                    suppressContentChangeCallbacks = false
                    if (openToken != openGeneration.get()) return@post
                    vscode.maybeAutoApplyVSCodeSyntaxByFileName(absolutePath)
                }
            }
        }
    }

    private fun buildSyncTarget(normalizedReq: FileOpenRequest, file: File): EditorSyncTarget {
        val isRemote = normalizedReq.originType == FileOpenRequest.ORIGIN_SFTP_VIRTUAL &&
            !normalizedReq.originPath.isNullOrBlank()
        return EditorSyncTarget(
            localPath = normalizedReq.path,
            displayName = normalizedReq.displayName ?: file.name,
            readOnly = normalizedReq.readOnly || !file.canWrite(),
            extension = normalizedReq.extension,
            mimeType = normalizedReq.mimeType,
            kind = if (isRemote) EditorSyncTargetKind.SFTP_VIRTUAL_FILE else EditorSyncTargetKind.LOCAL_FILE,
            originPath = normalizedReq.originPath,
            originDisplayPath = normalizedReq.originDisplayPath,
            originModifiedMs = normalizedReq.originModifiedMs,
            originSize = normalizedReq.originSize,
            originSha256 = normalizedReq.originSha256,
            originFingerprintLevel = normalizedReq.originFingerprintLevel,
            originFingerprintMethod = normalizedReq.originFingerprintMethod
        )
    }

    private fun recordRecentFileOpen(request: FileOpenRequest, file: File) {
        RecentFileHistory.recordOpenedFile(
            context = activity,
            path = request.path,
            displayName = request.displayName ?: file.name,
            originType = request.originType,
            originPath = request.originPath,
            originDisplayPath = request.originDisplayPath,
            sizeBytes = request.originSize
                ?: file.takeIf { it.exists() && it.isFile }?.length()?.takeIf { it >= 0L }
        )
    }

    private fun flushPendingEditsOnVisibilityLoss() {
        if (!documentSync.isAutoSaveEnabled()) return
        if (!documentSync.canSave() || !documentSync.hasUnsavedChanges()) return
        lifecycleScope.launch {
            val result = documentSync.flushPendingSave(EditorSaveTrigger.AUTO)
            if (!result.ok) {
                Log.w(TAG, "Auto-save flush on pause failed: ${result.error ?: "unknown"}")
            } else {
                refreshSnapshotAfterSuccessfulSave(result.targetPath)
            }
        }
    }

    private fun proceedOpenRequest(normalizedReq: FileOpenRequest, source: String, bridgeSequence: Long?) {
        lastOpenRequest = normalizedReq

        if (source.startsWith("bridge")) {
            val seq = bridgeSequence ?: FileOpenBridge.getLatestSequence()
            if (seq > lastBridgeSeqHandled) {
                lastBridgeSeqHandled = seq
            }
        }
        title = normalizedReq.displayName ?: File(normalizedReq.path).name
        openDiskFile(normalizedReq)
    }

    private fun showUnsavedChangesDialogForOpen(
        normalizedReq: FileOpenRequest,
        source: String,
        bridgeSequence: Long?
    ) {
        AlertDialog.Builder(activity)
            .setTitle("未保存更改")
            .setMessage("当前文件还有未保存修改，切换前需要先处理。")
            .setPositiveButton("保存并切换") { _, _ ->
                lifecycleScope.launch {
                    val save = documentSync.saveNow(EditorSaveTrigger.MANUAL)
                    if (save.ok) {
                        refreshSnapshotAfterSuccessfulSave(save.targetPath)
                        proceedOpenRequest(normalizedReq, source, bridgeSequence)
                    } else {
                        toast("切换前保存失败：${save.error ?: "unknown"}")
                    }
                }
            }
            .setNeutralButton("放弃更改") { _, _ ->
                proceedOpenRequest(normalizedReq, source, bridgeSequence)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyPendingBridgeRequests(source: String) {
        val events = FileOpenBridge.getEventsAfter(lastBridgeSeqHandled)
        if (events.isEmpty()) return

        for (event in events) {
            applyOpenRequest(event.request, source, event.sequence)
        }
    }

    private fun applyOpenRequest(req: FileOpenRequest?, source: String, bridgeSequence: Long? = null) {
        if (req == null) return
        val normalizedPath = req.path.trim()
        if (normalizedPath.isEmpty()) return

        val normalizedReq = if (normalizedPath == req.path) req else req.copy(path = normalizedPath)
        val currentPath = lastOpenRequest?.path?.trim()?.takeIf { it.isNotEmpty() }
        val hasProtectedDirtyDocument = currentPath != null && documentSync.canSave() && documentSync.hasUnsavedChanges()
        if (hasProtectedDirtyDocument) {
            if (documentSync.isAutoSaveEnabled()) {
                lifecycleScope.launch {
                    val save = documentSync.flushPendingSave(EditorSaveTrigger.AUTO)
                    if (save.ok) {
                        refreshSnapshotAfterSuccessfulSave(save.targetPath)
                        proceedOpenRequest(normalizedReq, source, bridgeSequence)
                    } else {
                        toast("切换前自动保存失败：${save.error ?: "unknown"}")
                    }
                }
            } else {
                showUnsavedChangesDialogForOpen(normalizedReq, source, bridgeSequence)
            }
            return
        }
        proceedOpenRequest(normalizedReq, source, bridgeSequence)
    }

    private val fileOpenListener = FileOpenListener { event: FileOpenEvent ->
        if (event.sequence <= lastBridgeSeqHandled) return@FileOpenListener
        applyOpenRequest(event.request, "bridge.callback", event.sequence)
    }

    /**
     * Update buttons state for undo/redo
     */
    private fun updateBtnState() {
        undo?.isEnabled = binding.editor.canUndo()
        redo?.isEnabled = binding.editor.canRedo()
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        setupPersistentSymbolBar()
        editorEnv.applyUserPreferredTheme()
    }

    fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        undo = menu.findItem(R.id.text_undo)
        redo = menu.findItem(R.id.text_redo)
        menu.findItem(R.id.open_terminal_workspace)?.isVisible = false
        menu.findItem(R.id.bridge_self_test)?.isVisible = false
        menu.findItem(R.id.auto_save_enabled)?.isChecked = documentSync.isAutoSaveEnabled()
        menu.findItem(R.id.save_file)?.isEnabled = documentSync.state.value.canSave
        saveStatusUi.bind(menu, onClick = ::refreshCurrentEditorFromSource)
        saveStatusUi.render(documentSync.state.value)
        return true
    }

    fun onDestroy() {
        fractionAnimator?.cancel()
        fractionAnimator = null
        stopWatchingOpenFile()
        runCatching { ViewCompat.setWindowInsetsAnimationCallback(window.decorView, null) }
        FileOpenBridge.removeListener(fileOpenListener)
        binding.activityToolbar.navigationIcon = null
        binding.activityToolbar.setNavigationOnClickListener(null)
        binding.editor.release()
    }

    fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        val editor = binding.editor
        when (id) {
            R.id.open_test_activity -> startActivity<TestActivity>()

            R.id.open_lsp_activity -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    AlertDialog.Builder(activity)
                        .setTitle(getString(R.string.not_supported))
                        .setMessage(getString(R.string.dialog_api_warning_msg))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    val options = arrayOf(
                        "Lua LSP (Kotlin)",
                        "Lua LSP (Java)",
                        "Python LSP (Desktop)"
                    )
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.dialog_lsp_entry_title)
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> startActivity<LspTestActivity>()
                                1 -> startActivity<LspTestJavaActivity>()
                                2 -> startActivity<PythonLspTestActivity>()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }

            R.id.text_undo -> editor.undo()
            R.id.text_redo -> editor.redo()
            R.id.goto_end -> editor.setSelection(
                editor.text.lineCount - 1,
                editor.text.getColumnCount(editor.text.lineCount - 1)
            )

            R.id.move_up -> editor.moveSelection(SelectionMovement.UP)
            R.id.move_down -> editor.moveSelection(SelectionMovement.DOWN)
            R.id.home -> editor.moveSelection(SelectionMovement.LINE_START)
            R.id.end -> editor.moveSelection(SelectionMovement.LINE_END)
            R.id.move_left -> editor.moveSelection(SelectionMovement.LEFT)
            R.id.move_right -> editor.moveSelection(SelectionMovement.RIGHT)

            R.id.magnifier -> {
                item.isChecked = !item.isChecked
                editor.getComponent(Magnifier::class.java).isEnabled = item.isChecked
            }

            R.id.useIcu -> {
                item.isChecked = !item.isChecked
                editor.props.useICULibToSelectWords = item.isChecked
            }

            R.id.ln_panel_fixed -> editorEnv.chooseLineNumberPanelPositionFixed()
            R.id.ln_panel_follow -> editorEnv.chooseLineNumberPanelPositionFollow()

            R.id.code_format -> editor.formatCodeAsync()

            R.id.switch_language -> editorEnv.chooseLanguage(
                launchTMLFromFile = { loadTMLLauncher.launch("*/*") }
            )

            R.id.search_panel_st -> toggleSearchPanel(item)

            R.id.search_am -> {
                showTitlebarSearchMode()
                return true
            }

            R.id.switch_colors -> editorEnv.chooseTheme(
                launchTMThemeFromFile = { loadTMTLauncher.launch("*/*") }
            )

            R.id.vscode_syntax_highlight -> vscode.chooseVSCodeSyntaxHighlight()
            R.id.vscode_theme -> vscode.chooseVSCodeTheme()
            R.id.vscode_typeface -> vscode.chooseVSCodeTypeface()

            R.id.text_wordwrap -> {
                item.isChecked = !item.isChecked
                editor.isWordwrap = item.isChecked
            }

            R.id.completionAnim -> {
                item.isChecked = !item.isChecked
                editor.getComponent<EditorAutoCompletion>()
                    .setEnabledAnimation(item.isChecked)
            }

            R.id.open_logs -> openLogs()
            R.id.clear_logs -> clearLogs()
            R.id.auto_save_enabled -> {
                item.isChecked = !item.isChecked
                documentSync.setAutoSaveEnabled(item.isChecked)
                activity.invalidateOptionsMenu()
                toast(if (item.isChecked) "Auto-save enabled" else "Auto-save disabled")
            }
            R.id.save_file -> {
                lifecycleScope.launch {
                    val r = documentSync.saveNow(EditorSaveTrigger.MANUAL)
                    if (r.ok) {
                        refreshSnapshotAfterSuccessfulSave(r.targetPath)
                        toast("Saved")
                    } else {
                        toast("Save failed: ${r.error ?: "unknown"}")
                    }
                }
            }
            R.id.autosave_self_test -> {
                lifecycleScope.launch {
                    val report = documentSync.runSelfTest()
                    AlertDialog.Builder(activity)
                        .setTitle(getString(R.string.autosave_self_test))
                        .setMessage(report)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.copy_text) { _, _ ->
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("autosave-selftest", report))
                            toast("Copied")
                        }
                        .show()
                }
            }

            R.id.bridge_self_test -> {
                showBridgeSelfTestDialog()
                return true
            }

            R.id.editor_line_number -> {
                editor.isLineNumberEnabled = !editor.isLineNumberEnabled
                item.isChecked = editor.isLineNumberEnabled
            }

            R.id.pin_line_number -> {
                editor.setPinLineNumber(!editor.isLineNumberPinned)
                item.isChecked = editor.isLineNumberPinned
            }

            R.id.load_test_file -> openAssetsFile("samples/big_sample.txt")

            R.id.softKbdEnabled -> {
                editor.isSoftKeyboardEnabled = !editor.isSoftKeyboardEnabled
                item.isChecked = editor.isSoftKeyboardEnabled
            }

            R.id.disableSoftKbdOnHardKbd -> {
                editor.isDisableSoftKbdIfHardKbdAvailable =
                    !editor.isDisableSoftKbdIfHardKbdAvailable
                item.isChecked = editor.isDisableSoftKbdIfHardKbdAvailable
            }

            R.id.switch_typeface -> editorEnv.chooseTypeface()

            R.id.open_terminal_workspace -> {
                return toggleEmbeddedTerminalWorkspace()
            }
        }
        return false
    }

    fun syncEmbeddedTerminalWorkspaceUi() {
        val visible = embeddedTerminalHost?.isEmbeddedTerminalWorkspaceVisible() == true
        binding.activityToolbar.navigationIcon = if (visible) {
            AppCompatResources.getDrawable(activity, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        } else {
            null
        }
        binding.activityToolbar.setNavigationOnClickListener(
            if (visible) {
                View.OnClickListener {
                    if (embeddedTerminalHost?.hideEmbeddedTerminalWorkspace() == true) {
                        syncEmbeddedTerminalWorkspaceUi()
                        activity.invalidateOptionsMenu()
                    }
                }
            } else {
                null
            }
        )
        syncTerminalWorkspaceButtonState()
    }

    fun onEmbeddedTerminalShown(keepKeyboardVisible: Boolean) {
        restoreEditorImeAfterTerminal =
                binding.editor.hasFocus() ||
                binding.searchEditor.hasFocus() ||
                binding.replaceEditor.hasFocus() ||
                binding.titlebarSearchEditor.hasFocus()
        embeddedTerminalImeActive = true
        closeTitlebarSearchMode(returnFocusToEditor = false)
        binding.searchEditor.clearFocus()
        binding.replaceEditor.clearFocus()
        binding.editor.clearFocus()
        if (keepKeyboardVisible) {
            showEmbeddedTerminalIme()
        } else {
            binding.sharedImeHost.clearFocus()
        }
    }

    fun onEmbeddedTerminalHidden(keepKeyboardVisible: Boolean) {
        embeddedTerminalImeActive = false
        binding.sharedImeHost.clearFocus()
        if (keepKeyboardVisible && restoreEditorImeAfterTerminal) {
            binding.editor.requestFocus()
            KeyboardUtils.showSoftKeyboard(activity, binding.editor)
        }
        restoreEditorImeAfterTerminal = false
    }

    fun isEmbeddedTerminalImeActive(): Boolean = embeddedTerminalImeActive

    fun restoreEmbeddedTerminalIme(visible: Boolean) {
        embeddedTerminalImeActive = true
        if (visible) {
            showEmbeddedTerminalIme()
        } else {
            binding.sharedImeHost.clearFocus()
        }
    }

    fun showEmbeddedTerminalIme() {
        embeddedTerminalImeActive = true
        binding.sharedImeHost.post {
            binding.sharedImeHost.requestFocus()
            KeyboardUtils.showSoftKeyboard(activity, binding.sharedImeHost)
            binding.sharedImeHost.postDelayed(
                { KeyboardUtils.showSoftKeyboard(activity, binding.sharedImeHost) },
                120
            )
        }
    }

    private fun showBridgeSelfTestDialog() {
        val now = System.currentTimeMillis()
        val req = lastOpenRequest
        val latestSeq = FileOpenBridge.getLatestSequence()
        val latestReq = FileOpenBridge.getLatestRequest()
        val auto = vscode.lastAutoHighlight
        val editor = binding.editor
        val content = editor.text

        fun safe(block: () -> String): String {
            return runCatching { block() }.getOrElse { t ->
                val msg = t.message?.trim().orEmpty()
                val err = if (msg.isEmpty()) t::class.java.name else "${t::class.java.name}: $msg"
                "<error $err>"
            }
        }

        fun tailTextFile(path: String, maxBytes: Int): String? {
            return runCatching {
                val f = File(path)
                if (!f.exists() || !f.isFile || !f.canRead()) return@runCatching null
                java.io.RandomAccessFile(f, "r").use { raf ->
                    val len = raf.length()
                    if (len <= 0L) return@use ""
                    val read = kotlin.math.min(len, maxBytes.toLong()).toInt()
                    raf.seek(len - read)
                    val buf = ByteArray(read)
                    raf.readFully(buf)
                    String(buf, Charsets.UTF_8)
                }
            }.getOrNull()
        }

        val text = buildString {
            appendLine("timeMs=$now")
            appendLine("lifecycle=$lifecycle")
            appendLine("isFinishing=$isFinishing isDestroyed=$isDestroyed")
            appendLine("thread=${safe { Thread.currentThread().name }}")
            appendLine("sdkInt=${safe { Build.VERSION.SDK_INT.toString() }}")
            appendLine()

            appendLine("bridge.latestSeq=$latestSeq handledSeq=$lastBridgeSeqHandled")
            appendLine("bridge.latestReq=${latestReq?.path ?: "null"}")
            appendLine()

            appendLine("lastOpen.path=${req?.path ?: "null"}")
            appendLine("lastOpen.displayName=${req?.displayName ?: "null"}")
            appendLine("lastOpen.readOnly=${req?.readOnly ?: false}")
            appendLine("lastOpen.attemptAtMs=$lastOpenAttemptAtMs okAtMs=$lastOpenOkAtMs")
            appendLine("lastOpen.error=${lastOpenError ?: "null"}")
            appendLine()

            appendLine("autosave.enabled=${documentSync.isAutoSaveEnabled()}")
            appendLine("autosave.note=use menu Debug -> ${getString(R.string.autosave_self_test)}")
            appendLine("sync.snapshot=${documentSync.state.value}")
            appendLine()

            appendLine("vscode.cache.size=${vscode.languageCacheSize}")
            appendLine("vscode.preloadedAll=${vscode.allExtensionsPreloaded} preloadedRoots=${vscode.preloadedRootsSize}")
            appendLine("vscode.auto.enabled=${safe { vscode.isVSCodeAutoHighlightEnabled().toString() }}")
            appendLine("vscode.auto.attemptAtMs=${auto?.attemptAtMs ?: 0} okAtMs=${auto?.okAtMs ?: 0}")
            appendLine("vscode.auto.path=${auto?.path ?: "null"}")
            appendLine("vscode.auto.fileName=${auto?.fileName ?: "null"}")
            appendLine("vscode.auto.match=${auto?.matchKind ?: "null"} matchKey=${auto?.matchKey ?: "null"}")
            appendLine("vscode.auto.languageId=${auto?.languageId ?: "null"} displayName=${auto?.displayName ?: "null"}")
            appendLine("vscode.auto.scopeName=${auto?.scopeName ?: "null"}")
            appendLine("vscode.auto.grammarPath=${auto?.grammarPath ?: "null"}")
            appendLine("vscode.auto.extensionRoot=${auto?.extensionRoot ?: "null"}")
            appendLine("vscode.auto.warning=${auto?.warning ?: "null"}")
            appendLine("vscode.auto.error=${auto?.error ?: "null"}")
            appendLine(
                "vscode.auto.grammarLoaded=${
                    safe {
                        (auto?.scopeName?.let { GrammarRegistry.getInstance().findGrammar(it) != null } ?: false).toString()
                    }
                }"
            )
            appendLine()

            val p = req?.path
            if (p != null) {
                val f = File(p)
                appendLine("file.exists=${f.exists()} isFile=${f.isFile} isDir=${f.isDirectory}")
                appendLine("file.canRead=${f.canRead()} canWrite=${f.canWrite()}")
                appendLine("file.length=${runCatching { f.length() }.getOrNull()}")
                appendLine("file.lastModified=${runCatching { f.lastModified() }.getOrNull()}")
            }
            appendLine()

            appendLine("editor.lineCount=${content.lineCount}")
            appendLine("editor.charCount=${content.length}")
            appendLine("editor.isWordwrap=${editor.isWordwrap}")
            appendLine("editor.lineNumber.enabled=${editor.isLineNumberEnabled} pinned=${editor.isLineNumberPinned}")
            appendLine("editor.stickyScroll=${editor.props.stickyScroll}")
            appendLine("editor.softKeyboardEnabled=${editor.isSoftKeyboardEnabled}")
            appendLine("editor.disableSoftKbdIfHardKbdAvailable=${editor.isDisableSoftKbdIfHardKbdAvailable}")
            appendLine("editor.useIcu=${editor.props.useICULibToSelectWords}")
            val cursor = editor.cursor
            appendLine("cursor.left=${cursor.left} right=${cursor.right} selected=${cursor.isSelected}")
            appendLine("cursor.pos=${cursor.leftLine}:${cursor.leftColumn}")
            val hasQuery = safe { editor.searcher.hasQuery().toString() }
            val matchCount = safe {
                if (editor.searcher.hasQuery()) editor.searcher.matchedPositionCount.toString() else "0"
            }
            appendLine("search.hasQuery=$hasQuery matchCount=$matchCount")
            appendLine()

            val lang = editor.editorLanguage
            appendLine("lang.class=${lang?.javaClass?.name}")
            appendLine("scheme.class=${editor.colorScheme?.javaClass?.name}")
            appendLine("textmate.theme.name=${safe { ThemeRegistry.getInstance().currentThemeModel?.name ?: "null" }}")
            appendLine("oniguruma.native=${safe { Oniguruma().isUseNativeOniguruma.toString() }}")
            val rt = Runtime.getRuntime()
            appendLine("runtime.maxMemory=${rt.maxMemory()} totalMemory=${rt.totalMemory()} freeMemory=${rt.freeMemory()}")
            appendLine()

            val crashPath = "/data/data/com.termux/files/home/crash_log.md"
            appendLine("crashLog.path=$crashPath")
            val crashTail = tailTextFile(crashPath, 8192)
            appendLine("crashLog.readable=${crashTail != null}")
            appendLine("crashLog.tailBytes=8192")
            if (crashTail != null) {
                appendLine("crashLog.tail=")
                appendLine(crashTail.trimEnd())
            }
            appendLine()

            val i = intent
            appendLine("intent.action=${i?.action}")
            appendLine("intent.component=${i?.component}")
            val keys = i?.extras?.keySet()?.toList().orEmpty().sorted()
            appendLine("intent.extras.keys=${if (keys.isEmpty()) "[]" else keys.joinToString(prefix = "[", postfix = "]")}")
        }.trimEnd()

        AlertDialog.Builder(activity)
            .setTitle("Editor Self Test")
            .setMessage(text)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("editor_self_test", text))
                toast("Copied")
            }
            .setNeutralButton("Reload") { _, _ ->
                val latest = FileOpenBridge.getLatestRequest()
                if (latest != null) applyOpenRequest(latest, "bridge.selftest.reload") else toast("No file request")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleSearchPanel(item: MenuItem) {
        if (binding.searchPanel.visibility == View.GONE) {
            closeTitlebarSearchMode(returnFocusToEditor = false)
            binding.apply {
                replaceEditor.setText("")
                searchEditor.setText("")
                editor.searcher.stopSearch()
                searchPanel.visibility = View.VISIBLE
                item.isChecked = true
            }
        } else {
            binding.searchPanel.visibility = View.GONE
            binding.editor.searcher.stopSearch()
            item.isChecked = false
        }
    }

    private fun showTitlebarSearchMode() {
        if (embeddedTerminalHost?.isEmbeddedTerminalWorkspaceVisible() == true) {
            embeddedTerminalHost.hideEmbeddedTerminalWorkspace()
            syncEmbeddedTerminalWorkspaceUi()
            activity.invalidateOptionsMenu()
        }

        binding.searchPanel.visibility = View.GONE
        titlebarSearchModeActive = true
        binding.symbolBar.visibility = View.GONE
        binding.titlebarSearchPanel.visibility = View.VISIBLE
        binding.titlebarSearchEditor.setText("")
        binding.editor.searcher.stopSearch()

        binding.titlebarSearchEditor.requestFocus()
        KeyboardUtils.showSoftKeyboard(activity, binding.titlebarSearchEditor)
        binding.titlebarSearchEditor.postDelayed(
            { KeyboardUtils.showSoftKeyboard(activity, binding.titlebarSearchEditor) },
            120
        )
    }

    private fun closeTitlebarSearchMode(returnFocusToEditor: Boolean): Boolean {
        if (!titlebarSearchModeActive && binding.titlebarSearchPanel.visibility != View.VISIBLE) {
            return false
        }
        titlebarSearchModeActive = false
        binding.titlebarSearchPanel.visibility = View.GONE
        binding.symbolBar.visibility = View.VISIBLE
        binding.editor.searcher.stopSearch()
        binding.titlebarSearchEditor.clearFocus()
        if (returnFocusToEditor) {
            binding.editor.requestFocus()
            KeyboardUtils.showSoftKeyboard(activity, binding.editor)
        }
        return true
    }

    fun onBackPressedCompat(): Boolean {
        if (closeTitlebarSearchMode(returnFocusToEditor = false)) {
            KeyboardUtils.hideSoftKeyboard(activity, binding.titlebarSearchEditor)
            return true
        }
        return false
    }

    private fun openLogs() {
        runCatching {
            openFileInput(LOG_FILE).reader().readText()
        }.onSuccess {
            binding.editor.setText(it)
        }.onFailure {
            toast(it.toString())
        }
    }

    private fun clearLogs() {
        runCatching {
            openFileOutput(LOG_FILE, MODE_PRIVATE)?.use {}
        }.onFailure {
            toast(it.toString())
        }.onSuccess {
            toast(R.string.deleting_log_success)
        }
    }

    fun gotoNext(view: View) {
        try {
            binding.editor.searcher.gotoNext()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        }
    }

    fun gotoPrev(view: View) {
        try {
            binding.editor.searcher.gotoPrevious()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        }
    }

    fun replace(view: View) {
        replaceCurrent(binding.replaceEditor.text.toString())
    }

    fun replaceAll(view: View) {
        replaceAllMatches(binding.replaceEditor.text.toString())
    }

    private fun replaceCurrent(replacement: String) {
        try {
            binding.editor.searcher.replaceCurrentMatch(replacement)
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        }
    }

    private fun replaceAllMatches(replacement: String) {
        try {
            binding.editor.searcher.replaceAll(replacement)
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        }
    }

    fun showSearchOptions(view: View) {
        searchMenu.show()
    }

    fun onLoadTmlResult(result: Uri?) {
        if (result == null) return
        editorEnv.applyTextMateLanguageFromUri(result)
    }

    fun onLoadTmtResult(result: Uri?) {
        if (result == null) return
        editorEnv.applyTextMateThemeFromUri(result)
    }
}
