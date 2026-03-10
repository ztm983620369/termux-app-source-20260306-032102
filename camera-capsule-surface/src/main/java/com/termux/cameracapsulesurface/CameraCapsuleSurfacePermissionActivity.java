package com.termux.cameracapsulesurface;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import rikka.shizuku.Shizuku;

public final class CameraCapsuleSurfacePermissionActivity extends Activity {

    private static final String LOG_TAG = "CapsuleShizukuPerm";
    private static final int REQUEST_CODE = 42017;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::requestPermissionIfNeeded;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
        (requestCode, grantResult) -> {
            if (requestCode != REQUEST_CODE) return;
            if (grantResult == PERMISSION_GRANTED) {
                Log.i(LOG_TAG, "Shizuku permission granted");
                new CameraCapsuleSurfaceEngine(this).restore();
            } else {
                Log.w(LOG_TAG, "Shizuku permission denied");
            }
            finish();
        };

    public static void start(@NonNull Context context) {
        Intent intent = new Intent(context, CameraCapsuleSurfacePermissionActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
        requestPermissionIfNeeded();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        super.onDestroy();
    }

    private void requestPermissionIfNeeded() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(LOG_TAG, "Shizuku binder unavailable");
                finish();
                return;
            }
            if (Shizuku.checkSelfPermission() == PERMISSION_GRANTED) {
                Log.i(LOG_TAG, "Shizuku permission already granted");
                new CameraCapsuleSurfaceEngine(this).restore();
                finish();
                return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Log.w(LOG_TAG, "Shizuku permission denied previously");
                finish();
                return;
            }
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable throwable) {
            Log.w(LOG_TAG, "Failed to request Shizuku permission", throwable);
            finish();
        }
    }
}
