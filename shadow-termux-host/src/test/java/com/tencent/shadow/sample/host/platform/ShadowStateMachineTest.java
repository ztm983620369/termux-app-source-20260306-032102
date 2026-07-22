package com.tencent.shadow.sample.host.platform;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShadowStateMachineTest {

    @Test
    public void installActivateAndRemoveFlowIsValid() {
        assertTransition(ShadowLifecycleState.DISCOVERED, ShadowLifecycleState.STAGED);
        assertTransition(ShadowLifecycleState.STAGED, ShadowLifecycleState.VERIFYING);
        assertTransition(ShadowLifecycleState.VERIFYING, ShadowLifecycleState.VERIFIED);
        assertTransition(ShadowLifecycleState.VERIFIED, ShadowLifecycleState.INSTALLING);
        assertTransition(ShadowLifecycleState.INSTALLING, ShadowLifecycleState.INSTALLED);
        assertTransition(ShadowLifecycleState.INSTALLED, ShadowLifecycleState.ACTIVATING);
        assertTransition(ShadowLifecycleState.ACTIVATING, ShadowLifecycleState.HEALTHY);
        assertTransition(ShadowLifecycleState.HEALTHY, ShadowLifecycleState.SUPERSEDED);
        assertTransition(ShadowLifecycleState.SUPERSEDED, ShadowLifecycleState.REMOVING);
        assertTransition(ShadowLifecycleState.REMOVING, ShadowLifecycleState.REMOVED);
    }

    @Test
    public void rollbackFlowIsValid() {
        assertTransition(ShadowLifecycleState.ACTIVATING, ShadowLifecycleState.ROLLING_BACK);
        assertTransition(ShadowLifecycleState.ROLLING_BACK, ShadowLifecycleState.ROLLED_BACK);
        assertTransition(ShadowLifecycleState.ROLLED_BACK, ShadowLifecycleState.ACTIVATING);
    }

    @Test(expected = IllegalStateException.class)
    public void cannotJumpFromDiscoveredToHealthy() {
        ShadowStateMachine.requireTransition(
                ShadowLifecycleState.DISCOVERED,
                ShadowLifecycleState.HEALTHY
        );
    }

    @Test
    public void transientStatesAreExplicit() {
        assertTrue(ShadowStateMachine.isTransient(ShadowLifecycleState.VERIFYING));
        assertTrue(ShadowStateMachine.isTransient(ShadowLifecycleState.ACTIVATING));
        assertTrue(ShadowStateMachine.isTransient(ShadowLifecycleState.REMOVING));
        assertFalse(ShadowStateMachine.isTransient(ShadowLifecycleState.HEALTHY));
        assertFalse(ShadowStateMachine.isTransient(ShadowLifecycleState.QUARANTINED));
    }

    @Test
    public void operatorCanRemoveOrDisableActiveWork() {
        assertTransition(ShadowLifecycleState.DISCOVERED, ShadowLifecycleState.REMOVING);
        assertTransition(ShadowLifecycleState.VERIFYING, ShadowLifecycleState.REMOVING);
        assertTransition(ShadowLifecycleState.INSTALLING, ShadowLifecycleState.REMOVING);
        assertTransition(ShadowLifecycleState.ACTIVATING, ShadowLifecycleState.DISABLING);
        assertTransition(ShadowLifecycleState.ACTIVATING, ShadowLifecycleState.REMOVING);
        assertTransition(ShadowLifecycleState.FAILED, ShadowLifecycleState.DISABLING);
    }

    private static void assertTransition(ShadowLifecycleState from, ShadowLifecycleState to) {
        assertTrue(from + " -> " + to, ShadowStateMachine.canTransition(from, to));
        ShadowStateMachine.requireTransition(from, to);
    }
}
