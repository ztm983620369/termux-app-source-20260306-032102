package com.termux.cameracapsulesurface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class CameraCapsuleSurfaceBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(@NonNull Context context, @Nullable Intent intent) {
        new CameraCapsuleSurfaceEngine(context).restore();
    }
}
