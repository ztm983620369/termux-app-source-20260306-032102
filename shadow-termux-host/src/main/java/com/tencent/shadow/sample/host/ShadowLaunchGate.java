package com.tencent.shadow.sample.host;

/** Process-local single-flight gate for runtime activation. */
final class ShadowLaunchGate {

    private String leaseId;
    private String pluginId;
    private boolean claimed;

    synchronized void acquire(String requestedPluginId, String requestedLeaseId) {
        if (isBlank(requestedPluginId) || isBlank(requestedLeaseId)) {
            throw new IllegalArgumentException("launch pluginId and leaseId are required");
        }
        if (leaseId != null) {
            throw new IllegalStateException(
                    "LAUNCH_BUSY: " + pluginId + " already owns " + leaseId
            );
        }
        pluginId = requestedPluginId;
        leaseId = requestedLeaseId;
        claimed = false;
    }

    synchronized boolean owns(String requestedPluginId, String requestedLeaseId) {
        return requestedLeaseId != null
                && requestedLeaseId.equals(leaseId)
                && requestedPluginId != null
                && requestedPluginId.equals(pluginId);
    }

    synchronized boolean claim(String requestedPluginId, String requestedLeaseId) {
        if (!owns(requestedPluginId, requestedLeaseId)) {
            return false;
        }
        claimed = true;
        return true;
    }

    synchronized boolean releaseIfUnclaimed(String requestedPluginId, String requestedLeaseId) {
        if (!owns(requestedPluginId, requestedLeaseId) || claimed) {
            return false;
        }
        clear();
        return true;
    }

    synchronized void release(String requestedPluginId, String requestedLeaseId) {
        if (owns(requestedPluginId, requestedLeaseId)) {
            clear();
        }
    }

    private void clear() {
        leaseId = null;
        pluginId = null;
        claimed = false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }
}
