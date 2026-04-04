package com.termux.workspaceshell.ui

class WorkspaceTabTouchStateMachine(
    private val moveThresholdPx: Float
) {
    enum class State {
        IDLE,
        PRESSED,
        MOVED,
        LONG_PRESS_TRIGGERED
    }

    enum class ReleaseAction {
        NONE,
        CLICK,
        CONSUME
    }

    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f

    fun onDown(x: Float, y: Float) {
        downX = x
        downY = y
        state = State.PRESSED
    }

    fun onMove(x: Float, y: Float) {
        if (state != State.PRESSED) return
        val dx = x - downX
        val dy = y - downY
        if ((dx * dx) + (dy * dy) > moveThresholdPx * moveThresholdPx) {
            state = State.MOVED
        }
    }

    fun onLongPress(): Boolean {
        if (state != State.PRESSED) return false
        state = State.LONG_PRESS_TRIGGERED
        return true
    }

    fun onUp(): ReleaseAction {
        val result = when (state) {
            State.PRESSED -> ReleaseAction.CLICK
            State.LONG_PRESS_TRIGGERED -> ReleaseAction.CONSUME
            State.MOVED, State.IDLE -> ReleaseAction.NONE
        }
        state = State.IDLE
        return result
    }

    fun onCancel() {
        state = State.IDLE
    }
}
