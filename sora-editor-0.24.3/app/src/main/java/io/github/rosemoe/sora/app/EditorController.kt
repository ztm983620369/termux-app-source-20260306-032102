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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.termux.bridge.FileEditorContract
import com.termux.bridge.FileOpenBridge
import com.termux.bridge.FileOpenListener
import com.termux.bridge.FileOpenRequest
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
import io.github.rosemoe.sora.app.ide.IdeRunManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.tm4e.core.internal.oniguruma.Oniguruma
import java.io.File
import java.io.FileInputStream
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
    private val loadTMTLauncher: ActivityResultLauncher<String>
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
        private const val PREF_KEY_RUN_COMMAND = "pref_run_command"

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
    private lateinit var autoSave: LinuxAutoSaveManager

    private var lastBridgeSeqHandled: Long = 0L
    private var lastOpenRequest: FileOpenRequest? = null
    private var lastOpenAttemptAtMs: Long = 0L
    private var lastOpenOkAtMs: Long = 0L
    private var lastOpenError: String? = null

    private val prefs by lazy { activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

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
        applyEdgeToEdgeForViews(binding.toolbarContainer, binding.root)

        binding.mainBottomBar.visibility = View.INVISIBLE

        // ✅ Fully refactored: ultra-smooth IME bar
        setupImeAccessoryBarController()

        autoSave = LinuxAutoSaveManager(
            context = activity,
            prefs = prefs,
            scope = activity.lifecycleScope,
            editor = binding.editor,
            currentFilePathProvider = { lastOpenRequest?.path }
        )

        val typeface = Typeface.createFromAsset(activity.assets, "JetBrainsMono-Regular.ttf")

        // Setup Listeners
        binding.apply {
            btnGotoPrev.setOnClickListener(::gotoPrev)
            btnGotoNext.setOnClickListener(::gotoNext)
            btnReplace.setOnClickListener(::replace)
            btnReplaceAll.setOnClickListener(::replaceAll)
            searchOptions.setOnClickListener(::showSearchOptions)
        }

        // Configure symbol input view
        val inputView = binding.symbolInput
        inputView.bindEditor(binding.editor)
        inputView.addSymbols(SYMBOLS, SYMBOL_INSERT_TEXT)
        inputView.forEachButton { it.typeface = typeface }

        // Commit search when text changed
        binding.searchEditor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
            override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
            override fun afterTextChanged(editable: Editable) {
                tryCommitSearch()
            }
        })

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
                autoSave.onContentChanged()
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
        applyOpenRequest(FileOpenBridge.getLatestRequest(), "bridge.onCreate")

        updateBtnState()
        editorEnv.applyUserPreferredTheme()
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
        val latestSeq = FileOpenBridge.getLatestSequence()
        if (latestSeq != 0L && latestSeq != lastBridgeSeqHandled) {
            lastBridgeSeqHandled = latestSeq
            applyOpenRequest(FileOpenBridge.getLatestRequest(), "bridge.onResume")
        }
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
        val query = binding.searchEditor.editableText
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

    private fun openDiskFile(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val file = File(path)
            lastOpenAttemptAtMs = System.currentTimeMillis()
            lastOpenError = null

            if (!file.exists()) {
                lastOpenError = "文件不存在: $path"
                withContext(Dispatchers.Main) { toast(lastOpenError ?: "文件不存在") }
                return@launch
            }
            if (!file.isFile) {
                lastOpenError = "不是文件: $path"
                withContext(Dispatchers.Main) { toast(lastOpenError ?: "不是文件") }
                return@launch
            }
            if (!file.canRead()) {
                lastOpenError = "不可读: $path"
                withContext(Dispatchers.Main) { toast(lastOpenError ?: "不可读") }
                return@launch
            }

            val text = runCatching { FileInputStream(file).use { ContentIO.createFrom(it) } }
                .getOrElse {
                    lastOpenError = it.toString()
                    withContext(Dispatchers.Main) { toast(it.toString()) }
                    return@launch
                }

            withContext(Dispatchers.Main) {
                binding.editor.setText(text, null)
                updateBtnState()
                lastOpenOkAtMs = System.currentTimeMillis()
                autoSave.onFileOpenedFromDisk(path)

                // VS Code: auto apply syntax by file name (if enabled)
                vscode.maybeAutoApplyVSCodeSyntaxByFileName(path)
            }
        }
    }

    private fun applyOpenRequest(req: FileOpenRequest?, source: String) {
        if (req == null) return
        lastOpenRequest = req
        val seq = FileOpenBridge.getLatestSequence()
        if (source.startsWith("bridge")) {
            lastBridgeSeqHandled = seq
        }
        title = req.displayName ?: File(req.path).name
        openDiskFile(req.path)
    }

    private val fileOpenListener = FileOpenListener { request ->
        val seq = FileOpenBridge.getLatestSequence()
        if (seq != 0L) lastBridgeSeqHandled = seq
        applyOpenRequest(request, "bridge.callback")
    }

    /**
     * Update buttons state for undo/redo
     */
    private fun updateBtnState() {
        undo?.isEnabled = binding.editor.canUndo()
        redo?.isEnabled = binding.editor.canRedo()
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        // Re-measure bar height on rotation, then resync
        barFullHeightPx = 0
        binding.root.doOnLayout {
            ensureBarMeasured()
            syncToCurrentImeStateNoAnimation()
        }
        editorEnv.applyUserPreferredTheme()
    }

    fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        undo = menu.findItem(R.id.text_undo)
        redo = menu.findItem(R.id.text_redo)
        menu.findItem(R.id.auto_save_enabled)?.isChecked = autoSave.isEnabled()
        menu.findItem(R.id.save_file)?.isEnabled = runCatching {
            val p = lastOpenRequest?.path ?: return@runCatching false
            val f = File(p)
            f.exists() && f.isFile && f.canWrite()
        }.getOrDefault(false)
        return true
    }

    fun onDestroy() {
        fractionAnimator?.cancel()
        fractionAnimator = null
        runCatching { ViewCompat.setWindowInsetsAnimationCallback(window.decorView, null) }
        FileOpenBridge.removeListener(fileOpenListener)
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
                        "Lua LSP（Kotlin）",
                        "Lua LSP（Java）",
                        "Python LSP（电脑端）"
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
                binding.replaceEditor.setText("")
                binding.searchEditor.setText("")
                editor.searcher.stopSearch()
                editor.beginSearchMode()
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
                autoSave.setEnabled(item.isChecked)
                toast(if (item.isChecked) "自动保存：已开启" else "自动保存：已关闭")
            }
            R.id.save_file -> {
                lifecycleScope.launch {
                    val r = autoSave.saveNow("manual")
                    if (r.ok) {
                        toast("保存成功")
                    } else {
                        toast("保存失败：${r.error ?: "unknown"}")
                    }
                }
            }
            R.id.autosave_self_test -> {
                lifecycleScope.launch {
                    val report = autoSave.runSelfTest()
                    AlertDialog.Builder(activity)
                        .setTitle(getString(R.string.autosave_self_test))
                        .setMessage(report)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.copy_text) { _, _ ->
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("autosave-selftest", report))
                            toast("已复制")
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

            R.id.run_project -> {
                val path = lastOpenRequest?.path
                if (path.isNullOrBlank()) {
                    toast("未打开文件")
                    return true
                }
                val command = prefs.getString(PREF_KEY_RUN_COMMAND, "").orEmpty().trim()
                if (command.isBlank()) {
                    toast(getString(R.string.run_command_empty))
                    showRunCommandSettingsDialog()
                    return true
                }
                lifecycleScope.launch {
                    val save = autoSave.saveNow("run")
                    if (!save.ok) {
                        toast("保存失败：${save.error ?: "unknown"}")
                        return@launch
                    }
                    val workdir = File(path).parentFile?.absolutePath ?: File(path).absoluteFile.parentFile?.absolutePath.orEmpty()
                    if (workdir.isBlank()) {
                        toast("无法确定工作目录")
                        return@launch
                    }
                    IdeRunManager.launchCustomCommandInTerminal(
                        context = activity,
                        command = command,
                        workdir = workdir,
                        label = "run-custom"
                    )
                    sendBroadcast(
                        Intent("com.termux.app.action.SWITCH_TAB")
                            .setPackage(com.termux.shared.termux.TermuxConstants.TERMUX_PACKAGE_NAME)
                            .putExtra("tab", "terminal")
                    )
                    toast("运行：$command")
                }
                return true
            }

            R.id.run_command_settings -> {
                showRunCommandSettingsDialog()
                return true
            }
        }
        return false
    }

    private fun showRunCommandSettingsDialog() {
        val input = EditText(activity).apply {
            setText(prefs.getString(PREF_KEY_RUN_COMMAND, "").orEmpty())
            hint = getString(R.string.run_command_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setSingleLine(false)
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(activity)
            .setTitle(getString(R.string.run_command_title))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = input.text?.toString().orEmpty()
                prefs.edit().putString(PREF_KEY_RUN_COMMAND, v).apply()
                toast(getString(R.string.run_command_saved))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

            appendLine("autosave.enabled=${autoSave.isEnabled()}")
            appendLine("autosave.note=use menu Debug -> ${getString(R.string.autosave_self_test)}")
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
            .setTitle("编辑器自测")
            .setMessage(text)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("editor_self_test", text))
                toast("已复制")
            }
            .setNeutralButton("重载") { _, _ ->
                val latest = FileOpenBridge.getLatestRequest()
                if (latest != null) applyOpenRequest(latest, "bridge.selftest.reload") else toast("无文件请求")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleSearchPanel(item: MenuItem) {
        if (binding.searchPanel.visibility == View.GONE) {
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
        try {
            binding.editor.searcher.replaceCurrentMatch(binding.replaceEditor.text.toString())
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        }
    }

    fun replaceAll(view: View) {
        try {
            binding.editor.searcher.replaceAll(binding.replaceEditor.text.toString())
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
