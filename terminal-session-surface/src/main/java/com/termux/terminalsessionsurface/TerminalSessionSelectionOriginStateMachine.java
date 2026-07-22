package com.termux.terminalsessionsurface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/** Resolves the owner of an idle-page commit after asynchronous ViewPager settling. */
public final class TerminalSessionSelectionOriginStateMachine {

    public enum Origin {
        USER,
        PROGRAMMATIC
    }

    public static final class Resolution {
        @NonNull public final Origin origin;
        @Nullable public final String abandonedProgrammaticKey;

        Resolution(@NonNull Origin origin, @Nullable String abandonedProgrammaticKey) {
            this.origin = origin;
            this.abandonedProgrammaticKey = abandonedProgrammaticKey;
        }
    }

    @Nullable private String pendingProgrammaticKey;

    @Nullable
    public String beginProgrammaticSelection(@Nullable String targetKey) {
        String supersededKey = Objects.equals(pendingProgrammaticKey, targetKey)
            ? null
            : pendingProgrammaticKey;
        pendingProgrammaticKey = targetKey;
        return supersededKey;
    }

    @NonNull
    public Resolution resolveIdleSelection(@Nullable String selectedKey) {
        String pendingKey = pendingProgrammaticKey;
        pendingProgrammaticKey = null;
        if (pendingKey == null) return new Resolution(Origin.USER, null);
        if (Objects.equals(pendingKey, selectedKey)) {
            return new Resolution(Origin.PROGRAMMATIC, null);
        }
        return new Resolution(Origin.USER, pendingKey);
    }

    public void completeProgrammaticSelection(@Nullable String selectedKey) {
        if (Objects.equals(pendingProgrammaticKey, selectedKey)) {
            pendingProgrammaticKey = null;
        }
    }

    @Nullable
    public String clear() {
        String pendingKey = pendingProgrammaticKey;
        pendingProgrammaticKey = null;
        return pendingKey;
    }
}
