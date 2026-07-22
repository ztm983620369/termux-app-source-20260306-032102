package com.termux.app.terminal;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.luck.pictureselector.TermuxPictureSelectorLauncher;
import com.termux.app.TermuxService;
import com.termux.sessionsync.SessionEntry;
import com.termux.sessionsync.SessionTransport;
import com.termux.sessionsync.SftpProtocolManager;
import com.termux.terminal.TerminalSession;
import com.termux.terminalsessioncore.CodexImageAttachmentStateMachine;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Service-owned pipeline from an Android gallery selection to a native Codex composer attachment. */
public final class CodexImageAttachmentController {

    public interface UiListener {
        void onCodexImageAttachmentChanged(@NonNull Snapshot snapshot);
    }

    public static final class Snapshot {
        @NonNull public final String operationId;
        @NonNull public final CodexImageAttachmentStateMachine.Phase phase;
        public final int totalImages;
        public final int completedImages;
        public final int selectedImages;
        public final int duplicateImages;
        public final int localReusedImages;
        public final int remoteReusedImages;
        public final long localAvoidedBytes;
        public final long remoteAvoidedBytes;
        public final long uploadedBytes;
        @NonNull public final String message;

        private Snapshot(@NonNull String operationId,
                         @NonNull CodexImageAttachmentStateMachine.Phase phase,
                         int totalImages,
                         int completedImages,
                         int selectedImages,
                         int duplicateImages,
                         int localReusedImages,
                         int remoteReusedImages,
                         long localAvoidedBytes,
                         long remoteAvoidedBytes,
                         long uploadedBytes,
                         @NonNull String message) {
            this.operationId = operationId;
            this.phase = phase;
            this.totalImages = Math.max(0, totalImages);
            this.completedImages = Math.max(0, completedImages);
            this.selectedImages = Math.max(0, selectedImages);
            this.duplicateImages = Math.max(0, duplicateImages);
            this.localReusedImages = Math.max(0, localReusedImages);
            this.remoteReusedImages = Math.max(0, remoteReusedImages);
            this.localAvoidedBytes = Math.max(0L, localAvoidedBytes);
            this.remoteAvoidedBytes = Math.max(0L, remoteAvoidedBytes);
            this.uploadedBytes = Math.max(0L, uploadedBytes);
            this.message = message;
        }

        public boolean isTerminal() {
            return CodexImageAttachmentStateMachine.isTerminal(phase);
        }

        public boolean isSuccessful() {
            return phase == CodexImageAttachmentStateMachine.Phase.COMPLETED;
        }
    }

    public static final class PrepareResult {
        public final boolean success;
        @NonNull public final String operationId;
        @NonNull public final String message;

        private PrepareResult(boolean success, @NonNull String operationId, @NonNull String message) {
            this.success = success;
            this.operationId = operationId;
            this.message = message;
        }

        @NonNull
        static PrepareResult success(@NonNull String operationId) {
            return new PrepareResult(true, operationId, "");
        }

        @NonNull
        static PrepareResult failure(@NonNull String message) {
            return new PrepareResult(false, "", message);
        }
    }

    private static final long MIN_VISIBLE_PROCESSING_SURFACE_MS = 450L;
    private static final long MAX_PROCESSING_SURFACE_WAIT_MS = 2_000L;
    private static final long BETWEEN_ATTACHMENTS_MS = 80L;
    private static final int MAX_ERROR_TEXT = 240;

    @NonNull private final TermuxService mService;
    @NonNull private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    @NonNull private final ExecutorService mExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "codex-image-attachment");
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    });
    @NonNull private final Object mLock = new Object();

    @Nullable private Operation mOperation;
    @Nullable private UiListener mUiListener;
    private boolean mStopped;

    public CodexImageAttachmentController(@NonNull TermuxService service) {
        mService = service;
        CodexImageAttachmentStore.initialize();
    }

    @NonNull
    public PrepareResult prepareSelection(@Nullable String targetHandle,
                                          @Nullable String displayName,
                                          @Nullable String sshCommand,
                                          @Nullable String tmuxSession) {
        String handle = normalize(targetHandle);
        if (handle.isEmpty()) return PrepareResult.failure("当前终端缺少稳定会话标识。");
        TerminalSession session = mService.getTerminalSessionForHandle(handle);
        if (session == null || !session.isRunning()) {
            return PrepareResult.failure("当前终端已经结束，无法添加图片。");
        }

        synchronized (mLock) {
            if (mStopped) return PrepareResult.failure("图片处理服务正在停止。");
            if (mOperation != null && !CodexImageAttachmentStateMachine.isTerminal(mOperation.phase)) {
                return PrepareResult.failure("已有图片正在处理，请等待当前操作完成。");
            }

            String normalizedSshCommand = normalize(sshCommand);
            CodexImageAttachmentStateMachine.Route route =
                CodexImageAttachmentStateMachine.resolveRoute(!normalizedSshCommand.isEmpty());
            SessionTransport transport = route == CodexImageAttachmentStateMachine.Route.REMOTE_SFTP
                ? (normalize(tmuxSession).isEmpty() ? SessionTransport.SSH : SessionTransport.SSH_PERSIST)
                : SessionTransport.LOCAL;
            SessionEntry targetEntry = new SessionEntry.Builder(
                handle,
                normalize(displayName).isEmpty() ? "terminal" : normalize(displayName))
                .setTransport(transport)
                .setTerminalHandle(handle)
                .setSshCommand(normalizedSshCommand)
                .setTmuxSession(tmuxSession)
                .setActive(true)
                .setRunning(true)
                .setUpdatedAtMs(System.currentTimeMillis())
                .build();

            TermuxSessionRestoreStore.CodexLease lease =
                TermuxSessionRestoreStore.findCodexLeaseByHandle(handle);
            String operationId = UUID.randomUUID().toString();
            mOperation = new Operation(
                operationId,
                handle,
                lease == null ? "" : lease.threadId,
                targetEntry,
                route,
                SystemClock.elapsedRealtime());
            persistAndAuditLocked(mOperation, "selection_prepared", "");
            dispatchSnapshotLocked(mOperation);
            return PrepareResult.success(operationId);
        }
    }

    public void submitSelection(@NonNull String operationId,
                                @Nullable List<TermuxPictureSelectorLauncher.SelectedImage> images) {
        final Operation operation;
        final ArrayList<TermuxPictureSelectorLauncher.SelectedImage> selected = new ArrayList<>();
        if (images != null) {
            for (TermuxPictureSelectorLauncher.SelectedImage image : images) {
                if (image != null) selected.add(image);
            }
        }

        synchronized (mLock) {
            operation = currentOperationLocked(operationId);
            if (operation == null || operation.phase != CodexImageAttachmentStateMachine.Phase.AWAITING_SELECTION) {
                auditLateCallback(operationId, "selection_result_ignored");
                return;
            }
            if (selected.isEmpty()) {
                finishLocked(operation, CodexImageAttachmentStateMachine.Phase.CANCELLED,
                    "未选择图片。", "selection_empty");
                return;
            }
            operation.selectedImages = selected.size();
            operation.totalImages = selected.size();
            transitionLocked(operation, CodexImageAttachmentStateMachine.Phase.MATERIALIZING,
                "正在处理图片", "selection_confirmed");
        }

        try {
            mExecutor.execute(() -> processSelection(operation, selected));
        } catch (RejectedExecutionException e) {
            synchronized (mLock) {
                if (isCurrentLocked(operation) && !CodexImageAttachmentStateMachine.isTerminal(operation.phase)) {
                    finishLocked(operation, CodexImageAttachmentStateMachine.Phase.INTERRUPTED,
                        "图片处理服务已停止。", "executor_rejected");
                }
            }
        }
    }

    public void cancelSelection(@NonNull String operationId) {
        synchronized (mLock) {
            Operation operation = currentOperationLocked(operationId);
            if (operation == null || CodexImageAttachmentStateMachine.isTerminal(operation.phase)) return;
            operation.cancelled.set(true);
            finishLocked(operation, CodexImageAttachmentStateMachine.Phase.CANCELLED,
                "已取消图片操作。", "selection_cancelled");
        }
    }

    public void attachUiListener(@NonNull UiListener listener) {
        Snapshot snapshot = null;
        synchronized (mLock) {
            mUiListener = listener;
            if (mOperation != null) snapshot = snapshotOf(mOperation);
        }
        if (snapshot != null) dispatch(listener, snapshot);
    }

    public void detachUiListener(@NonNull UiListener listener) {
        synchronized (mLock) {
            if (mUiListener == listener) mUiListener = null;
        }
    }

    public void acknowledgeProcessingSurface(@NonNull String operationId) {
        synchronized (mLock) {
            Operation operation = currentOperationLocked(operationId);
            if (operation == null || operation.phase == CodexImageAttachmentStateMachine.Phase.AWAITING_SELECTION ||
                CodexImageAttachmentStateMachine.isTerminal(operation.phase)) return;
            if (operation.processingSurfaceVisibleAtElapsedMs <= 0L) {
                operation.processingSurfaceVisibleAtElapsedMs = SystemClock.elapsedRealtime();
                CodexImageAttachmentStore.appendEvent(
                    operation.operationId,
                    "processing_surface_visible",
                    operation.phase.name(),
                    operation.route.name(),
                    operation.totalImages,
                    "");
            }
        }
    }

    public void stop() {
        synchronized (mLock) {
            mStopped = true;
            if (mOperation != null && !CodexImageAttachmentStateMachine.isTerminal(mOperation.phase)) {
                mOperation.cancelled.set(true);
                finishLocked(mOperation, CodexImageAttachmentStateMachine.Phase.INTERRUPTED,
                    "Termux 服务停止，图片操作已中断。", "service_stopped");
            }
            mUiListener = null;
        }
        mExecutor.shutdownNow();
        mMainHandler.removeCallbacksAndMessages(this);
    }

    private void processSelection(@NonNull Operation operation,
                                  @NonNull List<TermuxPictureSelectorLauncher.SelectedImage> selected) {
        try {
            ArrayList<CodexImageAttachmentStore.MaterializedImage> selectedObjects = new ArrayList<>();
            int processedSelections = 0;
            for (TermuxPictureSelectorLauncher.SelectedImage image : selected) {
                ensureActive(operation);
                CodexImageAttachmentStore.MaterializedImage materialized =
                    CodexImageAttachmentStore.materialize(mService, image, operation.cancelled::get);
                selectedObjects.add(materialized);
                processedSelections++;
                updateProgress(operation, processedSelections, "正在处理图片");
            }
            CodexImageAttachmentStore.DeduplicatedBatch batch =
                CodexImageAttachmentStore.deduplicateByContent(selectedObjects);
            if (batch.uniqueImages.isEmpty()) throw new IllegalStateException("没有可用图片");

            ArrayList<CodexImageAttachmentStore.MaterializedImage> materialized =
                new ArrayList<>(batch.uniqueImages);
            synchronized (mLock) {
                ensureCurrentLocked(operation);
                operation.totalImages = materialized.size();
                operation.completedImages = 0;
                operation.duplicateImages = batch.duplicateCount;
                operation.localReusedImages = batch.reusedUniqueCount;
                operation.localAvoidedBytes = saturatedAdd(batch.reusedUniqueBytes, batch.duplicateBytes);
                if (operation.route == CodexImageAttachmentStateMachine.Route.REMOTE_SFTP) {
                    operation.remoteAvoidedBytes = batch.duplicateBytes;
                }
                persistAndAuditLocked(operation, "batch_content_plan_created",
                    "selected=" + operation.selectedImages +
                        " unique=" + operation.totalImages +
                        " exact_duplicates=" + operation.duplicateImages +
                        " local_reused=" + operation.localReusedImages +
                        " duplicate_bytes=" + batch.duplicateBytes);
                dispatchSnapshotLocked(operation);
            }
            ArrayList<String> injectablePaths = new ArrayList<>(materialized.size());

            if (operation.route == CodexImageAttachmentStateMachine.Route.REMOTE_SFTP) {
                synchronized (mLock) {
                    ensureCurrentLocked(operation);
                    transitionLocked(operation, CodexImageAttachmentStateMachine.Phase.TRANSFERRING,
                        "正在上传到远端", "remote_transfer_started");
                }
                for (CodexImageAttachmentStore.MaterializedImage image : materialized) {
                    ensureActive(operation);
                    SftpProtocolManager.CodexAttachmentUploadResult result =
                        SftpProtocolManager.getInstance().uploadCodexAttachment(
                            mService,
                            operation.targetEntry,
                            operation.operationId,
                            image.file.getAbsolutePath(),
                            image.sha256,
                            image.extension,
                            operation.cancelled::get);
                    if (!result.success) {
                        if (result.cancelled) throw new InterruptedIOException("Remote upload cancelled");
                        throw new IllegalStateException(result.messageCn);
                    }
                    injectablePaths.add(result.remotePath);
                    synchronized (mLock) {
                        ensureCurrentLocked(operation);
                        if (result.reused) {
                            operation.remoteReusedImages++;
                            operation.remoteAvoidedBytes = saturatedAdd(
                                operation.remoteAvoidedBytes, image.size);
                        } else {
                            operation.uploadedBytes = saturatedAdd(
                                operation.uploadedBytes, result.uploadedBytes);
                        }
                        persistAndAuditLocked(operation,
                            result.reused ? "remote_object_reused" : "remote_object_uploaded",
                            "bytes=" + image.size + " uploaded_bytes=" + result.uploadedBytes);
                    }
                    updateProgress(operation, injectablePaths.size(), "正在上传到远端");
                }
            } else {
                for (CodexImageAttachmentStore.MaterializedImage image : materialized) {
                    injectablePaths.add(image.file.getAbsolutePath());
                }
            }

            synchronized (mLock) {
                ensureCurrentLocked(operation);
                operation.injectablePaths.clear();
                operation.injectablePaths.addAll(injectablePaths);
                operation.completedImages = injectablePaths.size();
                transitionLocked(operation, CodexImageAttachmentStateMachine.Phase.READY_TO_INJECT,
                    "正在添加到 Codex", "attachments_ready");
            }
            operation.readyToInjectElapsedMs = SystemClock.elapsedRealtime();
            awaitProcessingSurface(operation);
        } catch (InterruptedIOException e) {
            synchronized (mLock) {
                if (isCurrentLocked(operation) && !CodexImageAttachmentStateMachine.isTerminal(operation.phase)) {
                    finishLocked(operation, CodexImageAttachmentStateMachine.Phase.CANCELLED,
                        "图片操作已取消。", "operation_cancelled");
                }
            }
        } catch (Throwable error) {
            fail(operation, userFacingError(error));
        }
    }

    private void awaitProcessingSurface(@NonNull Operation operation) {
        final long delay;
        synchronized (mLock) {
            if (!isCurrentLocked(operation) || operation.cancelled.get() ||
                operation.phase != CodexImageAttachmentStateMachine.Phase.READY_TO_INJECT) return;
            long now = SystemClock.elapsedRealtime();
            if (operation.processingSurfaceVisibleAtElapsedMs > 0L) {
                long visibleFor = now - operation.processingSurfaceVisibleAtElapsedMs;
                delay = Math.max(0L, MIN_VISIBLE_PROCESSING_SURFACE_MS - visibleFor);
            } else if (now - operation.readyToInjectElapsedMs < MAX_PROCESSING_SURFACE_WAIT_MS) {
                delay = 100L;
            } else {
                delay = 0L;
            }
        }
        if (delay <= 0L) {
            mMainHandler.postAtTime(() -> beginInjection(operation), this, SystemClock.uptimeMillis());
        } else {
            mMainHandler.postAtTime(
                () -> awaitProcessingSurface(operation),
                this,
                SystemClock.uptimeMillis() + delay);
        }
    }

    private void beginInjection(@NonNull Operation operation) {
        final TerminalSession session;
        synchronized (mLock) {
            if (!isCurrentLocked(operation) || operation.cancelled.get() ||
                operation.phase != CodexImageAttachmentStateMachine.Phase.READY_TO_INJECT) return;
            session = resolveTargetSessionLocked(operation);
            boolean identityMatches = targetIdentityMatchesLocked(operation, session);
            if (!CodexImageAttachmentStateMachine.canInject(
                session != null,
                session != null && session.isRunning(),
                identityMatches,
                operation.injectablePaths.size()) || session == null || session.getEmulator() == null) {
                finishLocked(operation, CodexImageAttachmentStateMachine.Phase.FAILED,
                    "原终端会话已不可用，图片未发送到其他 tab。", "target_unavailable");
                return;
            }
            transitionLocked(operation, CodexImageAttachmentStateMachine.Phase.INJECTING,
                "正在添加到 Codex", "injection_started");
        }
        injectNext(operation, session, 0);
    }

    private void injectNext(@NonNull Operation operation,
                            @NonNull TerminalSession expectedSession,
                            int index) {
        synchronized (mLock) {
            if (!isCurrentLocked(operation) || operation.cancelled.get() ||
                operation.phase != CodexImageAttachmentStateMachine.Phase.INJECTING) return;
            TerminalSession current = resolveTargetSessionLocked(operation);
            if (current != expectedSession || !targetIdentityMatchesLocked(operation, current) ||
                !current.isRunning() || current.getEmulator() == null) {
                finishLocked(operation, CodexImageAttachmentStateMachine.Phase.FAILED,
                    "原终端会话在发送前发生变化，已停止以避免误发。", "target_changed");
                return;
            }
            if (index >= operation.injectablePaths.size()) {
                finishLocked(operation, CodexImageAttachmentStateMachine.Phase.COMPLETED,
                    completionMessage(operation), "injection_completed");
                return;
            }
            String path = operation.injectablePaths.get(index);
            if (!isSafeAbsolutePath(path)) {
                finishLocked(operation, CodexImageAttachmentStateMachine.Phase.FAILED,
                    "附件路径校验失败。", "unsafe_injection_path");
                return;
            }
            current.getEmulator().paste(path);
        }
        mMainHandler.postAtTime(
            () -> injectNext(operation, expectedSession, index + 1),
            this,
            SystemClock.uptimeMillis() + BETWEEN_ATTACHMENTS_MS);
    }

    private void updateProgress(@NonNull Operation operation, int completed, @NonNull String message) {
        synchronized (mLock) {
            ensureCurrentLocked(operation);
            operation.completedImages = Math.max(0, completed);
            operation.message = message;
            persistAndAuditLocked(operation, "progress", "completed=" + completed);
            dispatchSnapshotLocked(operation);
        }
    }

    private void fail(@NonNull Operation operation, @NonNull String message) {
        synchronized (mLock) {
            if (!isCurrentLocked(operation) || CodexImageAttachmentStateMachine.isTerminal(operation.phase)) return;
            finishLocked(operation, CodexImageAttachmentStateMachine.Phase.FAILED,
                message, "operation_failed");
        }
    }

    private void transitionLocked(@NonNull Operation operation,
                                  @NonNull CodexImageAttachmentStateMachine.Phase next,
                                  @NonNull String message,
                                  @NonNull String event) {
        ensureCurrentLocked(operation);
        if (!CodexImageAttachmentStateMachine.canTransition(operation.phase, next)) {
            throw new IllegalStateException("Illegal image attachment transition " + operation.phase + " -> " + next);
        }
        operation.phase = next;
        operation.message = message;
        persistAndAuditLocked(operation, event, "");
        dispatchSnapshotLocked(operation);
    }

    private void finishLocked(@NonNull Operation operation,
                              @NonNull CodexImageAttachmentStateMachine.Phase terminal,
                              @NonNull String message,
                              @NonNull String event) {
        if (!isCurrentLocked(operation) || CodexImageAttachmentStateMachine.isTerminal(operation.phase)) return;
        if (!CodexImageAttachmentStateMachine.canTransition(operation.phase, terminal)) {
            throw new IllegalStateException("Illegal terminal image attachment transition " +
                operation.phase + " -> " + terminal);
        }
        operation.phase = terminal;
        operation.message = message;
        persistAndAuditLocked(operation, event, message);
        dispatchSnapshotLocked(operation);
    }

    private void persistAndAuditLocked(@NonNull Operation operation,
                                       @NonNull String event,
                                       @Nullable String detail) {
        boolean terminal = CodexImageAttachmentStateMachine.isTerminal(operation.phase);
        CodexImageAttachmentStore.persistOperation(
            operation.operationId,
            operation.phase.name(),
            operation.route.name(),
            operation.targetHandle,
            operation.codexThreadId,
            operation.totalImages,
            operation.selectedImages,
            operation.duplicateImages,
            operation.localReusedImages,
            operation.remoteReusedImages,
            operation.localAvoidedBytes,
            operation.remoteAvoidedBytes,
            operation.uploadedBytes,
            operation.message,
            terminal);
        CodexImageAttachmentStore.appendEvent(
            operation.operationId,
            event,
            operation.phase.name(),
            operation.route.name(),
            operation.totalImages,
            detail);
    }

    private void dispatchSnapshotLocked(@NonNull Operation operation) {
        UiListener listener = mUiListener;
        if (listener != null) dispatch(listener, snapshotOf(operation));
    }

    private void dispatch(@NonNull UiListener listener, @NonNull Snapshot snapshot) {
        mMainHandler.post(() -> {
            synchronized (mLock) {
                if (mUiListener != listener) return;
            }
            listener.onCodexImageAttachmentChanged(snapshot);
        });
    }

    @Nullable
    private TerminalSession resolveTargetSessionLocked(@NonNull Operation operation) {
        if (!operation.codexThreadId.isEmpty()) {
            TermuxSessionRestoreStore.CodexLease lease =
                TermuxSessionRestoreStore.findCodexLeaseByThread(operation.codexThreadId);
            if (lease == null || TextUtils.isEmpty(lease.handle)) return null;
            return mService.getTerminalSessionForHandle(lease.handle);
        }
        return mService.getTerminalSessionForHandle(operation.targetHandle);
    }

    private boolean targetIdentityMatchesLocked(@NonNull Operation operation,
                                                @Nullable TerminalSession session) {
        if (session == null || TextUtils.isEmpty(session.mHandle)) return false;
        if (operation.codexThreadId.isEmpty()) {
            return TextUtils.equals(operation.targetHandle, session.mHandle);
        }
        TermuxSessionRestoreStore.CodexLease lease =
            TermuxSessionRestoreStore.findCodexLeaseByHandle(session.mHandle);
        return lease != null && TextUtils.equals(operation.codexThreadId, lease.threadId);
    }

    private void ensureActive(@NonNull Operation operation) throws InterruptedIOException {
        synchronized (mLock) {
            if (!isCurrentLocked(operation) || operation.cancelled.get() ||
                CodexImageAttachmentStateMachine.isTerminal(operation.phase) || mStopped) {
                throw new InterruptedIOException("Image operation is no longer active");
            }
        }
    }

    private void ensureCurrentLocked(@NonNull Operation operation) {
        if (!isCurrentLocked(operation)) throw new IllegalStateException("Stale image attachment callback");
    }

    private boolean isCurrentLocked(@NonNull Operation operation) {
        return mOperation == operation && TextUtils.equals(mOperation.operationId, operation.operationId);
    }

    @Nullable
    private Operation currentOperationLocked(@Nullable String operationId) {
        return mOperation != null && TextUtils.equals(mOperation.operationId, normalize(operationId))
            ? mOperation : null;
    }

    private void auditLateCallback(@Nullable String operationId, @NonNull String event) {
        CodexImageAttachmentStore.appendEvent(
            normalize(operationId), event, "STALE", "UNKNOWN", 0, "late callback ignored");
    }

    @NonNull
    private static Snapshot snapshotOf(@NonNull Operation operation) {
        return new Snapshot(
            operation.operationId,
            operation.phase,
            operation.totalImages,
            operation.completedImages,
            operation.selectedImages,
            operation.duplicateImages,
            operation.localReusedImages,
            operation.remoteReusedImages,
            operation.localAvoidedBytes,
            operation.remoteAvoidedBytes,
            operation.uploadedBytes,
            operation.message);
    }

    private static boolean isSafeAbsolutePath(@Nullable String path) {
        if (TextUtils.isEmpty(path) || !path.startsWith("/") || path.length() > 4096) return false;
        for (int i = 0; i < path.length(); i++) {
            char ch = path.charAt(i);
            if (ch < 0x20 || ch == 0x7f) return false;
        }
        return true;
    }

    @NonNull
    private static String userFacingError(@Nullable Throwable error) {
        if (error == null) return "图片处理失败。";
        String raw = normalize(error.getMessage());
        if (raw.isEmpty()) raw = error.getClass().getSimpleName();
        if (raw.toLowerCase(Locale.ROOT).contains("1 gib")) {
            return "图片超过 Codex 的 1 GiB 输入上限。";
        }
        if (raw.length() > MAX_ERROR_TEXT) raw = raw.substring(0, MAX_ERROR_TEXT);
        return "图片处理失败：" + raw;
    }

    @NonNull
    private static String completionMessage(@NonNull Operation operation) {
        if (operation.duplicateImages <= 0 && operation.localReusedImages <= 0 &&
            operation.remoteReusedImages <= 0) {
            return "图片已添加到 Codex。";
        }
        return String.format(Locale.ROOT,
            "图片已添加到 Codex（批内去重 %d，本地复用 %d，远端复用 %d）。",
            operation.duplicateImages, operation.localReusedImages, operation.remoteReusedImages);
    }

    private static long saturatedAdd(long first, long second) {
        long safeSecond = Math.max(0L, second);
        if (first >= Long.MAX_VALUE - safeSecond) return Long.MAX_VALUE;
        return first + safeSecond;
    }

    @NonNull
    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Operation {
        @NonNull final String operationId;
        @NonNull final String targetHandle;
        @NonNull final String codexThreadId;
        @NonNull final SessionEntry targetEntry;
        @NonNull final CodexImageAttachmentStateMachine.Route route;
        final long startedElapsedMs;
        @NonNull final AtomicBoolean cancelled = new AtomicBoolean(false);
        @NonNull final ArrayList<String> injectablePaths = new ArrayList<>();
        @NonNull CodexImageAttachmentStateMachine.Phase phase =
            CodexImageAttachmentStateMachine.Phase.AWAITING_SELECTION;
        int totalImages;
        int completedImages;
        int selectedImages;
        int duplicateImages;
        int localReusedImages;
        int remoteReusedImages;
        long localAvoidedBytes;
        long remoteAvoidedBytes;
        long uploadedBytes;
        @NonNull String message = "等待选择图片";
        long processingSurfaceVisibleAtElapsedMs;
        long readyToInjectElapsedMs;

        Operation(@NonNull String operationId,
                  @NonNull String targetHandle,
                  @NonNull String codexThreadId,
                  @NonNull SessionEntry targetEntry,
                  @NonNull CodexImageAttachmentStateMachine.Route route,
                  long startedElapsedMs) {
            this.operationId = operationId;
            this.targetHandle = targetHandle;
            this.codexThreadId = codexThreadId;
            this.targetEntry = targetEntry;
            this.route = route;
            this.startedElapsedMs = startedElapsedMs;
        }
    }
}
