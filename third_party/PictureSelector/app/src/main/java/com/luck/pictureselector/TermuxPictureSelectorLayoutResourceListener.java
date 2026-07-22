package com.luck.pictureselector;

import android.content.Context;

import com.luck.picture.lib.config.InjectResourceSource;
import com.luck.picture.lib.interfaces.OnInjectLayoutResourceListener;

final class TermuxPictureSelectorLayoutResourceListener implements OnInjectLayoutResourceListener {
    @Override
    public int getLayoutResourceId(Context context, int resourceSource) {
        if (resourceSource == InjectResourceSource.MAIN_SELECTOR_LAYOUT_RESOURCE) {
            return R.layout.ps_termux_fragment_selector;
        }
        return InjectResourceSource.DEFAULT_LAYOUT_RESOURCE;
    }
}
