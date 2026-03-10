package com.termux.terminalsessioncore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

public final class SshTmuxSessionStateMachine {

    public static final String TMUX_DISPLAY_NAME_OPTION = "@termux_display_name_hex";
    public static final String MANAGED_REMOTE_SESSION_PREFIX = "termux-persist-v2-";
    private static final Pattern DIRECT_REMOTE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public enum DisplayState {
        EXPLICIT_INPUT,
        REMOTE_METADATA,
        LOCAL_RECORD,
        LOCAL_SESSION,
        REMOTE_SESSION_NAME,
        GENERATED_FALLBACK
    }

    public enum RemoteState {
        DIRECT_USER_NAME,
        GENERATED_MANAGED_ID,
        EXISTING_REMOTE_NAME
    }

    public static final class Snapshot {
        @NonNull public final String remoteSessionName;
        @NonNull public final String displayName;
        @NonNull public final DisplayState displayState;
        @NonNull public final RemoteState remoteState;

        public Snapshot(@NonNull String remoteSessionName, @NonNull String displayName,
                        @NonNull DisplayState displayState, @NonNull RemoteState remoteState) {
            this.remoteSessionName = remoteSessionName;
            this.displayName = displayName;
            this.displayState = displayState;
            this.remoteState = remoteState;
        }
    }

    private SshTmuxSessionStateMachine() {
    }

    @NonNull
    public static Snapshot planNewManagedSession(@Nullable String requestedDisplayName,
                                                 @Nullable String fallbackDisplayName,
                                                 @Nullable String sshCommand,
                                                 @Nullable String sessionHandle) {
        String requested = normalizeHumanName(requestedDisplayName);
        if (!requested.isEmpty()) {
            boolean direct = canUseDirectRemoteName(requested);
            return new Snapshot(
                direct ? requested : generateManagedRemoteSessionId(sshCommand, sessionHandle, requested),
                requested,
                DisplayState.EXPLICIT_INPUT,
                direct ? RemoteState.DIRECT_USER_NAME : RemoteState.GENERATED_MANAGED_ID
            );
        }

        String fallback = normalizeHumanName(fallbackDisplayName);
        if (fallback.isEmpty()) fallback = "tmux";
        return new Snapshot(
            generateManagedRemoteSessionId(sshCommand, sessionHandle, fallback),
            fallback,
            DisplayState.GENERATED_FALLBACK,
            RemoteState.GENERATED_MANAGED_ID
        );
    }

    @NonNull
    public static Snapshot resolveExistingRemote(@Nullable String remoteSessionName,
                                                 @Nullable String encodedRemoteDisplayName,
                                                 @Nullable String recordedDisplayName,
                                                 @Nullable String localSessionName,
                                                 @Nullable String fallbackDisplayName) {
        String remote = normalizeRemoteSessionName(remoteSessionName);
        if (remote.isEmpty()) remote = "termux";

        String remoteDisplay = decodeDisplayNameHex(encodedRemoteDisplayName);
        if (!remoteDisplay.isEmpty()) {
            return new Snapshot(remote, remoteDisplay, DisplayState.REMOTE_METADATA, RemoteState.EXISTING_REMOTE_NAME);
        }

        String recorded = normalizeHumanName(recordedDisplayName);
        if (!recorded.isEmpty()) {
            return new Snapshot(remote, recorded, DisplayState.LOCAL_RECORD, RemoteState.EXISTING_REMOTE_NAME);
        }

        String local = normalizeHumanName(localSessionName);
        if (!local.isEmpty() && !looksLikeOpaqueInternalName(local)) {
            return new Snapshot(remote, local, DisplayState.LOCAL_SESSION, RemoteState.EXISTING_REMOTE_NAME);
        }

        String fallback = normalizeHumanName(fallbackDisplayName);
        if (!fallback.isEmpty() && !looksLikeOpaqueInternalName(fallback)) {
            return new Snapshot(remote, fallback, DisplayState.LOCAL_SESSION, RemoteState.EXISTING_REMOTE_NAME);
        }

        if (looksLikeOpaqueInternalName(remote)) {
            return new Snapshot(remote, buildOpaqueFallbackDisplayName(remote),
                DisplayState.GENERATED_FALLBACK, RemoteState.EXISTING_REMOTE_NAME);
        }

        return new Snapshot(remote, remote, DisplayState.REMOTE_SESSION_NAME, RemoteState.EXISTING_REMOTE_NAME);
    }

    @NonNull
    public static String normalizeRemoteSessionName(@Nullable String raw) {
        return normalizeHumanName(raw);
    }

    @NonNull
    public static String normalizeDisplayName(@Nullable String raw, @Nullable String fallback) {
        String display = normalizeHumanName(raw);
        if (!display.isEmpty()) return display;
        display = normalizeHumanName(fallback);
        return display.isEmpty() ? "tmux" : display;
    }

    public static boolean canUseDirectRemoteName(@Nullable String displayName) {
        String normalized = normalizeHumanName(displayName);
        return !normalized.isEmpty() && DIRECT_REMOTE_NAME_PATTERN.matcher(normalized).matches();
    }

    public static boolean isManagedRemoteSession(@Nullable String remoteSessionName) {
        return normalizeRemoteSessionName(remoteSessionName).startsWith(MANAGED_REMOTE_SESSION_PREFIX);
    }

    public static boolean looksLikeOpaqueInternalName(@Nullable String value) {
        String normalized = normalizeHumanName(value);
        return normalized.startsWith("ssh-persistent-") ||
            normalized.startsWith(MANAGED_REMOTE_SESSION_PREFIX) ||
            normalized.startsWith("termux-persist-");
    }

    @NonNull
    public static String encodeDisplayNameHex(@Nullable String displayName) {
        String normalized = normalizeHumanName(displayName);
        if (normalized.isEmpty()) return "";
        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0x0F, 16));
            sb.append(Character.forDigit(b & 0x0F, 16));
        }
        return sb.toString();
    }

    @NonNull
    public static String decodeDisplayNameHex(@Nullable String encoded) {
        String value = encoded == null ? "" : encoded.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || (value.length() % 2) != 0) return "";

        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < value.length(); i += 2) {
            int hi = Character.digit(value.charAt(i), 16);
            int lo = Character.digit(value.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) return "";
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return normalizeHumanName(new String(out, StandardCharsets.UTF_8));
    }

    @NonNull
    private static String generateManagedRemoteSessionId(@Nullable String sshCommand,
                                                         @Nullable String sessionHandle,
                                                         @Nullable String displayName) {
        String seed = normalizeHumanName(displayName) + "\n" +
            normalizeHumanName(sshCommand) + "\n" +
            normalizeHumanName(sessionHandle) + "\n" +
            System.currentTimeMillis();
        return MANAGED_REMOTE_SESSION_PREFIX + shortSha1(seed);
    }

    @NonNull
    private static String shortSha1(@NonNull String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] data = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(data.length * 2);
            for (byte b : data) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            if (hex.length() > 12) return hex.substring(0, 12);
            return hex.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    @NonNull
    private static String buildOpaqueFallbackDisplayName(@NonNull String remote) {
        int marker = remote.lastIndexOf('-');
        String tail = marker >= 0 && marker + 1 < remote.length() ? remote.substring(marker + 1) : remote;
        if (tail.length() > 6) tail = tail.substring(tail.length() - 6);
        return tail.isEmpty() ? "tmux" : "tmux-" + tail;
    }

    @NonNull
    private static String normalizeHumanName(@Nullable String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        boolean previousSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == 0) continue;
            if (Character.isISOControl(ch) || Character.isWhitespace(ch)) {
                if (!previousSpace && sb.length() > 0) {
                    sb.append(' ');
                    previousSpace = true;
                }
            } else {
                sb.append(ch);
                previousSpace = false;
            }
        }
        return sb.toString().trim();
    }
}
