package com.termux.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TerminalImeCameraFrameRequestPolicyTest {

    @Test
    public void firstPresentedFrameAlwaysNotifies() {
        TerminalImeCameraFrameRequestPolicy policy = new TerminalImeCameraFrameRequestPolicy();

        assertTrue(policy.shouldNotify(false));
        policy.markNotified();
        assertFalse(policy.shouldNotify(false));
    }

    @Test
    public void imeRequestForcesOneNotificationWhenPixelsAreUnchanged() {
        TerminalImeCameraFrameRequestPolicy policy = new TerminalImeCameraFrameRequestPolicy();
        policy.markNotified();

        policy.request();
        assertTrue(policy.shouldNotify(false));
        policy.markNotified();
        assertFalse(policy.shouldNotify(false));
    }

    @Test
    public void changedFrameNotifiesWithoutAnImeRequest() {
        TerminalImeCameraFrameRequestPolicy policy = new TerminalImeCameraFrameRequestPolicy();
        policy.markNotified();

        assertTrue(policy.shouldNotify(true));
    }

    @Test
    public void resetMakesTheNextFrameAuthoritativeAgain() {
        TerminalImeCameraFrameRequestPolicy policy = new TerminalImeCameraFrameRequestPolicy();
        policy.markNotified();

        policy.reset();
        assertTrue(policy.shouldNotify(false));
    }
}
