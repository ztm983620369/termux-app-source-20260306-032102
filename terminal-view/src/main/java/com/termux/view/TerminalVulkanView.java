package com.termux.view;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Per-tab Vulkan surface with an atomic first-frame contract and Canvas fallback. */
public final class TerminalVulkanView extends TextureView
    implements TextureView.SurfaceTextureListener {
    private static final String LOG_TAG = "TermuxVulkanView";
    private static final String VULKAN_FEATURE = "android.hardware.vulkan.level";
    private static final long SURFACE_DESTROY_TIMEOUT_MS = 1500L;
    private static final int MAX_CONSECUTIVE_RENDERER_RECOVERIES = 1;

    private final AtomicReference<TerminalGpuFrame> pendingFrame = new AtomicReference<>();
    private final AtomicBoolean renderScheduled = new AtomicBoolean();
    private final AtomicBoolean destroyScheduled = new AtomicBoolean();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final boolean supported;

    @Nullable private HandlerThread renderThread;
    @Nullable private Handler renderHandler;
    @Nullable private TerminalVulkanRenderer renderer;
    @Nullable private volatile Surface currentSurface;
    @Nullable private TerminalView terminalView;
    private volatile boolean renderActive = true;
    private volatile boolean surfaceReady;
    private volatile boolean detached;
    private volatile boolean createPending;
    private volatile int surfaceWidth;
    private volatile int surfaceHeight;
    private volatile long surfaceGeneration;
    // Accessed only on the dedicated render thread.
    private int consecutiveRendererRecoveryAttempts;
    private volatile boolean frameReady;
    private volatile boolean permanentlyFailed;
    private volatile long consumedCommandGeneration = Long.MIN_VALUE;
    private volatile int consumedTopRow = Integer.MIN_VALUE;
    private volatile int presentedViewWidth = -1;
    private volatile int presentedViewHeight = -1;
    private volatile int presentedTextSize = -1;
    private volatile int presentedFontWidthBits;
    private volatile int presentedFontLineSpacing = -1;
    private volatile int presentedFontAscent;
    private volatile int presentedScreenRows = -1;
    private volatile int presentedViewportPixelOffsetBits;
    private volatile long presentedCommandGeneration = Long.MIN_VALUE;
    private volatile long presentedModelRevision = Long.MIN_VALUE;
    private volatile long presentedFrameId;
    private volatile long submittedFrameId;
    private volatile long presentedFrames;
    private volatile long incompleteFrames;
    private volatile long retryFrames;
    private volatile long failedFrames;
    private volatile long rendererRecoveries;
    private volatile long rendererRecoveryFailures;
    private volatile long lastRenderNanos;
    private volatile long maxRenderNanos;
    private volatile long lastLogMs;

    public TerminalVulkanView(Context context) {
        this(context, null);
    }

    public TerminalVulkanView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TerminalVulkanView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        supported = isSupported(context);
        setSurfaceTextureListener(this);
        setOpaque(true);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public static boolean isSupported(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            !TerminalVulkanRenderer.isNativeAvailable()) return false;
        PackageManager packageManager = context.getPackageManager();
        return packageManager != null && packageManager.hasSystemFeature(VULKAN_FEATURE);
    }

    public boolean isSupported() {
        return supported && !permanentlyFailed;
    }

    public boolean isHardwareSupportedForDiagnostics() {
        return supported;
    }

    public boolean hasPermanentlyFailedForDiagnostics() {
        return permanentlyFailed;
    }

    public boolean isFrameReady() {
        return renderActive && frameReady && surfaceReady && !permanentlyFailed;
    }

    boolean isFrameReadyForGeometry(int viewWidth, int viewHeight, int textSize,
                                    float fontWidth, int fontLineSpacing, int fontAscent,
                                    int screenRows, int viewportTopRow,
                                    float viewportPixelOffset, long commandGeneration,
                                    long modelRevision) {
        return isFrameReady() && presentedViewWidth == viewWidth &&
            presentedViewHeight == viewHeight && presentedTextSize == textSize &&
            presentedFontWidthBits == Float.floatToIntBits(fontWidth) &&
            presentedFontLineSpacing == fontLineSpacing &&
            presentedFontAscent == fontAscent && presentedScreenRows == screenRows &&
            consumedTopRow == viewportTopRow && matchesFrameIdentity(
                presentedViewportPixelOffsetBits, presentedCommandGeneration,
                presentedModelRevision, viewportPixelOffset, commandGeneration, modelRevision);
    }

    static boolean matchesFrameIdentity(int presentedViewportPixelOffsetBits,
                                        long presentedCommandGeneration,
                                        long presentedModelRevision,
                                        float viewportPixelOffset,
                                        long commandGeneration,
                                        long modelRevision) {
        return presentedViewportPixelOffsetBits == Float.floatToIntBits(viewportPixelOffset) &&
            presentedCommandGeneration == commandGeneration &&
            presentedModelRevision == modelRevision;
    }

    public boolean isRenderActive() {
        return renderActive;
    }

    public long getConsumedCommandGeneration() {
        return consumedCommandGeneration;
    }

    public int getConsumedTopRow() {
        return consumedTopRow;
    }

    public long getPresentedFrameId() {
        return presentedFrameId;
    }

    public long getPresentedFrameCountForDiagnostics() {
        return presentedFrames;
    }

    public String getDiagnostics() {
        TerminalVulkanRenderer local = renderer;
        return "supported=" + supported + " active=" + renderActive +
            " surface=" + surfaceReady +
            " ready=" + frameReady + " failed=" + permanentlyFailed +
            " submitted=" + submittedFrameId + " presented=" + presentedFrameId +
            " presentedFrames=" + presentedFrames + " incomplete=" + incompleteFrames +
            " retries=" + retryFrames + " failedFrames=" + failedFrames +
            " recoveries=" + rendererRecoveries + '/' + rendererRecoveryFailures +
            " renderUs=" + (lastRenderNanos / 1000L) + " maxRenderUs=" +
            (maxRenderNanos / 1000L) + (local == null ? "" : " " + local.diagnostics());
    }

    public void attachTerminalView(@Nullable TerminalView view) {
        terminalView = view;
        if (!isSupported()) notifyFallback();
    }

    public void submitFrame(@Nullable TerminalGpuFrame frame) {
        if (frame == null || !renderActive || !isSupported()) return;
        pendingFrame.set(frame);
        submittedFrameId = frame.frameId;
        scheduleRender();
    }

    /** Own a native swapchain only while this terminal page can contribute visible pixels. */
    public void setRenderActive(boolean active) {
        if (renderActive == active) return;
        renderActive = active;
        frameReady = false;
        consumedCommandGeneration = Long.MIN_VALUE;
        consumedTopRow = Integer.MIN_VALUE;

        if (!active) {
            surfaceGeneration++;
            createPending = false;
            pendingFrame.set(null);
            notifyFrameUnavailable();
            scheduleDestroy(false);
            return;
        }

        if (permanentlyFailed) {
            notifyFallback();
            return;
        }
        activateAvailableSurface();
        TerminalView view = terminalView;
        if (view != null) view.onVulkanSurfaceActivated(this);
    }

    public void releaseRenderResources() {
        setRenderActive(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        detached = false;
        if (renderActive && surfaceReady && !permanentlyFailed) activateAvailableSurface();
    }

    @Override
    protected void onDetachedFromWindow() {
        detached = true;
        frameReady = false;
        pendingFrame.set(null);
        scheduleDestroy(true);
        super.onDetachedFromWindow();
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture,
                                           int width, int height) {
        long generation = ++surfaceGeneration;
        surfaceReady = true;
        frameReady = false;
        consumedCommandGeneration = Long.MIN_VALUE;
        consumedTopRow = Integer.MIN_VALUE;
        surfaceWidth = Math.max(1, width);
        surfaceHeight = Math.max(1, height);
        if (permanentlyFailed) {
            createPending = false;
            notifyFallback();
            return;
        }
        if (renderActive) startNativeSurface(surfaceTexture, width, height, generation);
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture,
                                            int width, int height) {
        surfaceWidth = Math.max(1, width);
        surfaceHeight = Math.max(1, height);
        // A frame can carry the final View dimensions while Vulkan still owns an older Android
        // surface extent. Do not let that image satisfy the atomic first-frame contract.
        frameReady = false;
        consumedCommandGeneration = Long.MIN_VALUE;
        consumedTopRow = Integer.MIN_VALUE;
        TerminalView view = terminalView;
        if (view != null) view.onVulkanSurfaceSizeChanged(this);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
        surfaceGeneration++;
        surfaceReady = false;
        createPending = false;
        frameReady = false;
        consumedCommandGeneration = Long.MIN_VALUE;
        consumedTopRow = Integer.MIN_VALUE;
        notifyFrameUnavailable();
        return destroySurfaceSynchronously(surfaceTexture);
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
    }

    private void ensureRenderThread() {
        if (renderThread != null) return;
        HandlerThread thread = new HandlerThread("TermuxVulkan");
        thread.start();
        renderThread = thread;
        renderHandler = new Handler(thread.getLooper());
    }

    private void activateAvailableSurface() {
        if (!renderActive || detached || !surfaceReady || permanentlyFailed || createPending) return;
        SurfaceTexture surfaceTexture = getSurfaceTexture();
        if (surfaceTexture == null || !isAvailable()) return;
        int width = Math.max(1, getWidth() > 0 ? getWidth() : surfaceWidth);
        int height = Math.max(1, getHeight() > 0 ? getHeight() : surfaceHeight);
        long generation = ++surfaceGeneration;
        startNativeSurface(surfaceTexture, width, height, generation);
    }

    private void startNativeSurface(@NonNull SurfaceTexture surfaceTexture, int width, int height,
                                    long generation) {
        ensureRenderThread();
        Surface surface = new Surface(surfaceTexture);
        createPending = true;
        Handler handler = renderHandler;
        if (handler != null) {
            handler.post(() -> createNativeSurface(surface, width, height, generation));
        } else {
            createPending = false;
            surface.release();
        }
    }

    private void createNativeSurface(@NonNull Surface surface, int width, int height,
                                     long generation) {
        if (generation != surfaceGeneration || detached || !renderActive || !surfaceReady ||
            permanentlyFailed) {
            surface.release();
            if (generation == surfaceGeneration) createPending = false;
            return;
        }

        TerminalVulkanRenderer old = renderer;
        renderer = null;
        if (old != null) old.destroy();
        Surface oldSurface = currentSurface;
        currentSurface = surface;
        if (oldSurface != null && oldSurface != surface) oldSurface.release();

        TerminalVulkanRenderer next = new TerminalVulkanRenderer();
        boolean created = next.create(surface, width, height);
        boolean stillCurrent = generation == surfaceGeneration && !detached && renderActive &&
            surfaceReady && !permanentlyFailed && currentSurface == surface;
        if (!created || !stillCurrent) {
            next.destroy();
            if (currentSurface == surface) {
                currentSurface = null;
                surface.release();
            }
            if (generation == surfaceGeneration) createPending = false;
            if (!created && stillCurrent) {
                permanentlyFailed = true;
                failedFrames++;
                postFailure();
            }
            return;
        }
        createPending = false;
        renderer = next;
        consecutiveRendererRecoveryAttempts = 0;
        scheduleRender();
    }

    private void scheduleRender() {
        Handler handler = renderHandler;
        if (handler == null || !renderActive || !surfaceReady || permanentlyFailed ||
            pendingFrame.get() == null) return;
        if (!renderScheduled.compareAndSet(false, true)) return;
        handler.post(this::renderOne);
    }

    private void renderOne() {
        renderScheduled.set(false);
        if (!renderActive || !surfaceReady || permanentlyFailed) return;
        TerminalGpuFrame frame = pendingFrame.getAndSet(null);
        TerminalVulkanRenderer local = renderer;
        if (frame == null || local == null) {
            if (createPending) scheduleRender();
            return;
        }
        long started = System.nanoTime();
        TerminalVulkanRenderer.RenderResult result = local.render(frame);
        long elapsed = System.nanoTime() - started;
        lastRenderNanos = elapsed;
        if (elapsed > maxRenderNanos) maxRenderNanos = elapsed;
        if (result == TerminalVulkanRenderer.RenderResult.PRESENTED) {
            presentedFrames++;
            consecutiveRendererRecoveryAttempts = 0;
            presentedFrameId = frame.frameId;
            consumedCommandGeneration = frame.commandGeneration;
            consumedTopRow = frame.viewportTopRow;
            presentedViewWidth = frame.viewWidth;
            presentedViewHeight = frame.viewHeight;
            presentedTextSize = frame.textSize;
            presentedFontWidthBits = Float.floatToIntBits(frame.fontWidth);
            presentedFontLineSpacing = frame.fontLineSpacing;
            presentedFontAscent = frame.fontAscent;
            presentedScreenRows = frame.screenRows;
            presentedViewportPixelOffsetBits = Float.floatToIntBits(frame.viewportPixelOffset);
            presentedCommandGeneration = frame.commandGeneration;
            presentedModelRevision = frame.modelRevision;
            frameReady = true;
            postPresented(frame);
        } else if (result == TerminalVulkanRenderer.RenderResult.RETRY) {
            retryFrames++;
            pendingFrame.compareAndSet(null, frame);
            postDelayedRender();
        } else if (result == TerminalVulkanRenderer.RenderResult.INCOMPLETE) {
            incompleteFrames++;
            postNeedsFull(frame);
        } else {
            failedFrames++;
            if (recoverRenderer(local, frame)) {
                maybeLog();
                return;
            }
            if (renderer == local) renderer = null;
            local.destroy();
            pendingFrame.set(null);
            frameReady = false;
            if (!renderActive || !surfaceReady || detached) {
                maybeLog();
                return;
            }
            permanentlyFailed = true;
            postFailure();
        }
        if (pendingFrame.get() != null) scheduleRender();
        maybeLog();
    }

    /** Rebuild once after a fatal native submission and require a fresh complete frame. */
    private boolean recoverRenderer(@NonNull TerminalVulkanRenderer failed,
                                    @NonNull TerminalGpuFrame failedFrame) {
        Surface surface = currentSurface;
        long generation = surfaceGeneration;
        if (consecutiveRendererRecoveryAttempts >= MAX_CONSECUTIVE_RENDERER_RECOVERIES ||
            detached || !surfaceReady || permanentlyFailed || surface == null ||
            !renderActive || !surface.isValid() || renderer != failed) {
            rendererRecoveryFailures++;
            return false;
        }

        consecutiveRendererRecoveryAttempts++;
        frameReady = false;
        consumedCommandGeneration = Long.MIN_VALUE;
        consumedTopRow = Integer.MIN_VALUE;
        pendingFrame.set(null);
        renderer = null;
        failed.destroy();

        TerminalVulkanRenderer next = new TerminalVulkanRenderer();
        if (!next.create(surface, surfaceWidth, surfaceHeight) ||
            generation != surfaceGeneration || detached || !surfaceReady ||
            !renderActive || permanentlyFailed || currentSurface != surface) {
            next.destroy();
            rendererRecoveryFailures++;
            return false;
        }
        renderer = next;
        rendererRecoveries++;
        postNeedsFull(failedFrame);
        return true;
    }

    private void postDelayedRender() {
        Handler handler = renderHandler;
        if (handler == null) return;
        if (!renderScheduled.compareAndSet(false, true)) return;
        handler.postDelayed(this::renderOne, 1L);
    }

    private void postPresented(@NonNull TerminalGpuFrame frame) {
        TerminalView view = terminalView;
        if (view == null) return;
        mainHandler.post(() -> {
            if (terminalView == view && renderActive && surfaceReady) {
                view.onVulkanFramePresented(this, frame);
            }
        });
    }

    private void postNeedsFull(@NonNull TerminalGpuFrame frame) {
        TerminalView view = terminalView;
        if (view == null) return;
        mainHandler.post(() -> {
            if (terminalView == view && renderActive && surfaceReady) {
                view.onVulkanFrameNeedsFull(this, frame);
            }
        });
    }

    private void postFailure() {
        mainHandler.post(this::notifyFallback);
    }

    private void notifyFallback() {
        TerminalView view = terminalView;
        if (view != null) view.onVulkanRendererFailed(this);
    }

    private void notifyFrameUnavailable() {
        TerminalView view = terminalView;
        if (view != null) view.onVulkanFrameUnavailable(this);
    }

    private boolean destroySurfaceSynchronously(@NonNull SurfaceTexture surfaceTexture) {
        Handler handler = renderHandler;
        if (handler == null) {
            Surface surface = currentSurface;
            currentSurface = null;
            if (surface != null) surface.release();
            return true;
        }
        CountDownLatch latch = new CountDownLatch(1);
        destroyScheduled.set(true);
        handler.post(() -> {
            try {
                TerminalVulkanRenderer local = renderer;
                renderer = null;
                if (local != null) local.destroy();
                consecutiveRendererRecoveryAttempts = 0;
                Surface surface = currentSurface;
                currentSurface = null;
                if (surface != null) surface.release();
            } finally {
                destroyScheduled.set(false);
                latch.countDown();
            }
        });
        try {
            if (latch.await(SURFACE_DESTROY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Log.e(LOG_TAG, "Timed out waiting for Vulkan surface teardown");
        // Native owns an independent ANativeWindow reference; the framework can release the
        // SurfaceTexture while the render thread finishes tearing that reference down.
        return true;
    }

    private void scheduleDestroy(boolean quitThread) {
        Handler handler = renderHandler;
        if (handler == null || !destroyScheduled.compareAndSet(false, true)) return;
        handler.post(() -> {
            try {
                TerminalVulkanRenderer local = renderer;
                renderer = null;
                if (local != null) local.destroy();
                consecutiveRendererRecoveryAttempts = 0;
                Surface surface = currentSurface;
                currentSurface = null;
                if (surface != null) surface.release();
                if (quitThread && renderThread != null) {
                    renderThread.quitSafely();
                    renderThread = null;
                    renderHandler = null;
                }
            } finally {
                destroyScheduled.set(false);
            }
        });
    }

    private void maybeLog() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLogMs < 3000L) return;
        lastLogMs = now;
        Log.i(LOG_TAG, "frame=" + presentedFrameId + " " + getDiagnostics());
    }
}
