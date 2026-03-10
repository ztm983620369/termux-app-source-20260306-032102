package com.termux.cameracapsulesurface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class CameraCapsuleSurfaceReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "CameraCapsuleSurface";

    @Override
    public void onReceive(@NonNull Context context, @Nullable Intent intent) {
        Log.i(LOG_TAG, CameraCapsuleSurfaceCommandDispatcher.dispatch(context, intent));
    }
}
