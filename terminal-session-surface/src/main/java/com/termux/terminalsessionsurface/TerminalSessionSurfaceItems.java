package com.termux.terminalsessionsurface;

import androidx.annotation.NonNull;

import java.util.List;

public final class TerminalSessionSurfaceItems {

    private TerminalSessionSurfaceItems() {
    }

    public static boolean hasSameItems(@NonNull List<TerminalSessionSurfaceItem> currentItems,
                                       @NonNull List<TerminalSessionSurfaceItem> newItems) {
        if (currentItems.size() != newItems.size()) return false;

        for (int i = 0; i < currentItems.size(); i++) {
            TerminalSessionSurfaceItem currentItem = currentItems.get(i);
            TerminalSessionSurfaceItem newItem = newItems.get(i);
            if (!currentItem.key.equals(newItem.key)) return false;
            if (currentItem.session != newItem.session) return false;
        }

        return true;
    }
}
