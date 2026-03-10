package com.termux.cameracapsulesurface;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class StatusBarDisableSpec {

    final boolean hideSystemIcons;
    final boolean hideClock;
    final boolean hideNotificationIcons;

    StatusBarDisableSpec(boolean hideSystemIcons, boolean hideClock, boolean hideNotificationIcons) {
        this.hideSystemIcons = hideSystemIcons;
        this.hideClock = hideClock;
        this.hideNotificationIcons = hideNotificationIcons;
    }

    boolean isEmpty() {
        return !hideSystemIcons && !hideClock && !hideNotificationIcons;
    }

    @NonNull
    List<String> toDisableFlags() {
        if (isEmpty()) return Collections.emptyList();
        ArrayList<String> flags = new ArrayList<>(3);
        if (hideSystemIcons) flags.add("system-icons");
        if (hideClock) flags.add("clock");
        if (hideNotificationIcons) flags.add("notification-icons");
        return flags;
    }

    @NonNull
    String signature() {
        return (hideSystemIcons ? "1" : "0") +
            (hideClock ? "1" : "0") +
            (hideNotificationIcons ? "1" : "0");
    }

    @NonNull
    static StatusBarDisableSpec from(@NonNull CameraCapsuleSurfaceState state) {
        return new StatusBarDisableSpec(
            state.hideStatusBarSystemIcons,
            state.hideStatusBarClock,
            state.hideStatusBarNotificationIcons);
    }
}
