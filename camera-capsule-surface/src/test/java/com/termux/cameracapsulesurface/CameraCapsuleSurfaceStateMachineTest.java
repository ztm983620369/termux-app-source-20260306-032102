package com.termux.cameracapsulesurface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.core.app.NotificationCompat;

import org.junit.Test;

import java.util.ArrayList;

public class CameraCapsuleSurfaceStateMachineTest {

    @Test
    public void evaluate_returnsIdleWhenNoSurfaceExists() {
        CameraCapsuleSurfaceStateMachine stateMachine = new CameraCapsuleSurfaceStateMachine();

        CameraCapsuleSurfaceStateMachine.Snapshot snapshot =
            stateMachine.evaluate(new ArrayList<>(), true);

        assertEquals(CameraCapsuleSurfaceStateMachine.ControlState.IDLE, snapshot.controlState);
        assertEquals(CameraCapsuleSurfaceStateMachine.ServiceCommand.STOP, snapshot.serviceCommand);
        assertFalse(snapshot.shouldPublishFallback);
        assertFalse(snapshot.shouldRenderOverlay);
        assertNull(snapshot.primaryState);
    }

    @Test
    public void evaluate_returnsPermissionBlockedWithoutOverlayPermission() {
        CameraCapsuleSurfaceStateMachine stateMachine = new CameraCapsuleSurfaceStateMachine();
        ArrayList<CameraCapsuleSurfaceState> states = new ArrayList<>();
        states.add(new CameraCapsuleSurfaceState.Builder()
            .setSurfaceId("sync")
            .setTitle("同步")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build());

        CameraCapsuleSurfaceStateMachine.Snapshot snapshot =
            stateMachine.evaluate(states, false);

        assertEquals(CameraCapsuleSurfaceStateMachine.ControlState.PERMISSION_BLOCKED, snapshot.controlState);
        assertEquals(CameraCapsuleSurfaceStateMachine.ServiceCommand.STOP, snapshot.serviceCommand);
        assertTrue(snapshot.shouldPublishFallback);
        assertFalse(snapshot.shouldRenderOverlay);
        assertNotNull(snapshot.primaryState);
    }

    @Test
    public void evaluate_selectsNewestHighestPrioritySurface() {
        CameraCapsuleSurfaceStateMachine stateMachine = new CameraCapsuleSurfaceStateMachine();
        ArrayList<CameraCapsuleSurfaceState> states = new ArrayList<>();
        states.add(new CameraCapsuleSurfaceState.Builder()
            .setSurfaceId("low")
            .setTitle("低优先级")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setUpdatedAtMs(1L)
            .build());
        states.add(new CameraCapsuleSurfaceState.Builder()
            .setSurfaceId("high-old")
            .setTitle("高优先级旧")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setUpdatedAtMs(2L)
            .build());
        states.add(new CameraCapsuleSurfaceState.Builder()
            .setSurfaceId("high-new")
            .setTitle("高优先级新")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setUpdatedAtMs(3L)
            .build());

        CameraCapsuleSurfaceStateMachine.Snapshot snapshot =
            stateMachine.evaluate(states, true);

        assertEquals(CameraCapsuleSurfaceStateMachine.ControlState.ACTIVE, snapshot.controlState);
        assertEquals("high-new", snapshot.primaryState.surfaceId);
        assertTrue(snapshot.shouldRenderOverlay);
        assertTrue(snapshot.shouldPublishFallback);
    }
}
