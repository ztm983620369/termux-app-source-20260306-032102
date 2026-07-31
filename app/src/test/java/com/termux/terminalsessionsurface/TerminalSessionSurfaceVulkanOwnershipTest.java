package com.termux.terminalsessionsurface;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.termux.view.TerminalVulkanView;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class TerminalSessionSurfaceVulkanOwnershipTest {

    private ActivityController<Activity> activityController;
    private FrameLayout host;
    private TerminalSessionSurfaceView surface;

    @Before
    public void setUp() {
        activityController = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = activityController.get();
        host = new FrameLayout(activity);
        activity.setContentView(host);
        surface = new TerminalSessionSurfaceView(activity);
        host.addView(surface, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        layoutHost();
    }

    @After
    public void tearDown() {
        activityController.pause().stop().destroy();
    }

    @Test
    public void onlyCommittedVisiblePageOwnsVulkanResources() {
        surface.submitSessions(Arrays.asList(
            new TerminalSessionSurfaceItem("session-a", null),
            new TerminalSessionSurfaceItem("session-b", null),
            new TerminalSessionSurfaceItem("session-c", null)
        ), 1, false, 1L);
        surface.onHostVisible();
        settle();

        Assert.assertEquals(3, vulkanViews().size());
        Assert.assertEquals(1, activeVulkanViews());

        surface.setCurrentSessionPage(2, false, 2L);
        settle();
        Assert.assertEquals(1, activeVulkanViews());

        surface.setVisibility(View.GONE);
        Assert.assertEquals(0, activeVulkanViews());

        surface.setVisibility(View.VISIBLE);
        surface.onHostVisible();
        settle();
        Assert.assertEquals(1, activeVulkanViews());
    }

    private void settle() {
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        layoutHost();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
    }

    private void layoutHost() {
        host.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        );
        host.layout(0, 0, 1080, 1920);
    }

    private int activeVulkanViews() {
        int active = 0;
        for (TerminalVulkanView view : vulkanViews()) {
            if (view.isRenderActive()) active++;
        }
        return active;
    }

    private List<TerminalVulkanView> vulkanViews() {
        ArrayList<TerminalVulkanView> views = new ArrayList<>();
        collectVulkanViews(surface, views);
        return views;
    }

    private static void collectVulkanViews(View view, List<TerminalVulkanView> output) {
        if (view instanceof TerminalVulkanView) output.add((TerminalVulkanView) view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            collectVulkanViews(group.getChildAt(index), output);
        }
    }
}
