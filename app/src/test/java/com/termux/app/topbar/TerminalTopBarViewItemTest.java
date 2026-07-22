package com.termux.app.topbar;

import org.junit.Assert;
import org.junit.Test;

public class TerminalTopBarViewItemTest {

    @Test
    public void equivalentSnapshotsDoNotRequireRebinding() {
        TerminalTopBarView.Item first = item(false, "shell");
        TerminalTopBarView.Item second = item(false, "shell");

        Assert.assertTrue(first.hasSameVisualState(second));
    }

    @Test
    public void selectionAndTitleChangesRequireRebinding() {
        TerminalTopBarView.Item baseline = item(false, "shell");

        Assert.assertFalse(baseline.hasSameVisualState(item(true, "shell")));
        Assert.assertFalse(baseline.hasSameVisualState(item(false, "server")));
    }

    private TerminalTopBarView.Item item(boolean selected, String title) {
        return new TerminalTopBarView.Item(
            "session-1",
            title,
            selected,
            false,
            true,
            TerminalTopBarStateMachine.Tone.ACTIVE,
            null,
            title
        );
    }
}
