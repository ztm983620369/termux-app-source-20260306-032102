package com.termux.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.PopupWindow;

import androidx.annotation.Nullable;

import com.termux.terminal.TextStyle;
import com.termux.view.support.PopupWindowCompatGingerbread;

/**
 * A custom text-selection magnifier for {@link TerminalView}.
 *
 * <p>We intentionally do not use {@link android.widget.Magnifier} here since OEM implementations can
 * apply inconsistent source coordinate mapping for custom drawn views, leading to a permanent
 * +/-1 cell "character drift" feel. This magnifier re-renders the terminal content directly using
 * {@link TerminalRenderer}, guaranteeing exact alignment with the terminal grid.</p>
 */
final class TextSelectionMagnifier {

    private final TerminalView terminalView;
    private final MagnifierView magnifierView;
    private final PopupWindow popupWindow;
    private final int[] hostLocation = new int[2];

    private final int widthPx;
    private final int heightPx;
    private final int horizontalMargin;
    private final int topMargin;

    TextSelectionMagnifier(TerminalView terminalView) {
        this.terminalView = terminalView;
        Context context = terminalView.getContext();

        widthPx = dp(context, 180);
        heightPx = dp(context, 104);
        horizontalMargin = dp(context, 20);
        topMargin = dp(context, 12);

        magnifierView = new MagnifierView(context, terminalView);
        magnifierView.setLayoutParams(new ViewGroup.LayoutParams(widthPx, heightPx));

        popupWindow = new PopupWindow(magnifierView, widthPx, heightPx);
        popupWindow.setTouchable(false);
        popupWindow.setFocusable(false);
        popupWindow.setOutsideTouchable(false);
        popupWindow.setClippingEnabled(false);
        popupWindow.setAnimationStyle(0);
        popupWindow.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);

        GradientDrawable frame = new GradientDrawable();
        frame.setShape(GradientDrawable.RECTANGLE);
        frame.setCornerRadius(dp(context, 14));
        frame.setColor(Color.TRANSPARENT);
        frame.setStroke(dp(context, 1), Color.argb(48, 255, 255, 255));
        popupWindow.setBackgroundDrawable(frame);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            popupWindow.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
            popupWindow.setEnterTransition(null);
            popupWindow.setExitTransition(null);
        } else {
            PopupWindowCompatGingerbread.setWindowLayoutType(popupWindow, WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
        }
    }

    void show(float sourceX, float sourceY) {
        if (terminalView.mEmulator == null || terminalView.mRenderer == null) return;

        magnifierView.setSource(sourceX, sourceY);

        terminalView.getLocationInWindow(hostLocation);
        int hostX = hostLocation[0];
        int hostY = hostLocation[1];

        int desiredX = hostX + Math.round(sourceX) - (widthPx / 2);
        int minX = hostX + horizontalMargin;
        int maxX = hostX + terminalView.getWidth() - horizontalMargin - widthPx;
        int x = clamp(desiredX, minX, Math.max(minX, maxX));

        int desiredY = hostY + Math.round(sourceY) - heightPx - topMargin;
        int minY = hostY + topMargin;
        int maxY = hostY + terminalView.getHeight() - topMargin - heightPx;
        int y = clamp(desiredY, minY, Math.max(minY, maxY));

        if (popupWindow.isShowing()) {
            popupWindow.update(x, y, widthPx, heightPx);
        } else {
            popupWindow.showAtLocation(terminalView, Gravity.NO_GRAVITY, x, y);
        }
    }

    void hide() {
        if (popupWindow.isShowing()) {
            popupWindow.dismiss();
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static final class MagnifierView extends View {

        private final TerminalView terminalView;
        private final RectF clipRect = new RectF();
        private final Path clipPath = new Path();
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private float sourceX;
        private float sourceY;

        private final int[] selection = new int[]{-1, -1, -1, -1};
        private final float zoom;
        private final float cornerRadius;
        private final int paddingPx;

        MagnifierView(Context context, TerminalView terminalView) {
            super(context);
            this.terminalView = terminalView;
            setWillNotDraw(false);

            zoom = 1.8f;
            cornerRadius = dp(context, 14);
            paddingPx = dp(context, 2);
        }

        void setSource(float sourceX, float sourceY) {
            this.sourceX = sourceX;
            this.sourceY = sourceY;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (terminalView.mEmulator == null || terminalView.mRenderer == null) return;

            int bg = terminalView.mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND];
            backgroundPaint.setColor(bg);

            clipRect.set(0, 0, getWidth(), getHeight());
            clipPath.reset();
            clipPath.addRoundRect(clipRect, cornerRadius, cornerRadius, Path.Direction.CW);

            canvas.save();
            canvas.clipPath(clipPath);
            canvas.drawRect(clipRect, backgroundPaint);

            if (terminalView.isSelectingText()) {
                terminalView.getTextSelectionCursorController().getSelectors(selection);
            } else {
                selection[0] = selection[1] = selection[2] = selection[3] = -1;
            }

            canvas.translate(getWidth() / 2f, getHeight() / 2f);
            canvas.scale(zoom, zoom);
            canvas.translate(-sourceX, -sourceY);
            terminalView.mRenderer.render(terminalView.mEmulator, canvas, terminalView.getTopRow(),
                terminalView.getViewportPixelOffset(), selection[0], selection[1], selection[2], selection[3]);
            canvas.restore();

            // Inner padding to avoid any artifacts touching the rounded clip edge.
            if (paddingPx > 0) {
                // no-op for now, the background fill already provides a safe area
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
        }

        @Override
        public void setAlpha(float alpha) {
            super.setAlpha(alpha);
        }

        @Nullable
        @Override
        public CharSequence getAccessibilityClassName() {
            return MagnifierView.class.getName();
        }
    }
}
