package com.termux.terminalsessionsurface;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.terminal.TerminalSession;

import java.util.EnumMap;

public final class TerminalSessionBottomHostView extends SessionSwipeFrameLayout {

    public enum ContentKind {
        HIDDEN,
        EXTRA_KEYS,
        TEXT_INPUT,
        TMUX_PANEL
    }

    private final FrameLayout contentContainer;
    private final EnumMap<ContentKind, TerminalSessionBottomUiProvider> providers =
        new EnumMap<>(ContentKind.class);
    private final EnumMap<ContentKind, View> providerViews = new EnumMap<>(ContentKind.class);

    @Nullable private ContentKind activeContentKind;

    public TerminalSessionBottomHostView(@NonNull Context context) {
        this(context, null);
    }

    public TerminalSessionBottomHostView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TerminalSessionBottomHostView(@NonNull Context context,
                                         @Nullable AttributeSet attrs,
                                         int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        contentContainer = new FrameLayout(context);
        contentContainer.setLayoutParams(new LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ));
        addView(contentContainer);

        registerProvider(new ExtraKeysBottomUiProvider());
        registerProvider(new TextInputBottomUiProvider());
        registerProvider(new TmuxControlsBottomUiProvider());
    }

    public void bindContent(@NonNull ContentKind contentKind,
                            @Nullable TerminalSession session,
                            @Nullable ExtraKeysInfo extraKeysInfo,
                            @Nullable ExtraKeysView.IExtraKeysView extraKeysViewClient,
                            @Nullable TerminalSessionBottomActionListener bottomActionListener,
                            boolean buttonTextAllCaps,
                            float toolbarDefaultHeightPx) {
        if (contentKind == ContentKind.HIDDEN) {
            clearActiveContent();
            return;
        }

        TerminalSessionBottomUiProvider provider = providers.get(contentKind);
        if (provider == null) {
            clearActiveContent();
            return;
        }

        View providerView = providerViews.get(contentKind);
        if (providerView == null) {
            providerView = provider.createView(LayoutInflater.from(getContext()), contentContainer);
            providerView.setVisibility(GONE);
            contentContainer.addView(providerView);
            providerViews.put(contentKind, providerView);
        }

        TerminalSessionBottomUiProvider.Binding binding =
            new TerminalSessionBottomUiProvider.Binding(
                session,
                extraKeysInfo,
                extraKeysViewClient,
                bottomActionListener,
                buttonTextAllCaps,
                toolbarDefaultHeightPx
            );
        provider.bind(providerView, binding);

        if (activeContentKind != contentKind) {
            clearActiveContent();
            providerView.setVisibility(VISIBLE);
            activeContentKind = contentKind;
        } else {
            providerView.setVisibility(VISIBLE);
        }
    }

    @Nullable
    public ExtraKeysView getActiveExtraKeysView() {
        if (activeContentKind == null) return null;
        TerminalSessionBottomUiProvider provider = providers.get(activeContentKind);
        View providerView = providerViews.get(activeContentKind);
        if (provider == null || providerView == null) return null;
        return provider.getExtraKeysView(providerView);
    }

    @Nullable
    public EditText getActiveTextInputView() {
        if (activeContentKind == null) return null;
        TerminalSessionBottomUiProvider provider = providers.get(activeContentKind);
        View providerView = providerViews.get(activeContentKind);
        if (provider == null || providerView == null) return null;
        return provider.getTextInputView(providerView);
    }

    @NonNull
    public View getSwipeRegionView() {
        if (activeContentKind == null) return this;
        TerminalSessionBottomUiProvider provider = providers.get(activeContentKind);
        View providerView = providerViews.get(activeContentKind);
        if (provider == null || providerView == null) return this;
        return provider.getSwipeRegionView(providerView);
    }

    @NonNull
    public CharSequence getActiveTextInputText() {
        EditText editText = getActiveTextInputView();
        return editText == null ? "" : TextUtils.concat(editText.getText());
    }

    public void requestActiveTextInputFocus() {
        EditText editText = getActiveTextInputView();
        if (editText != null) {
            editText.requestFocus();
        }
    }

    public void clearActiveTextInputFocus() {
        EditText editText = getActiveTextInputView();
        if (editText != null) {
            editText.clearFocus();
        }
    }

    private void clearActiveContent() {
        if (activeContentKind == null) return;
        clearActiveTextInputFocus();
        View previous = providerViews.get(activeContentKind);
        if (previous != null) {
            previous.setVisibility(GONE);
        }
        activeContentKind = null;
    }

    private void registerProvider(@NonNull TerminalSessionBottomUiProvider provider) {
        providers.put(provider.getContentKind(), provider);
    }
}
