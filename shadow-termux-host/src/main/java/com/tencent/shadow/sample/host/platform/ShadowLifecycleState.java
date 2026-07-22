package com.tencent.shadow.sample.host.platform;

public enum ShadowLifecycleState {
    DISCOVERED,
    STAGED,
    VERIFYING,
    VERIFIED,
    INSTALLING,
    INSTALLED,
    ACTIVATING,
    HEALTHY,
    SUPERSEDED,
    DISABLING,
    DISABLED,
    ROLLING_BACK,
    ROLLED_BACK,
    REMOVING,
    REMOVED,
    QUARANTINED,
    FAILED
}
