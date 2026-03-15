package com.termux.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.termux.view.support.PopupWindowCompatGingerbread;

final class TextSelectionPreviewPopup {

    private final TerminalView terminalView;
    private final TextView previewText;
    private final PopupWindow popupWindow;
    private final int[] hostLocation = new int[2];
    private final int horizontalMargin;
    private final int topMargin;
    private final int previewMinWidth;

    TextSelectionPreviewPopup(TerminalView terminalView) {
        this.terminalView = terminalView;
        Context context = terminalView.getContext();

        horizontalMargin = dp(context, 20);
        topMargin = dp(context, 12);
        previewMinWidth = dp(context, 160);

        previewText = new TextView(context);
        previewText.setTypeface(Typeface.MONOSPACE);
        previewText.setIncludeFontPadding(false);
        previewText.setGravity(Gravity.CENTER);
        previewText.setSingleLine(true);
        previewText.setMinWidth(previewMinWidth);
        int horizontalPadding = dp(context, 14);
        int verticalPadding = dp(context, 10);
        previewText.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

        popupWindow = new PopupWindow(previewText, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        popupWindow.setTouchable(false);
        popupWindow.setFocusable(false);
        popupWindow.setOutsideTouchable(false);
        popupWindow.setClippingEnabled(false);
        popupWindow.setAnimationStyle(0);
        popupWindow.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            popupWindow.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
            popupWindow.setEnterTransition(null);
            popupWindow.setExitTransition(null);
        } else {
            PopupWindowCompatGingerbread.setWindowLayoutType(popupWindow, WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
        }
    }

    void show(CharSequence text, int foregroundColor, int backgroundColor, int anchorX, int anchorY) {
        CharSequence safeText = (text == null || text.length() == 0) ? " " : text;
        previewText.setText(safeText);
        previewText.setTextColor(foregroundColor);
        previewText.setTextSize(TypedValue.COMPLEX_UNIT_PX, resolvePreviewTextSizePx());
        previewText.setBackground(createBackgroundDrawable(foregroundColor, backgroundColor));

        int maxWidth = Math.max(previewMinWidth, terminalView.getWidth() - (horizontalMargin * 2));
        int widthSpec = View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        previewText.setMaxWidth(maxWidth);
        previewText.measure(widthSpec, heightSpec);

        int popupWidth = Math.max(previewMinWidth, previewText.getMeasuredWidth());
        int popupHeight = previewText.getMeasuredHeight();

        terminalView.getLocationInWindow(hostLocation);
        int hostX = hostLocation[0];
        int hostY = hostLocation[1];

        int minX = hostX + horizontalMargin;
        int maxX = hostX + terminalView.getWidth() - horizontalMargin - popupWidth;
        int desiredX = hostX + anchorX - (popupWidth / 2);
        int x = clamp(desiredX, minX, Math.max(minX, maxX));

        int minY = hostY + topMargin;
        int maxY = hostY + terminalView.getHeight() - topMargin - popupHeight;
        int aboveY = hostY + anchorY - popupHeight - topMargin;
        int belowY = hostY + anchorY + topMargin;
        int y = aboveY >= minY ? aboveY : belowY;
        y = clamp(y, minY, Math.max(minY, maxY));

        if (popupWindow.isShowing()) {
            popupWindow.update(x, y, popupWidth, popupHeight);
        } else {
            popupWindow.setWidth(popupWidth);
            popupWindow.setHeight(popupHeight);
            popupWindow.showAtLocation(terminalView, Gravity.NO_GRAVITY, x, y);
        }
    }

    void hide() {
        if (popupWindow.isShowing()) {
            popupWindow.dismiss();
        }
    }

    private float resolvePreviewTextSizePx() {
        return sp(terminalView.getContext(), 18f);
    }

    private GradientDrawable createBackgroundDrawable(int foregroundColor, int backgroundColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(terminalView.getContext(), 12));
        drawable.setColor(applyAlpha(backgroundColor, 242));
        drawable.setStroke(dp(terminalView.getContext(), 1), applyAlpha(foregroundColor, 48));
        return drawable;
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    private static float sp(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.getResources().getDisplayMetrics());
    }

    private static int applyAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
