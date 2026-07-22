package com.tencent.shadow.sample.host.platform;

public final class ShadowPackageContract {

    public static final int SCHEMA_VERSION = 2;
    public static final String FILE_SUFFIX = ".shadowpkg";
    public static final String INGRESS_MODE = "SHADOWPKG_INBOX_ONLY";

    private ShadowPackageContract() {
    }

    public static boolean isPackageFileName(String name) {
        return name != null && !name.startsWith(".") && name.endsWith(FILE_SUFFIX);
    }

    public static boolean isTransientFileName(String name) {
        return name != null && name.startsWith(".");
    }
}
