package com.termux.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TerminalVulkanFrameIdentityTest {

    @Test
    public void exactFrameIdentityRequiresOffsetAndRetainedGenerations() {
        int offsetBits = Float.floatToIntBits(6.5f);

        assertTrue(TerminalVulkanView.matchesFrameIdentity(offsetBits, 31L, 17L,
            6.5f, 31L, 17L));
        assertFalse(TerminalVulkanView.matchesFrameIdentity(offsetBits, 31L, 17L,
            6.25f, 31L, 17L));
        assertFalse(TerminalVulkanView.matchesFrameIdentity(offsetBits, 31L, 17L,
            6.5f, 32L, 17L));
        assertFalse(TerminalVulkanView.matchesFrameIdentity(offsetBits, 31L, 17L,
            6.5f, 31L, 18L));
    }
}
