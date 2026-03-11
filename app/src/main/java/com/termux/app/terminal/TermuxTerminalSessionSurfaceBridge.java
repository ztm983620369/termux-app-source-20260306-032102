package com.termux.app.terminal;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxService;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;
import com.termux.terminalsessionsurface.TerminalSessionSurfaceItem;

import java.util.ArrayList;

public final class TermuxTerminalSessionSurfaceBridge {

    public interface Host {
        @Nullable TermuxService getTermuxService();
        @Nullable TerminalSession getCurrentSession();
    }

    public static final class Snapshot {
        @NonNull public final ArrayList<TerminalSessionSurfaceItem> items;
        public final int selectedIndex;

        public Snapshot(@NonNull ArrayList<TerminalSessionSurfaceItem> items, int selectedIndex) {
            this.items = items;
            this.selectedIndex = selectedIndex;
        }
    }

    private final Host host;

    public TermuxTerminalSessionSurfaceBridge(@NonNull Host host) {
        this.host = host;
    }

    @NonNull
    public Snapshot capture() {
        TermuxService service = host.getTermuxService();
        if (service == null) {
            return new Snapshot(new ArrayList<>(), 0);
        }

        TerminalSession currentSession = host.getCurrentSession();
        ArrayList<TerminalSessionSurfaceItem> items = new ArrayList<>(service.getTermuxSessionsSize());
        int selectedIndex = 0;

        for (int i = 0; i < service.getTermuxSessionsSize(); i++) {
            TermuxSession termuxSession = service.getTermuxSession(i);
            if (termuxSession == null) continue;

            TerminalSession session = termuxSession.getTerminalSession();
            if (session == null) continue;

            if (session == currentSession) {
                selectedIndex = items.size();
            }

            String key = TextUtils.isEmpty(session.mHandle) ? "session-" + i : session.mHandle;
            items.add(new TerminalSessionSurfaceItem(key, session));
        }

        if (items.isEmpty()) selectedIndex = 0;
        else selectedIndex = Math.max(0, Math.min(selectedIndex, items.size() - 1));
        return new Snapshot(items, selectedIndex);
    }
}
