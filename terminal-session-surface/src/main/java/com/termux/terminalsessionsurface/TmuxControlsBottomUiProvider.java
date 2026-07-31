package com.termux.terminalsessionsurface;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;

final class TmuxControlsBottomUiProvider implements TerminalSessionBottomUiProvider {

    @NonNull
    @Override
    public TerminalSessionBottomHostView.ContentKind getContentKind() {
        return TerminalSessionBottomHostView.ContentKind.TMUX_PANEL;
    }

    @NonNull
    @Override
    public View createView(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
        return inflater.inflate(R.layout.view_terminal_session_surface_tmux_controls, parent, false);
    }

    @Override
    public void bind(@NonNull View view, @NonNull Binding binding) {
        bindAction(view, R.id.tmux_action_display_panes, binding, TerminalSessionBottomTmuxAction.DISPLAY_PANES);
        bindAction(view, R.id.tmux_action_split_vertical, binding, TerminalSessionBottomTmuxAction.SPLIT_VERTICAL);
        bindAction(view, R.id.tmux_action_split_horizontal, binding, TerminalSessionBottomTmuxAction.SPLIT_HORIZONTAL);
        bindAction(view, R.id.tmux_action_next_pane, binding, TerminalSessionBottomTmuxAction.NEXT_PANE);
        bindAction(view, R.id.tmux_action_last_pane, binding, TerminalSessionBottomTmuxAction.LAST_PANE);
        bindAction(view, R.id.tmux_action_zoom_pane, binding, TerminalSessionBottomTmuxAction.ZOOM_PANE);
        bindAction(view, R.id.tmux_action_resize_left, binding, TerminalSessionBottomTmuxAction.RESIZE_LEFT);
        bindAction(view, R.id.tmux_action_resize_right, binding, TerminalSessionBottomTmuxAction.RESIZE_RIGHT);
        bindAction(view, R.id.tmux_action_resize_up, binding, TerminalSessionBottomTmuxAction.RESIZE_UP);
        bindAction(view, R.id.tmux_action_resize_down, binding, TerminalSessionBottomTmuxAction.RESIZE_DOWN);
        bindAction(view, R.id.tmux_action_kill_pane, binding, TerminalSessionBottomTmuxAction.KILL_PANE);
        bindAction(view, R.id.tmux_action_prev, binding, TerminalSessionBottomTmuxAction.PREVIOUS_WINDOW);
        bindAction(view, R.id.tmux_action_next, binding, TerminalSessionBottomTmuxAction.NEXT_WINDOW);
        bindAction(view, R.id.tmux_action_new, binding, TerminalSessionBottomTmuxAction.NEW_WINDOW);
        bindAction(view, R.id.tmux_action_copy, binding, TerminalSessionBottomTmuxAction.COPY_MODE);
        bindAction(view, R.id.tmux_action_detach, binding, TerminalSessionBottomTmuxAction.DETACH);
    }

    private void bindAction(@NonNull View root,
                            int viewId,
                            @NonNull Binding binding,
                            @NonNull TerminalSessionBottomTmuxAction action) {
        MaterialButton button = root.findViewById(viewId);
        if (button == null) return;

        button.setEnabled(binding.bottomActionListener != null && binding.session != null);
        button.setOnClickListener(v -> {
            if (binding.bottomActionListener != null) {
                binding.bottomActionListener.onTmuxActionRequested(binding.session, action);
            }
        });
    }
}
