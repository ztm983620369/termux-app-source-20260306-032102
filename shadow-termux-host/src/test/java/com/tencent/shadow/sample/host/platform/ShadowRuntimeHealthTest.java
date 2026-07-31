package com.tencent.shadow.sample.host.platform;

import org.junit.Test;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ShadowRuntimeHealthTest {

    @Test
    public void smokeProofFieldsAreCorrelatedAndBounded() {
        ShadowRuntimeHealth valid = new ShadowRuntimeHealth(
                1, 100L, 200L, 123, "plugin", true, true, 2, 50L, null
        );
        assertTrue(valid.isStable());

        assertThrows(IllegalArgumentException.class, () -> new ShadowRuntimeHealth(
                1, 100L, 200L, 123, "plugin", false, true, 1, 10L, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new ShadowRuntimeHealth(
                1, 100L, 200L, 123, "plugin", true, false, 1, 10L, "failed"
        ));
    }
}
