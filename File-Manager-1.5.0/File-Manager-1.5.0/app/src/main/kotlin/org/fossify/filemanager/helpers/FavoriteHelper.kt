package org.fossify.filemanager.helpers

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.termux.sessionsync.SessionFileCoordinator
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.humanizePath
import org.fossify.commons.extensions.toast
import org.fossify.filemanager.R
import org.fossify.filemanager.extensions.config

object FavoriteHelper {

    fun displayPath(context: Context, path: String): String {
        val coordinator = SessionFileCoordinator.getInstance()
        return if (coordinator.isVirtualPath(context, path)) {
            coordinator.getDisplayPath(context, path).takeIf { it.isNotBlank() } ?: path
        } else {
            context.humanizePath(path)
        }
    }

    fun displayTitle(context: Context, path: String): String {
        return context.config.getFavoriteRemark(path) ?: displayPath(context, path)
    }

    fun showAddFavoriteDialog(activity: BaseSimpleActivity, onConfirm: (remark: String) -> Unit) {
        val margin = activity.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
        val remarkInput = EditText(activity).apply {
            hint = activity.getString(R.string.favorite_remark_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
        }

        val inputContainer = FrameLayout(activity).apply {
            setPadding(margin, margin / 2, margin, 0)
            addView(
                remarkInput,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
            )
        }

        val dialog = activity.getAlertDialogBuilder()
            .setTitle(org.fossify.commons.R.string.add_to_favorites)
            .setMessage(R.string.favorite_remark_message)
            .setView(inputContainer)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun submit(dialogInterface: AlertDialog) {
            val remark = remarkInput.text.toString().trim()
            if (remark.isEmpty()) {
                activity.toast(R.string.favorite_remark_required)
                return
            }
            onConfirm(remark)
            dialogInterface.dismiss()
        }

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.isEnabled = false
            positiveButton.setOnClickListener {
                submit(dialog)
            }
            remarkInput.doAfterTextChanged { editable ->
                positiveButton.isEnabled = editable?.toString()?.trim()?.isNotEmpty() == true
            }
            remarkInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submit(dialog)
                    true
                } else {
                    false
                }
            }
            remarkInput.requestFocus()
        }

        dialog.show()
    }
}
