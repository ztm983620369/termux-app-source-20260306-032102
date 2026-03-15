package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.InputDevice;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class TerminalConfigCanvasView extends View {

    public interface Callbacks {
        void onCreateLocalSession();
        void onAddSshProfile();
        void onConnectProfile(@NonNull String profileId);
        void onEditProfile(@NonNull String profileId);
        void onOpenTmuxProfile(@NonNull String profileId);
        void onBackFromTmux();
        void onRefreshTmux(@NonNull String profileId);
        void onInstallTmux(@NonNull String profileId);
        void onCreateTmux(@NonNull String profileId);
        void onConnectTmux(@NonNull String profileId, @NonNull String tmuxSession, @NonNull String displayName);
        void onDestroyTmux(@NonNull String profileId, @NonNull String tmuxSession);
        void onDeleteProfile(@NonNull String profileId);
        void onCloseConfigTab();
    }

    private static final int ACTION_LOCAL = 1;
    private static final int ACTION_ADD_SSH = 2;
    private static final int ACTION_PROFILE_CONNECT = 10;
    private static final int ACTION_PROFILE_EDIT = 11;
    private static final int ACTION_PROFILE_TMUX = 12;
    private static final int ACTION_PROFILE_DELETE = 13;
    private static final int ACTION_CLOSE = 20;
    private static final int ACTION_TMUX_BACK = 21;
    private static final int ACTION_TMUX_REFRESH = 22;
    private static final int ACTION_TMUX_NEW = 23;
    private static final int ACTION_TMUX_INSTALL = 24;
    private static final int ACTION_TMUX_CONNECT = 30;
    private static final int ACTION_TMUX_DESTROY = 31;

    private static final class HitTarget {
        final RectF rect = new RectF();
        final int action;
        @Nullable final String profileId;

        HitTarget(float left, float top, float right, float bottom, int action, @Nullable String profileId) {
            this.rect.set(left, top, right, bottom);
            this.action = action;
            this.profileId = profileId;
        }
    }

    private final float density;
    private final float titleSizePx;
    private final float bodySizePx;
    private final float smallSizePx;
    private final float headerHeightPx;
    private final float sectionGapPx;
    private final float cardRadiusPx;
    private final float cardGapPx;
    private final float contentPaddingPx;
    private final float actionChipHeightPx;
    private final float actionChipGapPx;
    private final int touchSlop;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardAccentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipDangerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint smallPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint chipTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint closePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final ArrayList<TermuxTerminalSessionActivityClient.ConfigProfileItem> profiles = new ArrayList<>();
    private final ArrayList<HitTarget> hitTargets = new ArrayList<>();
    private final OverScroller scroller;
    private final GestureDetector gestureDetector;

    private static final int GESTURE_NONE = 0;
    private static final int GESTURE_VERTICAL_SCROLL = 1;
    private static final int GESTURE_HORIZONTAL_PAGE = 2;

    @Nullable
    private Callbacks callbacks;
    private int scrollOffsetPx;
    private int contentHeightPx;
    private boolean movedDuringTouch;
    private float downX;
    private float downY;
    private int activeGesture = GESTURE_NONE;
    @Nullable private String tmuxProfileId;
    @Nullable private String tmuxProfileTitle;
    @Nullable private String tmuxTargetLabel;
    @Nullable private String tmuxErrorMessage;
    private boolean tmuxLoading;
    private boolean tmuxMissing;
    private final ArrayList<TermuxTerminalSessionActivityClient.ConfigTmuxSessionItem> tmuxSessions = new ArrayList<>();

    public TerminalConfigCanvasView(Context context) {
        this(context, null);
    }

    public TerminalConfigCanvasView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        titleSizePx = 20f * density;
        bodySizePx = 14f * density;
        smallSizePx = 12f * density;
        headerHeightPx = 64f * density;
        sectionGapPx = 18f * density;
        cardRadiusPx = 18f * density;
        cardGapPx = 14f * density;
        contentPaddingPx = 18f * density;
        actionChipHeightPx = 28f * density;
        actionChipGapPx = 8f * density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        bgPaint.setColor(0xFF0C1117);
        headerPaint.setColor(0xFF111A24);
        cardPaint.setColor(0xFF17222E);
        cardAccentPaint.setColor(0xFF1E3347);
        chipPaint.setColor(0xFF244057);
        chipDangerPaint.setColor(0xFF5B2330);

        titlePaint.setColor(Color.WHITE);
        titlePaint.setTextSize(titleSizePx);
        titlePaint.setFakeBoldText(true);

        bodyPaint.setColor(0xFFE8EEF5);
        bodyPaint.setTextSize(bodySizePx);

        smallPaint.setColor(0xFF9FB3C8);
        smallPaint.setTextSize(smallSizePx);

        chipTextPaint.setColor(Color.WHITE);
        chipTextPaint.setTextSize(smallSizePx);
        chipTextPaint.setFakeBoldText(true);

        closePaint.setColor(0xFFD7E3EF);
        closePaint.setTextSize(22f * density);
        closePaint.setFakeBoldText(true);
        closePaint.setTextAlign(Paint.Align.CENTER);

        scroller = new OverScroller(context);
        gestureDetector = new GestureDetector(context, new GestureListener());
        setFocusable(true);
        setClickable(true);
    }

    public void setCallbacks(@Nullable Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    public void setProfiles(@Nullable List<TermuxTerminalSessionActivityClient.ConfigProfileItem> items) {
        profiles.clear();
        if (items != null) profiles.addAll(items);
        contentHeightPx = 0;
        clampScroll();
        invalidate();
    }

    public void setTmuxState(@Nullable String profileId,
                             @Nullable String profileTitle,
                             @Nullable String targetLabel,
                             boolean loading,
                             boolean tmuxMissing,
                             @Nullable String errorMessage,
                             @Nullable List<TermuxTerminalSessionActivityClient.ConfigTmuxSessionItem> sessions) {
        this.tmuxProfileId = profileId;
        this.tmuxProfileTitle = profileTitle;
        this.tmuxTargetLabel = targetLabel;
        this.tmuxLoading = loading;
        this.tmuxMissing = tmuxMissing;
        this.tmuxErrorMessage = errorMessage;
        this.tmuxSessions.clear();
        if (sessions != null) this.tmuxSessions.addAll(sessions);
        contentHeightPx = 0;
        clampScroll();
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        final int width = getWidth();
        final int height = getHeight();
        if (width <= 0 || height <= 0) return;

        hitTargets.clear();

        canvas.drawRect(0, 0, width, height, bgPaint);
        drawHeader(canvas, width);

        canvas.save();
        canvas.translate(0, -scrollOffsetPx);
        float y = headerHeightPx + contentPaddingPx;
        y = drawPrimaryActions(canvas, width, y);
        y += sectionGapPx;
        if (tmuxProfileId == null) {
            y = drawProfiles(canvas, width, y);
        } else {
            y = drawTmuxSection(canvas, width, y);
        }
        contentHeightPx = Math.max((int) Math.ceil(y + contentPaddingPx), height);
        canvas.restore();

        drawScrollBar(canvas, width, height);
        clampScroll();
    }

    private void drawHeader(@NonNull Canvas canvas, int width) {
        canvas.drawRect(0, 0, width, headerHeightPx, headerPaint);
        float titleBaseline = 26f * density + contentPaddingPx;
        canvas.drawText("新建连接", contentPaddingPx, titleBaseline, titlePaint);
        canvas.drawText("点击操作，直接在这里连接或管理 SSH 配置。", contentPaddingPx,
            titleBaseline + 22f * density, smallPaint);

        float closeCenterX = width - contentPaddingPx;
        float closeCenterY = 24f * density;
        float half = 18f * density;
        hitTargets.add(new HitTarget(
            closeCenterX - half,
            closeCenterY - half,
            closeCenterX + half,
            closeCenterY + half,
            ACTION_CLOSE,
            null
        ));
        canvas.drawText("×", closeCenterX, closeCenterY + 8f * density, closePaint);
    }

    private float drawPrimaryActions(@NonNull Canvas canvas, int width, float top) {
        float left = contentPaddingPx;
        float right = width - contentPaddingPx;
        float cardHeight = 92f * density;

        RectF localRect = new RectF(left, top, right, top + cardHeight);
        canvas.drawRoundRect(localRect, cardRadiusPx, cardRadiusPx, cardPaint);
        canvas.drawRoundRect(
            new RectF(localRect.left, localRect.top, localRect.right, localRect.top + 6f * density),
            cardRadiusPx, cardRadiusPx, cardAccentPaint
        );
        hitTargets.add(new HitTarget(localRect.left, localRect.top, localRect.right, localRect.bottom, ACTION_LOCAL, null));
        drawSingleLine(canvas, "本地终端", localRect.left + 16f * density, localRect.top + 30f * density, titlePaint, localRect.width() - 32f * density);
        drawSingleLine(canvas, "直接基于当前工作目录创建新的本地会话。", localRect.left + 16f * density,
            localRect.top + 58f * density, smallPaint, localRect.width() - 32f * density);

        top += cardHeight + cardGapPx;

        RectF sshRect = new RectF(left, top, right, top + 82f * density);
        canvas.drawRoundRect(sshRect, cardRadiusPx, cardRadiusPx, cardPaint);
        hitTargets.add(new HitTarget(sshRect.left, sshRect.top, sshRect.right, sshRect.bottom, ACTION_ADD_SSH, null));
        drawSingleLine(canvas, "新增 SSH 配置", sshRect.left + 16f * density, sshRect.top + 28f * density, bodyPaint, sshRect.width() - 32f * density);
        drawSingleLine(canvas, "保存一个新的 SSH 命令，随后可在本页直接连接 / 编辑 / 持久化。", sshRect.left + 16f * density,
            sshRect.top + 54f * density, smallPaint, sshRect.width() - 32f * density);

        return top + sshRect.height();
    }

    private float drawProfiles(@NonNull Canvas canvas, int width, float top) {
        float left = contentPaddingPx;
        float right = width - contentPaddingPx;
        canvas.drawText("SSH 配置", left, top + 16f * density, titlePaint);
        top += 28f * density;

        if (profiles.isEmpty()) {
            RectF emptyRect = new RectF(left, top, right, top + 84f * density);
            canvas.drawRoundRect(emptyRect, cardRadiusPx, cardRadiusPx, cardPaint);
            drawSingleLine(canvas, "还没有已保存的 SSH 配置。", emptyRect.left + 16f * density,
                emptyRect.top + 34f * density, bodyPaint, emptyRect.width() - 32f * density);
            drawSingleLine(canvas, "先点上面的“新增 SSH 配置”。", emptyRect.left + 16f * density,
                emptyRect.top + 58f * density, smallPaint, emptyRect.width() - 32f * density);
            return emptyRect.bottom;
        }

        for (TermuxTerminalSessionActivityClient.ConfigProfileItem profile : profiles) {
            float cardHeight = 116f * density;
            RectF card = new RectF(left, top, right, top + cardHeight);
            canvas.drawRoundRect(card, cardRadiusPx, cardRadiusPx, cardPaint);
            hitTargets.add(new HitTarget(card.left, card.top, card.right, card.bottom, ACTION_PROFILE_CONNECT, profile.id));

            drawSingleLine(canvas, profile.title, card.left + 16f * density, card.top + 28f * density,
                bodyPaint, card.width() - 32f * density);
            drawSingleLine(canvas, profile.summary, card.left + 16f * density, card.top + 52f * density,
                smallPaint, card.width() - 32f * density);

            float chipTop = card.bottom - actionChipHeightPx - 14f * density;
            float chipLeft = card.left + 16f * density;
            chipLeft = drawActionChip(canvas, chipLeft, chipTop, "连接", ACTION_PROFILE_CONNECT, profile.id, false);
            chipLeft = drawActionChip(canvas, chipLeft, chipTop, "tmux", ACTION_PROFILE_TMUX, profile.id, false);
            chipLeft = drawActionChip(canvas, chipLeft, chipTop, "编辑", ACTION_PROFILE_EDIT, profile.id, false);
            drawActionChip(canvas, chipLeft, chipTop, "删除", ACTION_PROFILE_DELETE, profile.id, true);

            top += cardHeight + cardGapPx;
        }
        return top;
    }

    private float drawTmuxSection(@NonNull Canvas canvas, int width, float top) {
        float left = contentPaddingPx;
        float right = width - contentPaddingPx;
        String profileTitle = tmuxProfileTitle == null ? "tmux" : tmuxProfileTitle;
        drawSingleLine(canvas, "TMUX · " + profileTitle, left, top + 16f * density, titlePaint, right - left);
        top += 28f * density;

        float chipTop = top;
        float chipLeft = left;
        chipLeft = drawActionChip(canvas, chipLeft, chipTop, "返回", ACTION_TMUX_BACK, tmuxProfileId, false);
        chipLeft = drawActionChip(canvas, chipLeft, chipTop, "刷新", ACTION_TMUX_REFRESH, tmuxProfileId, false);
        if (tmuxMissing) {
            chipLeft = drawActionChip(canvas, chipLeft, chipTop, "安装 tmux", ACTION_TMUX_INSTALL, tmuxProfileId, false);
        } else {
            chipLeft = drawActionChip(canvas, chipLeft, chipTop, "新建会话", ACTION_TMUX_NEW, tmuxProfileId, false);
        }
        top += actionChipHeightPx + 14f * density;

        String targetLabel = tmuxTargetLabel == null ? "" : tmuxTargetLabel;
        if (!targetLabel.isEmpty()) {
            drawSingleLine(canvas, targetLabel, left, top + 14f * density, smallPaint, right - left);
            top += 24f * density;
        }

        if (tmuxLoading) {
            RectF loadingRect = new RectF(left, top, right, top + 78f * density);
            canvas.drawRoundRect(loadingRect, cardRadiusPx, cardRadiusPx, cardPaint);
            drawSingleLine(canvas, "正在获取远程 tmux 会话...", loadingRect.left + 16f * density,
                loadingRect.top + 42f * density, bodyPaint, loadingRect.width() - 32f * density);
            return loadingRect.bottom;
        }

        if (tmuxErrorMessage != null && !tmuxErrorMessage.isEmpty()) {
            RectF errRect = new RectF(left, top, right, top + 86f * density);
            canvas.drawRoundRect(errRect, cardRadiusPx, cardRadiusPx, chipDangerPaint);
            drawSingleLine(canvas, tmuxErrorMessage, errRect.left + 16f * density,
                errRect.top + 34f * density, bodyPaint, errRect.width() - 32f * density);
            return errRect.bottom;
        }

        if (tmuxMissing) {
            RectF missingRect = new RectF(left, top, right, top + 96f * density);
            canvas.drawRoundRect(missingRect, cardRadiusPx, cardRadiusPx, cardPaint);
            drawSingleLine(canvas, "服务器未安装 tmux。", missingRect.left + 16f * density,
                missingRect.top + 36f * density, bodyPaint, missingRect.width() - 32f * density);
            drawSingleLine(canvas, "点上方“安装 tmux”后再刷新。", missingRect.left + 16f * density,
                missingRect.top + 62f * density, smallPaint, missingRect.width() - 32f * density);
            return missingRect.bottom;
        }

        if (tmuxSessions.isEmpty()) {
            RectF emptyRect = new RectF(left, top, right, top + 86f * density);
            canvas.drawRoundRect(emptyRect, cardRadiusPx, cardRadiusPx, cardPaint);
            drawSingleLine(canvas, "该服务器暂无 tmux 会话。", emptyRect.left + 16f * density,
                emptyRect.top + 36f * density, bodyPaint, emptyRect.width() - 32f * density);
            drawSingleLine(canvas, "可直接点上方“新建会话”。", emptyRect.left + 16f * density,
                emptyRect.top + 62f * density, smallPaint, emptyRect.width() - 32f * density);
            return emptyRect.bottom;
        }

        for (TermuxTerminalSessionActivityClient.ConfigTmuxSessionItem item : tmuxSessions) {
            float cardHeight = 104f * density;
            RectF card = new RectF(left, top, right, top + cardHeight);
            canvas.drawRoundRect(card, cardRadiusPx, cardRadiusPx, item.current ? cardAccentPaint : cardPaint);
            drawSingleLine(canvas, item.title, card.left + 16f * density, card.top + 30f * density,
                bodyPaint, card.width() - 32f * density);
            drawSingleLine(canvas, item.summary, card.left + 16f * density, card.top + 54f * density,
                smallPaint, card.width() - 32f * density);

            float actionTop = card.bottom - actionChipHeightPx - 14f * density;
            float actionLeft = card.left + 16f * density;
            actionLeft = drawActionChip(canvas, actionLeft, actionTop, "连接", ACTION_TMUX_CONNECT, item.name, false);
            drawActionChip(canvas, actionLeft, actionTop, "销毁", ACTION_TMUX_DESTROY, item.name, true);
            top += cardHeight + cardGapPx;
        }
        return top;
    }

    private float drawActionChip(@NonNull Canvas canvas, float left, float top, @NonNull String text,
                                 int action, @NonNull String profileId, boolean danger) {
        float width = bodyPaint.measureText(text) + 26f * density;
        RectF rect = new RectF(left, top, left + width, top + actionChipHeightPx);
        canvas.drawRoundRect(rect, actionChipHeightPx / 2f, actionChipHeightPx / 2f,
            danger ? chipDangerPaint : chipPaint);
        float baseline = rect.centerY() - ((chipTextPaint.descent() + chipTextPaint.ascent()) / 2f);
        canvas.drawText(text, rect.left + 13f * density, baseline, chipTextPaint);
        hitTargets.add(new HitTarget(rect.left, rect.top, rect.right, rect.bottom, action, profileId));
        return rect.right + actionChipGapPx;
    }

    private void drawSingleLine(@NonNull Canvas canvas, @NonNull String text, float x, float y,
                                @NonNull TextPaint paint, float maxWidth) {
        CharSequence ellipsized = TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END);
        canvas.drawText(ellipsized, 0, ellipsized.length(), x, y, paint);
    }

    private void drawScrollBar(@NonNull Canvas canvas, int width, int height) {
        int maxScroll = getMaxScrollOffset();
        if (maxScroll <= 0) return;
        float trackLeft = width - 4f * density;
        float trackTop = headerHeightPx + 10f * density;
        float trackBottom = height - 10f * density;
        Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setColor(0x33263B52);
        canvas.drawRoundRect(new RectF(trackLeft, trackTop, trackLeft + 2f * density, trackBottom),
            density, density, trackPaint);

        float visible = Math.max(1f, height - headerHeightPx);
        float thumbHeight = Math.max(36f * density, visible * (visible / contentHeightPx));
        float travel = Math.max(1f, (trackBottom - trackTop) - thumbHeight);
        float top = trackTop + (travel * scrollOffsetPx / (float) maxScroll);
        Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setColor(0x99B2C7DD);
        canvas.drawRoundRect(new RectF(trackLeft, top, trackLeft + 2f * density, top + thumbHeight),
            density, density, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureDetector.onTouchEvent(event);
                movedDuringTouch = false;
                downX = event.getX();
                downY = event.getY();
                activeGesture = GESTURE_NONE;
                scroller.forceFinished(true);
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                float absDx = Math.abs(dx);
                float absDy = Math.abs(dy);
                if (activeGesture == GESTURE_NONE && (absDx > touchSlop || absDy > touchSlop)) {
                    activeGesture = absDx > absDy ? GESTURE_HORIZONTAL_PAGE : GESTURE_VERTICAL_SCROLL;
                    movedDuringTouch = true;
                    getParent().requestDisallowInterceptTouchEvent(activeGesture == GESTURE_VERTICAL_SCROLL);
                }
                if (activeGesture == GESTURE_HORIZONTAL_PAGE) {
                    return false;
                }
                gestureDetector.onTouchEvent(event);
                if (!movedDuringTouch) {
                    movedDuringTouch = absDx > touchSlop || absDy > touchSlop;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (activeGesture != GESTURE_HORIZONTAL_PAGE) {
                    gestureDetector.onTouchEvent(event);
                }
                getParent().requestDisallowInterceptTouchEvent(false);
                if (!movedDuringTouch && activeGesture != GESTURE_HORIZONTAL_PAGE) {
                    handleTap(event.getX(), event.getY() + scrollOffsetPx);
                }
                activeGesture = GESTURE_NONE;
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                activeGesture = GESTURE_NONE;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event == null) return super.onGenericMotionEvent(event);
        if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) == 0 ||
            event.getAction() != MotionEvent.ACTION_SCROLL) {
            return super.onGenericMotionEvent(event);
        }

        float axisValue = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        if (axisValue == 0f) {
            axisValue = event.getAxisValue(MotionEvent.AXIS_SCROLL);
        }
        if (axisValue == 0f) {
            return super.onGenericMotionEvent(event);
        }

        scrollByInternal(Math.round(-axisValue * 48f * density));
        movedDuringTouch = true;
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void handleTap(float x, float y) {
        Callbacks c = callbacks;
        if (c == null) return;
        for (int i = hitTargets.size() - 1; i >= 0; i--) {
            HitTarget target = hitTargets.get(i);
            if (!target.rect.contains(x, y)) continue;
            switch (target.action) {
                case ACTION_LOCAL:
                    c.onCreateLocalSession();
                    return;
                case ACTION_ADD_SSH:
                    c.onAddSshProfile();
                    return;
                case ACTION_PROFILE_CONNECT:
                    if (target.profileId != null) c.onConnectProfile(target.profileId);
                    return;
                case ACTION_PROFILE_EDIT:
                    if (target.profileId != null) c.onEditProfile(target.profileId);
                    return;
                case ACTION_PROFILE_TMUX:
                    if (target.profileId != null) c.onOpenTmuxProfile(target.profileId);
                    return;
                case ACTION_PROFILE_DELETE:
                    if (target.profileId != null) c.onDeleteProfile(target.profileId);
                    return;
                case ACTION_CLOSE:
                    c.onCloseConfigTab();
                    return;
                case ACTION_TMUX_BACK:
                    c.onBackFromTmux();
                    return;
                case ACTION_TMUX_REFRESH:
                    if (tmuxProfileId != null) c.onRefreshTmux(tmuxProfileId);
                    return;
                case ACTION_TMUX_NEW:
                    if (tmuxProfileId != null) c.onCreateTmux(tmuxProfileId);
                    return;
                case ACTION_TMUX_INSTALL:
                    if (tmuxProfileId != null) c.onInstallTmux(tmuxProfileId);
                    return;
                case ACTION_TMUX_CONNECT:
                    if (tmuxProfileId != null && target.profileId != null) {
                        String displayName = target.profileId;
                        for (TermuxTerminalSessionActivityClient.ConfigTmuxSessionItem item : tmuxSessions) {
                            if (item.name.equals(target.profileId)) {
                                displayName = item.title;
                                break;
                            }
                        }
                        c.onConnectTmux(tmuxProfileId, target.profileId, displayName);
                    }
                    return;
                case ACTION_TMUX_DESTROY:
                    if (tmuxProfileId != null && target.profileId != null) {
                        c.onDestroyTmux(tmuxProfileId, target.profileId);
                    }
                    return;
                default:
                    return;
            }
        }
    }

    private void scrollByInternal(int dy) {
        if (dy == 0) return;
        int max = getMaxScrollOffset();
        int next = scrollOffsetPx + dy;
        if (next < 0) next = 0;
        if (next > max) next = max;
        if (next != scrollOffsetPx) {
            scrollOffsetPx = next;
            invalidate();
        }
    }

    private void clampScroll() {
        int max = getMaxScrollOffset();
        if (scrollOffsetPx < 0) scrollOffsetPx = 0;
        if (scrollOffsetPx > max) scrollOffsetPx = max;
    }

    private int getMaxScrollOffset() {
        return Math.max(0, contentHeightPx - getHeight());
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffsetPx = scroller.getCurrY();
            invalidate();
        } else {
            super.computeScroll();
        }
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(@NonNull MotionEvent e) {
            scroller.forceFinished(true);
            return true;
        }

        @Override
        public boolean onScroll(@NonNull MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
            if (activeGesture == GESTURE_HORIZONTAL_PAGE) {
                return false;
            }
            activeGesture = GESTURE_VERTICAL_SCROLL;
            getParent().requestDisallowInterceptTouchEvent(true);
            scrollByInternal(Math.round(distanceY));
            movedDuringTouch = true;
            return true;
        }

        @Override
        public boolean onFling(@NonNull MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
            int max = getMaxScrollOffset();
            if (max <= 0) return false;
            scroller.fling(0, scrollOffsetPx, 0, Math.round(-velocityY), 0, 0, 0, max);
            invalidate();
            movedDuringTouch = true;
            return true;
        }
    }
}
