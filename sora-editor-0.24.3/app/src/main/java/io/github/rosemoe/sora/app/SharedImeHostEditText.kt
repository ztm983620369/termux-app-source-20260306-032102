package io.github.rosemoe.sora.app

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatEditText

class SharedImeHostEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    interface Callbacks {
        fun onBackPressedPreIme(event: KeyEvent): Boolean
    }

    var callbacks: Callbacks? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && callbacks?.onBackPressedPreIme(event) == true) {
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
