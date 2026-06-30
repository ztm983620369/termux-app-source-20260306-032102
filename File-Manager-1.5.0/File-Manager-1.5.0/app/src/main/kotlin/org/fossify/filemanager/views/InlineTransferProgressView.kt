package org.fossify.filemanager.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.filemanager.helpers.ActiveTransferStatus
import org.fossify.filemanager.helpers.RowTransferState

class InlineTransferProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bounds = RectF()
    private val textBounds = Rect()

    private val primaryColor = context.getProperPrimaryColor()
    private val backgroundColor = context.getProperBackgroundColor()
    private val textColor = context.getProperTextColor()
    private val successColor = Color.rgb(46, 125, 50)
    private val warningColor = Color.rgb(245, 124, 0)
    private val errorColor = Color.rgb(198, 40, 40)
    private val cancelledColor = textColor.adjustAlpha(0.55f)

    private var state: RowTransferState? = null
    private var indeterminatePhase = 0f

    init {
        setWillNotDraw(false)
        visibility = GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bind(newState: RowTransferState?) {
        state = newState
        if (newState == null) {
            visibility = GONE
            contentDescription = null
            return
        }

        visibility = VISIBLE
        contentDescription = buildContentDescription(newState)
        invalidate()
        if (shouldAnimate(newState)) {
            postInvalidateDelayed(90L)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = state ?: return
        val size = width.coerceAtMost(height).toFloat()
        if (size <= 0f) return

        val stroke = dp(3).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val radius = size / 2f - stroke / 2f
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        fillPaint.color = backgroundColor.adjustAlpha(0.92f)
        canvas.drawCircle(cx, cy, radius - stroke / 2f, fillPaint)

        ringPaint.strokeWidth = stroke
        ringPaint.color = textColor.adjustAlpha(0.18f)
        canvas.drawArc(bounds, -90f, 360f, false, ringPaint)

        ringPaint.color = colorFor(current.status)
        val progress = current.percent.coerceIn(-1, 100)
        if (progress >= 0) {
            canvas.drawArc(bounds, -90f, progress * 3.6f, false, ringPaint)
        } else {
            indeterminatePhase = (indeterminatePhase + 9f) % 360f
            canvas.drawArc(bounds, indeterminatePhase - 90f, 96f, false, ringPaint)
            if (shouldAnimate(current)) {
                postInvalidateDelayed(90L)
            }
        }

        when (current.status) {
            ActiveTransferStatus.SUCCESS -> drawCheck(canvas, cx, cy, radius)
            ActiveTransferStatus.PARTIAL -> drawExclamation(canvas, cx, cy, radius)
            ActiveTransferStatus.FAILED -> drawCross(canvas, cx, cy, radius)
            ActiveTransferStatus.CANCELLED -> drawMinus(canvas, cx, cy, radius)
            ActiveTransferStatus.CANCELLING -> drawPercentOrDots(canvas, current, cx, cy, radius, "…")
            else -> drawPercentOrDots(canvas, current, cx, cy, radius, null)
        }
    }

    private fun drawPercentOrDots(canvas: Canvas, current: RowTransferState, cx: Float, cy: Float, radius: Float, fallback: String?) {
        val label = if (current.percent >= 0) {
            current.percent.coerceIn(0, 100).toString()
        } else {
            fallback ?: ""
        }
        if (label.isBlank()) return
        textPaint.color = textColor
        textPaint.textSize = (radius * 0.72f).coerceAtLeast(dp(9).toFloat())
        textPaint.getTextBounds(label, 0, label.length, textBounds)
        canvas.drawText(label, cx, cy - textBounds.exactCenterY(), textPaint)
    }

    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        markPaint.color = successColor
        markPaint.strokeWidth = dp(2.4f)
        canvas.drawLine(cx - radius * 0.42f, cy, cx - radius * 0.12f, cy + radius * 0.30f, markPaint)
        canvas.drawLine(cx - radius * 0.12f, cy + radius * 0.30f, cx + radius * 0.46f, cy - radius * 0.34f, markPaint)
    }

    private fun drawCross(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        markPaint.color = errorColor
        markPaint.strokeWidth = dp(2.3f)
        canvas.drawLine(cx - radius * 0.34f, cy - radius * 0.34f, cx + radius * 0.34f, cy + radius * 0.34f, markPaint)
        canvas.drawLine(cx + radius * 0.34f, cy - radius * 0.34f, cx - radius * 0.34f, cy + radius * 0.34f, markPaint)
    }

    private fun drawMinus(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        markPaint.color = cancelledColor
        markPaint.strokeWidth = dp(2.3f)
        canvas.drawLine(cx - radius * 0.38f, cy, cx + radius * 0.38f, cy, markPaint)
    }

    private fun drawExclamation(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        markPaint.color = warningColor
        markPaint.strokeWidth = dp(2.2f)
        canvas.drawLine(cx, cy - radius * 0.40f, cx, cy + radius * 0.12f, markPaint)
        fillPaint.color = warningColor
        canvas.drawCircle(cx, cy + radius * 0.40f, dp(1.6f), fillPaint)
    }

    private fun buildContentDescription(current: RowTransferState): String {
        val progress = if (current.percent >= 0) " ${current.percent.coerceIn(0, 100)}%" else ""
        val counts = if (current.totalFiles > 0) "${current.completedFiles}/${current.totalFiles}" else ""
        return when (current.status) {
            ActiveTransferStatus.PREPARING -> "准备下载"
            ActiveTransferStatus.RUNNING -> "正在下载$progress${if (counts.isNotBlank()) "，$counts" else ""}"
            ActiveTransferStatus.CANCELLING -> "正在取消下载"
            ActiveTransferStatus.SUCCESS -> "下载完成"
            ActiveTransferStatus.PARTIAL -> "下载部分完成${if (counts.isNotBlank()) "，$counts" else ""}"
            ActiveTransferStatus.FAILED -> "下载失败"
            ActiveTransferStatus.CANCELLED -> "下载已取消"
        }
    }

    private fun shouldAnimate(current: RowTransferState): Boolean {
        return current.status == ActiveTransferStatus.PREPARING ||
            current.status == ActiveTransferStatus.RUNNING ||
            current.status == ActiveTransferStatus.CANCELLING
    }

    private fun colorFor(status: ActiveTransferStatus): Int {
        return when (status) {
            ActiveTransferStatus.SUCCESS -> successColor
            ActiveTransferStatus.PARTIAL -> warningColor
            ActiveTransferStatus.FAILED -> errorColor
            ActiveTransferStatus.CANCELLED -> cancelledColor
            ActiveTransferStatus.CANCELLING -> cancelledColor
            else -> primaryColor
        }
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value + 0.5f).toInt()
    private fun dp(value: Float): Float = resources.displayMetrics.density * value
}
