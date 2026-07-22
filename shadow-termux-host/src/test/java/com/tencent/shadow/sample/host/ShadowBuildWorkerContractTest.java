package com.tencent.shadow.sample.host;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ShadowBuildWorkerContractTest {

    @Test
    public void mapsOnlyExplicitWorkerMethods() {
        assertEquals(
                ShadowBuildWorkerContract.ACTION_ENSURE,
                ShadowBuildWorkerContract.actionForMethod("ensure-worker")
        );
        assertEquals(
                ShadowBuildWorkerContract.ACTION_QUERY,
                ShadowBuildWorkerContract.actionForMethod("query-worker")
        );
        assertEquals(
                ShadowBuildWorkerContract.ACTION_STOP,
                ShadowBuildWorkerContract.actionForMethod("stop-worker")
        );
        assertNull(ShadowBuildWorkerContract.actionForMethod("run"));
        assertNull(ShadowBuildWorkerContract.actionForMethod("shell"));
    }
}
