package com.tencent.shadow.sample.host;

import android.app.Activity;
import android.content.Intent;

import androidx.annotation.NonNull;

public final class ShadowTermuxHostLauncher {

    private ShadowTermuxHostLauncher() {
    }

    public static void openHost(@NonNull Activity activity) {
        HostApplication.init(activity.getApplication());
        activity.startActivity(new Intent(activity, MainActivity.class));
    }

    public static void openMiniApps(@NonNull Activity activity) {
        openHost(activity);
    }
}
