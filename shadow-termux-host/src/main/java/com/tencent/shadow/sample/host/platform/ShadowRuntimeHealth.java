package com.tencent.shadow.sample.host.platform;

/** Correlated proof emitted by the Shadow runtime after the plugin's first frame. */
public final class ShadowRuntimeHealth {

    public static final int PROTOCOL_VERSION = 1;

    public final int protocolVersion;
    public final long firstFrameElapsedMs;
    public final long stableElapsedMs;
    public final int pluginProcessPid;
    public final String pluginProcessName;

    public ShadowRuntimeHealth(
            int protocolVersion,
            long firstFrameElapsedMs,
            long stableElapsedMs,
            int pluginProcessPid,
            String pluginProcessName
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
        this.protocolVersion = protocolVersion;
        this.firstFrameElapsedMs = firstFrameElapsedMs;
        this.stableElapsedMs = stableElapsedMs;
        this.pluginProcessPid = pluginProcessPid;
        this.pluginProcessName = pluginProcessName;
    }

    public boolean isStable() {
        return stableElapsedMs > 0L;
    }
}
