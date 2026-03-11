package com.termux.terminalsessionsurface;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.annotation.NonNull;

import com.termux.terminal.TerminalSession;

final class TextInputBottomUiProvider implements TerminalSessionBottomUiProvider {

    @NonNull
    @Override
    public TerminalSessionBottomHostView.ContentKind getContentKind() {
        return TerminalSessionBottomHostView.ContentKind.TEXT_INPUT;
    }

    @NonNull
    @Override
    public View createView(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
        return inflater.inflate(R.layout.view_terminal_session_surface_text_input, parent, false);
    }

    @Override
    public void bind(@NonNull View view, @NonNull Binding binding) {
        EditText editText = (EditText) view;
        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEND &&
                (event == null || event.getKeyCode() != KeyEvent.KEYCODE_ENTER)) {
                return false;
            }

            TerminalSession session = binding.session;
            if (session != null && session.isRunning()) {
                String textToSend = editText.getText().toString();
                if (textToSend.length() == 0) textToSend = "\r";
                session.write(textToSend);
            }
            editText.setText("");
            return true;
        });
    }

    @NonNull
    @Override
    public EditText getTextInputView(@NonNull View view) {
        return (EditText) view;
    }
}
