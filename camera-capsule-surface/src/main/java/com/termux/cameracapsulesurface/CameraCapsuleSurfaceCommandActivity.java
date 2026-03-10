package com.termux.cameracapsulesurface;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

public final class CameraCapsuleSurfaceCommandActivity extends Activity {

    private static final String LOG_TAG = "CameraCapsuleCmdAct";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dispatchAndFinish();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        dispatchAndFinish();
    }

    private void dispatchAndFinish() {
        Log.i(LOG_TAG, CameraCapsuleSurfaceCommandDispatcher.dispatch(this, getIntent()));
        finish();
    }
}
