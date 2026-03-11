package com.termux.terminalsessionsurface;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.termux.shared.termux.extrakeys.ExtraKeysView;

final class ExtraKeysBottomUiProvider implements TerminalSessionBottomUiProvider {

    @NonNull
    @Override
    public TerminalSessionBottomHostView.ContentKind getContentKind() {
        return TerminalSessionBottomHostView.ContentKind.EXTRA_KEYS;
    }

    @NonNull
    @Override
    public View createView(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
        return inflater.inflate(R.layout.view_terminal_session_surface_extra_keys, parent, false);
    }

    @Override
    public void bind(@NonNull View view, @NonNull Binding binding) {
        ExtraKeysView extraKeysView = (ExtraKeysView) view;
        extraKeysView.setExtraKeysViewClient(binding.extraKeysViewClient);
        extraKeysView.setButtonTextAllCaps(binding.buttonTextAllCaps);
        if (binding.extraKeysInfo != null) {
            extraKeysView.reload(binding.extraKeysInfo, binding.toolbarDefaultHeightPx);
        } else {
            extraKeysView.removeAllViews();
        }
    }

    @NonNull
    @Override
    public ExtraKeysView getExtraKeysView(@NonNull View view) {
        return (ExtraKeysView) view;
    }
}
