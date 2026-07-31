package com.termux.view;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

/** A combination of {@link GestureDetector} and {@link ScaleGestureDetector}. */
final class GestureAndScaleRecognizer {

    public interface Listener {
        boolean onSingleTapUp(MotionEvent e);

        boolean onDoubleTap(MotionEvent e);

        boolean onScroll(MotionEvent e2, float dx, float dy);

        boolean onFling(MotionEvent e, float velocityX, float velocityY);

        boolean onScale(float focusX, float focusY, float scale, long eventTimeMillis);

        boolean onScaleBegin(float focusX, float focusY, long eventTimeMillis);

        void onScaleEnd(boolean cancelled);

        boolean onDown(float x, float y);

        boolean onUp(MotionEvent e);

        void onLongPress(MotionEvent e);
    }

    private final GestureDetector mGestureDetector;
    private final ScaleGestureDetector mScaleDetector;
    final Listener mListener;
    boolean isAfterLongPress;
    private boolean mMultiTouchSequence;
    private boolean mScaleListenerActive;
    private int mDispatchAction = MotionEvent.ACTION_CANCEL;
    private long mDispatchEventTimeMillis;

    public GestureAndScaleRecognizer(Context context, Listener listener) {
        mListener = listener;

        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                return mListener.onScroll(e2, dx, dy);
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                return mListener.onSingleTapUp(e);
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                return mListener.onFling(e2, velocityX, velocityY);
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return mListener.onDown(e.getX(), e.getY());
            }

            @Override
            public void onLongPress(MotionEvent e) {
                mListener.onLongPress(e);
                isAfterLongPress = true;
            }
        }, null, true /* ignoreMultitouch */);

        mGestureDetector.setOnDoubleTapListener(new GestureDetector.OnDoubleTapListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                return mListener.onDoubleTap(e);
            }

            @Override
            public boolean onDoubleTapEvent(MotionEvent e) {
                return true;
            }
        });

        mScaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                boolean accepted = mListener.onScaleBegin(detector.getFocusX(), detector.getFocusY(),
                    mDispatchEventTimeMillis);
                mScaleListenerActive = accepted;
                return accepted;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                return mListener.onScale(detector.getFocusX(), detector.getFocusY(),
                    detector.getScaleFactor(), mDispatchEventTimeMillis);
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                finishScaleListener(mDispatchAction == MotionEvent.ACTION_CANCEL);
            }
        });
        mScaleDetector.setQuickScaleEnabled(false);
    }

    public void onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        mDispatchAction = action;
        mDispatchEventTimeMillis = event.getEventTime();
        if (action == MotionEvent.ACTION_DOWN) {
            mMultiTouchSequence = false;
            isAfterLongPress = false;
        }

        // Scale owns every event after a second pointer enters. Feed it first so scale state is
        // current before any listener callback, then cancel and quarantine the one-finger detector.
        mScaleDetector.onTouchEvent(event);
        // Some framework/device combinations leave ScaleGestureDetector in progress after the
        // second pointer departs. The MotionEvent stream is authoritative: once fewer than two
        // pointers remain, finish the accepted listener transaction exactly once.
        if (mScaleListenerActive && isTerminalScaleStreamEvent(action, event.getPointerCount())) {
            finishScaleListener(action == MotionEvent.ACTION_CANCEL);
        }
        if (!mMultiTouchSequence &&
            (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1)) {
            mMultiTouchSequence = true;
            cancelSinglePointerGesture(event);
        }
        if (!mMultiTouchSequence) {
            mGestureDetector.onTouchEvent(event);
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP && !mMultiTouchSequence && !isAfterLongPress) {
                // This behaviour is desired when in e.g. vim with mouse events, where we do not
                // want to move the cursor when lifting finger after a long press.
                mListener.onUp(event);
            }
            mMultiTouchSequence = false;
        }
    }

    private void cancelSinglePointerGesture(MotionEvent source) {
        MotionEvent cancel = MotionEvent.obtain(source);
        try {
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            mGestureDetector.onTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
    }

    private void finishScaleListener(boolean cancelled) {
        if (!mScaleListenerActive) return;
        mScaleListenerActive = false;
        mListener.onScaleEnd(cancelled);
    }

    static boolean isTerminalScaleStreamEvent(int action, int pointerCount) {
        return action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ||
            (action == MotionEvent.ACTION_POINTER_UP && pointerCount == 2);
    }

    public boolean isInProgress() {
        return mScaleDetector.isInProgress();
    }

    boolean isMultiTouchSequence() {
        return mMultiTouchSequence;
    }

}
