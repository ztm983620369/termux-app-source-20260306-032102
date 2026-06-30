package org.fossify.filemanager.dialogs

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.termux.ecjbridge.EcjBridgeContract
import com.termux.ecjbridge.EcjProjectDetector
import com.termux.ecjbridge.EcjProjectLauncher
import com.termux.ecjbridge.EcjTemplateCreateRequest
import com.termux.ecjbridge.EcjTemplateScaffold
import com.termux.sessionsync.SessionFileCoordinator
import org.fossify.commons.extensions.createAndroidSAFDirectory
import org.fossify.commons.extensions.createAndroidSAFFile
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getDocumentFile
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.isAValidFilename
import org.fossify.commons.extensions.isRestrictedSAFOnlyRoot
import org.fossify.commons.extensions.needsStupidWritePermissions
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.SimpleActivity
import org.fossify.filemanager.extensions.isPathOnRoot
import org.fossify.filemanager.helpers.RootHelpers
import java.io.File
import java.io.IOException

class CreateNewItemDialog(
    val activity: SimpleActivity,
    val path: String,
    val callback: (success: Boolean, createdPath: String?) -> Unit
) {
    private data class CreateOption(
        val id: String,
        val title: String,
        val requiredPanel: Int,
        val inputHint: String
    )

    private val sessionFileCoordinator = SessionFileCoordinator.getInstance()
    private val typeSelectionUpdaters = ArrayList<() -> Unit>()
    private val appSelectionUpdaters = ArrayList<() -> Unit>()
    private val registryOptions = arrayListOf(
        CreateOption(OPT_FOLDER, "文件夹", PANEL_TEXT_INPUT, "请输入文件夹名称..."),
        CreateOption(OPT_FILE, "空文件", PANEL_TEXT_INPUT, "请输入文件名称..."),
        CreateOption(OPT_ECJ_TEMPLATE, "ECJ 模板", PANEL_TEXT_INPUT, "请输入 ECJ 项目名称..."),
        CreateOption("opt_app", "运行应用 (App)", PANEL_APP_SELECTOR, ""),
        CreateOption("opt_shortcut", "桌面快捷方式", PANEL_TEXT_INPUT, "请输入快捷指令名称..."),
        CreateOption("opt_url", "远程 Web 链接", PANEL_TEXT_INPUT, "请输入 URL 地址 (http://)..."),
        CreateOption("opt_script", "自动化 Shell 脚本", PANEL_TEXT_INPUT, "请输入 .sh 脚本名称..."),
        CreateOption("opt_cloud", "挂载 WebDAV 云盘", PANEL_TEXT_INPUT, "请输入远程挂载点名称...")
    )
    private val appOptions = arrayOf("ecj", "QQ", "微信", "淘宝", "系统设置", "终端模拟器")

    private var currentSelectedOption = registryOptions.first()
    private var selectedAppIndex = 0
    private lateinit var alertDialog: AlertDialog

    init {
        val dialogView = createDialogView()
        alertDialog = activity.getAlertDialogBuilder()
            .setView(dialogView)
            .create()

        alertDialog.setOnShowListener {
            alertDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            alertDialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialogView.post {
                val width = minOf(dp(340), activity.resources.displayMetrics.widthPixels - dp(32))
                alertDialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        alertDialog.show()
    }

    private fun createDialogView(): View {
        val dialogCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(24).toFloat()
            outlineProvider = ViewOutlineProvider.BACKGROUND
            setPadding(0, dp(24), 0, 0)
            isFocusable = true
            isFocusableInTouchMode = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222223"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#38383A"))
            }
        }

        val title = TextView(activity).apply {
            text = "新建"
            setTextColor(Color.parseColor("#F0F0F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(24), 0, dp(24), 0)
        }
        dialogCard.addView(title)

        val dynamicSlot = FrameLayout(activity)
        dialogCard.addView(
            dynamicSlot,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)).apply {
                topMargin = dp(12)
            }
        )

        val inputPanel = createInputField().apply {
            hint = currentSelectedOption.inputHint
        }
        dynamicSlot.addView(
            inputPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
        )

        val appPanel = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(dp(24), dp(4), dp(24), dp(8))
            visibility = View.INVISIBLE
            alpha = 0f
        }
        val appListContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appPanel.addView(
            appListContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        dynamicSlot.addView(
            appPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        appOptions.forEachIndexed { index, appName ->
            appListContainer.addView(createAppCapsule(appName, index, index == appOptions.lastIndex))
        }

        val scrollAndActionFrame = FrameLayout(activity)
        dialogCard.addView(
            scrollAndActionFrame,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        )

        val optionsScroller = object : ScrollView(activity) {
            private val maxHeight = dp(260)

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(70))
        }
        scrollAndActionFrame.addView(
            optionsScroller,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val optionsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        optionsScroller.addView(
            optionsContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        registryOptions.forEach { option ->
            optionsContainer.addView(createExtensibleRadioRow(option, inputPanel, appPanel, dialogCard))
        }

        val actionContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00222223, 0xD9222223.toInt(), 0xFF222223.toInt())
            )
        }
        scrollAndActionFrame.addView(
            actionContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        val btnCancel = createActionButton("取消", "#8E8E93").apply {
            setOnClickListener {
                alertDialog.dismiss()
                callback(false, null)
            }
        }
        actionContainer.addView(btnCancel)
        actionContainer.addView(View(activity), LinearLayout.LayoutParams(dp(4), 1))

        val btnConfirm = createActionButton("确定", "#4B8DF8").apply {
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                confirmSelection(inputPanel)
            }
        }
        actionContainer.addView(btnConfirm)

        refreshTypeUI()
        refreshAppUI()
        inputPanel.post {
            inputPanel.requestFocus()
            showKeyboard(inputPanel)
        }
        return dialogCard
    }

    private fun createExtensibleRadioRow(
        optionModel: CreateOption,
        inputPanel: EditText,
        appPanel: View,
        rootContainer: View
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        }

        val radioIconFrame = FrameLayout(activity)
        val ringBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }
        val outerRing = View(activity).apply {
            background = ringBg
        }
        val dotBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#4B8DF8"))
        }
        val innerDot = View(activity).apply {
            background = dotBg
        }

        val iconSize = dp(20)
        val dotSize = dp(10)
        radioIconFrame.addView(outerRing, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        radioIconFrame.addView(innerDot, FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER))
        row.addView(radioIconFrame, LinearLayout.LayoutParams(iconSize, iconSize))

        val label = TextView(activity).apply {
            text = optionModel.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(12)
            }
        )

        typeSelectionUpdaters.add {
            val isSelected = currentSelectedOption.id == optionModel.id

            ringBg.setStroke(
                dp(if (isSelected) 2 else 1),
                if (isSelected) Color.parseColor("#4B8DF8") else Color.parseColor("#5A5A5C")
            )
            label.setTextColor(if (isSelected) Color.parseColor("#4B8DF8") else Color.parseColor("#8E8E93"))
            label.typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            if (isSelected) {
                innerDot.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300)
                    .setInterpolator(OvershootInterpolator(1.5f))
                    .start()

                if (optionModel.requiredPanel == PANEL_APP_SELECTOR) {
                    hideKeyboard(inputPanel)
                    inputPanel.clearFocus()
                    rootContainer.requestFocus()

                    if (inputPanel.visibility == View.VISIBLE) {
                        inputPanel.animate()
                            .alpha(0f)
                            .setDuration(150)
                            .withEndAction { inputPanel.visibility = View.INVISIBLE }
                            .start()
                    }
                    appPanel.visibility = View.VISIBLE
                    appPanel.animate().alpha(1f).setDuration(250).start()
                } else {
                    if (appPanel.visibility == View.VISIBLE) {
                        appPanel.animate()
                            .alpha(0f)
                            .setDuration(150)
                            .withEndAction { appPanel.visibility = View.INVISIBLE }
                            .start()
                    }
                    inputPanel.visibility = View.VISIBLE
                    inputPanel.animate().alpha(1f).setDuration(250).start()
                    inputPanel.hint = optionModel.inputHint
                    inputPanel.requestFocus()
                }
            } else {
                innerDot.animate()
                    .scaleX(0f)
                    .scaleY(0f)
                    .setDuration(150)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        row.setOnClickListener {
            if (currentSelectedOption.id != optionModel.id) {
                currentSelectedOption = optionModel
                refreshTypeUI()
            }
        }
        return row
    }

    private fun createAppCapsule(appName: String, index: Int, isLast: Boolean): View {
        val capsuleBg = GradientDrawable().apply {
            cornerRadius = dp(19).toFloat()
        }
        val capsule = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(12), 0)
            background = capsuleBg
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(38)
            ).apply {
                rightMargin = if (isLast) 0 else dp(10)
            }
        }

        val nameText = TextView(activity).apply {
            text = appName
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        capsule.addView(
            nameText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        capsule.addView(View(activity), LinearLayout.LayoutParams(dp(14), 1))
        val divider = View(activity)
        capsule.addView(divider, LinearLayout.LayoutParams(dp(1), dp(14)))
        capsule.addView(View(activity), LinearLayout.LayoutParams(dp(14), 1))

        val appIcon = ImageView(activity).apply {
            setImageResource(android.R.drawable.sym_def_app_icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(4).toFloat())
                }
            }
            clipToOutline = true
        }
        capsule.addView(appIcon, LinearLayout.LayoutParams(dp(20), dp(20)))

        appSelectionUpdaters.add {
            val isSelected = selectedAppIndex == index
            if (isSelected) {
                capsuleBg.setColor(Color.parseColor("#15243B"))
                capsuleBg.setStroke(dp(1), Color.parseColor("#4B8DF8"))
                nameText.setTextColor(Color.parseColor("#5A9CF9"))
                divider.setBackgroundColor(Color.parseColor("#2A4B80"))
            } else {
                capsuleBg.setColor(Color.parseColor("#1C1C1E"))
                capsuleBg.setStroke(dp(1), Color.parseColor("#323234"))
                nameText.setTextColor(Color.parseColor("#D0D0D0"))
                divider.setBackgroundColor(Color.parseColor("#3A3A3C"))
            }
        }

        capsule.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> view.animate()
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(120)
                    .setInterpolator(DecelerateInterpolator())
                    .start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            }
            false
        }

        capsule.setOnClickListener {
            if (selectedAppIndex != index) {
                selectedAppIndex = index
                refreshAppUI()
            }
        }

        return capsule
    }

    private fun createInputField(): EditText {
        val inputBg = GradientDrawable().apply {
            setColor(Color.parseColor("#121212"))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.parseColor("#2C2C2E"))
        }
        return EditText(activity).apply {
            setHintTextColor(Color.parseColor("#555555"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = inputBg
            setOnFocusChangeListener { _, hasFocus ->
                inputBg.setStroke(
                    dp(if (hasFocus) 2 else 1),
                    if (hasFocus) Color.parseColor("#4B8DF8") else Color.parseColor("#2C2C2E")
                )
            }
        }
    }

    private fun createActionButton(text: String, colorHex: String): TextView {
        return TextView(activity).apply {
            this.text = text
            setTextColor(Color.parseColor(colorHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#1AFFFFFF")),
                null,
                GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(8).toFloat()
                }
            )
            isClickable = true
        }
    }

    private fun confirmSelection(inputPanel: EditText) {
        when (currentSelectedOption.id) {
            OPT_FOLDER, OPT_FILE -> createSelectedFileSystemItem(inputPanel)
            OPT_ECJ_TEMPLATE -> createEcjTemplate(inputPanel)
            "opt_app" -> {
                val selectedAppName = appOptions.getOrElse(selectedAppIndex) { appOptions.first() }
                if (selectedAppName.equals("ecj", ignoreCase = true)) {
                    launchEcjProject()
                } else {
                    activity.toast("【保留入口】目标App绑定: $selectedAppName")
                }
            }
            else -> {
                activity.toast("该入口已保留，后续接入。")
            }
        }
    }

    private fun createEcjTemplate(inputPanel: EditText) {
        val rawName = inputPanel.text?.toString()?.trim().orEmpty()
        if (rawName.isEmpty()) {
            activity.toast(R.string.empty_name)
            return
        }
        if (!rawName.isAValidFilename()) {
            activity.toast(R.string.invalid_name)
            return
        }

        val safeName = EcjTemplateScaffold.sanitizeProjectName(rawName)
        val newPath = buildChildPath(path, safeName)
        val isVirtualPath = sessionFileCoordinator.isVirtualPath(activity, path)
        val isStaleVirtualPath = sessionFileCoordinator.isStaleVirtualPath(activity, path)
        if (isStaleVirtualPath) {
            activity.showErrorToast("SFTP 会话已变化，请重新选择会话。")
            callback(false, null)
            return
        }
        if (isVirtualPath) {
            activity.showErrorToast("ECJ 模板需要本地可写目录；远程目录后续走专用同步入口。")
            return
        }
        if (activity.needsStupidWritePermissions(newPath) || activity.isRestrictedSAFOnlyRoot(newPath)) {
            activity.showErrorToast("ECJ 模板需要本地可写目录，当前 SAF 位置暂不支持多文件模板原子创建。")
            return
        }
        if (activity.getDoesFilePathExist(newPath)) {
            activity.toast(R.string.name_taken)
            return
        }

        ensureBackgroundThread {
            val result = runCatching {
                EcjTemplateScaffold.createProject(
                    EcjTemplateCreateRequest(
                        parentDirectory = File(path),
                        projectName = safeName,
                        sourceAppPackage = activity.packageName
                    )
                )
            }
            activity.runOnUiThread {
                result
                    .onSuccess { success(it.projectRoot.absolutePath) }
                    .onFailure {
                        showThrowableError(it)
                        callback(false, null)
                    }
            }
        }
    }

    private fun launchEcjProject() {
        val isVirtualPath = sessionFileCoordinator.isVirtualPath(activity, path)
        val isStaleVirtualPath = sessionFileCoordinator.isStaleVirtualPath(activity, path)
        if (isStaleVirtualPath) {
            activity.showErrorToast("SFTP 会话已变化，请重新选择会话。")
            callback(false, null)
            return
        }
        if (isVirtualPath) {
            activity.showErrorToast("ECJ 原生运行需要本地项目目录；远程目录后续走同步运行入口。")
            return
        }

        val currentDirectory = File(path)
        if (!currentDirectory.exists() || !currentDirectory.isDirectory) {
            activity.showErrorToast("当前路径不是本地目录：$path")
            return
        }
        val projectRoot = EcjProjectDetector.findNearestProjectRoot(currentDirectory)
        if (projectRoot == null) {
            activity.showErrorToast("当前路径不属于 ECJ 项目：缺少 ${EcjBridgeContract.PROJECT_CONFIG} 或入口源码。")
            return
        }

        try {
            EcjProjectLauncher.launchProject(activity, projectRoot)
            alertDialog.dismiss()
            callback(false, null)
        } catch (_: ActivityNotFoundException) {
            activity.showErrorToast("未找到 ECJ App：${EcjBridgeContract.ECJ_APP_PACKAGE}")
        } catch (t: Throwable) {
            showThrowableError(t)
        }
    }

    private fun showThrowableError(t: Throwable) {
        if (t is Exception) {
            activity.showErrorToast(t)
        } else {
            activity.showErrorToast(t.message ?: t.toString())
        }
    }

    private fun createSelectedFileSystemItem(inputPanel: EditText) {
        val name = inputPanel.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            activity.toast(R.string.empty_name)
            return
        }
        if (!name.isAValidFilename()) {
            activity.toast(R.string.invalid_name)
            return
        }

        val isDirectory = currentSelectedOption.id == OPT_FOLDER
        val newPath = buildChildPath(path, name)
        val isVirtualPath = sessionFileCoordinator.isVirtualPath(activity, path)
        val isStaleVirtualPath = sessionFileCoordinator.isStaleVirtualPath(activity, path)
        if (isStaleVirtualPath) {
            activity.showErrorToast("SFTP 会话已变化，请重新选择会话。")
            callback(false, null)
            return
        }
        if (!isVirtualPath && activity.getDoesFilePathExist(newPath)) {
            activity.toast(R.string.name_taken)
            return
        }

        if (isVirtualPath) {
            createVirtualItem(path, newPath, name, isDirectory) {
                callback(it, null)
            }
            return
        }

        if (isDirectory) {
            createDirectory(newPath) {
                callback(it, null)
            }
        } else {
            createFile(newPath) {
                callback(it, null)
            }
        }
    }

    private fun createDirectory(path: String, callback: (Boolean) -> Unit) {
        when {
            activity.needsStupidWritePermissions(path) -> activity.handleSAFDialog(path) {
                if (!it) {
                    callback(false)
                    return@handleSAFDialog
                }

                val documentFile = activity.getDocumentFile(path.getParentPath())
                if (documentFile == null) {
                    showCreateError(path, true, "")
                    callback(false)
                    return@handleSAFDialog
                }
                if (documentFile.createDirectory(path.getFilenameFromPath()) != null) {
                    success(path)
                } else {
                    showCreateError(path, true, "")
                    callback(false)
                }
            }

            isRPlus() || !activity.isPathOnRoot(path) -> {
                if (activity.isRestrictedSAFOnlyRoot(path)) {
                    activity.handleAndroidSAFDialog(path) {
                        if (!it) {
                            callback(false)
                            return@handleAndroidSAFDialog
                        }
                        if (activity.createAndroidSAFDirectory(path)) {
                            success(path)
                        } else {
                            showCreateError(path, true, "")
                            callback(false)
                        }
                    }
                } else {
                    if (File(path).mkdirs()) {
                        success(path)
                    } else {
                        showCreateError(path, true, "")
                        callback(false)
                    }
                }
            }

            else -> {
                RootHelpers(activity).createFileFolder(path, false) {
                    if (it) {
                        success(path)
                    } else {
                        callback(false)
                    }
                }
            }
        }
    }

    private fun createFile(path: String, callback: (Boolean) -> Unit) {
        try {
            when {
                activity.isRestrictedSAFOnlyRoot(path) -> {
                    activity.handleAndroidSAFDialog(path) {
                        if (!it) {
                            callback(false)
                            return@handleAndroidSAFDialog
                        }
                        if (activity.createAndroidSAFFile(path)) {
                            success(path)
                        } else {
                            showCreateError(path, false, "")
                            callback(false)
                        }
                    }
                }

                activity.needsStupidWritePermissions(path) -> {
                    activity.handleSAFDialog(path) {
                        if (!it) {
                            callback(false)
                            return@handleSAFDialog
                        }

                        val documentFile = activity.getDocumentFile(path.getParentPath())
                        if (documentFile == null) {
                            showCreateError(path, false, "")
                            callback(false)
                            return@handleSAFDialog
                        }
                        if (documentFile.createFile(path.getMimeType(), path.getFilenameFromPath()) != null) {
                            success(path)
                        } else {
                            showCreateError(path, false, "")
                            callback(false)
                        }
                    }
                }

                isRPlus() || !activity.isPathOnRoot(path) -> {
                    if (File(path).createNewFile()) {
                        success(path)
                    } else {
                        showCreateError(path, false, "")
                        callback(false)
                    }
                }

                else -> {
                    RootHelpers(activity).createFileFolder(path, true) {
                        if (it) {
                            success(path)
                        } else {
                            callback(false)
                        }
                    }
                }
            }
        } catch (exception: IOException) {
            activity.showErrorToast(exception)
            callback(false)
        }
    }

    private fun createVirtualItem(
        parentPath: String,
        newPath: String,
        name: String,
        isDirectory: Boolean,
        callback: (Boolean) -> Unit
    ) {
        ensureBackgroundThread {
            val result = sessionFileCoordinator.createVirtualItem(activity.applicationContext, parentPath, name, isDirectory)
            activity.runOnUiThread {
                if (result.success) {
                    success(newPath)
                } else {
                    showCreateError(newPath, isDirectory, result.messageCn)
                    callback(false)
                }
            }
        }
    }

    private fun buildChildPath(parentPath: String, name: String): String {
        val normalizedParent = parentPath.trimEnd('/')
        return if (normalizedParent.isEmpty() || normalizedParent == "/") {
            "/$name"
        } else {
            "$normalizedParent/$name"
        }
    }

    private fun showCreateError(path: String, isDirectory: Boolean, detail: String) {
        val message = detail.trim().ifEmpty {
            val errorRes = if (isDirectory) R.string.could_not_create_folder else R.string.could_not_create_file
            String.format(activity.getString(errorRes), path)
        }
        activity.showErrorToast(message)
    }

    private fun success(createdPath: String) {
        alertDialog.dismiss()
        callback(true, createdPath)
    }

    private fun showKeyboard(view: View) {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: View) {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun refreshTypeUI() {
        typeSelectionUpdaters.forEach { it.invoke() }
    }

    private fun refreshAppUI() {
        appSelectionUpdaters.forEach { it.invoke() }
    }

    private fun dp(value: Int): Int {
        return (value * activity.resources.displayMetrics.density + 0.5f).toInt()
    }

    companion object {
        private const val PANEL_TEXT_INPUT = 1
        private const val PANEL_APP_SELECTOR = 2
        private const val OPT_FOLDER = "opt_folder"
        private const val OPT_FILE = "opt_file"
        private const val OPT_ECJ_TEMPLATE = "opt_ecj_template"
    }
}
