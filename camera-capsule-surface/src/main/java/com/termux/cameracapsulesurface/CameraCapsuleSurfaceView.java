package com.termux.cameracapsulesurface;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class CameraCapsuleSurfaceView extends View {

    private static final int BACKGROUND_COLOR_FLOATING = 0xEE121212;
    private static final int BACKGROUND_COLOR_DOCKED = 0xF4121212;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int SUBTITLE_COLOR = 0xCCFFFFFF;
    private static final int STATUS_COLOR = 0xFFFFFFFF;
    private static final int PROGRESS_TRACK_COLOR = 0x26FFFFFF;

    @NonNull
    private final Paint backgroundPaint;
    @NonNull
    private final Paint strokePaint;
    @NonNull
    private final Paint indicatorPaint;
    @NonNull
    private final Paint progressTrackPaint;
    @NonNull
    private final Paint progressFillPaint;
    @NonNull
    private final TextPaint titlePaint;
    @NonNull
    private final TextPaint subtitlePaint;
    @NonNull
    private final TextPaint statusPaint;
    @NonNull
    private final RectF backgroundRect;
    @NonNull
    private final RectF bodyRect;
    @NonNull
    private final RectF progressTrackRect;
    @NonNull
    private final RectF progressFillRect;
    @NonNull
    private final Path backgroundPath;

    @Nullable
    private RenderSnapshot renderSnapshot;
    private int desiredWidthPx;
    private int desiredHeightPx;

    CameraCapsuleSurfaceView(@NonNull Context context) {
        super(context);
        backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setStyle(Paint.Style.FILL);
        strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        indicatorPaint.setStyle(Paint.Style.FILL);
        progressTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressTrackPaint.setStyle(Paint.Style.FILL);
        progressTrackPaint.setColor(PROGRESS_TRACK_COLOR);
        progressFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressFillPaint.setStyle(Paint.Style.FILL);
        titlePaint = buildTextPaint(Typeface.DEFAULT_BOLD);
        subtitlePaint = buildTextPaint(Typeface.DEFAULT);
        statusPaint = buildTextPaint(Typeface.DEFAULT_BOLD);
        backgroundRect = new RectF();
        bodyRect = new RectF();
        progressTrackRect = new RectF();
        progressFillRect = new RectF();
        backgroundPath = new Path();
        setWillNotDraw(false);
    }

    @NonNull
    CameraCapsulePlacementEngine.ContentMetrics buildContentMetrics(@NonNull CameraCapsuleSurfaceState state,
                                                                    int floatingWidthPx,
                                                                    int dockedTopWidthPx,
                                                                    int dockedSideWidthPx,
                                                                    int dockedSideHeightPx) {
        LayoutMetrics floatingMetrics = buildLayoutMetrics(
            state,
            CameraCapsulePlacementEngine.SurfaceMode.FLOATING,
            floatingWidthPx,
            0,
            false);
        LayoutMetrics dockedTopMetrics = buildLayoutMetrics(
            state,
            CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP,
            dockedTopWidthPx,
            0,
            false);
        LayoutMetrics dockedSideMetrics = buildLayoutMetrics(
            state,
            CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT,
            dockedSideWidthPx,
            dockedSideHeightPx,
            false);
        return new CameraCapsulePlacementEngine.ContentMetrics(
            floatingWidthPx,
            floatingMetrics.heightPx,
            dockedTopWidthPx,
            dockedTopMetrics.heightPx,
            dockedSideWidthPx,
            dockedSideMetrics.heightPx);
    }

    void bind(@NonNull CameraCapsuleSurfaceState state,
              @NonNull CameraCapsulePlacementEngine.PlacementResult placementResult) {
        bind(state, placementResult, placementResult, null);
    }

    void bind(@NonNull CameraCapsuleSurfaceState state,
              @NonNull CameraCapsulePlacementEngine.PlacementResult containerPlacement,
              @NonNull CameraCapsulePlacementEngine.PlacementResult drawPlacement) {
        bind(state, containerPlacement, drawPlacement, null);
    }

    void bind(@NonNull CameraCapsuleSurfaceState state,
              @NonNull CameraCapsulePlacementEngine.PlacementResult containerPlacement,
              @NonNull CameraCapsulePlacementEngine.PlacementResult drawPlacement,
              @Nullable RenderDeformation deformation) {
        LayoutMetrics layoutMetrics = buildLayoutMetrics(
            state,
            drawPlacement.surfaceMode,
            drawPlacement.widthPx,
            drawPlacement.heightPx,
            true);
        desiredWidthPx = containerPlacement.widthPx;
        desiredHeightPx = containerPlacement.heightPx;
        int drawOffsetXPx = drawPlacement.xPx - containerPlacement.xPx;
        int drawOffsetYPx = drawPlacement.yPx - containerPlacement.yPx;
        renderSnapshot = new RenderSnapshot(
            drawPlacement.surfaceMode,
            drawPlacement.dockEdge,
            desiredWidthPx,
            desiredHeightPx,
            drawPlacement.widthPx,
            drawPlacement.heightPx,
            drawOffsetXPx,
            drawOffsetYPx,
            layoutMetrics.backgroundColor,
            layoutMetrics.strokeColor,
            layoutMetrics.accentColor,
            layoutMetrics.cornerRadiusPx,
            layoutMetrics.indicatorCenterXPx,
            layoutMetrics.indicatorCenterYPx,
            layoutMetrics.indicatorRadiusPx,
            layoutMetrics.titleText,
            layoutMetrics.titleLeftPx,
            layoutMetrics.titleBaselinePx,
            layoutMetrics.subtitleLeftPx,
            layoutMetrics.subtitleTopPx,
            layoutMetrics.subtitleLayout,
            layoutMetrics.statusText,
            layoutMetrics.statusLeftPx,
            layoutMetrics.statusBaselinePx,
            layoutMetrics.showProgress,
            layoutMetrics.progressIndeterminate,
            layoutMetrics.progressTrackLeftPx,
            layoutMetrics.progressTrackTopPx,
            layoutMetrics.progressTrackRightPx,
            layoutMetrics.progressTrackBottomPx,
            layoutMetrics.progressFraction,
            deformation);
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int resolvedWidth = resolveSize(desiredWidthPx > 0 ? desiredWidthPx : dp(224), widthMeasureSpec);
        int resolvedHeight = resolveSize(desiredHeightPx > 0 ? desiredHeightPx : dp(54), heightMeasureSpec);
        setMeasuredDimension(resolvedWidth, resolvedHeight);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        RenderSnapshot snapshot = renderSnapshot;
        if (snapshot == null) return;

        backgroundPaint.setColor(snapshot.backgroundColor);
        strokePaint.setColor(snapshot.strokeColor);
        strokePaint.setStrokeWidth(dp(1));
        indicatorPaint.setColor(snapshot.accentColor);
        progressFillPaint.setColor(snapshot.accentColor);

        canvas.save();
        canvas.translate(snapshot.drawOffsetXPx, snapshot.drawOffsetYPx);
        resolveBodyRect(snapshot);
        backgroundRect.set(bodyRect);
        buildBackgroundPath(snapshot);
        canvas.drawPath(backgroundPath, backgroundPaint);
        canvas.drawPath(backgroundPath, strokePaint);
        int contentAlpha = resolveContentAlpha(snapshot);
        if (snapshot.surfaceMode != CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP && contentAlpha > 0) {
            float contentOffsetXPx = resolveContentOffsetXPx(snapshot);
            float contentOffsetYPx = resolveContentOffsetYPx(snapshot);
            canvas.save();
            if (isSidePreview(snapshot)) canvas.clipPath(backgroundPath);
            canvas.translate(contentOffsetXPx, contentOffsetYPx);
            indicatorPaint.setAlpha(contentAlpha);
            canvas.drawCircle(
                snapshot.indicatorCenterXPx,
                snapshot.indicatorCenterYPx,
                snapshot.indicatorRadiusPx,
                indicatorPaint);

            if (!TextUtils.isEmpty(snapshot.titleText)) {
                titlePaint.setColor(withAlpha(TITLE_COLOR, contentAlpha));
                canvas.drawText(snapshot.titleText, snapshot.titleLeftPx, snapshot.titleBaselinePx, titlePaint);
            }

            if (snapshot.subtitleLayout != null) {
                subtitlePaint.setColor(withAlpha(SUBTITLE_COLOR, Math.round(contentAlpha * 0.80f)));
                canvas.save();
                canvas.translate(snapshot.subtitleLeftPx, snapshot.subtitleTopPx);
                snapshot.subtitleLayout.draw(canvas);
                canvas.restore();
            }

            if (!TextUtils.isEmpty(snapshot.statusText)) {
                statusPaint.setColor(withAlpha(STATUS_COLOR, Math.round(contentAlpha * 0.92f)));
                canvas.drawText(snapshot.statusText, snapshot.statusLeftPx, snapshot.statusBaselinePx, statusPaint);
            }
            canvas.restore();
        }

        if (snapshot.showProgress) {
            resolveProgressTrackRect(snapshot);
            float progressRadius = progressTrackRect.height() / 2f;
            boolean clipToBody = snapshot.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP
                || snapshot.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT
                || snapshot.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_RIGHT
                || isSidePreview(snapshot);
            if (clipToBody) {
                canvas.save();
                canvas.clipPath(backgroundPath);
                canvas.drawRect(progressTrackRect, progressTrackPaint);
            } else {
                canvas.drawRoundRect(progressTrackRect, progressRadius, progressRadius, progressTrackPaint);
            }

            float progressFraction = snapshot.progressFraction;
            if (snapshot.progressIndeterminate) {
                boolean vertical = snapshot.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT
                    || snapshot.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_RIGHT
                    || isSidePreview(snapshot);
                float trackSpan = vertical ? Math.max(0f, progressTrackRect.height()) : Math.max(0f, progressTrackRect.width());
                float segmentSpan = trackSpan * ((snapshot.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.FLOATING && !isSidePreview(snapshot)) ? 0.34f : 0.22f);
                float phase = (SystemClock.uptimeMillis() % 1400L) / 1400f;
                if (vertical) {
                    float start = progressTrackRect.top + ((trackSpan - segmentSpan) * phase);
                    progressFillRect.set(
                        progressTrackRect.left,
                        start,
                        progressTrackRect.right,
                        Math.min(progressTrackRect.bottom, start + segmentSpan));
                } else {
                    float start = progressTrackRect.left + ((trackSpan - segmentSpan) * phase);
                    progressFillRect.set(
                        start,
                        progressTrackRect.top,
                        Math.min(progressTrackRect.right, start + segmentSpan),
                        progressTrackRect.bottom);
                }
                postInvalidateOnAnimation();
            } else {
                boolean vertical = snapshot.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT
                    || snapshot.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_RIGHT
                    || isSidePreview(snapshot);
                if (vertical) {
                    float filledTop = progressTrackRect.bottom - (progressTrackRect.height() * progressFraction);
                    progressFillRect.set(
                        progressTrackRect.left,
                        filledTop,
                        progressTrackRect.right,
                        progressTrackRect.bottom);
                } else {
                    progressFillRect.set(
                        progressTrackRect.left,
                        progressTrackRect.top,
                        progressTrackRect.left + (progressTrackRect.width() * progressFraction),
                        progressTrackRect.bottom);
                }
            }

            if (!progressFillRect.isEmpty()) {
                if (clipToBody) {
                    canvas.drawRect(progressFillRect, progressFillPaint);
                } else {
                    canvas.drawRoundRect(progressFillRect, progressRadius, progressRadius, progressFillPaint);
                }
            }
            if (clipToBody) {
                canvas.restore();
            }
        }
        canvas.restore();
    }

    private void resolveBodyRect(@NonNull RenderSnapshot snapshot) {
        bodyRect.set(0f, 0f, snapshot.drawWidthPx, snapshot.drawHeightPx);
        RenderDeformation deformation = snapshot.deformation;
        if (deformation == null || !deformation.isSidePreview()) return;

        float squeeze = smoothStep01(deformation.bodySqueezeProgress);
        float stretch = smoothStep01(deformation.bodyStretchProgress);
        float attach = smoothStep01(deformation.bodyAttachProgress);
        float page = smoothStep01(deformation.pageProgress);
        float widthReductionPx = snapshot.drawWidthPx * ((0.12f * squeeze) + (0.74f * attach));
        float widthPx = Math.max(dp(12), snapshot.drawWidthPx - widthReductionPx);
        float heightPx = snapshot.drawHeightPx
            * (1f + (0.08f * stretch) + (0.20f * deformation.pressProgress * (1f - (0.35f * page))));
        float topPx = Math.max(
            0f,
            snapshot.containerHeightPx - snapshot.drawOffsetYPx - snapshot.drawHeightPx);
        float leftPx = deformation.contactSurfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_RIGHT
            ? snapshot.drawWidthPx - widthPx
            : 0f;
        bodyRect.set(leftPx, topPx, leftPx + widthPx, topPx + heightPx);
    }

    private void resolveProgressTrackRect(@NonNull RenderSnapshot snapshot) {
        RenderDeformation deformation = snapshot.deformation;
        if (deformation != null && deformation.isSidePreview()) {
            float verticalInsetPx = Math.max(dp(3), bodyRect.height() * 0.06f);
            float horizontalInsetPx = Math.max(dp(1), bodyRect.width() * 0.10f);
            float trackWidthPx = Math.max(
                dp(4),
                Math.min(bodyRect.width() - (horizontalInsetPx * 2f), (bodyRect.width() * 0.36f) + dp(1)));
            if (deformation.contactSurfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT) {
                progressTrackRect.set(
                    bodyRect.left + horizontalInsetPx,
                    bodyRect.top + verticalInsetPx,
                    Math.min(bodyRect.right - horizontalInsetPx, bodyRect.left + horizontalInsetPx + trackWidthPx),
                    bodyRect.bottom - verticalInsetPx);
            } else {
                progressTrackRect.set(
                    Math.max(bodyRect.left + horizontalInsetPx, bodyRect.right - horizontalInsetPx - trackWidthPx),
                    bodyRect.top + verticalInsetPx,
                    bodyRect.right - horizontalInsetPx,
                    bodyRect.bottom - verticalInsetPx);
            }
            return;
        }

        progressTrackRect.set(
            snapshot.progressTrackLeftPx,
            snapshot.progressTrackTopPx,
            snapshot.progressTrackRightPx,
            snapshot.progressTrackBottomPx);
    }

    private int resolveContentAlpha(@NonNull RenderSnapshot snapshot) {
        RenderDeformation deformation = snapshot.deformation;
        if (deformation == null) return 255;
        return Math.max(0, Math.min(255, Math.round(deformation.contentVisibilityProgress * 255f)));
    }

    private float resolveContentOffsetXPx(@NonNull RenderSnapshot snapshot) {
        RenderDeformation deformation = snapshot.deformation;
        if (deformation == null || !deformation.isSidePreview()) return 0f;
        return bodyRect.left * 0.52f;
    }

    private float resolveContentOffsetYPx(@NonNull RenderSnapshot snapshot) {
        RenderDeformation deformation = snapshot.deformation;
        if (deformation == null || !deformation.isSidePreview()) return 0f;
        return Math.max(0f, bodyRect.top * 0.32f);
    }

    private boolean isSidePreview(@NonNull RenderSnapshot snapshot) {
        return snapshot.deformation != null && snapshot.deformation.isSidePreview();
    }

    private void buildBackgroundPath(@NonNull RenderSnapshot snapshot) {
        backgroundPath.reset();
        RenderDeformation deformation = snapshot.deformation;
        if (deformation != null && deformation.isSidePreview()) {
            buildSidePreviewPath(snapshot, deformation);
            return;
        }
        if (snapshot.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP) {
            float radius = snapshot.cornerRadiusPx;
            backgroundPath.addRoundRect(
                backgroundRect,
                new float[] {
                    0f, 0f,
                    0f, 0f,
                    radius, radius,
                    radius, radius
                },
                Path.Direction.CW);
            return;
        }
        if (snapshot.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT) {
            float radius = snapshot.cornerRadiusPx;
            backgroundPath.addRoundRect(
                backgroundRect,
                new float[] {
                    0f, 0f,
                    radius, radius,
                    radius, radius,
                    0f, 0f
                },
                Path.Direction.CW);
            return;
        }
        if (snapshot.surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_RIGHT) {
            float radius = snapshot.cornerRadiusPx;
            backgroundPath.addRoundRect(
                backgroundRect,
                new float[] {
                    radius, radius,
                    0f, 0f,
                    0f, 0f,
                    radius, radius
                },
                Path.Direction.CW);
            return;
        }
        backgroundPath.addRoundRect(
            backgroundRect,
            snapshot.cornerRadiusPx,
            snapshot.cornerRadiusPx,
            Path.Direction.CW);
    }

    private void buildSidePreviewPath(@NonNull RenderSnapshot snapshot,
                                      @NonNull RenderDeformation deformation) {
        float left = backgroundRect.left;
        float top = backgroundRect.top;
        float right = backgroundRect.right;
        float bottom = backgroundRect.bottom;
        float width = Math.max(1f, right - left);
        float height = Math.max(1f, bottom - top);
        float radius = Math.min(snapshot.cornerRadiusPx, Math.min(width, height) / 2f);
        float attach = smoothStep01(deformation.bodyAttachProgress);
        float squeeze = smoothStep01(deformation.bodySqueezeProgress);
        float stretch = smoothStep01(deformation.bodyStretchProgress);
        float page = smoothStep01(deformation.pageProgress);
        float contactRadius = Math.max(dp(2), radius * (1f - (0.92f * attach)));
        float freeRadius = Math.max(dp(4), radius * (1f - (0.34f * page)));
        float waistPx = Math.min(width * 0.72f, width * ((0.06f * squeeze) + (0.30f * attach)));
        float bellyPx = Math.min(width * 0.26f, width * ((0.04f * stretch) + (0.12f * page)));
        float middleYPx = (top + bottom) / 2f;
        float upperYPx = top + (height * 0.26f);
        float lowerYPx = bottom - (height * 0.26f);
        boolean dockLeft = deformation.contactSurfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT;

        if (dockLeft) {
            backgroundPath.moveTo(left + contactRadius, top);
            backgroundPath.lineTo(right - freeRadius, top);
            backgroundPath.quadTo(right, top, right, top + freeRadius);
            backgroundPath.cubicTo(
                right - bellyPx,
                upperYPx,
                Math.max(left + contactRadius, right - waistPx - bellyPx),
                middleYPx - (height * 0.10f),
                Math.max(left + contactRadius, right - waistPx),
                middleYPx);
            backgroundPath.cubicTo(
                Math.max(left + contactRadius, right - waistPx - bellyPx),
                middleYPx + (height * 0.10f),
                right - bellyPx,
                lowerYPx,
                right,
                bottom - freeRadius);
            backgroundPath.quadTo(right, bottom, right - freeRadius, bottom);
            backgroundPath.lineTo(left + contactRadius, bottom);
            backgroundPath.quadTo(left, bottom, left, bottom - contactRadius);
            backgroundPath.lineTo(left, top + contactRadius);
            backgroundPath.quadTo(left, top, left + contactRadius, top);
        } else {
            backgroundPath.moveTo(left + freeRadius, top);
            backgroundPath.lineTo(right - contactRadius, top);
            backgroundPath.quadTo(right, top, right, top + contactRadius);
            backgroundPath.lineTo(right, bottom - contactRadius);
            backgroundPath.quadTo(right, bottom, right - contactRadius, bottom);
            backgroundPath.lineTo(left + freeRadius, bottom);
            backgroundPath.quadTo(left, bottom, left, bottom - freeRadius);
            backgroundPath.cubicTo(
                left + bellyPx,
                lowerYPx,
                Math.min(right - contactRadius, left + waistPx + bellyPx),
                middleYPx + (height * 0.10f),
                Math.min(right - contactRadius, left + waistPx),
                middleYPx);
            backgroundPath.cubicTo(
                Math.min(right - contactRadius, left + waistPx + bellyPx),
                middleYPx - (height * 0.10f),
                left + bellyPx,
                upperYPx,
                left,
                top + freeRadius);
            backgroundPath.quadTo(left, top, left + freeRadius, top);
        }
        backgroundPath.close();
    }

    @NonNull
    private LayoutMetrics buildLayoutMetrics(@NonNull CameraCapsuleSurfaceState state,
                                             @NonNull CameraCapsulePlacementEngine.SurfaceMode surfaceMode,
                                             int widthPx,
                                             int requestedHeightPx,
                                             boolean includeLayouts) {
        boolean docked = surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP;
        int accentColor = resolveAccentColor(state);
        int backgroundColor = docked ? BACKGROUND_COLOR_DOCKED : BACKGROUND_COLOR_FLOATING;
        int strokeColor = withAlpha(accentColor, docked ? 128 : 96);

        if (docked) {
            boolean indeterminate = state.progressIndeterminate || state.progressMax <= 0;
            float progressFraction = indeterminate
                ? 0.22f
                : Math.max(0f, Math.min(1f, state.progressCurrent / (float) Math.max(1, state.progressMax)));
            int barHeightPx = dp(12);
            return new LayoutMetrics(
                accentColor,
                0x59121212,
                withAlpha(accentColor, 72),
                barHeightPx,
                dp(6),
                0,
                0,
                0,
                "",
                0,
                0,
                0,
                0,
                null,
                "",
                0,
                0,
                true,
                indeterminate,
                0,
                0,
                widthPx,
                barHeightPx,
                progressFraction);
        }

        if (surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT
            || surfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_RIGHT) {
            boolean indeterminate = state.progressIndeterminate || state.progressMax <= 0;
            float progressFraction = indeterminate
                ? 0.22f
                : Math.max(0f, Math.min(1f, state.progressCurrent / (float) Math.max(1, state.progressMax)));
            int barHeightPx = Math.max(dp(72), requestedHeightPx);
            return new LayoutMetrics(
                accentColor,
                0x59121212,
                withAlpha(accentColor, 72),
                barHeightPx,
                dp(6),
                0,
                0,
                0,
                "",
                0,
                0,
                0,
                0,
                null,
                "",
                0,
                0,
                true,
                indeterminate,
                0,
                0,
                widthPx,
                barHeightPx,
                progressFraction);
        }

        float titleSizePx = sp(docked ? 13f : 14f);
        float subtitleSizePx = sp(docked ? 10.5f : 12f);
        float statusSizePx = sp(docked ? 11f : 12f);
        titlePaint.setTextSize(titleSizePx);
        subtitlePaint.setTextSize(subtitleSizePx);
        subtitlePaint.setColor(SUBTITLE_COLOR);
        statusPaint.setTextSize(statusSizePx);

        int horizontalPaddingPx = dp(docked ? 14 : 14);
        int verticalPaddingPx = dp(docked ? 6 : 10);
        int indicatorDiameterPx = dp(docked ? 5 : 8);
        int indicatorGapPx = dp(docked ? 8 : 10);
        int statusGapPx = dp(docked ? 10 : 10);
        int subtitleGapPx = dp(2);
        int progressGapPx = dp(docked ? 0 : 8);
        int progressHeightPx = dp(docked ? 2 : 4);
        int indicatorRadiusPx = indicatorDiameterPx / 2;
        int contentLeftPx = horizontalPaddingPx + indicatorDiameterPx + indicatorGapPx;
        int contentRightPx = widthPx - horizontalPaddingPx;

        String statusText = resolveStatusText(state);
        boolean showStatus = !TextUtils.isEmpty(statusText);
        float rawStatusWidthPx = showStatus ? statusPaint.measureText(statusText) : 0f;
        int statusWidthPx = showStatus
            ? Math.min(Math.round(rawStatusWidthPx), Math.max(dp(48), widthPx / (docked ? 2 : 3)))
            : 0;
        if (showStatus) {
            statusText = String.valueOf(TextUtils.ellipsize(statusText, statusPaint, statusWidthPx, TextUtils.TruncateAt.END));
            statusWidthPx = Math.round(statusPaint.measureText(statusText));
        }

        int textAvailableWidthPx = Math.max(
            dp(48),
            contentRightPx - contentLeftPx - (showStatus ? statusWidthPx + statusGapPx : 0));
        String titleText = String.valueOf(TextUtils.ellipsize(
            state.title,
            titlePaint,
            textAvailableWidthPx,
            TextUtils.TruncateAt.END));

        String subtitleText = docked ? "" : resolveSubtitle(state);
        boolean showSubtitle = !TextUtils.isEmpty(subtitleText);
        StaticLayout subtitleLayout = null;
        int subtitleHeightPx = 0;
        if (showSubtitle) {
            subtitleLayout = buildStaticLayout(
                subtitleText,
                subtitlePaint,
                textAvailableWidthPx,
                state.expanded ? 2 : 1);
            subtitleHeightPx = subtitleLayout.getHeight();
        }

        Paint.FontMetricsInt titleMetrics = titlePaint.getFontMetricsInt();
        Paint.FontMetricsInt statusMetrics = statusPaint.getFontMetricsInt();
        int titleHeightPx = titleMetrics.bottom - titleMetrics.top;
        int statusHeightPx = statusMetrics.bottom - statusMetrics.top;
        int textBlockHeightPx = titleHeightPx + (showSubtitle ? subtitleGapPx + subtitleHeightPx : 0);
        int rowHeightPx = Math.max(indicatorDiameterPx, Math.max(textBlockHeightPx, statusHeightPx));
        boolean showProgress = state.progressIndeterminate || state.progressMax > 0;
        int heightPx = verticalPaddingPx + rowHeightPx + verticalPaddingPx
            + (showProgress ? progressGapPx + progressHeightPx : 0);

        int rowTopPx = verticalPaddingPx;
        int rowCenterYPx = rowTopPx + (rowHeightPx / 2);
        int indicatorCenterXPx = horizontalPaddingPx + indicatorRadiusPx;
        int indicatorCenterYPx = rowCenterYPx;
        int titleTopPx = rowTopPx + Math.max(0, (rowHeightPx - textBlockHeightPx) / 2);
        int titleBaselinePx = titleTopPx - titleMetrics.top;
        int subtitleTopPx = titleTopPx + titleHeightPx + subtitleGapPx;
        int statusLeftPx = showStatus ? contentRightPx - statusWidthPx : contentRightPx;
        int statusBaselinePx = rowTopPx + Math.max(0, (rowHeightPx - statusHeightPx) / 2) - statusMetrics.top;
        int progressTrackLeftPx = docked ? 0 : horizontalPaddingPx;
        int progressTrackTopPx = docked ? (heightPx - progressHeightPx) : (rowTopPx + rowHeightPx + progressGapPx);
        int progressTrackRightPx = docked ? widthPx : (widthPx - horizontalPaddingPx);
        int progressTrackBottomPx = progressTrackTopPx + progressHeightPx;
        float progressFraction = state.progressIndeterminate
            ? 0.34f
            : Math.max(0f, Math.min(1f, state.progressMax <= 0
                ? 0f
                : state.progressCurrent / (float) Math.max(1, state.progressMax)));

        return new LayoutMetrics(
            accentColor,
            backgroundColor,
            strokeColor,
            heightPx,
            docked ? dp(16) : (heightPx / 2f),
            indicatorCenterXPx,
            indicatorCenterYPx,
            indicatorRadiusPx,
            titleText,
            contentLeftPx,
            titleBaselinePx,
            contentLeftPx,
            subtitleTopPx,
            includeLayouts ? subtitleLayout : null,
            statusText,
            statusLeftPx,
            statusBaselinePx,
            showProgress,
            state.progressIndeterminate,
            progressTrackLeftPx,
            progressTrackTopPx,
            progressTrackRightPx,
            progressTrackBottomPx,
            progressFraction);
    }

    @NonNull
    private StaticLayout buildStaticLayout(@NonNull String text,
                                           @NonNull TextPaint paint,
                                           int widthPx,
                                           int maxLines) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, widthPx))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(Math.max(1, maxLines))
            .build();
    }

    @NonNull
    private TextPaint buildTextPaint(@NonNull Typeface typeface) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(typeface);
        return paint;
    }

    @ColorInt
    private int resolveAccentColor(@NonNull CameraCapsuleSurfaceState state) {
        return state.colorArgb != 0 ? state.colorArgb : 0xFF4CAF50;
    }

    @NonNull
    private String resolveSubtitle(@NonNull CameraCapsuleSurfaceState state) {
        if (!TextUtils.isEmpty(state.text)) return state.text;
        if (state.progressMax > 0 && !state.progressIndeterminate) {
            return "Progress " + state.progressCurrent + "/" + state.progressMax;
        }
        return "";
    }

    @NonNull
    private String resolveStatusText(@NonNull CameraCapsuleSurfaceState state) {
        if (!TextUtils.isEmpty(state.shortText)) return state.shortText;
        if (!TextUtils.isEmpty(state.status)) return state.status;
        if (state.progressMax > 0 && !state.progressIndeterminate) {
            int percent = Math.max(0, Math.min(100,
                (int) Math.round(state.progressCurrent * 100.0d / Math.max(1, state.progressMax))));
            return percent + "%";
        }
        return "";
    }

    private int withAlpha(@ColorInt int color, int alpha) {
        return (color & 0x00FFFFFF) | ((Math.max(0, Math.min(255, alpha)) & 0xFF) << 24);
    }

    private float smoothStep01(float value) {
        float clamped = Math.max(0f, Math.min(1f, value));
        return clamped * clamped * (3f - (2f * clamped));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static final class LayoutMetrics {
        final int accentColor;
        final int backgroundColor;
        final int strokeColor;
        final int heightPx;
        final float cornerRadiusPx;
        final int indicatorCenterXPx;
        final int indicatorCenterYPx;
        final int indicatorRadiusPx;
        @NonNull final String titleText;
        final int titleLeftPx;
        final int titleBaselinePx;
        final int subtitleLeftPx;
        final int subtitleTopPx;
        @Nullable final StaticLayout subtitleLayout;
        @NonNull final String statusText;
        final int statusLeftPx;
        final int statusBaselinePx;
        final boolean showProgress;
        final boolean progressIndeterminate;
        final int progressTrackLeftPx;
        final int progressTrackTopPx;
        final int progressTrackRightPx;
        final int progressTrackBottomPx;
        final float progressFraction;

        LayoutMetrics(int accentColor,
                      int backgroundColor,
                      int strokeColor,
                      int heightPx,
                      float cornerRadiusPx,
                      int indicatorCenterXPx,
                      int indicatorCenterYPx,
                      int indicatorRadiusPx,
                      @NonNull String titleText,
                      int titleLeftPx,
                      int titleBaselinePx,
                      int subtitleLeftPx,
                      int subtitleTopPx,
                      @Nullable StaticLayout subtitleLayout,
                      @NonNull String statusText,
                      int statusLeftPx,
                      int statusBaselinePx,
                      boolean showProgress,
                      boolean progressIndeterminate,
                      int progressTrackLeftPx,
                      int progressTrackTopPx,
                      int progressTrackRightPx,
                      int progressTrackBottomPx,
                      float progressFraction) {
            this.accentColor = accentColor;
            this.backgroundColor = backgroundColor;
            this.strokeColor = strokeColor;
            this.heightPx = heightPx;
            this.cornerRadiusPx = cornerRadiusPx;
            this.indicatorCenterXPx = indicatorCenterXPx;
            this.indicatorCenterYPx = indicatorCenterYPx;
            this.indicatorRadiusPx = indicatorRadiusPx;
            this.titleText = titleText;
            this.titleLeftPx = titleLeftPx;
            this.titleBaselinePx = titleBaselinePx;
            this.subtitleLeftPx = subtitleLeftPx;
            this.subtitleTopPx = subtitleTopPx;
            this.subtitleLayout = subtitleLayout;
            this.statusText = statusText;
            this.statusLeftPx = statusLeftPx;
            this.statusBaselinePx = statusBaselinePx;
            this.showProgress = showProgress;
            this.progressIndeterminate = progressIndeterminate;
            this.progressTrackLeftPx = progressTrackLeftPx;
            this.progressTrackTopPx = progressTrackTopPx;
            this.progressTrackRightPx = progressTrackRightPx;
            this.progressTrackBottomPx = progressTrackBottomPx;
            this.progressFraction = progressFraction;
        }
    }

    static final class RenderDeformation {
        @NonNull final CameraCapsulePlacementEngine.SurfaceMode contactSurfaceMode;
        final float compressionProgress;
        final float pageProgress;
        final float pressProgress;
        final float bodySqueezeProgress;
        final float bodyStretchProgress;
        final float bodyAttachProgress;
        final float contentVisibilityProgress;

        RenderDeformation(@NonNull CameraCapsulePlacementEngine.SurfaceMode contactSurfaceMode,
                          float compressionProgress,
                          float pageProgress,
                          float pressProgress,
                          float bodySqueezeProgress,
                          float bodyStretchProgress,
                          float bodyAttachProgress,
                          float contentVisibilityProgress) {
            this.contactSurfaceMode = contactSurfaceMode;
            this.compressionProgress = clamp01(compressionProgress);
            this.pageProgress = clamp01(pageProgress);
            this.pressProgress = clamp01(pressProgress);
            this.bodySqueezeProgress = clamp01(bodySqueezeProgress);
            this.bodyStretchProgress = clamp01(bodyStretchProgress);
            this.bodyAttachProgress = clamp01(bodyAttachProgress);
            this.contentVisibilityProgress = clamp01(contentVisibilityProgress);
        }

        boolean isSidePreview() {
            return contactSurfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT
                || contactSurfaceMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_RIGHT;
        }
    }

    private static final class RenderSnapshot {
        @NonNull final CameraCapsulePlacementEngine.SurfaceMode surfaceMode;
        @NonNull final String dockEdge;
        final int containerWidthPx;
        final int containerHeightPx;
        final int drawWidthPx;
        final int drawHeightPx;
        final int drawOffsetXPx;
        final int drawOffsetYPx;
        final int backgroundColor;
        final int strokeColor;
        final int accentColor;
        final float cornerRadiusPx;
        final int indicatorCenterXPx;
        final int indicatorCenterYPx;
        final int indicatorRadiusPx;
        @NonNull final String titleText;
        final int titleLeftPx;
        final int titleBaselinePx;
        final int subtitleLeftPx;
        final int subtitleTopPx;
        @Nullable final StaticLayout subtitleLayout;
        @NonNull final String statusText;
        final int statusLeftPx;
        final int statusBaselinePx;
        final boolean showProgress;
        final boolean progressIndeterminate;
        final int progressTrackLeftPx;
        final int progressTrackTopPx;
        final int progressTrackRightPx;
        final int progressTrackBottomPx;
        final float progressFraction;
        @Nullable final RenderDeformation deformation;

        RenderSnapshot(@NonNull CameraCapsulePlacementEngine.SurfaceMode surfaceMode,
                       @NonNull String dockEdge,
                       int containerWidthPx,
                       int containerHeightPx,
                       int drawWidthPx,
                       int drawHeightPx,
                       int drawOffsetXPx,
                       int drawOffsetYPx,
                       int backgroundColor,
                       int strokeColor,
                       int accentColor,
                       float cornerRadiusPx,
                       int indicatorCenterXPx,
                       int indicatorCenterYPx,
                       int indicatorRadiusPx,
                       @NonNull String titleText,
                       int titleLeftPx,
                       int titleBaselinePx,
                       int subtitleLeftPx,
                       int subtitleTopPx,
                       @Nullable StaticLayout subtitleLayout,
                       @NonNull String statusText,
                       int statusLeftPx,
                       int statusBaselinePx,
                       boolean showProgress,
                       boolean progressIndeterminate,
                       int progressTrackLeftPx,
                       int progressTrackTopPx,
                       int progressTrackRightPx,
                       int progressTrackBottomPx,
                       float progressFraction,
                       @Nullable RenderDeformation deformation) {
            this.surfaceMode = surfaceMode;
            this.dockEdge = dockEdge;
            this.containerWidthPx = containerWidthPx;
            this.containerHeightPx = containerHeightPx;
            this.drawWidthPx = drawWidthPx;
            this.drawHeightPx = drawHeightPx;
            this.drawOffsetXPx = drawOffsetXPx;
            this.drawOffsetYPx = drawOffsetYPx;
            this.backgroundColor = backgroundColor;
            this.strokeColor = strokeColor;
            this.accentColor = accentColor;
            this.cornerRadiusPx = cornerRadiusPx;
            this.indicatorCenterXPx = indicatorCenterXPx;
            this.indicatorCenterYPx = indicatorCenterYPx;
            this.indicatorRadiusPx = indicatorRadiusPx;
            this.titleText = titleText;
            this.titleLeftPx = titleLeftPx;
            this.titleBaselinePx = titleBaselinePx;
            this.subtitleLeftPx = subtitleLeftPx;
            this.subtitleTopPx = subtitleTopPx;
            this.subtitleLayout = subtitleLayout;
            this.statusText = statusText;
            this.statusLeftPx = statusLeftPx;
            this.statusBaselinePx = statusBaselinePx;
            this.showProgress = showProgress;
            this.progressIndeterminate = progressIndeterminate;
            this.progressTrackLeftPx = progressTrackLeftPx;
            this.progressTrackTopPx = progressTrackTopPx;
            this.progressTrackRightPx = progressTrackRightPx;
            this.progressTrackBottomPx = progressTrackBottomPx;
            this.progressFraction = progressFraction;
            this.deformation = deformation;
        }
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
