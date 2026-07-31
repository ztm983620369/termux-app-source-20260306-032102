package com.termux.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ShadowPluginToolingInstallerTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void treeFingerprintIsStableAndContentSensitive() throws Exception {
        File root = temporaryFolder.newFolder("template");
        File nested = new File(root, "scripts");
        assertTrue(nested.mkdirs());
        File first = new File(root, "shadow-plugin.properties");
        File second = new File(nested, "doctor.sh");
        Files.write(first.toPath(), "pluginId=test\n".getBytes(StandardCharsets.UTF_8));
        Files.write(second.toPath(), "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));

        String before = ShadowPluginToolingInstaller.treeSha256(root);
        assertEquals(before, ShadowPluginToolingInstaller.treeSha256(root));

        Files.write(second.toPath(), "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(before, ShadowPluginToolingInstaller.treeSha256(root));
    }

    @Test
    public void assetSegmentsCannotEscapeTheStagingRoot() {
        assertTrue(ShadowPluginToolingInstaller.isSafeSegment("gradle-wrapper.jar"));
        assertFalse(ShadowPluginToolingInstaller.isSafeSegment(".."));
        assertFalse(ShadowPluginToolingInstaller.isSafeSegment("a/b"));
        assertFalse(ShadowPluginToolingInstaller.isSafeSegment("a\\b"));
    }

    @Test
    public void installedMarkerUsesTheStableToolingName() throws Exception {
        File shareRoot = temporaryFolder.newFolder("share-root");
        assertEquals(
                new File(shareRoot, "tooling-manifest.properties"),
                ShadowPluginToolingInstaller.installedMarker(shareRoot)
        );
    }

    @Test
    public void transportedGitignoreIsRestoredBeforeFingerprinting() throws Exception {
        File template = temporaryFolder.newFolder("gitignore-template");
        File transported = new File(template, "gitignore.shadow-template");
        Files.write(transported.toPath(), "build/\n".getBytes(StandardCharsets.UTF_8));

        ShadowPluginToolingInstaller.restoreTemplateGitignore(template);

        assertFalse(transported.exists());
        assertTrue(new File(template, ".gitignore").isFile());
    }

    @Test
    public void missingGitignoreTransportRejectsIncompleteEmbeddedTemplate() throws Exception {
        File template = temporaryFolder.newFolder("missing-gitignore-template");
        assertThrows(java.io.IOException.class, () ->
                ShadowPluginToolingInstaller.restoreTemplateGitignore(template));
    }

    @Test
    public void debugFaultPointsAreAnExplicitClosedSet() {
        assertTrue(ShadowPluginToolingInstaller.isSupportedDebugFaultPoint(
                "after-share-rename"));
        assertTrue(ShadowPluginToolingInstaller.isSupportedDebugFaultPoint(
                "after-binary-old-rename"));
        assertTrue(ShadowPluginToolingInstaller.isSupportedDebugFaultPoint(
                "after-binary-new-rename"));
        assertFalse(ShadowPluginToolingInstaller.isSupportedDebugFaultPoint("before-copy"));
        assertFalse(ShadowPluginToolingInstaller.isSupportedDebugFaultPoint(""));
    }
}
