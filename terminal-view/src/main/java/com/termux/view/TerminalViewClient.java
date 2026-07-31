package com.termux.view;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.termux.terminal.TerminalSession;

/**
 * The interface for communication between {@link TerminalView} and its client. It allows for getting
 * various  configuration options from the client and for sending back data to the client like logs,
 * key events, both hardware and IME (which makes it different from that available with
 * {@link View#setOnKeyListener(View.OnKeyListener)}, etc. It must be set for the
 * {@link TerminalView} through {@link TerminalView#setTerminalViewClient(TerminalViewClient)}.
 */
public interface TerminalViewClient {

    /**
     * Callback function on scale events according to {@link ScaleGestureDetector#getScaleFactor()}.
     */
    float onScale(float scale);

    /** Persist the text size committed by TerminalView's real-time reflow gesture. */
    void onScaleTextSizeChanged(int textSize, boolean finished);



    /**
     * On a single tap on the terminal if terminal mouse reporting not enabled.
     */
    void onSingleTapUp(MotionEvent e);

    /**
     * On a single tap that may be handled as text input focus by the client.
     *
     * This is called even while terminal mouse reporting is enabled so clients
     * can show the IME for application-owned input regions without disabling
     * mouse reporting for the running terminal program.
     */
    void onTextInputTap(MotionEvent e);

    /**
     * A tap routed with its owning view/session. Implementations that keep Activity-level
     * selection state may use this to commit focus before asynchronous page reconciliation.
     */
    default void onTerminalViewTap(TerminalView terminalView, TerminalSession session, MotionEvent e) {
        onTextInputTap(e);
        onSingleTapUp(e);
    }

    /** Open a URL already validated by the terminal link resolver. */
    void onOpenUrl(String url);

    boolean shouldTerminalViewRequestFocusOnTap();

    boolean shouldBackButtonBeMappedToEscape();

    boolean shouldEnforceCharBasedInput();

    boolean shouldUseCtrlSpaceWorkaround();

    boolean isTerminalViewSelected();

    boolean shouldSendMouseWheelEventsForTouchScroll(TerminalSession session);

    /**
     * Whether a touch tap should be reported as a terminal mouse click.
     *
     * By default this follows the touch-scroll policy for compatibility with
     * existing clients. Clients may opt out separately when a terminal needs
     * remote wheel input but a tap must remain available for local focus/IME.
     */
    default boolean shouldSendMouseClickEventsForTouchTap(TerminalSession session) {
        return shouldSendMouseWheelEventsForTouchScroll(session);
    }



    void copyModeChanged(boolean copyMode);



    boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session);

    boolean onKeyUp(int keyCode, KeyEvent e);

    boolean onLongPress(MotionEvent event);



    boolean readControlKey();

    boolean readAltKey();

    boolean readShiftKey();

    boolean readFnKey();



    boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session);


    void onEmulatorSet();


    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
