package com.termux.terminalsessionsurface;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;

import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TerminalSessionSurfaceStructuralSelectionTest {

    private ActivityController<Activity> activityController;
    private Activity activity;
    private TerminalSessionSurfaceView surface;

    @Before
    public void setUp() {
        activityController = Robolectric.buildActivity(Activity.class).setup();
        activity = activityController.get();
        FrameLayout host = new FrameLayout(activity);
        activity.setContentView(host);
        surface = new TerminalSessionSurfaceView(activity);
        host.addView(surface, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        layoutHost(host);
        surface.setConfigPageView(new View(activity));
        surface.setConfigPageEnabled(true);
        surface.setCurrentConfigPage(false);
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        layoutHost(host);
        Assert.assertNull(surface.getCurrentTerminalView());
    }

    @After
    public void tearDown() {
        activityController.pause().stop().destroy();
    }

    @Test
    public void insertingSessionBeforeConfigSelectsRequestedSessionAtSameOldIndex() {
        surface.submitSessions(
            Collections.singletonList(new TerminalSessionSurfaceItem("session-a", null)),
            0,
            false,
            1L
        );

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        layoutHost((FrameLayout) surface.getParent());

        Assert.assertNotNull(surface.getCurrentTerminalView());
    }

    @Test
    public void structuralChangeKeepsConfigWhenConfigRemainsRequested() {
        surface.submitSessions(
            Collections.singletonList(new TerminalSessionSurfaceItem("session-a", null)),
            1,
            false,
            1L
        );
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        surface.submitSessions(
            Arrays.asList(
                new TerminalSessionSurfaceItem("session-a", null),
                new TerminalSessionSurfaceItem("session-b", null)
            ),
            2,
            false,
            2L
        );
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        layoutHost((FrameLayout) surface.getParent());

        Assert.assertNull(surface.getCurrentTerminalView());
    }

    @Test
    public void reorderingSessionsKeepsSelectedStablePageIdentity() {
        surface.submitSessions(
            Arrays.asList(
                new TerminalSessionSurfaceItem("session-a", null),
                new TerminalSessionSurfaceItem("session-b", null)
            ),
            0,
            false,
            1L
        );
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        TerminalView selectedSessionView = surface.getCurrentTerminalView();
        Assert.assertNotNull(selectedSessionView);

        surface.submitSessions(
            Arrays.asList(
                new TerminalSessionSurfaceItem("session-b", null),
                new TerminalSessionSurfaceItem("session-a", null)
            ),
            1,
            false,
            2L
        );
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        layoutHost((FrameLayout) surface.getParent());

        Assert.assertSame(selectedSessionView, surface.getCurrentTerminalView());
        Assert.assertNotNull(selectedSessionView.getParent());
    }

    @Test
    public void repeatedHostVisibleRebindsSameAttachedPage() {
        surface.submitSessions(
            Collections.singletonList(new TerminalSessionSurfaceItem("session-a", null)),
            0,
            false,
            1L
        );
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        TerminalView selectedSessionView = surface.getCurrentTerminalView();
        Assert.assertNotNull(selectedSessionView);

        RecordingCallbacks callbacks = new RecordingCallbacks();
        surface.setCallbacks(callbacks);
        callbacks.activeViewChanges = 0;

        surface.onHostVisible();
        surface.onHostVisible();

        Assert.assertEquals(2, callbacks.activeViewChanges);
        Assert.assertSame(selectedSessionView, callbacks.lastActiveView);
        Assert.assertSame(selectedSessionView, surface.getCurrentTerminalView());
        Assert.assertNotNull(selectedSessionView.getParent());
    }

    private void layoutHost(FrameLayout host) {
        int width = 1080;
        int height = 1920;
        host.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        );
        host.layout(0, 0, width, height);
    }

    private static final class RecordingCallbacks implements TerminalSessionSurfaceView.Callbacks {
        int activeViewChanges;
        TerminalView lastActiveView;

        @Override
        public void onSessionPageSwipeTouchDown() {
        }

        @Override
        public void onSessionPageChangeStarted() {
        }

        @Override
        public void onSessionPageChangeFinished() {
        }

        @Override
        public void onSessionPagePreviewSelected(int index, TerminalSession session) {
        }

        @Override
        public void onConfigPagePreviewSelected() {
        }

        @Override
        public void onSessionPageSelected(int index, TerminalSession session, boolean fromUser,
                                          long requestToken) {
        }

        @Override
        public void onConfigPageSelected(boolean fromUser, long requestToken) {
        }

        @Override
        public void onActiveTerminalViewChanged(TerminalView terminalView, TerminalSession session) {
            activeViewChanges++;
            lastActiveView = terminalView;
        }

        @Override
        public void onExtraKeysViewCreated(ExtraKeysView extraKeysView) {
        }
    }
}
