package com.termux.terminalsessioncore;

import androidx.annotation.NonNull;

/** Pure lifecycle policy for attaching Android-selected images to a Codex composer. */
public final class CodexImageAttachmentStateMachine {

    public enum Phase {
        AWAITING_SELECTION,
        MATERIALIZING,
        TRANSFERRING,
        READY_TO_INJECT,
        INJECTING,
        COMPLETED,
        CANCELLED,
        FAILED,
        INTERRUPTED
    }

    public enum Route {
        LOCAL_FILESYSTEM,
        REMOTE_SFTP
    }

    private CodexImageAttachmentStateMachine() {
    }

    @NonNull
    public static Route resolveRoute(boolean hasSshBootstrapCommand) {
        return hasSshBootstrapCommand ? Route.REMOTE_SFTP : Route.LOCAL_FILESYSTEM;
    }

    public static boolean canTransition(@NonNull Phase from, @NonNull Phase to) {
        if (from == to) return true;
        if (isTerminal(from)) return false;
        if (to == Phase.FAILED || to == Phase.CANCELLED || to == Phase.INTERRUPTED) return true;

        switch (from) {
            case AWAITING_SELECTION:
                return to == Phase.MATERIALIZING;
            case MATERIALIZING:
                return to == Phase.TRANSFERRING || to == Phase.READY_TO_INJECT;
            case TRANSFERRING:
                return to == Phase.READY_TO_INJECT;
            case READY_TO_INJECT:
                return to == Phase.INJECTING;
            case INJECTING:
                return to == Phase.COMPLETED;
            default:
                return false;
        }
    }

    public static boolean canInject(boolean targetExists,
                                    boolean targetRunning,
                                    boolean targetIdentityMatches,
                                    int attachmentCount) {
        return targetExists && targetRunning && targetIdentityMatches && attachmentCount > 0;
    }

    public static boolean isTerminal(@NonNull Phase phase) {
        return phase == Phase.COMPLETED || phase == Phase.CANCELLED ||
            phase == Phase.FAILED || phase == Phase.INTERRUPTED;
    }
}
