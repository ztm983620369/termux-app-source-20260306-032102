package com.termux.terminalsessionsurface;

import androidx.annotation.NonNull;

import java.util.List;

public final class TerminalSessionSurfaceItems {

    public enum ChangeType {
        NONE,
        CONTENT,
        STRUCTURE
    }

    private TerminalSessionSurfaceItems() {
    }

    public static boolean hasSameItems(@NonNull List<TerminalSessionSurfaceItem> currentItems,
                                       @NonNull List<TerminalSessionSurfaceItem> newItems) {
        return classifyChange(currentItems, newItems) == ChangeType.NONE;
    }

    @NonNull
    public static ChangeType classifyChange(@NonNull List<TerminalSessionSurfaceItem> currentItems,
                                            @NonNull List<TerminalSessionSurfaceItem> newItems) {
        if (currentItems.size() != newItems.size()) return ChangeType.STRUCTURE;

        boolean contentChanged = false;
        for (int i = 0; i < currentItems.size(); i++) {
            TerminalSessionSurfaceItem currentItem = currentItems.get(i);
            TerminalSessionSurfaceItem newItem = newItems.get(i);
            if (!currentItem.key.equals(newItem.key)) return ChangeType.STRUCTURE;
            if (currentItem.session != newItem.session) contentChanged = true;
        }

        return contentChanged ? ChangeType.CONTENT : ChangeType.NONE;
    }
}
