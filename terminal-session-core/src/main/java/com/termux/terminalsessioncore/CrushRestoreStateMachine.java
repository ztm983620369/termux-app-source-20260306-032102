package com.termux.terminalsessioncore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;

public final class CrushRestoreStateMachine {

    public enum Authority {
        NONE(0),
        EXECUTION_COMMAND(1),
        TERMUX_RESTORE_RECORD(2),
        CRUSH_STATE_FILE(3),
        LIVE_PROCESS(4);

        private final int strength;

        Authority(int strength) {
            this.strength = strength;
        }
    }

    public enum TabCloseAction {
        FORGET_FRONTEND,
        DETACH_FRONTEND,
        DESTROY_AUTHORITY
    }

    public enum SnapshotAction {
        WRITE_SHELL_RECORD,
        WRITE_CRUSH_RECORD,
        MATERIALIZE_DETACHED_CRUSH_RECORD,
        DETACH_STALE_CRUSH_RECORD,
        SKIP
    }

    public enum StoredRecordState {
        NONE,
        ACTIVE,
        DETACHED,
        CLOSED
    }

    public enum RestoreAction {
        START_CRUSH,
        START_FALLBACK_SHELL,
        SKIP
    }

    public static final class AuthorityInput {
        public final boolean hasLiveProcess;
        public final boolean hasCrushStateFileRecord;
        public final boolean hasTermuxRestoreRecord;
        public final boolean hasExecutionCommandIntent;

        public AuthorityInput(boolean hasLiveProcess,
                              boolean hasCrushStateFileRecord,
                              boolean hasTermuxRestoreRecord,
                              boolean hasExecutionCommandIntent) {
            this.hasLiveProcess = hasLiveProcess;
            this.hasCrushStateFileRecord = hasCrushStateFileRecord;
            this.hasTermuxRestoreRecord = hasTermuxRestoreRecord;
            this.hasExecutionCommandIntent = hasExecutionCommandIntent;
        }
    }

    public static final class TabCloseInput {
        @NonNull public final Authority authority;
        public final boolean destroyRequested;

        public TabCloseInput(@NonNull Authority authority, boolean destroyRequested) {
            this.authority = authority;
            this.destroyRequested = destroyRequested;
        }
    }

    public static final class SnapshotInput {
        @NonNull public final Authority authority;
        public final boolean frontendAttached;
        public final boolean processRunning;
        public final boolean restoreRecordExists;
        @NonNull public final StoredRecordState storedRecordState;

        public SnapshotInput(@NonNull Authority authority,
                             boolean frontendAttached,
                             boolean processRunning,
                             boolean restoreRecordExists) {
            this(authority, frontendAttached, processRunning, restoreRecordExists,
                restoreRecordExists ? StoredRecordState.ACTIVE : StoredRecordState.NONE);
        }

        public SnapshotInput(@NonNull Authority authority,
                             boolean frontendAttached,
                             boolean processRunning,
                             boolean restoreRecordExists,
                             @NonNull StoredRecordState storedRecordState) {
            this.authority = authority;
            this.frontendAttached = frontendAttached;
            this.processRunning = processRunning;
            this.restoreRecordExists = restoreRecordExists;
            this.storedRecordState = storedRecordState;
        }
    }

    public static final class RestoreInput {
        @NonNull public final Authority authority;
        public final boolean executableAvailable;
        public final boolean hasInstanceId;

        public RestoreInput(@NonNull Authority authority,
                            boolean executableAvailable,
                            boolean hasInstanceId) {
            this.authority = authority;
            this.executableAvailable = executableAvailable;
            this.hasInstanceId = hasInstanceId;
        }
    }

    public static final class ShellProjectionInput {
        @NonNull public final String type;
        @Nullable public final String displayName;
        @Nullable public final String shellName;
        @Nullable public final String executable;
        @Nullable public final String[] arguments;
        public final boolean hasCrushAuthority;
        public final boolean hasSshAuthority;
        public final boolean hasTmuxAuthority;

        public ShellProjectionInput(@NonNull String type,
                                    @Nullable String displayName,
                                    @Nullable String shellName,
                                    @Nullable String executable,
                                    @Nullable String[] arguments,
                                    boolean hasCrushAuthority,
                                    boolean hasSshAuthority,
                                    boolean hasTmuxAuthority) {
            this.type = type;
            this.displayName = displayName;
            this.shellName = shellName;
            this.executable = executable;
            this.arguments = arguments == null ? null : arguments.clone();
            this.hasCrushAuthority = hasCrushAuthority;
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
        public final boolean hasCrushAuthority;
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
                                              boolean hasCrushAuthority,
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
            this.hasCrushAuthority = hasCrushAuthority;
            this.hasSshAuthority = hasSshAuthority;
            this.hasTmuxAuthority = hasTmuxAuthority;
            this.hasManagedRestoreRecord = hasManagedRestoreRecord;
            this.order = order;
            this.maxManagedOrder = maxManagedOrder;
        }
    }

    private CrushRestoreStateMachine() {
    }

    @NonNull
    public static Authority resolveAuthority(@NonNull AuthorityInput input) {
        Authority authority = Authority.NONE;
        if (input.hasExecutionCommandIntent) authority = stronger(authority, Authority.EXECUTION_COMMAND);
        if (input.hasTermuxRestoreRecord) authority = stronger(authority, Authority.TERMUX_RESTORE_RECORD);
        if (input.hasCrushStateFileRecord) authority = stronger(authority, Authority.CRUSH_STATE_FILE);
        if (input.hasLiveProcess) authority = stronger(authority, Authority.LIVE_PROCESS);
        return authority;
    }

    @NonNull
    public static TabCloseAction resolveTabClose(@NonNull TabCloseInput input) {
        if (input.destroyRequested && input.authority != Authority.NONE) {
            return TabCloseAction.DESTROY_AUTHORITY;
        }
        if (input.authority != Authority.NONE) {
            return TabCloseAction.DETACH_FRONTEND;
        }
        return TabCloseAction.FORGET_FRONTEND;
    }

    @NonNull
    public static SnapshotAction resolveSnapshot(@NonNull SnapshotInput input) {
        if (input.authority == Authority.NONE) {
            return input.frontendAttached && input.processRunning
                ? SnapshotAction.WRITE_SHELL_RECORD
                : SnapshotAction.SKIP;
        }
        if (input.frontendAttached) {
            return SnapshotAction.WRITE_CRUSH_RECORD;
        }
        if (!input.restoreRecordExists) {
            return SnapshotAction.SKIP;
        }
        if (input.storedRecordState == StoredRecordState.DETACHED) {
            return SnapshotAction.MATERIALIZE_DETACHED_CRUSH_RECORD;
        }
        if (input.storedRecordState == StoredRecordState.ACTIVE && !input.processRunning) {
            return SnapshotAction.DETACH_STALE_CRUSH_RECORD;
        }
        return SnapshotAction.SKIP;
    }

    @NonNull
    public static RestoreAction resolveRestore(@NonNull RestoreInput input) {
        if (input.authority == Authority.NONE || !input.hasInstanceId) {
            return RestoreAction.SKIP;
        }
        return input.executableAvailable
            ? RestoreAction.START_CRUSH
            : RestoreAction.START_FALLBACK_SHELL;
    }

    public static boolean shouldDropStaleCrushShellProjection(@NonNull ShellProjectionInput input) {
        if (!"shell".equals(input.type)) return false;
        if (input.hasCrushAuthority || input.hasSshAuthority || input.hasTmuxAuthority) return false;
        return looksLikeCrushShellRestoreName(input.displayName, input.shellName) &&
            isPlainInteractiveShellRestoreCommand(input.executable, input.arguments);
    }

    public static boolean shouldDropDisposableGeneratedShellProjection(@NonNull DisposableShellProjectionInput input) {
        if (!"shell".equals(input.type)) return false;
        if (input.hasCrushAuthority || input.hasSshAuthority || input.hasTmuxAuthority) return false;
        if (!input.hasManagedRestoreRecord) return false;

        int order = normalizeOrder(input.order);
        int maxManagedOrder = normalizeOrder(input.maxManagedOrder);
        if (order == Integer.MAX_VALUE || maxManagedOrder == Integer.MAX_VALUE) return false;
        if (order <= maxManagedOrder) return false;

        return looksLikeGeneratedTerminalName(input.displayName, input.order) &&
            (isEmpty(input.shellName) || looksLikeGeneratedTerminalName(input.shellName, input.order)) &&
            isPlainInteractiveShellRestoreCommand(input.executable, input.arguments);
    }

    @NonNull
    private static Authority stronger(@NonNull Authority left, @NonNull Authority right) {
        return right.strength > left.strength ? right : left;
    }

    private static boolean looksLikeCrushShellRestoreName(@Nullable String displayName,
                                                          @Nullable String shellName) {
        return looksLikeCrushShellRestoreName(displayName) || looksLikeCrushShellRestoreName(shellName);
    }

    private static boolean looksLikeCrushShellRestoreName(@Nullable String raw) {
        if (isEmpty(raw)) return false;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return "crush".equals(value) ||
            value.startsWith("crush ") ||
            value.startsWith("crush\t") ||
            value.startsWith("crush~") ||
            value.startsWith("crush/");
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
