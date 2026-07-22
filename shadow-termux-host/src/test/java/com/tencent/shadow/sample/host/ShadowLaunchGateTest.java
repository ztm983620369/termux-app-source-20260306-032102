package com.tencent.shadow.sample.host;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ShadowLaunchGateTest {

    @Test
    public void rejectsConcurrentLaunchAndOnlyOwnerCanRelease() {
        ShadowLaunchGate gate = new ShadowLaunchGate();
        gate.acquire("plugin.one", "lease-1");
        assertTrue(gate.owns("plugin.one", "lease-1"));

        try {
            gate.acquire("plugin.two", "lease-2");
            fail("concurrent launch must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().startsWith("LAUNCH_BUSY:"));
        }

        gate.release("plugin.two", "lease-2");
        assertTrue(gate.owns("plugin.one", "lease-1"));
        gate.release("plugin.one", "lease-1");
        assertFalse(gate.owns("plugin.one", "lease-1"));

        gate.acquire("plugin.two", "lease-2");
        assertTrue(gate.owns("plugin.two", "lease-2"));
    }

    @Test
    public void unclaimedLeaseExpiresButClaimedLeaseRemainsOwned() {
        ShadowLaunchGate gate = new ShadowLaunchGate();
        gate.acquire("plugin.one", "lease-1");
        assertTrue(gate.releaseIfUnclaimed("plugin.one", "lease-1"));
        assertFalse(gate.owns("plugin.one", "lease-1"));

        gate.acquire("plugin.two", "lease-2");
        assertTrue(gate.claim("plugin.two", "lease-2"));
        assertFalse(gate.releaseIfUnclaimed("plugin.two", "lease-2"));
        assertTrue(gate.owns("plugin.two", "lease-2"));
        gate.release("plugin.two", "lease-2");
        assertFalse(gate.owns("plugin.two", "lease-2"));
    }
}
