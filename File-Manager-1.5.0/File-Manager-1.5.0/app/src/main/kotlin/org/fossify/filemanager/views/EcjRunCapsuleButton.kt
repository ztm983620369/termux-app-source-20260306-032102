package org.fossify.filemanager.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.CornerPathEffect
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.termux.ecjbridge.EcjBridgeContract
import org.fossify.filemanager.R

class EcjRunCapsuleButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(36)
        setPadding(dp(16), 0, dp(16), 0)
        background = createCapsuleBackground()
        isClickable = true
        isFocusable = true
        elevation = dp(8).toFloat()
        outlineProvider = ViewOutlineProvider.BACKGROUND
        contentDescription = context.getString(R.string.run_ecj_project)

        addView(
            View(context).apply {
                background = PlayDrawable(context)
            },
            LayoutParams(dp(24), dp(24))
        )
        addView(View(context), LayoutParams(dp(8), 1))
        addView(
            TextView(context).apply {
                text = "运行"
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
        addView(View(context), LayoutParams(dp(16), 1))
        addView(
            View(context).apply {
                setBackgroundColor(Color.parseColor("#3A3A3C"))
            },
            LayoutParams(dp(1), dp(14))
        )
        addView(View(context), LayoutParams(dp(16), 1))
        addView(
            ImageView(context).apply {
                setImageDrawable(resolveEcjAppIcon(context))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#222222"))
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(4).toFloat())
                    }
                }
                clipToOutline = true
            },
            LayoutParams(dp(24), dp(24))
        )

        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(120).start()
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            }
            false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            minimumHeight = dp(36)
        }
    }

    private fun createCapsuleBackground(): Drawable {
        val capsule = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#28282A"), Color.parseColor("#161618"))
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.parseColor("#363638"))
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#444444")),
            capsule,
            capsule
        )
    }

    private fun resolveEcjAppIcon(context: Context): Drawable? {
        return runCatching {
            context.packageManager.getApplicationIcon(EcjBridgeContract.ECJ_APP_PACKAGE)
        }.getOrElse {
            ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
        }
    }

    private fun dp(value: Int): Int {
        return (resources.displayMetrics.density * value + 0.5f).toInt()
    }

    private class PlayDrawable(context: Context) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#32D74B")
            style = Paint.Style.FILL
            pathEffect = CornerPathEffect(4f * density)
        }
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val bounds: Rect = bounds
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()

            path.reset()
            path.moveTo(0f, height * 0.05f)
            path.lineTo(width * 0.90f, height * 0.5f)
            path.lineTo(0f, height * 0.95f)
            path.close()
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
