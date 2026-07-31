package com.termux.view;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Separates observed Android layout from the terminal grid committed to the PTY.
 *
 * IME animation is a visual viewport concern. It must not turn any IME layout height into a
 * TIOCSWINSZ/Ghostty reflow transaction. Only explicit structural layout changes are committed
 * after two matching frames while the IME is visible.
 */
final class TerminalGeometryCommitPolicy {

    enum Source {
        INITIAL_ATTACH,
        LAYOUT,
        RENDER_BARRIER,
        STRUCTURAL,
        USER_TEXT_SCALE
    }

    enum Decision {
        COMMIT,
        WAIT_FOR_STABLE_FRAME,
        SUPPRESSED_BY_IME,
        UNCHANGED
    }

    static final class Geometry {
        final int columns;
        final int rows;
        final int cellWidth;
        final int cellHeight;

        Geometry(int columns, int rows, int cellWidth, int cellHeight) {
            this.columns = columns;
            this.rows = rows;
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
        }

        boolean isValid() {
            return columns >= 2 && rows >= 2 && cellWidth > 0 && cellHeight > 0;
        }

        boolean sameAs(@Nullable Geometry other) {
            return other != null && columns == other.columns && rows == other.rows &&
                cellWidth == other.cellWidth && cellHeight == other.cellHeight;
        }

        @NonNull
        String describe() {
            return columns + "x" + rows + "@" + cellWidth + "x" + cellHeight;
        }
    }

    private static final int REQUIRED_STABLE_FRAME_COUNT = 2;

    @Nullable private Geometry mCommitted;
    @Nullable private Geometry mCandidate;
    @Nullable private Source mCandidateSource;
    private boolean mImeViewportActive;
    private int mCandidateStableFrames;
    private long mGeneration;
    private long mSuppressedByImeCount;
    private long mStableCommitCount;

    void reset() {
        mCommitted = null;
        clearCandidate();
        mImeViewportActive = false;
        mGeneration++;
    }

    void setImeViewportActive(boolean active) {
        if (mImeViewportActive == active) return;
        mImeViewportActive = active;
        clearCandidate();
        mGeneration++;
    }

    boolean isImeViewportActive() {
        return mImeViewportActive;
    }

    Decision request(@NonNull Geometry geometry, @NonNull Source source) {
        if (!geometry.isValid()) return Decision.UNCHANGED;

        if (mCommitted == null || source == Source.INITIAL_ATTACH ||
            source == Source.USER_TEXT_SCALE) {
            clearCandidate();
            return Decision.COMMIT;
        }

        if (geometry.sameAs(mCommitted)) {
            clearCandidate();
            return Decision.UNCHANGED;
        }

        if (mImeViewportActive && !mayCommitWhileImeActive(source)) {
            mSuppressedByImeCount++;
            // Ordinary output/layout callbacks cannot invalidate an explicit structural resize
            // that is already proving stable, but they must never create a terminal resize just
            // because the IME boundary moved.
            if (!mayCommitWhileImeActive(mCandidateSource)) clearCandidate();
            return Decision.SUPPRESSED_BY_IME;
        }

        if (!geometry.sameAs(mCandidate) || mCandidateSource != source) {
            mCandidate = geometry;
            mCandidateSource = source;
            mCandidateStableFrames = 0;
            mGeneration++;
        }
        return Decision.WAIT_FOR_STABLE_FRAME;
    }

    Decision onVsync(@NonNull Geometry observed) {
        if (mCandidate == null) return Decision.UNCHANGED;
        if (mImeViewportActive && !mayCommitWhileImeActive(mCandidateSource)) {
            clearCandidate();
            return Decision.SUPPRESSED_BY_IME;
        }
        if (!observed.sameAs(mCandidate)) {
            mCandidate = observed;
            mCandidateStableFrames = 0;
            mGeneration++;
            return Decision.WAIT_FOR_STABLE_FRAME;
        }
        mCandidateStableFrames++;
        return mCandidateStableFrames >= REQUIRED_STABLE_FRAME_COUNT
            ? Decision.COMMIT : Decision.WAIT_FOR_STABLE_FRAME;
    }

    void markCommitted(@NonNull Geometry geometry) {
        mCommitted = geometry;
        clearCandidate();
        mStableCommitCount++;
        mGeneration++;
    }

    boolean matchesCommitted(int columns, int rows, int cellWidth, int cellHeight) {
        return mCommitted != null && mCommitted.columns == columns && mCommitted.rows == rows &&
            mCommitted.cellWidth == cellWidth && mCommitted.cellHeight == cellHeight;
    }

    @Nullable
    Geometry getCommitted() {
        return mCommitted;
    }

    long getGeneration() {
        return mGeneration;
    }

    long getSuppressedByImeCount() {
        return mSuppressedByImeCount;
    }

    long getStableCommitCount() {
        return mStableCommitCount;
    }

    @NonNull
    String getDiagnostics() {
        return "generation=" + mGeneration + " ime=" + mImeViewportActive +
            " committed=" + (mCommitted == null ? "none" : mCommitted.describe()) +
            " candidate=" + (mCandidate == null ? "none" : mCandidate.describe()) +
            " candidateSource=" + mCandidateSource +
            " stableFrames=" + mCandidateStableFrames +
            " suppressedIme=" + mSuppressedByImeCount +
            " stableCommits=" + mStableCommitCount;
    }

    private void clearCandidate() {
        mCandidate = null;
        mCandidateSource = null;
        mCandidateStableFrames = 0;
    }

    private static boolean mayCommitWhileImeActive(@Nullable Source source) {
        return source == Source.STRUCTURAL;
    }

}
