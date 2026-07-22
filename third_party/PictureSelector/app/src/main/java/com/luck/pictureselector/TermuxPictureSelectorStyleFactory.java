package com.luck.pictureselector;

import android.content.Context;
import android.graphics.Color;

import androidx.core.content.ContextCompat;

import com.luck.picture.lib.style.BottomNavBarStyle;
import com.luck.picture.lib.style.PictureSelectorStyle;
import com.luck.picture.lib.style.PictureWindowAnimationStyle;
import com.luck.picture.lib.style.SelectMainStyle;
import com.luck.picture.lib.style.TitleBarStyle;
import com.luck.picture.lib.utils.DensityUtil;

final class TermuxPictureSelectorStyleFactory {
    private TermuxPictureSelectorStyleFactory() {
    }

    static PictureSelectorStyle create(Context context, TermuxPictureSelectorSettings.Snapshot settings) {
        PictureSelectorStyle style = new PictureSelectorStyle();
        if (settings.windowAnimationUp) {
            PictureWindowAnimationStyle animationStyle = new PictureWindowAnimationStyle();
            animationStyle.setActivityEnterAnimation(R.anim.ps_anim_up_in);
            animationStyle.setActivityExitAnimation(R.anim.ps_anim_down_out);
            style.setWindowAnimationStyle(animationStyle);
        }

        if (settings.styleMode == TermuxPictureSelectorSettings.STYLE_WHITE) {
            applyWhiteStyle(context, style);
        } else if (settings.styleMode == TermuxPictureSelectorSettings.STYLE_NUMBER) {
            applyNumberStyle(context, style);
        } else if (settings.styleMode == TermuxPictureSelectorSettings.STYLE_WECHAT) {
            applyWechatStyle(context, style, settings.onlySandboxDir);
        }
        style.getTitleBarStyle().setHideCancelButton(false);
        return style;
    }

    private static void applyWhiteStyle(Context context, PictureSelectorStyle style) {
        TitleBarStyle titleBarStyle = new TitleBarStyle();
        titleBarStyle.setTitleBackgroundColor(ContextCompat.getColor(context, R.color.ps_color_white));
        titleBarStyle.setTitleDrawableRightResource(R.drawable.ic_orange_arrow_down);
        titleBarStyle.setTitleLeftBackResource(R.drawable.ps_ic_black_back);
        titleBarStyle.setTitleTextColor(ContextCompat.getColor(context, R.color.ps_color_black));
        titleBarStyle.setTitleCancelTextColor(ContextCompat.getColor(context, R.color.ps_color_53575e));
        titleBarStyle.setDisplayTitleBarLine(true);

        BottomNavBarStyle bottomNavBarStyle = new BottomNavBarStyle();
        bottomNavBarStyle.setBottomNarBarBackgroundColor(Color.parseColor("#EEEEEE"));
        bottomNavBarStyle.setBottomPreviewNormalTextColor(ContextCompat.getColor(context, R.color.ps_color_9b));
        bottomNavBarStyle.setBottomPreviewSelectTextColor(ContextCompat.getColor(context, R.color.ps_color_fa632d));
        bottomNavBarStyle.setCompleteCountTips(false);
        bottomNavBarStyle.setBottomEditorTextColor(ContextCompat.getColor(context, R.color.ps_color_53575e));
        bottomNavBarStyle.setBottomOriginalTextColor(ContextCompat.getColor(context, R.color.ps_color_53575e));

        SelectMainStyle selectMainStyle = new SelectMainStyle();
        selectMainStyle.setStatusBarColor(ContextCompat.getColor(context, R.color.ps_color_white));
        selectMainStyle.setDarkStatusBarBlack(true);
        selectMainStyle.setSelectNormalTextColor(ContextCompat.getColor(context, R.color.ps_color_9b));
        selectMainStyle.setSelectTextColor(ContextCompat.getColor(context, R.color.ps_color_fa632d));
        selectMainStyle.setPreviewSelectBackground(R.drawable.ps_demo_white_preview_selector);
        selectMainStyle.setSelectBackground(R.drawable.ps_checkbox_selector);
        selectMainStyle.setSelectText(R.string.ps_done_front_num);
        selectMainStyle.setMainListBackgroundColor(ContextCompat.getColor(context, R.color.ps_color_white));

        style.setTitleBarStyle(titleBarStyle);
        style.setBottomBarStyle(bottomNavBarStyle);
        style.setSelectMainStyle(selectMainStyle);
    }

    private static void applyNumberStyle(Context context, PictureSelectorStyle style) {
        TitleBarStyle titleBarStyle = new TitleBarStyle();
        titleBarStyle.setTitleBackgroundColor(ContextCompat.getColor(context, R.color.ps_color_blue));

        BottomNavBarStyle bottomNavBarStyle = new BottomNavBarStyle();
        bottomNavBarStyle.setBottomPreviewNormalTextColor(ContextCompat.getColor(context, R.color.ps_color_9b));
        bottomNavBarStyle.setBottomPreviewSelectTextColor(ContextCompat.getColor(context, R.color.ps_color_blue));
        bottomNavBarStyle.setBottomNarBarBackgroundColor(ContextCompat.getColor(context, R.color.ps_color_white));
        bottomNavBarStyle.setBottomSelectNumResources(R.drawable.ps_demo_blue_num_selected);
        bottomNavBarStyle.setBottomEditorTextColor(ContextCompat.getColor(context, R.color.ps_color_53575e));
        bottomNavBarStyle.setBottomOriginalTextColor(ContextCompat.getColor(context, R.color.ps_color_53575e));

        SelectMainStyle selectMainStyle = new SelectMainStyle();
        selectMainStyle.setStatusBarColor(ContextCompat.getColor(context, R.color.ps_color_blue));
        selectMainStyle.setSelectNumberStyle(true);
        selectMainStyle.setPreviewSelectNumberStyle(true);
        selectMainStyle.setSelectBackground(R.drawable.ps_demo_blue_num_selector);
        selectMainStyle.setMainListBackgroundColor(ContextCompat.getColor(context, R.color.ps_color_white));
        selectMainStyle.setPreviewSelectBackground(R.drawable.ps_demo_preview_blue_num_selector);
        selectMainStyle.setSelectNormalTextColor(ContextCompat.getColor(context, R.color.ps_color_9b));
        selectMainStyle.setSelectTextColor(ContextCompat.getColor(context, R.color.ps_color_blue));
        selectMainStyle.setSelectText(R.string.ps_completed);

        style.setTitleBarStyle(titleBarStyle);
        style.setBottomBarStyle(bottomNavBarStyle);
        style.setSelectMainStyle(selectMainStyle);
    }

    private static void applyWechatStyle(Context context, PictureSelectorStyle style, boolean onlySandboxDir) {
        SelectMainStyle selectMainStyle = new SelectMainStyle();
        selectMainStyle.setSelectNumberStyle(true);
        selectMainStyle.setPreviewSelectNumberStyle(false);
        selectMainStyle.setPreviewDisplaySelectGallery(true);
        selectMainStyle.setSelectBackground(R.drawable.ps_default_num_selector);
        selectMainStyle.setPreviewSelectBackground(R.drawable.ps_preview_checkbox_selector);
        selectMainStyle.setSelectNormalBackgroundResources(R.drawable.ps_select_complete_normal_bg);
        selectMainStyle.setSelectNormalTextColor(ContextCompat.getColor(context, R.color.ps_color_53575e));
        selectMainStyle.setSelectNormalText(R.string.ps_send);
        selectMainStyle.setAdapterPreviewGalleryBackgroundResource(R.drawable.ps_preview_gallery_bg);
        selectMainStyle.setAdapterPreviewGalleryItemSize(DensityUtil.dip2px(context, 52));
        selectMainStyle.setPreviewSelectText(R.string.ps_select);
        selectMainStyle.setPreviewSelectTextSize(14);
        selectMainStyle.setPreviewSelectTextColor(ContextCompat.getColor(context, R.color.ps_color_white));
        selectMainStyle.setPreviewSelectMarginRight(DensityUtil.dip2px(context, 6));
        selectMainStyle.setSelectBackgroundResources(R.drawable.ps_select_complete_bg);
        selectMainStyle.setSelectText(R.string.ps_send_num);
        selectMainStyle.setSelectTextColor(ContextCompat.getColor(context, R.color.ps_color_white));
        selectMainStyle.setMainListBackgroundColor(ContextCompat.getColor(context, R.color.ps_color_black));
        selectMainStyle.setCompleteSelectRelativeTop(true);
        selectMainStyle.setPreviewSelectRelativeBottom(true);
        selectMainStyle.setAdapterItemIncludeEdge(false);

        TitleBarStyle titleBarStyle = new TitleBarStyle();
        titleBarStyle.setHideCancelButton(false);
        titleBarStyle.setAlbumTitleRelativeLeft(true);
        titleBarStyle.setTitleAlbumBackgroundResource(onlySandboxDir
                ? R.drawable.ps_demo_only_album_bg : R.drawable.ps_album_bg);
        titleBarStyle.setTitleDrawableRightResource(R.drawable.ps_ic_grey_arrow);
        titleBarStyle.setPreviewTitleLeftBackResource(R.drawable.ps_ic_normal_back);

        BottomNavBarStyle bottomNavBarStyle = new BottomNavBarStyle();
        bottomNavBarStyle.setBottomPreviewNarBarBackgroundColor(ContextCompat.getColor(context, R.color.ps_color_half_grey));
        bottomNavBarStyle.setBottomPreviewNormalText(R.string.ps_preview);
        bottomNavBarStyle.setBottomPreviewNormalTextColor(ContextCompat.getColor(context, R.color.ps_color_9b));
        bottomNavBarStyle.setBottomPreviewNormalTextSize(16);
        bottomNavBarStyle.setCompleteCountTips(false);
        bottomNavBarStyle.setBottomPreviewSelectText(R.string.ps_preview_num);
        bottomNavBarStyle.setBottomPreviewSelectTextColor(ContextCompat.getColor(context, R.color.ps_color_white));

        style.setTitleBarStyle(titleBarStyle);
        style.setBottomBarStyle(bottomNavBarStyle);
        style.setSelectMainStyle(selectMainStyle);
    }
}
