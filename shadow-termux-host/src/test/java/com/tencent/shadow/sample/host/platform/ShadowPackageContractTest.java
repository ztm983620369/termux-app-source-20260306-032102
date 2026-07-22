package com.tencent.shadow.sample.host.platform;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShadowPackageContractTest {

    @Test
    public void acceptsOnlyExactShadowPackageSuffix() {
        assertTrue(ShadowPackageContract.isPackageFileName("plugin.shadowpkg"));
        assertFalse(ShadowPackageContract.isPackageFileName("plugin.zip"));
        assertFalse(ShadowPackageContract.isPackageFileName("plugin.SHADOWPKG"));
        assertFalse(ShadowPackageContract.isPackageFileName(".plugin.shadowpkg"));
        assertFalse(ShadowPackageContract.isPackageFileName(null));
    }

    @Test
    public void recognizesAtomicPublisherTemporaryFiles() {
        assertTrue(ShadowPackageContract.isTransientFileName(".plugin.shadowpkg.part"));
        assertFalse(ShadowPackageContract.isTransientFileName("plugin.shadowpkg"));
    }
}
