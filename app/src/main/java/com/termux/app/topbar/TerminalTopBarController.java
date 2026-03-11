package com.termux.app.topbar;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class TerminalTopBarController {

    public interface Callbacks {
        void onAddSession();
        void onAddLongPress();
        void onSelectSession(int index);
        void onCloseSession(int index);
        void onLongPressSession(int index);
    }

    private final TerminalTopBarView view;
    private final Callbacks callbacks;

    public TerminalTopBarController(@NonNull TerminalTopBarView view,
                                    @NonNull Callbacks callbacks) {
        this.view = view;
        this.callbacks = callbacks;
        bindListeners();
    }

    @NonNull
    public TerminalTopBarView getView() {
        return view;
    }

    public void render(@NonNull List<TerminalTopBarSessionModel> models) {
        ArrayList<TerminalTopBarView.Item> items = new ArrayList<>(models.size());
        for (TerminalTopBarSessionModel model : models) {
            TerminalTopBarStateMachine.Snapshot snapshot = TerminalTopBarStateMachine.resolve(
                new TerminalTopBarStateMachine.Input(
                    model.selected,
                    model.pinned,
                    model.running,
                    model.exitStatus,
                    model.pinnedDisplayName,
                    model.sessionName,
                    model.terminalTitle,
                    model.transportKind,
                    model.runtimeState
                )
            );

            items.add(new TerminalTopBarView.Item(
                model.key,
                snapshot.title,
                snapshot.selected,
                snapshot.locked,
                snapshot.closable,
                snapshot.tone,
                badgeText(snapshot.badgeKind),
                buildContentDescription(snapshot)
            ));
        }
        view.setItems(items);
    }

    private void bindListeners() {
        view.setOnAddClickListener(callbacks::onAddSession);
        view.setOnAddLongPressListener(callbacks::onAddLongPress);
        view.setOnTabSelectedListener((index, item) -> callbacks.onSelectSession(index));
        view.setOnTabCloseListener(callbacks::onCloseSession);
        view.setOnTabLongPressListener((index, item) -> callbacks.onLongPressSession(index));
    }

    @Nullable
    private String badgeText(@NonNull TerminalTopBarStateMachine.BadgeKind badgeKind) {
        switch (badgeKind) {
            case SSH:
                return "SSH";
            case PIN:
                return "PIN";
            case BUSY:
                return "SYNC";
            case RETRY:
                return "RETRY";
            case DONE:
                return "DONE";
            case ERROR:
                return "ERR";
            case NONE:
            default:
                return null;
        }
    }

    @NonNull
    private String buildContentDescription(@NonNull TerminalTopBarStateMachine.Snapshot snapshot) {
        StringBuilder sb = new StringBuilder(snapshot.title);
        String badge = badgeText(snapshot.badgeKind);
        if (!TextUtils.isEmpty(badge)) {
            sb.append(" ").append(badge);
        }
        if (snapshot.selected) {
            sb.append(" current");
        }
        if (snapshot.locked) {
            sb.append(" locked");
        }
        return sb.toString();
    }
}
