package com.tencent.shadow.sample.host.platform;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

public final class ShadowStateMachine {

    private static final Map<ShadowLifecycleState, EnumSet<ShadowLifecycleState>> TRANSITIONS =
            new EnumMap<>(ShadowLifecycleState.class);

    static {
        allow(ShadowLifecycleState.DISCOVERED,
                ShadowLifecycleState.STAGED,
                ShadowLifecycleState.QUARANTINED,
                ShadowLifecycleState.REMOVING,
                ShadowLifecycleState.FAILED);
        allow(ShadowLifecycleState.STAGED,
                ShadowLifecycleState.VERIFYING,
                ShadowLifecycleState.QUARANTINED,
                ShadowLifecycleState.REMOVING,
                ShadowLifecycleState.FAILED);
        allow(ShadowLifecycleState.VERIFYING,
                ShadowLifecycleState.VERIFIED,
                ShadowLifecycleState.QUARANTINED,
                ShadowLifecycleState.REMOVING,
                ShadowLifecycleState.FAILED);
        allow(ShadowLifecycleState.VERIFIED,
                ShadowLifecycleState.INSTALLING,
                ShadowLifecycleState.QUARANTINED,
                ShadowLifecycleState.REMOVING,
                ShadowLifecycleState.FAILED);
        allow(ShadowLifecycleState.INSTALLING,
                ShadowLifecycleState.INSTALLED,
                ShadowLifecycleState.REMOVING,
                ShadowLifecycleState.FAILED);
        allow(ShadowLifecycleState.INSTALLED,
                ShadowLifecycleState.ACTIVATING,
                ShadowLifecycleState.DISABLING,
                ShadowLifecycleState.REMOVING,
                ShadowLifecycleState.FAILED);
        allow(ShadowLifecycleState.ACTIVATING,
                ShadowLifecycleState.HEALTHY,
                ShadowLifecycleState.ROLLING_BACK,
                ShadowLifecycleState.DISABLING,
                ShadowLifecycleState.REMOVING,
                ShadowLifecycleState.FAILED);
        allow(ShadowLifecycleState.HEALTHY,
                ShadowLifecycleState.ACTIVATING,
                ShadowLifecycleState.SUPERSEDED,
                ShadowLifecycleState.DISABLING,
                ShadowLifecycleState.REMOVING,
                ShadowLifecycleState.FAILED);
        allow(ShadowLifecycleState.SUPERSEDED,
                ShadowLifecycleState.ACTIVATING,
                ShadowLifecycleState.DISABLING,
                ShadowLifecycleState.REMOVING,
                ShadowLifecycleState.FAILED);
        allow(ShadowLifecycleState.DISABLING,
                ShadowLifecycleState.DISABLED,
                ShadowLifecycleState.FAILED);
        allow(ShadowLifecycleState.DISABLED,
                ShadowLifecycleState.ACTIVATING,
                ShadowLifecycleState.REMOVING,
                ShadowLifecycleState.FAILED);
        allow(ShadowLifecycleState.ROLLING_BACK,
                ShadowLifecycleState.ROLLED_BACK,
                ShadowLifecycleState.FAILED);
        allow(ShadowLifecycleState.ROLLED_BACK,
                ShadowLifecycleState.ACTIVATING,
                ShadowLifecycleState.DISABLING,
                ShadowLifecycleState.REMOVING,
                ShadowLifecycleState.FAILED);
        allow(ShadowLifecycleState.QUARANTINED,
                ShadowLifecycleState.STAGED,
                ShadowLifecycleState.REMOVING,
                ShadowLifecycleState.FAILED);
        allow(ShadowLifecycleState.FAILED,
                ShadowLifecycleState.STAGED,
                ShadowLifecycleState.ROLLING_BACK,
                ShadowLifecycleState.DISABLING,
                ShadowLifecycleState.REMOVING);
        allow(ShadowLifecycleState.REMOVING,
                ShadowLifecycleState.REMOVED,
                ShadowLifecycleState.FAILED);
    }

    private ShadowStateMachine() {
    }

    public static boolean canTransition(ShadowLifecycleState from, ShadowLifecycleState to) {
        if (from == to) {
            return true;
        }
        EnumSet<ShadowLifecycleState> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public static void requireTransition(ShadowLifecycleState from, ShadowLifecycleState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Illegal Shadow state transition: " + from + " -> " + to);
        }
    }

    public static boolean isTransient(ShadowLifecycleState state) {
        return state == ShadowLifecycleState.STAGED
                || state == ShadowLifecycleState.VERIFYING
                || state == ShadowLifecycleState.INSTALLING
                || state == ShadowLifecycleState.ACTIVATING
                || state == ShadowLifecycleState.DISABLING
                || state == ShadowLifecycleState.ROLLING_BACK
                || state == ShadowLifecycleState.REMOVING;
    }

    private static void allow(ShadowLifecycleState from, ShadowLifecycleState... to) {
        TRANSITIONS.put(from, EnumSet.of(to[0], to));
    }
}
