package com.termux.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.system.Os;

import com.termux.BuildConfig;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/** Installs the APK-owned native Shadow tooling without using adb or mutating user projects. */
final class ShadowPluginToolingInstaller {

    private static final String LOG_TAG = "ShadowToolingInstaller";
    private static final String ASSET_ROOT = "shadow-plugin-tooling";
    private static final String ASSET_MANIFEST = "manifest.properties";
    private static final String INSTALLED_MANIFEST = "tooling-manifest.properties";
    private static final String DEBUG_FAULT_TRIGGER = "shadow-plugin-tooling-fault.trigger";
    private static final String DEBUG_FAULT_CONSUMED = "shadow-plugin-tooling-fault.consumed";
    private static final String DEBUG_FAULT_REACHED = "shadow-plugin-tooling-fault.reached";
    private static final String TEMPLATE_GITIGNORE_ASSET = "gitignore.shadow-template";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;

    enum DebugFaultPoint {
        AFTER_SHARE_RENAME("after-share-rename"),
        AFTER_BINARY_OLD_RENAME("after-binary-old-rename"),
        AFTER_BINARY_NEW_RENAME("after-binary-new-rename");

        final String wireName;

        DebugFaultPoint(String wireName) {
            this.wireName = wireName;
        }
    }

    private ShadowPluginToolingInstaller() {
    }

    static synchronized void installIfPresent(Context context) {
        try {
            if (!supportsEmbeddedArchitecture()) {
                return;
            }
            File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
            File shell = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR, "sh");
            if (!prefix.isDirectory() || !shell.isFile()) {
                return;
            }
            AssetManager assets = context.getAssets();
            Properties manifest = readManifest(assets);
            String cliSha = requiredDigest(manifest, "cliSha256");
            String templateSha = requiredDigest(manifest, "templateSha256");
            File binaryTarget = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR, "shadow-plugin");
            File shareRoot = new File(
                    TermuxConstants.TERMUX_SHARE_PREFIX_DIR,
                    "termux-shadow-plugin"
            );
            File marker = installedMarker(shareRoot);
            File stagingRoot = new File(
                    TermuxConstants.TERMUX_SHARE_PREFIX_DIR,
                    ".shadow-plugin-tooling-stage"
            );
            File oldRoot = new File(
                    TermuxConstants.TERMUX_SHARE_PREFIX_DIR,
                    ".termux-shadow-plugin.old"
            );
            File binaryOld = new File(
                    TermuxConstants.TERMUX_BIN_PREFIX_DIR,
                    ".shadow-plugin.old"
            );
            if (!shareRoot.exists() && oldRoot.isDirectory()) {
                restoreDirectory(oldRoot, shareRoot);
            }
            if (!binaryTarget.exists() && binaryOld.isFile()) {
                restoreFile(binaryOld, binaryTarget);
            }
            File templateTarget = new File(shareRoot, "template");
            if (markerMatches(marker, cliSha, templateSha)
                    && cliSha.equals(sha256(binaryTarget))
                    && templateSha.equals(treeSha256(templateTarget))) {
                deleteTree(stagingRoot);
                deleteTree(oldRoot);
                binaryOld.delete();
                return;
            }

            deleteTree(stagingRoot);
            ensurePrivateDirectory(stagingRoot);
            File stagedShare = new File(stagingRoot, "termux-shadow-plugin");
            File stagedTemplate = new File(stagedShare, "template");
            copyTree(assets, ASSET_ROOT + "/template", stagedTemplate);
            restoreTemplateGitignore(stagedTemplate);
            File stagedBinary = new File(stagingRoot, "shadow-plugin");
            copyAsset(assets, ASSET_ROOT + "/aarch64/shadow-plugin", stagedBinary);
            if (!cliSha.equals(sha256(stagedBinary))
                    || !templateSha.equals(treeSha256(new File(stagedShare, "template")))) {
                throw new IOException("embedded Shadow tooling fingerprint mismatch");
            }
            chmodExecutable(stagedBinary);
            writeMarker(installedMarker(stagedShare), manifest);

            File binaryStage = new File(
                    TermuxConstants.TERMUX_BIN_PREFIX_DIR,
                    ".shadow-plugin.new"
            );
            binaryStage.delete();
            copyFile(stagedBinary, binaryStage);
            chmodExecutable(binaryStage);

            deleteTree(oldRoot);
            if (shareRoot.exists() && !shareRoot.renameTo(oldRoot)) {
                throw new IOException("cannot stage existing Shadow tooling directory");
            }
            if (!stagedShare.renameTo(shareRoot)) {
                restoreDirectory(oldRoot, shareRoot);
                throw new IOException("cannot install Shadow tooling template");
            }
            maybeHaltForDebugFault(context, DebugFaultPoint.AFTER_SHARE_RENAME);
            binaryOld.delete();
            if (binaryTarget.exists() && !binaryTarget.renameTo(binaryOld)) {
                deleteTree(shareRoot);
                restoreDirectory(oldRoot, shareRoot);
                throw new IOException("cannot stage existing shadow-plugin binary");
            }
            maybeHaltForDebugFault(context, DebugFaultPoint.AFTER_BINARY_OLD_RENAME);
            if (!binaryStage.renameTo(binaryTarget)) {
                restoreFile(binaryOld, binaryTarget);
                deleteTree(shareRoot);
                restoreDirectory(oldRoot, shareRoot);
                throw new IOException("cannot install shadow-plugin binary");
            }
            maybeHaltForDebugFault(context, DebugFaultPoint.AFTER_BINARY_NEW_RENAME);
            deleteTree(oldRoot);
            binaryOld.delete();
            deleteTree(stagingRoot);
            Logger.logInfo(LOG_TAG, "Installed native shadow-plugin " + manifest.getProperty("cliVersion")
                    + " sha256=" + cliSha);
        } catch (Throwable error) {
            Logger.logWarn(LOG_TAG, "Shadow tooling install skipped: " + rootMessage(error));
        }
    }

    private static Properties readManifest(AssetManager assets) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = assets.open(ASSET_ROOT + "/" + ASSET_MANIFEST)) {
            properties.load(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
        }
        if (!"1".equals(properties.getProperty("schemaVersion"))) {
            throw new IOException("unsupported Shadow tooling manifest schema");
        }
        return properties;
    }

    static File installedMarker(File shareRoot) {
        return new File(shareRoot, INSTALLED_MANIFEST);
    }

    static boolean isSupportedDebugFaultPoint(String value) {
        for (DebugFaultPoint point : DebugFaultPoint.values()) {
            if (point.wireName.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static void maybeHaltForDebugFault(Context context, DebugFaultPoint point)
            throws IOException {
        if (!BuildConfig.DEBUG) {
            return;
        }
        File directory = context.getNoBackupFilesDir();
        File trigger = new File(directory, DEBUG_FAULT_TRIGGER);
        if (!trigger.isFile() || !point.wireName.equals(readSmallText(trigger).trim())) {
            return;
        }

        File consumed = new File(directory, DEBUG_FAULT_CONSUMED);
        deleteTree(consumed);
        if (!trigger.renameTo(consumed)) {
            throw new IOException("cannot consume Shadow tooling debug fault trigger");
        }
        makePrivateFile(consumed);

        File reached = new File(directory, DEBUG_FAULT_REACHED);
        File temporary = new File(directory, DEBUG_FAULT_REACHED + ".tmp");
        byte[] record = (point.wireName + "\n" + android.os.Process.myPid() + "\n")
                .getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(record);
            output.getFD().sync();
        }
        deleteTree(reached);
        if (!temporary.renameTo(reached)) {
            temporary.delete();
            throw new IOException("cannot commit Shadow tooling debug fault evidence");
        }
        makePrivateFile(reached);
        Logger.logWarn(LOG_TAG, "Debug fault reached: " + point.wireName);

        android.os.Process.killProcess(android.os.Process.myPid());
        Runtime.getRuntime().halt(86);
    }

    private static String readSmallText(File file) throws IOException {
        byte[] buffer = new byte[256];
        int length = 0;
        try (InputStream input = new FileInputStream(file)) {
            int read;
            while (length < buffer.length
                    && (read = input.read(buffer, length, buffer.length - length)) != -1) {
                length += read;
            }
            if (length == buffer.length && input.read() != -1) {
                throw new IOException("Shadow tooling debug fault trigger is too large");
            }
        }
        return new String(buffer, 0, length, StandardCharsets.UTF_8);
    }

    private static String requiredDigest(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key, "").trim().toLowerCase(java.util.Locale.US);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IOException("invalid tooling digest: " + key);
        }
        return value;
    }

    private static boolean markerMatches(File marker, String cliSha, String templateSha) {
        if (!marker.isFile()) {
            return false;
        }
        try (InputStream input = new FileInputStream(marker)) {
            Properties current = new Properties();
            current.load(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
            return cliSha.equalsIgnoreCase(current.getProperty("cliSha256", ""))
                    && templateSha.equalsIgnoreCase(current.getProperty("templateSha256", ""));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void copyTree(AssetManager assets, String assetPath, File target) throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            copyAsset(assets, assetPath, target);
            return;
        }
        ensurePrivateDirectory(target);
        for (String child : children) {
            if (!isSafeSegment(child)) {
                throw new IOException("unsafe Shadow tooling asset path");
            }
            copyTree(assets, assetPath + "/" + child, new File(target, child));
        }
    }

    static void restoreTemplateGitignore(File template) throws IOException {
        File transported = new File(template, TEMPLATE_GITIGNORE_ASSET);
        if (!transported.isFile()) {
            throw new IOException("embedded template .gitignore transport is missing");
        }
        File target = new File(template, ".gitignore");
        if (target.exists() && !target.delete()) {
            throw new IOException("cannot replace template .gitignore");
        }
        if (!transported.renameTo(target)) {
            throw new IOException("cannot restore template .gitignore");
        }
        makePrivateFile(target);
    }

    private static void copyAsset(AssetManager assets, String assetPath, File target) throws IOException {
        File parent = target.getParentFile();
        ensurePrivateDirectory(parent);
        try (InputStream input = new BufferedInputStream(assets.open(assetPath));
             FileOutputStream output = new FileOutputStream(target, false)) {
            copyBounded(input, output);
            output.getFD().sync();
        }
        target.setReadable(false, false);
        target.setWritable(false, false);
        target.setReadable(true, true);
        target.setWritable(true, true);
        if (assetPath.endsWith("/gradlew")
                || assetPath.endsWith("/shadow-plugin")
                || (assetPath.contains("/scripts/") && assetPath.endsWith(".sh"))) {
            chmodExecutable(target);
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             FileOutputStream output = new FileOutputStream(target, false)) {
            copyBounded(input, output);
            output.getFD().sync();
        }
    }

    private static void copyBounded(InputStream input, FileOutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_FILE_BYTES) {
                throw new IOException("Shadow tooling asset is too large");
            }
            output.write(buffer, 0, read);
        }
    }

    private static void writeMarker(File marker, Properties source) throws IOException {
        File parent = marker.getParentFile();
        ensurePrivateDirectory(parent);
        File temporary = new File(parent, ".tooling-manifest.tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            source.store(output, "Termux Shadow native tooling");
            output.getFD().sync();
        }
        if (!temporary.renameTo(marker)) {
            temporary.delete();
            throw new IOException("cannot commit tooling manifest");
        }
        makePrivateFile(marker);
    }

    private static String sha256(File file) throws Exception {
        if (file == null || !file.isFile()) {
            return "";
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    static String treeSha256(File root) throws Exception {
        if (root == null || !root.isDirectory()) {
            return "";
        }
        List<File> files = new java.util.ArrayList<>();
        collectFiles(root, files);
        Collections.sort(files, (left, right) ->
                relative(root, left).compareTo(relative(root, right)));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (File file : files) {
            digest.update(relative(root, file).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return hex(digest.digest());
    }

    private static void collectFiles(File directory, List<File> files) throws IOException {
        if (isSymlink(directory)) {
            throw new IOException("symbolic links are forbidden in installed Shadow tooling");
        }
        File[] children = directory.listFiles();
        if (children == null) {
            throw new IOException("cannot list Shadow tooling directory: " + directory);
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectFiles(child, files);
            } else if (child.isFile()) {
                files.add(child);
            }
        }
    }

    private static String relative(File root, File file) {
        String rootPath = root.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void restoreDirectory(File oldRoot, File target) throws IOException {
        if (oldRoot.isDirectory() && !target.exists() && !oldRoot.renameTo(target)) {
            throw new IOException("cannot restore Shadow tooling directory");
        }
    }

    private static void restoreFile(File oldFile, File target) throws IOException {
        if (oldFile.isFile() && !target.exists() && !oldFile.renameTo(target)) {
            throw new IOException("cannot restore shadow-plugin binary");
        }
    }

    private static void ensurePrivateDirectory(File directory) throws IOException {
        if (directory == null || (!directory.mkdirs() && !directory.isDirectory())) {
            throw new IOException("cannot create directory: " + directory);
        }
        directory.setReadable(false, false);
        directory.setWritable(false, false);
        directory.setExecutable(false, false);
        directory.setReadable(true, true);
        directory.setWritable(true, true);
        directory.setExecutable(true, true);
    }

    private static void makePrivateFile(File file) {
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }

    private static void chmodExecutable(File file) throws IOException {
        try {
            Os.chmod(file.getAbsolutePath(), 0700);
        } catch (Throwable error) {
            if (!file.setExecutable(true, true)) {
                throw new IOException("cannot mark executable: " + file, error);
            }
        }
    }

    private static boolean supportsEmbeddedArchitecture() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi) || "aarch64".equals(abi)) {
                return true;
            }
        }
        return false;
    }

    static boolean isSafeSegment(String value) {
        return value != null && !value.isEmpty() && !".".equals(value) && !"..".equals(value)
                && value.indexOf('/') < 0 && value.indexOf('\\') < 0;
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory() && !isSymlink(file)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTree(child);
                }
            }
        }
        file.delete();
    }

    private static boolean isSymlink(File file) {
        try {
            File parent = file.getParentFile();
            File canonicalParent = parent == null ? null : parent.getCanonicalFile();
            File canonical = canonicalParent == null
                    ? file.getCanonicalFile()
                    : new File(canonicalParent, file.getName());
            return !canonical.getCanonicalFile().equals(canonical.getAbsoluteFile());
        } catch (IOException ignored) {
            return true;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
