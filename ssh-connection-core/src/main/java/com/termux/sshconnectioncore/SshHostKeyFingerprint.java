package com.termux.sshconnectioncore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Canonical OpenSSH SHA-256 host-key fingerprint handling. */
public final class SshHostKeyFingerprint {

    private static final String PREFIX = "SHA256:";
    private static final char[] BASE64 =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private SshHostKeyFingerprint() {
    }

    /** Compute the same unpadded, case-sensitive SHA-256 fingerprint printed by OpenSSH. */
    @NonNull
    public static String fromPublicKeyBlob(@Nullable byte[] publicKeyBlob) {
        if (publicKeyBlob == null || publicKeyBlob.length == 0) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob);
            return PREFIX + encodeBase64WithoutPadding(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    /**
     * Normalize only the algorithm prefix and optional legacy padding. The Base64 payload is
     * deliberately never case-folded: Base64 is case-sensitive and changing its case changes the
     * fingerprint.
     */
    @NonNull
    public static String normalizeSha256(@Nullable String fingerprint) {
        String value = fingerprint == null ? "" : fingerprint.trim();
        if (value.length() <= PREFIX.length()
            || !value.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return "";
        }

        String payload = value.substring(PREFIX.length());
        if (payload.length() == 44 && payload.charAt(43) == '=') {
            payload = payload.substring(0, 43);
        }
        if (payload.length() != 43) return "";

        for (int i = 0; i < payload.length(); i++) {
            if (base64Index(payload.charAt(i)) < 0) return "";
        }
        // A 32-byte SHA-256 digest leaves two input bytes in the final Base64 quantum, so the
        // low two bits of the last Base64 symbol must be zero for a canonical encoding.
        if ((base64Index(payload.charAt(42)) & 0x03) != 0) return "";
        return PREFIX + payload;
    }

    public static boolean isValidSha256(@Nullable String fingerprint) {
        return !normalizeSha256(fingerprint).isEmpty();
    }

    @NonNull
    private static String encodeBase64WithoutPadding(@NonNull byte[] input) {
        StringBuilder out = new StringBuilder((input.length * 4 + 2) / 3);
        int offset = 0;
        while (offset + 2 < input.length) {
            int value = ((input[offset] & 0xff) << 16)
                | ((input[offset + 1] & 0xff) << 8)
                | (input[offset + 2] & 0xff);
            out.append(BASE64[(value >>> 18) & 0x3f]);
            out.append(BASE64[(value >>> 12) & 0x3f]);
            out.append(BASE64[(value >>> 6) & 0x3f]);
            out.append(BASE64[value & 0x3f]);
            offset += 3;
        }
        int remaining = input.length - offset;
        if (remaining == 1) {
            int value = (input[offset] & 0xff) << 16;
            out.append(BASE64[(value >>> 18) & 0x3f]);
            out.append(BASE64[(value >>> 12) & 0x3f]);
        } else if (remaining == 2) {
            int value = ((input[offset] & 0xff) << 16) | ((input[offset + 1] & 0xff) << 8);
            out.append(BASE64[(value >>> 18) & 0x3f]);
            out.append(BASE64[(value >>> 12) & 0x3f]);
            out.append(BASE64[(value >>> 6) & 0x3f]);
        }
        return out.toString();
    }

    private static int base64Index(char value) {
        if (value >= 'A' && value <= 'Z') return value - 'A';
        if (value >= 'a' && value <= 'z') return value - 'a' + 26;
        if (value >= '0' && value <= '9') return value - '0' + 52;
        if (value == '+') return 62;
        if (value == '/') return 63;
        return -1;
    }
}
