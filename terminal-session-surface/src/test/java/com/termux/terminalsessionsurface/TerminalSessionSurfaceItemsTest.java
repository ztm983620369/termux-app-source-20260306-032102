package com.termux.terminalsessionsurface;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class TerminalSessionSurfaceItemsTest {

    @Test
    public void sameOrderedSnapshotIsDetectedAsUnchanged() {
        List<TerminalSessionSurfaceItem> currentItems = Arrays.asList(
            new TerminalSessionSurfaceItem("a", null),
            new TerminalSessionSurfaceItem("b", null)
        );
        List<TerminalSessionSurfaceItem> newItems = Arrays.asList(
            new TerminalSessionSurfaceItem("a", null),
            new TerminalSessionSurfaceItem("b", null)
        );

        Assert.assertTrue(TerminalSessionSurfaceItems.hasSameItems(currentItems, newItems));
    }

    @Test
    public void reorderedSnapshotIsDetectedAsChanged() {
        List<TerminalSessionSurfaceItem> currentItems = Arrays.asList(
            new TerminalSessionSurfaceItem("a", null),
            new TerminalSessionSurfaceItem("b", null)
        );
        List<TerminalSessionSurfaceItem> newItems = Arrays.asList(
            new TerminalSessionSurfaceItem("b", null),
            new TerminalSessionSurfaceItem("a", null)
        );

        Assert.assertFalse(TerminalSessionSurfaceItems.hasSameItems(currentItems, newItems));
    }

    @Test
    public void differentSnapshotSizeIsDetectedAsChanged() {
        List<TerminalSessionSurfaceItem> currentItems = Arrays.asList(
            new TerminalSessionSurfaceItem("a", null),
            new TerminalSessionSurfaceItem("b", null)
        );
        List<TerminalSessionSurfaceItem> newItems = Arrays.asList(
            new TerminalSessionSurfaceItem("a", null)
        );

        Assert.assertFalse(TerminalSessionSurfaceItems.hasSameItems(currentItems, newItems));
    }
}
