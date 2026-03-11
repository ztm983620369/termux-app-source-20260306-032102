package com.termux.app.terminal;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxService;
import com.termux.app.topbar.TerminalTopBarRuntimeState;
import com.termux.app.topbar.TerminalTopBarSessionModel;
import com.termux.app.topbar.TerminalTopBarStateMachine;
import com.termux.app.topbar.TerminalTopBarTitleStateMachine;
import com.termux.sessionsync.SessionEntry;
import com.termux.sessionsync.SessionRegistry;
import com.termux.sessionsync.SessionSnapshot;
import com.termux.sessionsync.SessionTransport;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

public final class TermuxTerminalTopBarBridge {

    public interface Host {
        @NonNull Context getContext();
        @Nullable TermuxService getTermuxService();
        @Nullable TermuxTerminalSessionActivityClient getSessionClient();
        @Nullable TerminalSession getCurrentSession();
    }

    public static final class Snapshot {
        @NonNull public final ArrayList<TerminalTopBarSessionModel> models;
        @NonNull public final SessionSnapshot sessionSnapshot;

        public Snapshot(@NonNull ArrayList<TerminalTopBarSessionModel> models,
                        @NonNull SessionSnapshot sessionSnapshot) {
            this.models = models;
            this.sessionSnapshot = sessionSnapshot;
        }
    }

    private final Host host;

    public TermuxTerminalTopBarBridge(@NonNull Host host) {
        this.host = host;
    }

    @NonNull
    public Snapshot capture() {
        TermuxService service = host.getTermuxService();
        if (service == null) {
            return new Snapshot(new ArrayList<>(), SessionSnapshot.empty());
        }

        long nowMs = System.currentTimeMillis();
        TerminalSession current = host.getCurrentSession();
        TermuxTerminalSessionActivityClient sessionClient = host.getSessionClient();
        Set<String> pinnedSessionHandles = sessionClient == null
            ? Collections.emptySet()
            : sessionClient.getPinnedSessionHandleSnapshot();
        ArrayList<TerminalTopBarSessionModel> models = new ArrayList<>(service.getTermuxSessionsSize());
        ArrayList<SessionEntry> syncEntries = new ArrayList<>(service.getTermuxSessionsSize());

        for (int i = 0; i < service.getTermuxSessionsSize(); i++) {
            TermuxSession termuxSession = service.getTermuxSession(i);
            if (termuxSession == null) continue;

            TerminalSession session = termuxSession.getTerminalSession();
            if (session == null) continue;

            boolean selected = session == current;
            boolean pinned = !TextUtils.isEmpty(session.mHandle) &&
                pinnedSessionHandles.contains(session.mHandle);
            String pinnedDisplayName = pinned && sessionClient != null
                ? sessionClient.getPinnedDisplayNameForSession(session)
                : null;
            String sshCommand = sessionClient == null
                ? null
                : sessionClient.getSshBootstrapCommandForSession(session);

            SessionTransport transport = SessionTransport.LOCAL;
            String tmuxSession = null;
            if (!TextUtils.isEmpty(sshCommand)) {
                if (pinned) {
                    transport = SessionTransport.SSH_PERSIST;
                    tmuxSession = sessionClient == null ? null : sessionClient.getPinnedTmuxSessionForSession(session);
                } else {
                    transport = SessionTransport.SSH;
                }
            }

            String sessionKey = TextUtils.isEmpty(session.mHandle) ? "session-" + i : session.mHandle;
            models.add(new TerminalTopBarSessionModel.Builder(sessionKey)
                .setSelected(selected)
                .setPinned(pinned)
                .setRunning(session.isRunning())
                .setExitStatus(session.isRunning() ? 0 : session.getExitStatus())
                .setPinnedDisplayName(pinnedDisplayName)
                .setSessionName(session.mSessionName)
                .setTerminalTitle(session.getTitle())
                .setTransportKind(toTransportKind(transport))
                .setRuntimeState(sessionClient == null
                    ? TerminalTopBarRuntimeState.IDLE
                    : sessionClient.getTopBarRuntimeStateForSession(session))
                .build());

            String syncTitle = resolveSyncTitle(pinned, pinnedDisplayName, session.mSessionName, session.getTitle());
            syncEntries.add(new SessionEntry.Builder(sessionKey, syncTitle)
                .setTransport(transport)
                .setTerminalHandle(session.mHandle)
                .setSshCommand(sshCommand)
                .setTmuxSession(tmuxSession)
                .setActive(selected)
                .setRunning(session.isRunning())
                .setUpdatedAtMs(nowMs)
                .build());
        }

        return new Snapshot(models, new SessionSnapshot(
            syncEntries, resolveActiveSessionId(current, syncEntries), nowMs));
    }

    public void publish(@NonNull Snapshot snapshot) {
        SessionRegistry.getInstance().publish(host.getContext(), snapshot.sessionSnapshot);
    }

    @NonNull
    private TerminalTopBarStateMachine.TransportKind toTransportKind(@NonNull SessionTransport transport) {
        switch (transport) {
            case SSH:
                return TerminalTopBarStateMachine.TransportKind.SSH;
            case SSH_PERSIST:
                return TerminalTopBarStateMachine.TransportKind.SSH_PERSIST;
            case LOCAL:
            default:
                return TerminalTopBarStateMachine.TransportKind.LOCAL;
        }
    }

    @NonNull
    private String resolveSyncTitle(boolean pinned,
                                    @Nullable String pinnedDisplayName,
                                    @Nullable String sessionName,
                                    @Nullable String terminalTitle) {
        return TerminalTopBarTitleStateMachine.resolveTitle(
            pinned,
            pinnedDisplayName,
            sessionName,
            terminalTitle
        ).title;
    }

    @Nullable
    private String resolveActiveSessionId(@Nullable TerminalSession current,
                                          @NonNull ArrayList<SessionEntry> syncEntries) {
        if (current != null && !TextUtils.isEmpty(current.mHandle)) {
            return current.mHandle;
        }
        for (SessionEntry entry : syncEntries) {
            if (entry.active) return entry.id;
        }
        return null;
    }
}
