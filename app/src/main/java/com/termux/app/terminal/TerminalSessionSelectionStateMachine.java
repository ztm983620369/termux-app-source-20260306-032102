package com.termux.app.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class TerminalSessionSelectionStateMachine {

    public enum SurfaceMode {
        SESSION,
        CONFIG
    }

    public enum PreviewMode {
        NONE,
        SESSION,
        CONFIG
    }

    public enum PendingKind {
        NONE,
        SESSION,
        CONFIG
    }

    public static final class Snapshot {
        @NonNull public final ArrayList<String> sessionHandles;
        @Nullable public final String selectedSessionHandle;
        @Nullable public final String committedSessionHandle;
        @Nullable public final String currentSessionHandle;
        @Nullable public final String topBarSelectedSessionHandle;
        @NonNull public final SurfaceMode surfaceMode;
        @NonNull public final PreviewMode previewMode;
        @NonNull public final PendingKind pendingKind;
        public final boolean configSelected;
        public final boolean topBarConfigSelected;
        public final long pendingToken;
        @Nullable public final String pendingSessionHandle;

        Snapshot(@NonNull ArrayList<String> sessionHandles,
                 @Nullable String selectedSessionHandle,
                 @Nullable String committedSessionHandle,
                 @Nullable String currentSessionHandle,
                 @Nullable String topBarSelectedSessionHandle,
                 @NonNull SurfaceMode surfaceMode,
                 @NonNull PreviewMode previewMode,
                 @NonNull PendingKind pendingKind,
                 boolean configSelected,
                 boolean topBarConfigSelected,
                 long pendingToken,
                 @Nullable String pendingSessionHandle) {
            this.sessionHandles = sessionHandles;
            this.selectedSessionHandle = selectedSessionHandle;
            this.committedSessionHandle = committedSessionHandle;
            this.currentSessionHandle = currentSessionHandle;
            this.topBarSelectedSessionHandle = topBarSelectedSessionHandle;
            this.surfaceMode = surfaceMode;
            this.previewMode = previewMode;
            this.pendingKind = pendingKind;
            this.configSelected = configSelected;
            this.topBarConfigSelected = topBarConfigSelected;
            this.pendingToken = pendingToken;
            this.pendingSessionHandle = pendingSessionHandle;
        }
    }

    @NonNull private final ArrayList<String> mSessionHandles = new ArrayList<>();
    @NonNull private SurfaceMode mSurfaceMode = SurfaceMode.SESSION;
    @NonNull private PreviewMode mPreviewMode = PreviewMode.NONE;
    @Nullable private String mPreviewSessionHandle;
    @Nullable private String mSelectedSessionHandle;
    @Nullable private String mCommittedSessionHandle;
    @NonNull private PendingKind mPendingKind = PendingKind.NONE;
    @Nullable private String mPendingSessionHandle;
    private long mPendingToken = 0L;
    private long mNextToken = 1L;

    public synchronized void restore(@Nullable String selectedSessionHandle, boolean configSelected) {
        String normalizedSelected = normalizeHandle(selectedSessionHandle);
        if (!normalizedSelected.isEmpty()) {
            mSelectedSessionHandle = normalizedSelected;
            if (isEmpty(mCommittedSessionHandle)) {
                mCommittedSessionHandle = normalizedSelected;
            }
        }
        mSurfaceMode = configSelected ? SurfaceMode.CONFIG : SurfaceMode.SESSION;
        clearPreviewInternal();
        clearPendingInternal();
    }

    public synchronized void syncSessions(@NonNull List<String> sessionHandles,
                                          @Nullable String preferredSessionHandle) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String handle : sessionHandles) {
            String value = normalizeHandle(handle);
            if (!value.isEmpty()) normalized.add(value);
        }

        mSessionHandles.clear();
        mSessionHandles.addAll(normalized);

        String preferred = normalizeHandle(preferredSessionHandle);
        String first = mSessionHandles.isEmpty() ? null : mSessionHandles.get(0);

        if (!isHandleAvailable(mSelectedSessionHandle)) {
            if (isHandleAvailable(preferred)) {
                mSelectedSessionHandle = preferred;
            } else if (isHandleAvailable(mCommittedSessionHandle)) {
                mSelectedSessionHandle = mCommittedSessionHandle;
            } else {
                mSelectedSessionHandle = first;
            }
        }

        if (!isHandleAvailable(mCommittedSessionHandle)) {
            if (isHandleAvailable(mSelectedSessionHandle)) {
                mCommittedSessionHandle = mSelectedSessionHandle;
            } else {
                mCommittedSessionHandle = first;
            }
        }

        if (mPendingKind == PendingKind.SESSION && !isEmpty(mPendingSessionHandle) &&
            !isHandleAvailable(mPendingSessionHandle)) {
            clearPendingInternal();
        }

        if (mPreviewMode == PreviewMode.SESSION && !isHandleAvailable(mPreviewSessionHandle)) {
            clearPreviewInternal();
        }

        if (mSelectedSessionHandle == null && first != null) {
            mSelectedSessionHandle = first;
        }
        if (mCommittedSessionHandle == null && first != null) {
            mCommittedSessionHandle = first;
        }
    }

    public synchronized void bootstrapSessionSelection(@Nullable String sessionHandle) {
        String normalizedHandle = normalizeHandle(sessionHandle);
        if (normalizedHandle.isEmpty()) return;
        mSelectedSessionHandle = normalizedHandle;
        if (isEmpty(mCommittedSessionHandle)) {
            mCommittedSessionHandle = normalizedHandle;
        }
        clearPreviewInternal();
    }

    public synchronized long requestSessionSelection(@Nullable String sessionHandle) {
        String normalizedHandle = normalizeHandle(sessionHandle);
        if (normalizedHandle.isEmpty()) return 0L;

        mSurfaceMode = SurfaceMode.SESSION;
        mSelectedSessionHandle = normalizedHandle;
        mPendingKind = PendingKind.SESSION;
        mPendingSessionHandle = normalizedHandle;
        mPendingToken = mNextToken++;
        clearPreviewInternal();
        return mPendingToken;
    }

    public synchronized long requestConfigSelection() {
        mSurfaceMode = SurfaceMode.CONFIG;
        mPendingKind = PendingKind.CONFIG;
        mPendingSessionHandle = null;
        mPendingToken = mNextToken++;
        clearPreviewInternal();
        return mPendingToken;
    }

    public synchronized long requestReturnToSessionSelection() {
        String handle = resolveCurrentSessionHandle();
        if (handle.isEmpty()) {
            mSurfaceMode = SurfaceMode.SESSION;
            clearPendingInternal();
            clearPreviewInternal();
            return 0L;
        }
        return requestSessionSelection(handle);
    }

    public synchronized void previewSession(@Nullable String sessionHandle) {
        String normalizedHandle = normalizeHandle(sessionHandle);
        if (normalizedHandle.isEmpty()) {
            clearPreviewInternal();
            return;
        }
        mPreviewMode = PreviewMode.SESSION;
        mPreviewSessionHandle = normalizedHandle;
    }

    public synchronized void previewConfig() {
        mPreviewMode = PreviewMode.CONFIG;
        mPreviewSessionHandle = null;
    }

    public synchronized void clearPreview() {
        clearPreviewInternal();
    }

    public synchronized boolean commitSessionSelection(@Nullable String sessionHandle,
                                                       long token,
                                                       boolean fromUser) {
        String normalizedHandle = normalizeHandle(sessionHandle);
        if (normalizedHandle.isEmpty()) return false;

        if (fromUser) {
            applyCommittedSessionInternal(normalizedHandle);
            return true;
        }

        if (mPendingKind == PendingKind.SESSION) {
            if (token != 0L && token == mPendingToken &&
                normalizedHandle.equals(normalizeHandle(mPendingSessionHandle))) {
                applyCommittedSessionInternal(normalizedHandle);
                return true;
            }

            if (token == 0L && normalizedHandle.equals(normalizeHandle(mPendingSessionHandle))) {
                applyCommittedSessionInternal(normalizedHandle);
                return true;
            }

            return false;
        }

        if (mPendingKind == PendingKind.CONFIG) {
            return false;
        }

        String currentHandle = resolveCurrentSessionHandle();
        if (isEmpty(mSelectedSessionHandle) ||
            normalizedHandle.equals(normalizeHandle(mSelectedSessionHandle)) ||
            normalizedHandle.equals(currentHandle)) {
            applyCommittedSessionInternal(normalizedHandle);
            return true;
        }

        return false;
    }

    public synchronized boolean commitConfigSelection(long token, boolean fromUser) {
        if (fromUser) {
            mSurfaceMode = SurfaceMode.CONFIG;
            clearPendingInternal();
            clearPreviewInternal();
            return true;
        }

        if (mPendingKind == PendingKind.CONFIG && token != 0L && token == mPendingToken) {
            mSurfaceMode = SurfaceMode.CONFIG;
            clearPendingInternal();
            clearPreviewInternal();
            return true;
        }

        if (mPendingKind == PendingKind.NONE && mSurfaceMode == SurfaceMode.CONFIG) {
            clearPreviewInternal();
            return true;
        }

        return false;
    }

    @NonNull
    public synchronized Snapshot snapshot() {
        String currentHandle = resolveCurrentSessionHandle();
        boolean topBarConfigSelected = mPreviewMode == PreviewMode.CONFIG || mSurfaceMode == SurfaceMode.CONFIG;
        String topBarSelectedHandle = topBarConfigSelected
            ? null
            : (mPreviewMode == PreviewMode.SESSION ? normalizeHandle(mPreviewSessionHandle) : currentHandle);

        return new Snapshot(
            new ArrayList<>(mSessionHandles),
            nullIfEmpty(mSelectedSessionHandle),
            nullIfEmpty(mCommittedSessionHandle),
            nullIfEmpty(currentHandle),
            nullIfEmpty(topBarSelectedHandle),
            mSurfaceMode,
            mPreviewMode,
            mPendingKind,
            mSurfaceMode == SurfaceMode.CONFIG,
            topBarConfigSelected,
            mPendingToken,
            nullIfEmpty(mPendingSessionHandle)
        );
    }

    @Nullable
    public synchronized String getCurrentSessionHandle() {
        return nullIfEmpty(resolveCurrentSessionHandle());
    }

    private void applyCommittedSessionInternal(@NonNull String sessionHandle) {
        mSurfaceMode = SurfaceMode.SESSION;
        mSelectedSessionHandle = sessionHandle;
        mCommittedSessionHandle = sessionHandle;
        clearPendingInternal();
        clearPreviewInternal();
    }

    @NonNull
    private String resolveCurrentSessionHandle() {
        String pendingHandle = normalizeHandle(mPendingSessionHandle);
        if (mPendingKind == PendingKind.SESSION && !pendingHandle.isEmpty()) {
            return pendingHandle;
        }

        String selectedHandle = normalizeHandle(mSelectedSessionHandle);
        if (!selectedHandle.isEmpty()) {
            return selectedHandle;
        }

        String committedHandle = normalizeHandle(mCommittedSessionHandle);
        if (!committedHandle.isEmpty()) {
            return committedHandle;
        }

        return mSessionHandles.isEmpty() ? "" : mSessionHandles.get(0);
    }

    private boolean isHandleAvailable(@Nullable String sessionHandle) {
        String normalized = normalizeHandle(sessionHandle);
        return !normalized.isEmpty() && mSessionHandles.contains(normalized);
    }

    private void clearPendingInternal() {
        mPendingKind = PendingKind.NONE;
        mPendingSessionHandle = null;
        mPendingToken = 0L;
    }

    private void clearPreviewInternal() {
        mPreviewMode = PreviewMode.NONE;
        mPreviewSessionHandle = null;
    }

    @Nullable
    private static String nullIfEmpty(@Nullable String value) {
        String normalized = normalizeHandle(value);
        return normalized.isEmpty() ? null : normalized;
    }

    private static boolean isEmpty(@Nullable String value) {
        return normalizeHandle(value).isEmpty();
    }

    @NonNull
    private static String normalizeHandle(@Nullable String value) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.isEmpty() ? "" : normalized;
    }
}
