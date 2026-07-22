package com.tencent.shadow.sample.host.plugin_view;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.tencent.shadow.dynamic.host.PluginManager;
import com.tencent.shadow.sample.constant.Constant;
import com.tencent.shadow.sample.host.HostApplication;

public class MainProcessManagerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PluginManager pluginManager = HostApplication.getApp().getPluginManager();
        if (pluginManager != null) {
            pluginManager.enter(context, Constant.FROM_ID_LOAD_VIEW_TO_HOST, intent.getExtras(), null);
        }
    }
}
