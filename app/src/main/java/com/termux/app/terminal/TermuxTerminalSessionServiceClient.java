package com.termux.app.terminal;

import android.app.Service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxService;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

/** The {@link TerminalSessionClient} implementation that may require a {@link Service} for its interface methods. */
public class TermuxTerminalSessionServiceClient extends TermuxTerminalSessionClientBase {

    private static final String LOG_TAG = "TermuxTerminalSessionServiceClient";

    private final TermuxService mService;

    public TermuxTerminalSessionServiceClient(TermuxService service) {
        this.mService = service;
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        mService.getCodexSessionRecoveryController().handleFinishedSession(finishedSession);
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession terminalSession, int pid) {
        TermuxSession termuxSession = mService.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession != null)
            termuxSession.getExecutionCommand().mPid = pid;
    }

    @Override
    public void onTerminalHostControlCommand(@NonNull TerminalSession terminalSession,
                                             @NonNull String command,
                                             @Nullable String argument) {
        if (!CodexSessionHostProtocol.COMMAND.equals(command)) return;

        CodexSessionHostProtocol.Event event = CodexSessionHostProtocol.parse(argument);
        if (event == null) {
            Logger.logWarn(LOG_TAG, "Ignoring invalid Codex session host event");
            return;
        }
        if (event.type == com.termux.terminalsessioncore.CodexRestoreStateMachine.HostEvent.READY &&
            !CodexProcessIdentity.isLiveCodexProcessForSession(terminalSession, event.processId)) {
            Logger.logWarn(LOG_TAG, "Ignoring Codex session host event from an unrelated process");
            return;
        }

        int order = mService.getIndexOfSession(terminalSession);
        if (event.type == com.termux.terminalsessioncore.CodexRestoreStateMachine.HostEvent.READY &&
            event.workingDirectory.isEmpty()) {
            String cwd = terminalSession.getCwd();
            event = new CodexSessionHostProtocol.Event(
                event.type, event.threadId, event.processId, cwd == null ? "" : cwd,
                event.rolloutPath, event.title);
        }
        TermuxSessionRestoreStore.UpdateResult result = TermuxSessionRestoreStore.applyCodexEvent(
            terminalSession.mHandle == null ? "" : terminalSession.mHandle,
            order,
            event);
        if (result == TermuxSessionRestoreStore.UpdateResult.FAILED) {
            Logger.logWarn(LOG_TAG, "Failed to persist Codex session host event");
            return;
        }

        if (event.type == com.termux.terminalsessioncore.CodexRestoreStateMachine.HostEvent.CLOSED) {
            if (result == TermuxSessionRestoreStore.UpdateResult.APPLIED) {
                mService.getCodexSessionRecoveryController().onLeaseClosed(event.threadId);
            }
            return;
        }
        if (result != TermuxSessionRestoreStore.UpdateResult.APPLIED) return;

        terminalSession.mSessionName = event.title.isEmpty()
            ? "Codex " + event.threadId.substring(0, 8)
            : event.title;
        mService.getCodexSessionRecoveryController().onLeaseReady(event.threadId, terminalSession);
    }

}
