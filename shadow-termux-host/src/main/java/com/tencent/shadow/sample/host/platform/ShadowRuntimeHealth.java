package com.tencent.shadow.sample.host.platform;

/** Correlated proof emitted by the Shadow runtime after the plugin's first frame. */
public final class ShadowRuntimeHealth {

    public static final int PROTOCOL_VERSION = 1;

    public final int protocolVersion;
    public final long firstFrameElapsedMs;
    public final long stableElapsedMs;
    public final int pluginProcessPid;
    public final String pluginProcessName;
    public final boolean smokeRequested;
    public final boolean smokePassed;
    public final int smokeStepCount;
    public final long smokeDurationMs;
    public final String smokeError;

    public ShadowRuntimeHealth(
            int protocolVersion,
            long firstFrameElapsedMs,
            long stableElapsedMs,
            int pluginProcessPid,
            String pluginProcessName
    ) {
        this(
                protocolVersion,
                firstFrameElapsedMs,
                stableElapsedMs,
                pluginProcessPid,
                pluginProcessName,
                false,
                false,
                0,
                0L,
                null
        );
    }

    public ShadowRuntimeHealth(
            int protocolVersion,
            long firstFrameElapsedMs,
            long stableElapsedMs,
            int pluginProcessPid,
            String pluginProcessName,
            boolean smokeRequested,
            boolean smokePassed,
            int smokeStepCount,
            long smokeDurationMs,
            String smokeError
    ) {
        if (protocolVersion != PROTOCOL_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported runtime health protocol: " + protocolVersion
            );
        }
        if (firstFrameElapsedMs <= 0L) {
            throw new IllegalArgumentException("first-frame timestamp is missing");
        }
        if (stableElapsedMs > 0L && stableElapsedMs < firstFrameElapsedMs) {
            throw new IllegalArgumentException("stable timestamp precedes first frame");
        }
        if (pluginProcessPid <= 0) {
            throw new IllegalArgumentException("plugin process pid is missing");
        }
        if (smokeRequested && !smokePassed && stableElapsedMs > 0L) {
            throw new IllegalArgumentException("stable runtime proof cannot contain a failed UI smoke test");
        }
        if (smokeStepCount < 0 || smokeDurationMs < 0L) {
            throw new IllegalArgumentException("invalid UI smoke proof counters");
        }
        if (!smokeRequested
                && (smokePassed || smokeStepCount != 0 || smokeDurationMs != 0L
                || smokeError != null)) {
            throw new IllegalArgumentException("non-smoke proof contains UI smoke fields");
        }
        if (smokePassed && smokeStepCount <= 0) {
            throw new IllegalArgumentException("successful UI smoke proof has no executed steps");
        }
        this.protocolVersion = protocolVersion;
        this.firstFrameElapsedMs = firstFrameElapsedMs;
        this.stableElapsedMs = stableElapsedMs;
        this.pluginProcessPid = pluginProcessPid;
        this.pluginProcessName = pluginProcessName;
        this.smokeRequested = smokeRequested;
        this.smokePassed = smokePassed;
        this.smokeStepCount = smokeStepCount;
        this.smokeDurationMs = smokeDurationMs;
        this.smokeError = smokeError;
    }

    public boolean isStable() {
        return stableElapsedMs > 0L;
    }
}
