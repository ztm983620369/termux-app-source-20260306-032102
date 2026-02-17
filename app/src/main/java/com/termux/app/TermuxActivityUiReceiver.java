package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.termux.shared.termux.TermuxConstants;

public class TermuxActivityUiReceiver extends BroadcastReceiver {

    public static final String ACTION_SWITCH_TAB = "com.termux.app.action.SWITCH_TAB";
    public static final String ACTION_OPEN_FILES_AT = "com.termux.app.action.OPEN_FILES_AT";

    public static final String EXTRA_TAB = "tab";
    public static final String EXTRA_PATH = "path";
    public static final String REQUESTS_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.termux/ui-bridge";
    public static final String OPEN_FILES_AT_REQUEST_FILE_PATH = REQUESTS_DIR_PATH + "/open-files-at.path";

    private final TermuxActivity mActivity;

    public TermuxActivityUiReceiver(TermuxActivity activity) {
        mActivity = activity;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION_SWITCH_TAB.equals(action)) {
            String tab = intent.getStringExtra(EXTRA_TAB);
            mActivity.switchBottomTabByName(tab);
        } else if (ACTION_OPEN_FILES_AT.equals(action)) {
            String path = intent.getStringExtra(EXTRA_PATH);
            mActivity.openFilesAtPath(path);
        }
    }
}
