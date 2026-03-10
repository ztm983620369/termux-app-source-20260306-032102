package com.termux.cameracapsulesurface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

final class CameraCapsuleSurfaceSelector {

    private CameraCapsuleSurfaceSelector() {
    }

    @Nullable
    static CameraCapsuleSurfaceState selectPrimary(@NonNull List<CameraCapsuleSurfaceState> states) {
        CameraCapsuleSurfaceState best = null;
        for (CameraCapsuleSurfaceState candidate : states) {
            if (candidate == null) continue;
            if (best == null || compare(candidate, best) > 0) best = candidate;
        }
        return best;
    }

    private static int compare(@NonNull CameraCapsuleSurfaceState left,
                               @NonNull CameraCapsuleSurfaceState right) {
        if (left.priority != right.priority) return Integer.compare(left.priority, right.priority);
        if (left.updatedAtMs != right.updatedAtMs) return Long.compare(left.updatedAtMs, right.updatedAtMs);
        return right.surfaceId.compareTo(left.surfaceId) * -1;
    }
}
