package com.termux.terminal;

import org.junit.Assert;
import org.junit.Test;

public class TerminalSessionProcessSafetyTest {

    @Test
    public void rejectsUninitializedAndFinishedProcessIds() {
        Assert.assertFalse(TerminalSession.isSafeSignalTarget(0, 100));
        Assert.assertFalse(TerminalSession.isSafeSignalTarget(-1, 100));
    }

    @Test
    public void rejectsHostProcessId() {
        Assert.assertFalse(TerminalSession.isSafeSignalTarget(100, 100));
    }

    @Test
    public void acceptsPositiveChildProcessId() {
        Assert.assertTrue(TerminalSession.isSafeSignalTarget(101, 100));
    }
}
