package com.termux.shared.view;

import android.view.WindowManager;

import org.junit.Assert;
import org.junit.Test;

public class KeyboardUtilsTest {

    @Test
    public void replacingAdjustBitsPreservesKeyboardState() {
        int current = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN |
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

        int updated = KeyboardUtils.replaceSoftInputModeBits(current,
            WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);

        Assert.assertEquals(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
            updated & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE);
        Assert.assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            updated & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
    }

    @Test
    public void replacingStateBitsPreservesTerminalAdjustmentPolicy() {
        int current = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED |
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;

        int updated = KeyboardUtils.replaceSoftInputModeBits(current,
            WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE,
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        Assert.assertEquals(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
            updated & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE);
        Assert.assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            updated & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
    }
}
