package com.termux.shadowtemplate;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class DemoEntry {

    private static final String TARGET_PACKAGE = "com.termux.artifactcalc";
    private static final String REPORT_FILE = "calculator-dex-report.txt";

    private DemoEntry() {
    }

    public static String run(Context context) throws Exception {
        long now = System.currentTimeMillis();
        String report = buildReport(now, true);
        File runtimeDir = new File(context.getFilesDir(), "shadow-demo/runtime");
        if (!runtimeDir.exists() && !runtimeDir.mkdirs()) {
            return "runtime dir create failed";
        }

        File marker = new File(runtimeDir, REPORT_FILE);
        try (FileOutputStream out = new FileOutputStream(marker, false)) {
            out.write(report.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        boolean launchSent = false;
        try {
            PackageManager pm = context.getPackageManager();
            Intent launch = pm.getLaunchIntentForPackage(TARGET_PACKAGE);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
                launchSent = true;
            }
        } catch (Throwable ignored) {
            launchSent = false;
        }

        return "dex ok, launchSent=" + launchSent + ", report=" + marker.getAbsolutePath();
    }

    public static void main(String[] args) {
        long now = System.currentTimeMillis();
        System.out.println(buildReport(now, false));
    }

    private static String buildReport(long now, boolean withContext) {
        double sample = evaluateSample();
        return "calculator-dex report"
            + "\nnow=" + now
            + "\nmode=" + (withContext ? "context" : "standalone")
            + "\nsample=(12.5+7.5)*3-8/2"
            + "\nresult=" + sample
            + "\ntargetPackage=" + TARGET_PACKAGE;
    }

    private static double evaluateSample() {
        return (12.5d + 7.5d) * 3.0d - 8.0d / 2.0d;
    }
}
