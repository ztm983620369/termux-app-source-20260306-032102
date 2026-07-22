package com.termux.extensionshub;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.luck.pictureselector.TermuxPictureSelectorLauncher;
import com.tencent.shadow.sample.host.ShadowTermuxHostLauncher;

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
        View pictureSelectorButton = mRootView.findViewById(R.id.extensions_hub_picture_selector_button);
        pictureSelectorButton.setOnClickListener(v -> openPictureSelector());
        View shadowHostButton = mRootView.findViewById(R.id.extensions_hub_shadow_miniapps_button);
        shadowHostButton.setOnClickListener(v -> openShadowHost());
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

    private void openPictureSelector() {
        try {
            Activity activity = findActivity(mContext);
            if (activity == null) {
                throw new IllegalStateException("PictureSelector requires an Activity context");
            }
            TermuxPictureSelectorLauncher.openGallery(activity);
            mStatusView.setText(mContext.getString(R.string.extensions_hub_status_picture_selector_opened));
        } catch (RuntimeException e) {
            mStatusView.setText(mContext.getString(R.string.extensions_hub_status_picture_selector_failed));
            Toast.makeText(mContext, R.string.extensions_hub_status_picture_selector_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openShadowHost() {
        try {
            Activity activity = findActivity(mContext);
            if (activity == null) {
                throw new IllegalStateException("Shadow host requires an Activity context");
            }
            ShadowTermuxHostLauncher.openHost(activity);
            mStatusView.setText(mContext.getString(R.string.extensions_hub_status_shadow_opened));
        } catch (RuntimeException e) {
            mStatusView.setText(mContext.getString(R.string.extensions_hub_status_shadow_failed));
            Toast.makeText(mContext, R.string.extensions_hub_status_shadow_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static Activity findActivity(@NonNull Context context) {
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
