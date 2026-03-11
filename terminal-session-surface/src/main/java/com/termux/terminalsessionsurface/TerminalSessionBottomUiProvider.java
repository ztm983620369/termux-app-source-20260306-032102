package com.termux.terminalsessionsurface;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.terminal.TerminalSession;

interface TerminalSessionBottomUiProvider {

    @NonNull
    TerminalSessionBottomHostView.ContentKind getContentKind();

    @NonNull
    View createView(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent);

    void bind(@NonNull View view, @NonNull Binding binding);

    @Nullable
    default ExtraKeysView getExtraKeysView(@NonNull View view) {
        return null;
    }

    @Nullable
    default EditText getTextInputView(@NonNull View view) {
        return null;
    }

    @NonNull
    default View getSwipeRegionView(@NonNull View view) {
        return view;
    }

    final class Binding {
        @Nullable public final TerminalSession session;
        @Nullable public final ExtraKeysInfo extraKeysInfo;
        @Nullable public final ExtraKeysView.IExtraKeysView extraKeysViewClient;
        @Nullable public final TerminalSessionBottomActionListener bottomActionListener;
        public final boolean buttonTextAllCaps;
        public final float toolbarDefaultHeightPx;

        Binding(@Nullable TerminalSession session,
                @Nullable ExtraKeysInfo extraKeysInfo,
                @Nullable ExtraKeysView.IExtraKeysView extraKeysViewClient,
                @Nullable TerminalSessionBottomActionListener bottomActionListener,
                boolean buttonTextAllCaps,
                float toolbarDefaultHeightPx) {
            this.session = session;
            this.extraKeysInfo = extraKeysInfo;
            this.extraKeysViewClient = extraKeysViewClient;
            this.bottomActionListener = bottomActionListener;
            this.buttonTextAllCaps = buttonTextAllCaps;
            this.toolbarDefaultHeightPx = toolbarDefaultHeightPx;
        }
    }
}
