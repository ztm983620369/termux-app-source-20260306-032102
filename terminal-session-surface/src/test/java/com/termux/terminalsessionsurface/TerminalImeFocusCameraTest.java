package com.termux.terminalsessionsurface;

import org.junit.Assert;
import org.junit.Test;

public class TerminalImeFocusCameraTest {

    @Test
    public void tracksCursorAgainstOneStableFocusTarget() {
        TerminalImeFocusCamera camera = new TerminalImeFocusCamera();

        TerminalImeFocusCamera.Decision initial = camera.update(ready(800, 824, 0));
        TerminalImeFocusCamera.Decision moved = camera.update(ready(500, 524,
            initial.translationY));

        Assert.assertEquals(700, initial.focusTargetBottomInWindow);
        Assert.assertEquals(-224, initial.translationY);
        Assert.assertEquals(initial.translationY, moved.translationY);
        Assert.assertTrue(100 + 524 + moved.translationY <=
            initial.focusTargetBottomInWindow);
        Assert.assertEquals(TerminalImeFocusCamera.Cause.STABLE, moved.cause);
    }

    @Test
    public void neverPushesTerminalBelowItsNaturalOrigin() {
        TerminalImeFocusCamera camera = new TerminalImeFocusCamera();

        TerminalImeFocusCamera.Decision decision = camera.update(ready(100, 124, 0));

        Assert.assertEquals(0, decision.translationY);
        Assert.assertEquals(TerminalImeFocusCamera.Cause.INITIAL_FOCUS, decision.cause);
    }

    @Test
    public void bottomCursorCannotExposeAnArtificialGapBelowTerminalPixels() {
        TerminalImeFocusCamera camera = new TerminalImeFocusCamera();

        TerminalImeFocusCamera.Decision decision = camera.update(ready(976, 1000, 0));

        Assert.assertEquals(-400, decision.translationY);
        Assert.assertEquals(700, 100 + 1000 + decision.translationY);
    }

    @Test
    public void presentedSemanticFooterStaysAboveTerminalChrome() {
        TerminalImeFocusCamera camera = new TerminalImeFocusCamera();

        TerminalImeFocusCamera.Decision decision = camera.update(
            readyWithProtectedTail(800, 824, 960, 0));

        Assert.assertEquals(-360, decision.translationY);
        Assert.assertEquals(700, 100 + 960 + decision.translationY);
        Assert.assertEquals(TerminalImeFocusCamera.Cause.INITIAL_FOCUS, decision.cause);
    }

    @Test
    public void paintedFooterNeverPushesWritableCursorAboveTerminalTop() {
        TerminalImeFocusCamera camera = new TerminalImeFocusCamera();

        TerminalImeFocusCamera.Decision decision = camera.update(
            readyWithProtectedTail(40, 64, 960, 0));

        Assert.assertEquals(-40, decision.translationY);
        Assert.assertEquals(100, 100 + 40 + decision.translationY);
    }

    @Test
    public void committedFooterMovementDoesNotMoveTheCamera() {
        TerminalImeFocusCamera camera = new TerminalImeFocusCamera();
        TerminalImeFocusCamera.Decision initial = camera.update(
            readyWithProtectedTail(800, 824, 960, 0));

        TerminalImeFocusCamera.Decision moved = camera.update(
            readyWithProtectedTail(800, 824, 920, initial.translationY));

        Assert.assertEquals(initial.focusTargetBottomInWindow,
            moved.focusTargetBottomInWindow);
        Assert.assertEquals(initial.translationY, moved.translationY);
        Assert.assertEquals(TerminalImeFocusCamera.Cause.STABLE, moved.cause);
    }

    @Test
    public void committedFooterMayTightenTheCameraWithoutChangingItsTarget() {
        TerminalImeFocusCamera camera = new TerminalImeFocusCamera();
        TerminalImeFocusCamera.Decision initial = camera.update(
            readyWithProtectedTail(800, 824, 920, 0));

        TerminalImeFocusCamera.Decision tightened = camera.update(
            readyWithProtectedTail(800, 824, 960, initial.translationY));

        Assert.assertEquals(700, initial.focusTargetBottomInWindow);
        Assert.assertEquals(initial.focusTargetBottomInWindow,
            tightened.focusTargetBottomInWindow);
        Assert.assertEquals(-320, initial.translationY);
        Assert.assertEquals(-360, tightened.translationY);
    }

    @Test
    public void pendingFrameHoldsTheExactCommittedTransform() {
        TerminalImeFocusCamera camera = new TerminalImeFocusCamera();
        TerminalImeFocusCamera.Decision initial = camera.update(ready(800, 824, 0));

        TerminalImeFocusCamera.Decision pending = camera.update(new TerminalImeFocusCamera.Request(
            true, false, TerminalImeFocusCamera.Availability.FRAME_PENDING,
            0, 0, 0, -1, -1, initial.translationY));

        Assert.assertEquals(initial.translationY, pending.translationY);
        Assert.assertEquals(TerminalImeFocusCamera.Phase.WAITING_READY, pending.phase);
        Assert.assertEquals(TerminalImeFocusCamera.Cause.FRAME_PENDING_HELD, pending.cause);

        TerminalImeFocusCamera.Decision resumed = camera.update(ready(760, 784,
            pending.translationY));
        Assert.assertEquals(initial.focusTargetBottomInWindow,
            resumed.focusTargetBottomInWindow);
        Assert.assertEquals(initial.translationY, resumed.translationY);
        Assert.assertEquals(TerminalImeFocusCamera.Cause.STABLE, resumed.cause);
    }

    @Test
    public void unavailablePaintedTailDoesNotCancelAValidCursorAnchor() {
        TerminalImeFocusCamera camera = new TerminalImeFocusCamera();

        TerminalImeFocusCamera.Decision decision = camera.update(
            new TerminalImeFocusCamera.Request(true, false,
                TerminalImeFocusCamera.Availability.READY,
                100, 1000, 700, 800, 824, -1, 0));

        Assert.assertEquals(-224, decision.translationY);
        Assert.assertEquals(TerminalImeFocusCamera.Phase.TRACKING, decision.phase);
    }

    @Test
    public void historyOwnsViewportUntilExplicitInputReturnsToLiveEdge() {
        TerminalImeFocusCamera camera = new TerminalImeFocusCamera();
        TerminalImeFocusCamera.Decision initial = camera.update(ready(800, 824, 0));

        TerminalImeFocusCamera.Decision history = camera.update(
            new TerminalImeFocusCamera.Request(true, false,
                TerminalImeFocusCamera.Availability.HISTORY_OWNED,
                100, 1000, 700, -1, -1, initial.translationY));
        TerminalImeFocusCamera.Decision passiveLive = camera.update(ready(800, 824,
            history.translationY));
        camera.requestExplicitLiveFocus();
        TerminalImeFocusCamera.Decision explicitLive = camera.update(ready(800, 824,
            passiveLive.translationY));

        Assert.assertEquals(initial.translationY, history.translationY);
        Assert.assertEquals(TerminalImeFocusCamera.Phase.HISTORY_OWNED, history.phase);
        Assert.assertEquals(history.translationY, passiveLive.translationY);
        Assert.assertEquals(TerminalImeFocusCamera.Cause.HISTORY_HELD, passiveLive.cause);
        Assert.assertEquals(-224, explicitLive.translationY);
        Assert.assertEquals(TerminalImeFocusCamera.Phase.TRACKING, explicitLive.phase);
    }

    @Test
    public void explicitInputSurvivesInterveningHistoryAndPendingFrames() {
        TerminalImeFocusCamera camera = new TerminalImeFocusCamera();
        TerminalImeFocusCamera.Decision history = camera.update(
            new TerminalImeFocusCamera.Request(true, false,
                TerminalImeFocusCamera.Availability.HISTORY_OWNED,
                100, 1000, 700, -1, -1, 0));

        camera.requestExplicitLiveFocus();
        TerminalImeFocusCamera.Decision staleHistory = camera.update(
            new TerminalImeFocusCamera.Request(true, false,
                TerminalImeFocusCamera.Availability.HISTORY_OWNED,
                100, 1000, 700, -1, -1, history.translationY));
        TerminalImeFocusCamera.Decision pending = camera.update(
            new TerminalImeFocusCamera.Request(true, false,
                TerminalImeFocusCamera.Availability.FRAME_PENDING,
                100, 1000, 700, -1, -1, staleHistory.translationY));
        TerminalImeFocusCamera.Decision ready = camera.update(ready(800, 824,
            pending.translationY));

        Assert.assertEquals(TerminalImeFocusCamera.Phase.WAITING_READY,
            staleHistory.phase);
        Assert.assertEquals(TerminalImeFocusCamera.Cause.FRAME_PENDING_HELD,
            staleHistory.cause);
        Assert.assertEquals(TerminalImeFocusCamera.Phase.WAITING_READY, pending.phase);
        Assert.assertEquals(-224, ready.translationY);
        Assert.assertEquals(TerminalImeFocusCamera.Phase.TRACKING, ready.phase);
        Assert.assertEquals(TerminalImeFocusCamera.Cause.INITIAL_FOCUS, ready.cause);
    }

    @Test
    public void realBoundaryChangeReanchorsButContentDoesNotChangeTarget() {
        TerminalImeFocusCamera camera = new TerminalImeFocusCamera();
        TerminalImeFocusCamera.Decision initial = camera.update(ready(800, 824, 0));
        TerminalImeFocusCamera.Decision content = camera.update(ready(760, 784,
            initial.translationY));
        TerminalImeFocusCamera.Decision boundary = camera.update(
            new TerminalImeFocusCamera.Request(true, true,
                TerminalImeFocusCamera.Availability.READY,
                100, 1000, 600, 760, 784, content.translationY));

        Assert.assertEquals(initial.focusTargetBottomInWindow,
            content.focusTargetBottomInWindow);
        Assert.assertEquals(600, boundary.focusTargetBottomInWindow);
        Assert.assertEquals(TerminalImeFocusCamera.Cause.GEOMETRY_CHANGED, boundary.cause);
    }

    @Test
    public void repeatedExplicitInputDoesNotMoveTheEstablishedTarget() {
        TerminalImeFocusCamera camera = new TerminalImeFocusCamera();
        TerminalImeFocusCamera.Decision initial = camera.update(ready(800, 824, 0));

        camera.requestExplicitLiveFocus();
        TerminalImeFocusCamera.Decision repeated = camera.update(ready(800, 824,
            initial.translationY));

        Assert.assertEquals(initial.focusTargetBottomInWindow,
            repeated.focusTargetBottomInWindow);
        Assert.assertEquals(initial.translationY, repeated.translationY);
        Assert.assertEquals(TerminalImeFocusCamera.Cause.STABLE, repeated.cause);
    }

    @Test
    public void hidingImeRestoresNaturalPresentation() {
        TerminalImeFocusCamera camera = new TerminalImeFocusCamera();
        camera.update(ready(800, 824, 0));

        TerminalImeFocusCamera.Decision hidden = camera.update(
            new TerminalImeFocusCamera.Request(false, false,
                TerminalImeFocusCamera.Availability.FRAME_PENDING,
                0, 0, 0, -1, -1, -512));

        Assert.assertEquals(0, hidden.translationY);
        Assert.assertEquals(-1, hidden.focusTargetBottomInWindow);
        Assert.assertEquals(TerminalImeFocusCamera.Phase.HIDDEN, hidden.phase);
    }

    private static TerminalImeFocusCamera.Request ready(int cursorTop, int cursorBottom,
                                                        float translation) {
        return new TerminalImeFocusCamera.Request(true, false,
            TerminalImeFocusCamera.Availability.READY,
            100, 1000, 700, cursorTop, cursorBottom, translation);
    }

    private static TerminalImeFocusCamera.Request readyWithProtectedTail(
            int cursorTop, int cursorBottom, int protectedBottom, float translation) {
        return new TerminalImeFocusCamera.Request(true, false,
            TerminalImeFocusCamera.Availability.READY,
            100, 1000, 700, cursorTop, cursorBottom, protectedBottom, translation);
    }
}
