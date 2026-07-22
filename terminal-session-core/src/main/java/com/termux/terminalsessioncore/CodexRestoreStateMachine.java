package com.termux.terminalsessioncore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;

/** Pure transition policy for the native Codex-to-Termux restoration lifecycle. */
public final class CodexRestoreStateMachine {

    public enum HostEvent {
        READY,
        CLOSED
    }

    public enum HostAction {
        UPSERT,
        REMOVE,
        IGNORE
    }

    public enum RecoveryEvent {
        PROCESS_LOST,
        COLD_START,
        USER_REMOVE
    }

    public enum RecoveryAction {
        KEEP_MAPPING,
        START_CODEX,
        DEFER_RETRY,
        REMOVE_MAPPING,
        IGNORE
    }

    public static final class HostEventInput {
        @NonNull public final HostEvent event;
        public final boolean hasValidThreadId;
        public final boolean hasDurableRollout;
        public final boolean hasTrackedRecord;
        public final boolean trackedThreadMatches;

        public HostEventInput(@NonNull HostEvent event,
                              boolean hasValidThreadId,
                              boolean hasDurableRollout,
                              boolean hasTrackedRecord,
                              boolean trackedThreadMatches) {
            this.event = event;
            this.hasValidThreadId = hasValidThreadId;
            this.hasDurableRollout = hasDurableRollout;
            this.hasTrackedRecord = hasTrackedRecord;
            this.trackedThreadMatches = trackedThreadMatches;
        }
    }

    public static final class RecoveryInput {
        @NonNull public final RecoveryEvent event;
        public final boolean hasPersistedMapping;
        public final boolean hasValidThreadId;
        public final boolean executableAvailable;

        public RecoveryInput(@NonNull RecoveryEvent event,
                             boolean hasPersistedMapping,
                             boolean hasValidThreadId,
                             boolean executableAvailable) {
            this.event = event;
            this.hasPersistedMapping = hasPersistedMapping;
            this.hasValidThreadId = hasValidThreadId;
            this.executableAvailable = executableAvailable;
        }
    }

    public static final class ShellProjectionInput {
        @NonNull public final String type;
        @Nullable public final String displayName;
        @Nullable public final String shellName;
        @Nullable public final String executable;
        @Nullable public final String[] arguments;
        public final boolean hasCodexAuthority;
        public final boolean hasSshAuthority;
        public final boolean hasTmuxAuthority;

        public ShellProjectionInput(@NonNull String type,
                                    @Nullable String displayName,
                                    @Nullable String shellName,
                                    @Nullable String executable,
                                    @Nullable String[] arguments,
                                    boolean hasCodexAuthority,
                                    boolean hasSshAuthority,
                                    boolean hasTmuxAuthority) {
            this.type = type;
            this.displayName = displayName;
            this.shellName = shellName;
            this.executable = executable;
            this.arguments = arguments == null ? null : arguments.clone();
            this.hasCodexAuthority = hasCodexAuthority;
            this.hasSshAuthority = hasSshAuthority;
            this.hasTmuxAuthority = hasTmuxAuthority;
        }
    }

    public static final class DisposableShellProjectionInput {
        @NonNull public final String type;
        @Nullable public final String displayName;
        @Nullable public final String shellName;
        @Nullable public final String executable;
        @Nullable public final String[] arguments;
        public final boolean hasCodexAuthority;
        public final boolean hasSshAuthority;
        public final boolean hasTmuxAuthority;
        public final boolean hasManagedRestoreRecord;
        public final int order;
        public final int maxManagedOrder;

        public DisposableShellProjectionInput(@NonNull String type,
                                              @Nullable String displayName,
                                              @Nullable String shellName,
                                              @Nullable String executable,
                                              @Nullable String[] arguments,
                                              boolean hasCodexAuthority,
                                              boolean hasSshAuthority,
                                              boolean hasTmuxAuthority,
                                              boolean hasManagedRestoreRecord,
                                              int order,
                                              int maxManagedOrder) {
            this.type = type;
            this.displayName = displayName;
            this.shellName = shellName;
            this.executable = executable;
            this.arguments = arguments == null ? null : arguments.clone();
            this.hasCodexAuthority = hasCodexAuthority;
            this.hasSshAuthority = hasSshAuthority;
            this.hasTmuxAuthority = hasTmuxAuthority;
            this.hasManagedRestoreRecord = hasManagedRestoreRecord;
            this.order = order;
            this.maxManagedOrder = maxManagedOrder;
        }
    }

    private CodexRestoreStateMachine() {
    }

    @NonNull
    public static HostAction resolveHostEvent(@NonNull HostEventInput input) {
        if (!input.hasValidThreadId) return HostAction.IGNORE;
        if (input.event == HostEvent.READY) {
            return input.hasDurableRollout ? HostAction.UPSERT : HostAction.IGNORE;
        }
        return input.hasTrackedRecord && input.trackedThreadMatches
            ? HostAction.REMOVE
            : HostAction.IGNORE;
    }

    @NonNull
    public static RecoveryAction resolveRecovery(@NonNull RecoveryInput input) {
        if (!input.hasPersistedMapping || !input.hasValidThreadId) {
            return RecoveryAction.IGNORE;
        }
        switch (input.event) {
            case PROCESS_LOST:
                return RecoveryAction.KEEP_MAPPING;
            case USER_REMOVE:
                return RecoveryAction.REMOVE_MAPPING;
            case COLD_START:
                return input.executableAvailable
                    ? RecoveryAction.START_CODEX
                    : RecoveryAction.DEFER_RETRY;
            default:
                return RecoveryAction.IGNORE;
        }
    }

    public static boolean shouldDropStaleCodexShellProjection(@NonNull ShellProjectionInput input) {
        if (!"shell".equals(input.type)) return false;
        if (input.hasCodexAuthority || input.hasSshAuthority || input.hasTmuxAuthority) return false;
        return looksLikeCodexShellRestoreName(input.displayName, input.shellName) &&
            isPlainInteractiveShellRestoreCommand(input.executable, input.arguments);
    }

    public static boolean shouldDropDisposableGeneratedShellProjection(@NonNull DisposableShellProjectionInput input) {
        if (!"shell".equals(input.type)) return false;
        if (input.hasCodexAuthority || input.hasSshAuthority || input.hasTmuxAuthority) return false;
        if (!input.hasManagedRestoreRecord) return false;

        int order = normalizeOrder(input.order);
        int maxManagedOrder = normalizeOrder(input.maxManagedOrder);
        if (order == Integer.MAX_VALUE || maxManagedOrder == Integer.MAX_VALUE) return false;
        if (order <= maxManagedOrder) return false;

        return looksLikeGeneratedTerminalName(input.displayName, input.order) &&
            (isEmpty(input.shellName) || looksLikeGeneratedTerminalName(input.shellName, input.order)) &&
            isPlainInteractiveShellRestoreCommand(input.executable, input.arguments);
    }

    private static boolean looksLikeCodexShellRestoreName(@Nullable String displayName,
                                                          @Nullable String shellName) {
        return looksLikeCodexShellRestoreName(displayName) || looksLikeCodexShellRestoreName(shellName);
    }

    private static boolean looksLikeCodexShellRestoreName(@Nullable String raw) {
        if (isEmpty(raw)) return false;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return "codex".equals(value) ||
            value.startsWith("codex ") ||
            value.startsWith("codex\t") ||
            value.startsWith("codex~") ||
            value.startsWith("codex/");
    }

    private static boolean looksLikeGeneratedTerminalName(@Nullable String raw, int order) {
        if (isEmpty(raw)) return false;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        String prefix = "terminal ";
        if (!value.startsWith(prefix)) return false;
        String suffix = value.substring(prefix.length()).trim();
        if (suffix.length() == 0) return false;
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) return false;
        }
        int expected = order >= 0 && order < Integer.MAX_VALUE ? order + 1 : Integer.MAX_VALUE;
        if (expected == Integer.MAX_VALUE) return true;
        try {
            return Integer.parseInt(suffix) == expected;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isPlainInteractiveShellRestoreCommand(@Nullable String executable,
                                                                 @Nullable String[] arguments) {
        String name = isEmpty(executable)
            ? ""
            : new File(executable.trim()).getName().toLowerCase(Locale.ROOT);
        if (isEmpty(name)) return arguments == null || arguments.length == 0;

        if ("login".equals(name) || "login.exe".equals(name)) {
            return arguments == null || arguments.length == 0 ||
                (arguments.length == 1 && "-login".equals(nullToEmpty(arguments[0]).trim()));
        }

        if (!"bash".equals(name) && !"sh".equals(name) && !"zsh".equals(name) && !"fish".equals(name)) {
            return false;
        }

        if (arguments == null || arguments.length == 0) return true;
        if (arguments.length > 2) return false;
        for (String arg : arguments) {
            String value = nullToEmpty(arg).trim();
            if (!"-l".equals(value) && !"--login".equals(value) && !"-i".equals(value)) return false;
        }
        return true;
    }

    private static boolean isEmpty(@Nullable String value) {
        return value == null || value.length() == 0;
    }

    private static int normalizeOrder(int order) {
        return order < 0 ? Integer.MAX_VALUE : order;
    }

    @NonNull
    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
