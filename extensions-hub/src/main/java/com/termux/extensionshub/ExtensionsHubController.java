package com.termux.extensionshub;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;

public final class ExtensionsHubController {

    @NonNull
    private final Context mContext;
    @NonNull
    private final View mRootView;
    @NonNull
    private final TextView mStatusView;

    public ExtensionsHubController(@NonNull Context context) {
        mContext = context;
        mRootView = LayoutInflater.from(context).inflate(R.layout.view_extensions_hub, null, false);
        mStatusView = mRootView.findViewById(R.id.extensions_hub_status);
    }

    public void attachTo(@NonNull ViewGroup container) {
        View parent = (View) mRootView.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(mRootView);
        }
        container.removeAllViews();
        container.addView(mRootView, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    public void onShow() {
        mStatusView.setText(mContext.getString(R.string.extensions_hub_status_ready));
    }

    public void onHide() {
        mStatusView.setText(mContext.getString(R.string.extensions_hub_status_idle));
    }
}
