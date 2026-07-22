package com.luck.pictureselector;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import com.luck.picture.lib.config.SelectorProviders;
import com.luck.picture.lib.widget.TitleBar;

public class TermuxPictureSelectorTitleBar extends TitleBar {
    private TextView settingsView;

    public TermuxPictureSelectorTitleBar(Context context) {
        super(context);
        initSettingsButton();
    }

    public TermuxPictureSelectorTitleBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        initSettingsButton();
    }

    public TermuxPictureSelectorTitleBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initSettingsButton();
    }

    @Override
    protected void inflateLayout() {
        inflate(getContext(), R.layout.ps_termux_title_bar, this);
    }

    @Override
    public void setTitleBarStyle() {
        super.setTitleBarStyle();
        if (settingsView != null && tvCancel != null) {
            settingsView.setTextColor(tvCancel.getCurrentTextColor());
            settingsView.setTextSize(14);
            settingsView.setVisibility(VISIBLE);
        }
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.ps_tv_settings) {
            openSettings();
            return;
        }
        super.onClick(view);
    }

    private void initSettingsButton() {
        settingsView = findViewById(R.id.ps_tv_settings);
        if (settingsView != null) {
            settingsView.setOnClickListener(this);
        }
    }

    private void openSettings() {
        Context context = getContext();
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(TermuxPictureSelectorSettings.EXTRA_FROM_ALBUM_SETTINGS, true);
        Activity activity = findActivity(context);
        if (activity == null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } else {
            activity.startActivity(intent);
            activity.finish();
            SelectorProviders.getInstance().destroy();
        }
    }

    private static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }
}
