package org.fossify.filemanager.helpers

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.helpers.getFilePlaceholderDrawables
import org.fossify.filemanager.R
import org.fossify.filemanager.databinding.DialogTransferProgressCardBinding
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class TransferProgressWindow(
    private val activity: Activity,
    title: String,
    private val cancelLabel: String = "取消",
    initialFileName: String = "",
    private val mode: ActiveTransferMode = ActiveTransferMode.NORMAL_DOWNLOAD,
    private val isFolderTransfer: Boolean = false,
    @DrawableRes private val fallbackIconRes: Int = org.fossify.commons.R.drawable.ic_file_generic,
    private val onCancel: () -> Unit
) {
    private val cancelled = AtomicBoolean(false)
    private val tracker = TransferProgressFormatter()
    private val primaryColor = activity.getProperPrimaryColor()
    private val backgroundColor = activity.getProperBackgroundColor()
    private val textColor = activity.getProperTextColor()
    private val secondaryTextColor = blend(textColor, backgroundColor, 0.62f)
    private val cardColor = blend(primaryColor, backgroundColor, 0.12f)
    private val borderColor = blend(textColor, backgroundColor, 0.18f)
    private val iconHolderColor = blend(primaryColor, backgroundColor, 0.18f)
    private val fileDrawables = getFilePlaceholderDrawables(activity)
    private val genericFileDrawable = ContextCompat.getDrawable(activity, fallbackIconRes)
        ?: ContextCompat.getDrawable(activity, org.fossify.commons.R.drawable.ic_file_generic)

    private var dialog: AlertDialog? = null
    private var binding: DialogTransferProgressCardBinding? = null
    private var lastProgress = TransferProgressState(
        phaseLabel = title,
        currentFile = initialFileName.ifBlank { activity.getString(R.string.transfer_preparing) },
        totalFiles = 1,
        completedFiles = 0,
        failedFiles = 0,
        totalBytes = 0L,
        transferredBytes = 0L,
        currentFileTransferred = 0L,
        currentFileSize = 0L,
        speedBytesPerSecond = 0L
    )

    val isCancelled: Boolean
        get() = cancelled.get()

    fun show() {
        activity.runOnUiThread {
            if (!canTouchUi()) return@runOnUiThread
            ensureDialog()
            bind(lastProgress)
        }
    }

    fun updateDownload(
        phaseLabel: String,
        currentFile: String,
        completedFiles: Int,
        failedFiles: Int,
        totalFiles: Int,
        transferredBytes: Long,
        totalBytes: Long,
        currentFileTransferred: Long,
        currentFileSize: Long,
        detailMessage: String = "",
        force: Boolean = false
    ): Boolean {
        val speedBytesPerSecond = tracker.updateAndGetSpeed(transferredBytes)
        if (!force && !tracker.shouldRefresh(totalBytes, transferredBytes)) {
            return false
        }

        val state = TransferProgressState(
            phaseLabel = phaseLabel,
            currentFile = currentFile.ifBlank { lastProgress.currentFile.ifBlank { activity.getString(R.string.transfer_preparing) } },
            totalFiles = totalFiles,
            completedFiles = completedFiles,
            failedFiles = failedFiles,
            totalBytes = totalBytes,
            transferredBytes = transferredBytes,
            currentFileTransferred = currentFileTransferred,
            currentFileSize = currentFileSize,
            speedBytesPerSecond = speedBytesPerSecond,
            detailMessage = detailMessage
        )
        activity.runOnUiThread {
            if (canTouchUi()) {
                ensureDialog()
                bind(state)
            }
        }
        return true
    }

    fun updateMessage(message: String) {
        activity.runOnUiThread {
            if (!canTouchUi()) return@runOnUiThread
            lastProgress = lastProgress.copy(detailMessage = message)
            binding?.transferProgressStatus?.text = message
            if (cancelled.get()) {
                binding?.transferProgressCancel?.apply {
                    isEnabled = false
                    alpha = 0.45f
                }
            }
        }
    }

    fun dismiss() {
        activity.runOnUiThread {
            try {
                dialog?.dismiss()
            } catch (_: Exception) {
            } finally {
                dialog = null
                binding = null
            }
        }
    }

    private fun ensureDialog() {
        val currentDialog = dialog
        if (currentDialog != null && currentDialog.isShowing && binding != null) {
            return
        }

        val newBinding = DialogTransferProgressCardBinding.inflate(activity.layoutInflater)
        binding = newBinding
        applyTheme(newBinding)
        configureCancelButton(newBinding)

        dialog = activity.getAlertDialogBuilder()
            .setView(newBinding.root)
            .create()
            .apply {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
                setOnDismissListener {
                    if (dialog === this) {
                        dialog = null
                        binding = null
                    }
                }
                if (!activity.isFinishing && !activity.isDestroyed) {
                    show()
                    window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                }
            }
    }

    private fun applyTheme(binding: DialogTransferProgressCardBinding) {
        binding.transferProgressCard.background = roundedRect(cardColor, dp(22).toFloat(), borderColor, dp(1.2f).toInt())
        binding.transferProgressIconHolder.background = roundedRect(iconHolderColor, dp(18).toFloat(), Color.TRANSPARENT, 0)
        binding.transferProgressBadge.background = roundedRect(primaryColor, dp(999).toFloat(), Color.TRANSPARENT, 0)
        binding.transferProgressDivider.setBackgroundColor(borderColor)
        binding.transferProgressCancel.background = roundedRect(primaryColor.adjustAlpha(0.13f), dp(18).toFloat(), primaryColor.adjustAlpha(0.22f), dp(1))

        binding.transferProgressBar.applyProgressColors()
        binding.transferProgressIndeterminateBar.applyProgressColors()

        binding.transferProgressTitle.setTextColor(textColor)
        binding.transferProgressFileName.setTextColor(secondaryTextColor)
        binding.transferProgressBadge.setTextColor(primaryColor.getContrastColor())
        binding.transferProgressPercent.setTextColor(primaryColor)
        binding.transferProgressStatus.setTextColor(secondaryTextColor)
        binding.transferProgressCancel.setTextColor(primaryColor)

        bindInfoRow(binding.transferProgressFilesLabel, binding.transferProgressFilesValue)
        bindInfoRow(binding.transferProgressSizeLabel, binding.transferProgressSizeValue)
        bindInfoRow(binding.transferProgressSpeedLabel, binding.transferProgressSpeedValue)
        bindInfoRow(binding.transferProgressFailedLabel, binding.transferProgressFailedValue)
    }

    private fun configureCancelButton(binding: DialogTransferProgressCardBinding) {
        binding.transferProgressCancel.text = cancelLabel
        binding.transferProgressCancel.setOnClickListener {
            if (cancelled.compareAndSet(false, true)) {
                onCancel()
                binding.transferProgressCancel.isEnabled = false
                binding.transferProgressCancel.alpha = 0.45f
                binding.transferProgressStatus.text = activity.getString(R.string.transfer_cancelling)
            }
        }
    }

    private fun bind(state: TransferProgressState) {
        lastProgress = state
        val progressBinding = binding ?: return
        val percent = state.percent()
        val fileLabel = cleanDisplayFileName(state.currentFile)
        val iconName = fileLabel.ifBlank { state.currentFile }

        progressBinding.transferProgressTitle.text = state.phaseLabel
        progressBinding.transferProgressFileName.text = fileLabel.ifBlank { activity.getString(R.string.transfer_preparing) }
        progressBinding.transferProgressIcon.setImageDrawable(resolveFileIcon(iconName))
        progressBinding.transferProgressBadge.text = badgeText()
        progressBinding.transferProgressPercent.text = if (percent >= 0) "$percent%" else activity.getString(R.string.transfer_percent_unknown)
        progressBinding.transferProgressFilesValue.text = buildFileCountText(state)
        progressBinding.transferProgressSizeValue.text = buildSizeText(state)
        progressBinding.transferProgressSpeedValue.text = buildSpeedText(state)
        progressBinding.transferProgressStatus.text = buildStatusText(state)

        progressBinding.transferProgressFailedRow.visibility = if (state.failedFiles > 0) View.VISIBLE else View.GONE
        progressBinding.transferProgressFailedValue.text = state.failedFiles.toString()

        bindProgress(progressBinding, percent)
    }

    private fun bindProgress(binding: DialogTransferProgressCardBinding, percent: Int) {
        if (percent >= 0) {
            binding.transferProgressIndeterminateBar.visibility = View.GONE
            binding.transferProgressBar.visibility = View.VISIBLE
            binding.transferProgressBar.progress = percent.coerceIn(0, 100)
        } else {
            binding.transferProgressBar.visibility = View.GONE
            binding.transferProgressIndeterminateBar.visibility = View.VISIBLE
        }
    }

    private fun buildFileCountText(state: TransferProgressState): String {
        val finishedCount = state.completedFiles + state.failedFiles
        val total = state.totalFiles.coerceAtLeast(finishedCount).coerceAtLeast(1)
        return "$finishedCount / $total"
    }

    private fun buildSizeText(state: TransferProgressState): String {
        if (mode == ActiveTransferMode.REMOTE_DELETE) {
            val finishedCount = state.completedFiles + state.failedFiles
            val total = state.totalFiles.coerceAtLeast(finishedCount).coerceAtLeast(1)
            return "$finishedCount / $total 项"
        }
        return if (state.totalBytes > 0L) {
            "${state.transferredBytes.formatSize()} / ${state.totalBytes.formatSize()}"
        } else {
            "${state.transferredBytes.formatSize()} / ?"
        }
    }

    private fun buildSpeedText(state: TransferProgressState): String {
        if (mode == ActiveTransferMode.REMOTE_DELETE) {
            return activity.getString(if (cancelled.get()) R.string.transfer_cancelling else R.string.transfer_running)
        }
        return if (state.speedBytesPerSecond > 0L) {
            "${state.speedBytesPerSecond.formatSize()}/s"
        } else {
            activity.getString(R.string.transfer_percent_unknown)
        }
    }

    private fun buildStatusText(state: TransferProgressState): String {
        if (cancelled.get()) {
            return activity.getString(R.string.transfer_cancelling)
        }
        if (state.detailMessage.isNotBlank()) {
            return state.detailMessage
        }
        val hasStarted = state.transferredBytes > 0L || state.completedFiles > 0 || state.failedFiles > 0
        return activity.getString(if (hasStarted) R.string.transfer_running else R.string.transfer_preparing)
    }

    private fun badgeText(): String {
        return when {
            mode == ActiveTransferMode.REMOTE_DELETE -> "删除"
            mode == ActiveTransferMode.APK_DOWNLOAD -> activity.getString(R.string.transfer_badge_apk)
            isFolderTransfer -> activity.getString(R.string.transfer_badge_folder)
            else -> activity.getString(R.string.transfer_badge_file)
        }
    }

    private fun bindInfoRow(label: android.widget.TextView, value: android.widget.TextView) {
        label.setTextColor(secondaryTextColor)
        value.setTextColor(textColor)
        value.typeface = Typeface.DEFAULT
    }

    private fun LinearProgressIndicator.applyProgressColors() {
        setIndicatorColor(primaryColor)
        trackColor = primaryColor.adjustAlpha(0.18f)
    }

    private fun resolveFileIcon(fileName: String): Drawable? {
        val extension = cleanDisplayFileName(fileName)
            .substringAfterLast('.', "")
            .lowercase(Locale.getDefault())
        val drawable = fileDrawables[extension] ?: genericFileDrawable
        return drawable?.constantState?.newDrawable(activity.resources)?.mutate()
    }

    private fun cleanDisplayFileName(raw: String): String {
        var value = raw.substringAfterLast('/').trim()
        val suffixIndex = value.indexOf('（')
        if (suffixIndex > 0) {
            value = value.substring(0, suffixIndex).trim()
        }
        return value
    }

    private fun canTouchUi(): Boolean = !activity.isDestroyed && !activity.isFinishing

    private fun dp(value: Int): Int = (activity.resources.displayMetrics.density * value + 0.5f).toInt()
    private fun dp(value: Float): Float = activity.resources.displayMetrics.density * value

    private fun roundedRect(color: Int, radius: Float, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
            if (strokeWidth > 0) {
                setStroke(strokeWidth, strokeColor)
            }
        }
    }

    private fun blend(foreground: Int, background: Int, ratio: Float): Int {
        return ColorUtils.blendARGB(background, foreground, ratio.coerceIn(0f, 1f))
    }
}

data class TransferProgressState(
    val phaseLabel: String,
    val currentFile: String,
    val totalFiles: Int,
    val completedFiles: Int,
    val failedFiles: Int,
    val totalBytes: Long,
    val transferredBytes: Long,
    val currentFileTransferred: Long,
    val currentFileSize: Long,
    val speedBytesPerSecond: Long,
    val detailMessage: String = ""
) {
    fun percent(): Int {
        return when {
            totalBytes > 0L -> ((transferredBytes * 100L) / totalBytes).coerceIn(0L, 100L).toInt()
            currentFileSize > 0L -> ((currentFileTransferred * 100L) / currentFileSize).coerceIn(0L, 100L).toInt()
            else -> -1
        }
    }
}

class TransferProgressFormatter {
    private var lastUiUpdateAt = 0L
    private var lastSpeedAt = 0L
    private var lastSpeedBytes = 0L
    private var lastComputedSpeed = 0L

    fun updateAndGetSpeed(transferredBytes: Long): Long {
        val now = SystemClock.elapsedRealtime()
        if (lastSpeedAt == 0L) {
            lastSpeedAt = now
            lastSpeedBytes = transferredBytes
            lastComputedSpeed = 0L
            return lastComputedSpeed
        }

        val deltaMs = now - lastSpeedAt
        if (deltaMs >= 260L || transferredBytes < lastSpeedBytes) {
            val deltaBytes = transferredBytes - lastSpeedBytes
            lastComputedSpeed = if (deltaMs > 0L && deltaBytes > 0L) {
                deltaBytes * 1000L / deltaMs
            } else {
                0L
            }
            lastSpeedAt = now
            lastSpeedBytes = transferredBytes
        }
        return lastComputedSpeed
    }

    fun shouldRefresh(totalBytes: Long, transferredBytes: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiUpdateAt < 100L && transferredBytes < totalBytes) {
            return false
        }
        lastUiUpdateAt = now
        return true
    }
}
