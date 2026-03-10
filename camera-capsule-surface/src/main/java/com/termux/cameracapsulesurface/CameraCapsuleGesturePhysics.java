package com.termux.cameracapsulesurface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class CameraCapsuleGesturePhysics {

    static final class Snapshot {
        @NonNull final CameraCapsulePlacementEngine.SurfaceMode dominantMode;
        final float compressionProgress;
        final float pageProgress;
        final float pressProgress;
        final float bodySqueezeProgress;
        final float bodyStretchProgress;
        final float bodyAttachProgress;
        final float contactMemoryProgress;
        final float contentVisibilityProgress;
        final float releaseConfidence;
        final int distancePx;

        Snapshot(@NonNull CameraCapsulePlacementEngine.SurfaceMode dominantMode,
                 float compressionProgress,
                 float pageProgress,
                 float pressProgress,
                 float bodySqueezeProgress,
                 float bodyStretchProgress,
                 float bodyAttachProgress,
                 float contactMemoryProgress,
                 float contentVisibilityProgress,
                 float releaseConfidence,
                 int distancePx) {
            this.dominantMode = dominantMode;
            this.compressionProgress = clamp01(compressionProgress);
            this.pageProgress = clamp01(pageProgress);
            this.pressProgress = clamp01(pressProgress);
            this.bodySqueezeProgress = clamp01(bodySqueezeProgress);
            this.bodyStretchProgress = clamp01(bodyStretchProgress);
            this.bodyAttachProgress = clamp01(bodyAttachProgress);
            this.contactMemoryProgress = clamp01(contactMemoryProgress);
            this.contentVisibilityProgress = clamp01(contentVisibilityProgress);
            this.releaseConfidence = clamp01(releaseConfidence);
            this.distancePx = Math.max(0, distancePx);
        }

        boolean isActive() {
            return dominantMode != CameraCapsulePlacementEngine.SurfaceMode.FLOATING
                && (compressionProgress > 0.01f
                    || pageProgress > 0.01f
                    || bodyAttachProgress > 0.08f
                    || contactMemoryProgress > 0.10f);
        }
    }

    private static final class AxisState {
        @NonNull final CameraCapsulePlacementEngine.SurfaceMode mode;
        float compression;
        float compressionVelocity;
        float page;
        float pageVelocity;
        float press;
        float pressVelocity;
        float squeeze;
        float squeezeVelocity;
        float stretch;
        float stretchVelocity;
        float attach;
        float attachVelocity;
        float contactMemory;
        int distancePx;
        int penetrationPx;
        float outwardVelocityPxPerSec;
        float alignment;

        AxisState(@NonNull CameraCapsulePlacementEngine.SurfaceMode mode) {
            this.mode = mode;
            reset();
        }

        void reset() {
            compression = 0f;
            compressionVelocity = 0f;
            page = 0f;
            pageVelocity = 0f;
            press = 0f;
            pressVelocity = 0f;
            squeeze = 0f;
            squeezeVelocity = 0f;
            stretch = 0f;
            stretchVelocity = 0f;
            attach = 0f;
            attachVelocity = 0f;
            contactMemory = 0f;
            distancePx = Integer.MAX_VALUE;
            penetrationPx = 0;
            outwardVelocityPxPerSec = 0f;
            alignment = 0f;
        }
    }

    private final float density;
    @NonNull
    private final AxisState leftAxis = new AxisState(CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT);
    @NonNull
    private final AxisState rightAxis = new AxisState(CameraCapsulePlacementEngine.SurfaceMode.DOCKED_RIGHT);
    @NonNull
    private final AxisState topAxis = new AxisState(CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP);

    private boolean initialized;
    private float lastRawX;
    private float lastRawY;
    private long lastStepTimeMs;
    @NonNull
    private Snapshot lastSnapshot = idleSnapshot();

    CameraCapsuleGesturePhysics(float density) {
        this.density = Math.max(0.75f, density);
    }

    void beginGesture(float rawX, float rawY, long eventTimeMs) {
        initialized = true;
        lastRawX = rawX;
        lastRawY = rawY;
        lastStepTimeMs = Math.max(0L, eventTimeMs);
        leftAxis.reset();
        rightAxis.reset();
        topAxis.reset();
        lastSnapshot = idleSnapshot();
    }

    void cancelGesture() {
        initialized = false;
        lastStepTimeMs = 0L;
        leftAxis.reset();
        rightAxis.reset();
        topAxis.reset();
        lastSnapshot = idleSnapshot();
    }

    @NonNull
    Snapshot update(@NonNull CameraCapsulePlacementEngine.DisplayProfile displayProfile,
                    @NonNull CameraCapsulePlacementEngine.PlacementResult floatingPlacement,
                    float rawX,
                    float rawY,
                    long stepTimeMs) {
        if (!initialized) beginGesture(rawX, rawY, stepTimeMs);
        float deltaSeconds = resolveDeltaSeconds(stepTimeMs);
        float velocityXPxPerSec = (rawX - lastRawX) / deltaSeconds;
        float velocityYPxPerSec = (rawY - lastRawY) / deltaSeconds;

        float centerXPx = floatingPlacement.xPx + (floatingPlacement.widthPx / 2f);
        float centerYPx = floatingPlacement.yPx + (floatingPlacement.heightPx / 2f);
        float sideAlignment = smoothStep01(
            1f - (Math.abs(rawY - centerYPx) / Math.max(dp(120), floatingPlacement.heightPx * 1.10f)));
        float topAlignment = smoothStep01(
            1f - (Math.abs(rawX - centerXPx) / Math.max(dp(180), floatingPlacement.widthPx * 0.72f)));

        int leftContactInsetPx = Math.max(dp(8), displayProfile.systemGestureInsetLeftPx + dp(4));
        int rightContactInsetPx = Math.max(dp(8), displayProfile.systemGestureInsetRightPx + dp(4));
        int topContactInsetPx = Math.max(displayProfile.statusBarBottomPx, displayProfile.cutoutBottomPx);

        int leftDistancePx = Math.max(0, floatingPlacement.xPx - leftContactInsetPx);
        int leftPenetrationPx = Math.max(0, leftContactInsetPx - floatingPlacement.xPx);
        int rightBodyEdgePx = floatingPlacement.xPx + floatingPlacement.widthPx;
        int rightDistancePx = Math.max(0, (displayProfile.displayWidthPx - rightContactInsetPx) - rightBodyEdgePx);
        int rightPenetrationPx = Math.max(0, rightBodyEdgePx - (displayProfile.displayWidthPx - rightContactInsetPx));
        int topDistancePx = Math.max(0, floatingPlacement.yPx - topContactInsetPx);
        int topPenetrationPx = Math.max(0, topContactInsetPx - floatingPlacement.yPx);

        updateAxis(
            leftAxis,
            leftDistancePx,
            leftPenetrationPx,
            Math.max(0f, -velocityXPxPerSec),
            sideAlignment,
            sideCompressionRangePx(),
            deltaSeconds);
        updateAxis(
            rightAxis,
            rightDistancePx,
            rightPenetrationPx,
            Math.max(0f, velocityXPxPerSec),
            sideAlignment,
            sideCompressionRangePx(),
            deltaSeconds);
        updateAxis(
            topAxis,
            topDistancePx,
            topPenetrationPx,
            Math.max(0f, -velocityYPxPerSec),
            topAlignment,
            topCompressionRangePx(),
            deltaSeconds);

        suppressCompetingAxes();

        Snapshot snapshot = buildSnapshot();
        lastRawX = rawX;
        lastRawY = rawY;
        lastStepTimeMs = Math.max(lastStepTimeMs, stepTimeMs);
        lastSnapshot = snapshot;
        return snapshot;
    }

    @NonNull
    CameraCapsulePlacementEngine.SurfaceMode resolveReleaseSurfaceMode() {
        return resolveReleaseSurfaceMode(lastSnapshot);
    }

    @NonNull
    CameraCapsulePlacementEngine.SurfaceMode resolveReleaseSurfaceMode(@Nullable Snapshot snapshot) {
        if (snapshot == null) snapshot = lastSnapshot;
        if (!snapshot.isActive()) return CameraCapsulePlacementEngine.SurfaceMode.FLOATING;
        if ((snapshot.dominantMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_LEFT
            || snapshot.dominantMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_RIGHT)
            && ((snapshot.compressionProgress >= 0.18f
                && snapshot.pageProgress >= 0.08f
                && snapshot.bodyAttachProgress >= 0.26f)
                || (snapshot.pageProgress >= 0.08f
                    && snapshot.bodyAttachProgress >= 0.12f)
                || (snapshot.compressionProgress >= 0.24f
                    && snapshot.pageProgress >= 0.04f))) {
            return snapshot.dominantMode;
        }
        float threshold = snapshot.dominantMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP
            ? 0.20f
            : 0.18f;
        float attachThreshold = snapshot.dominantMode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP
            ? 0f
            : 0.12f;
        return snapshot.releaseConfidence >= threshold
            && snapshot.bodyAttachProgress >= attachThreshold
            ? snapshot.dominantMode
            : CameraCapsulePlacementEngine.SurfaceMode.FLOATING;
    }

    @NonNull
    Snapshot currentSnapshot() {
        return lastSnapshot;
    }

    private void updateAxis(@NonNull AxisState axis,
                            int distancePx,
                            int penetrationPx,
                            float outwardVelocityPxPerSec,
                            float alignment,
                            int compressionRangePx,
                            float deltaSeconds) {
        axis.distancePx = distancePx;
        axis.penetrationPx = penetrationPx;
        axis.outwardVelocityPxPerSec = outwardVelocityPxPerSec;
        axis.alignment = alignment;

        float compressionTarget = resolveCompressionTarget(
            axis,
            penetrationPx,
            outwardVelocityPxPerSec,
            alignment,
            compressionRangePx);
        stepContactMemory(axis, compressionTarget, distancePx, penetrationPx, alignment, deltaSeconds);
        stepSpring(axis.mode, SpringChannel.COMPRESSION, axis, compressionTarget, deltaSeconds);

        float pageTarget = resolvePageTarget(axis.mode, axis.compression, axis.contactMemory);
        stepSpring(axis.mode, SpringChannel.PAGE, axis, pageTarget, deltaSeconds);

        float pressTarget = clamp01(axis.compression * (1f - (0.34f * axis.page)));
        stepSpring(axis.mode, SpringChannel.PRESS, axis, pressTarget, deltaSeconds);

        float squeezeTarget = resolveSqueezeTarget(axis.compression, axis.page, axis.press);
        stepSpring(axis.mode, SpringChannel.SQUEEZE, axis, squeezeTarget, deltaSeconds);

        float stretchTarget = resolveStretchTarget(axis.compression, axis.page, axis.press);
        stepSpring(axis.mode, SpringChannel.STRETCH, axis, stretchTarget, deltaSeconds);

        float attachTarget = resolveAttachTarget(axis.compression, axis.page, axis.contactMemory, alignment);
        stepSpring(axis.mode, SpringChannel.ATTACH, axis, attachTarget, deltaSeconds);
    }

    private float resolveCompressionTarget(@NonNull AxisState axis,
                                           int penetrationPx,
                                           float outwardVelocityPxPerSec,
                                           float alignment,
                                           int compressionRangePx) {
        if (penetrationPx <= 0 && axis.compression <= 0.0001f) return 0f;
        float contactProgress = smoothStep01(penetrationPx / (float) Math.max(1, compressionRangePx));
        float velocityBoost = 1f - (float) Math.exp(
            -outwardVelocityPxPerSec / Math.max(1f, dpPerSecond(2200f)));
        float memoryGain = 1f + (axis.compression * 0.12f);
        return clamp01(contactProgress * alignment * (0.78f + (0.22f * velocityBoost)) * memoryGain);
    }

    private void stepContactMemory(@NonNull AxisState axis,
                                   float compressionTarget,
                                   int distancePx,
                                   int penetrationPx,
                                   float alignment,
                                   float deltaSeconds) {
        float edgeEnvelopePx = axis.mode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP
            ? dp(10)
            : dp(12);
        float proximityTarget = smoothStep01(1f - (distancePx / Math.max(1f, edgeEnvelopePx)));
        float target = clamp01(Math.max(
            penetrationPx > 0 ? (compressionTarget * 0.72f) + (alignment * 0.28f) : 0f,
            proximityTarget * alignment * 0.82f));
        float response = target >= axis.contactMemory ? 6.2f : (target > 0.001f ? 2.4f : 1.7f);
        axis.contactMemory += (target - axis.contactMemory) * response * deltaSeconds;
        axis.contactMemory = clamp01(axis.contactMemory);
    }

    private float resolvePageTarget(@NonNull CameraCapsulePlacementEngine.SurfaceMode mode,
                                    float compression,
                                    float contactMemory) {
        float latchStart = pageLatchThreshold(mode);
        float drivenCompression = clamp01((compression * 0.38f) + (contactMemory * 0.82f));
        return smoothStep01((drivenCompression - latchStart) / Math.max(0.001f, 1f - latchStart));
    }

    private float resolveSqueezeTarget(float compression, float page, float press) {
        return clamp01((compression * 0.62f) + (page * 0.26f) + (press * 0.12f));
    }

    private float resolveStretchTarget(float compression, float page, float press) {
        return clamp01((compression * 0.14f) + (press * 0.48f) + (page * 0.12f));
    }

    private float resolveAttachTarget(float compression, float page, float contactMemory, float alignment) {
        float pageLead = smoothStep01((page - 0.04f) / 0.96f);
        return clamp01((compression * 0.18f * alignment) + (contactMemory * 0.46f) + (pageLead * 0.36f));
    }

    private void stepSpring(@NonNull CameraCapsulePlacementEngine.SurfaceMode mode,
                            @NonNull SpringChannel channel,
                            @NonNull AxisState axis,
                            float target,
                            float deltaSeconds) {
        float stiffness = stiffness(mode, channel);
        float damping = damping(mode, channel);
        float value = currentValue(axis, channel);
        float velocity = currentVelocity(axis, channel);
        float acceleration = (stiffness * (target - value)) - (damping * velocity);
        velocity += acceleration * deltaSeconds;
        value += velocity * deltaSeconds;
        if (value < 0f) {
            value = 0f;
            if (velocity < 0f) velocity = 0f;
        } else if (value > 1f) {
            value = 1f;
            if (velocity > 0f) velocity = 0f;
        }
        setValue(axis, channel, value, velocity);
    }

    private void suppressCompetingAxes() {
        AxisState dominantAxis = dominantAxis();
        if (dominantAxis == null) return;
        dampAxisIfCompeting(leftAxis, dominantAxis);
        dampAxisIfCompeting(rightAxis, dominantAxis);
        dampAxisIfCompeting(topAxis, dominantAxis);
    }

    private void dampAxisIfCompeting(@NonNull AxisState axis, @NonNull AxisState dominantAxis) {
        if (axis == dominantAxis) return;
        float dominantWeight = dominanceWeight(dominantAxis);
        float axisWeight = dominanceWeight(axis);
        if (dominantWeight <= axisWeight + 0.06f) return;
        axis.compression *= 0.84f;
        axis.compressionVelocity *= 0.62f;
        axis.page *= 0.72f;
        axis.pageVelocity *= 0.54f;
        axis.press *= 0.82f;
        axis.pressVelocity *= 0.60f;
        axis.squeeze *= 0.76f;
        axis.squeezeVelocity *= 0.58f;
        axis.stretch *= 0.78f;
        axis.stretchVelocity *= 0.60f;
        axis.attach *= 0.72f;
        axis.attachVelocity *= 0.54f;
        axis.contactMemory *= 0.74f;
    }

    @Nullable
    private AxisState dominantAxis() {
        AxisState dominant = leftAxis;
        if (dominanceWeight(rightAxis) > dominanceWeight(dominant)) dominant = rightAxis;
        if (dominanceWeight(topAxis) > dominanceWeight(dominant)) dominant = topAxis;
        return dominanceWeight(dominant) > 0.01f ? dominant : null;
    }

    private float dominanceWeight(@NonNull AxisState axis) {
        return (axis.page * 1.24f)
            + (axis.attach * 0.62f)
            + (axis.contactMemory * 0.52f)
            + (axis.compression * 0.34f)
            + (axis.press * 0.12f);
    }

    @NonNull
    private Snapshot buildSnapshot() {
        AxisState dominantAxis = dominantAxis();
        if (dominantAxis == null) return idleSnapshot();
        float kineticConfidence = 1f - (float) Math.exp(
            -dominantAxis.outwardVelocityPxPerSec / Math.max(1f, dpPerSecond(2200f)));
        float releaseConfidence = clamp01(
            (dominantAxis.page * 0.58f)
                + (dominantAxis.attach * 0.18f)
                + (dominantAxis.contactMemory * 0.18f)
                + (dominantAxis.compression * 0.02f)
                + (kineticConfidence * 0.04f));
        float contentVisibility = clamp01(
            1f - smoothStep01(
                (dominantAxis.squeeze * 0.56f)
                    + (dominantAxis.attach * 0.74f)
                    + (dominantAxis.page * 0.22f)
                    - 0.10f));
        return new Snapshot(
            dominantAxis.mode,
            dominantAxis.compression,
            dominantAxis.page,
            dominantAxis.press,
            dominantAxis.squeeze,
            dominantAxis.stretch,
            dominantAxis.attach,
            dominantAxis.contactMemory,
            contentVisibility,
            releaseConfidence,
            dominantAxis.distancePx);
    }

    private float resolveDeltaSeconds(long stepTimeMs) {
        if (stepTimeMs <= 0L || lastStepTimeMs <= 0L) return 1f / 60f;
        long deltaMs = Math.max(8L, Math.min(48L, stepTimeMs - lastStepTimeMs));
        return deltaMs / 1000f;
    }

    private float stiffness(@NonNull CameraCapsulePlacementEngine.SurfaceMode mode,
                            @NonNull SpringChannel channel) {
        switch (channel) {
            case PAGE:
                return mode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP ? 260f : 320f;
            case PRESS:
                return 240f;
            case SQUEEZE:
                return mode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP ? 240f : 320f;
            case STRETCH:
                return 220f;
            case ATTACH:
                return mode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP ? 280f : 360f;
            case COMPRESSION:
            default:
                return mode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP ? 420f : 560f;
        }
    }

    private float damping(@NonNull CameraCapsulePlacementEngine.SurfaceMode mode,
                          @NonNull SpringChannel channel) {
        switch (channel) {
            case PAGE:
                return mode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP ? 28f : 32f;
            case PRESS:
                return 26f;
            case SQUEEZE:
                return mode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP ? 24f : 30f;
            case STRETCH:
                return 22f;
            case ATTACH:
                return mode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP ? 28f : 34f;
            case COMPRESSION:
            default:
                return mode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP ? 34f : 42f;
        }
    }

    private float currentValue(@NonNull AxisState axis, @NonNull SpringChannel channel) {
        switch (channel) {
            case PAGE:
                return axis.page;
            case PRESS:
                return axis.press;
            case SQUEEZE:
                return axis.squeeze;
            case STRETCH:
                return axis.stretch;
            case ATTACH:
                return axis.attach;
            case COMPRESSION:
            default:
                return axis.compression;
        }
    }

    private float currentVelocity(@NonNull AxisState axis, @NonNull SpringChannel channel) {
        switch (channel) {
            case PAGE:
                return axis.pageVelocity;
            case PRESS:
                return axis.pressVelocity;
            case SQUEEZE:
                return axis.squeezeVelocity;
            case STRETCH:
                return axis.stretchVelocity;
            case ATTACH:
                return axis.attachVelocity;
            case COMPRESSION:
            default:
                return axis.compressionVelocity;
        }
    }

    private void setValue(@NonNull AxisState axis,
                          @NonNull SpringChannel channel,
                          float value,
                          float velocity) {
        switch (channel) {
            case PAGE:
                axis.page = value;
                axis.pageVelocity = velocity;
                return;
            case PRESS:
                axis.press = value;
                axis.pressVelocity = velocity;
                return;
            case SQUEEZE:
                axis.squeeze = value;
                axis.squeezeVelocity = velocity;
                return;
            case STRETCH:
                axis.stretch = value;
                axis.stretchVelocity = velocity;
                return;
            case ATTACH:
                axis.attach = value;
                axis.attachVelocity = velocity;
                return;
            case COMPRESSION:
            default:
                axis.compression = value;
                axis.compressionVelocity = velocity;
        }
    }

    private float pageLatchThreshold(@NonNull CameraCapsulePlacementEngine.SurfaceMode mode) {
        return mode == CameraCapsulePlacementEngine.SurfaceMode.DOCKED_TOP ? 0.18f : 0.12f;
    }

    private int sideCompressionRangePx() {
        return dp(42);
    }

    private int topCompressionRangePx() {
        return dp(30);
    }

    private float dpPerSecond(float valueDpPerSecond) {
        return valueDpPerSecond * density;
    }

    private int dp(int valueDp) {
        return Math.round(valueDp * density);
    }

    @NonNull
    private static Snapshot idleSnapshot() {
        return new Snapshot(
            CameraCapsulePlacementEngine.SurfaceMode.FLOATING,
            0f,
            0f,
            0f,
            0f,
            0f,
            0f,
            0f,
            1f,
            0f,
            Integer.MAX_VALUE);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float smoothStep01(float value) {
        float x = clamp01(value);
        return x * x * (3f - (2f * x));
    }

    private enum SpringChannel {
        COMPRESSION,
        PAGE,
        PRESS,
        SQUEEZE,
        STRETCH,
        ATTACH
    }
}
