package com.tencent.shadow.sample.host.platform;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ShadowPackageVerifier {

    public static final String METADATA_ENTRY = "termux-shadow.json";
    public static final String CONFIG_ENTRY = "config.json";
    public static final String CHECKSUMS_ENTRY = "checksums.sha256";
    public static final String SIGNATURE_ENTRY = "signature.json";

    private static final int MAX_ENTRIES = 512;
    private static final long MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_ENTRY_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_METADATA_BYTES = 256 * 1024;

    private final ShadowTrustPolicy policy;
    private final long hostVersionCode;

    public ShadowPackageVerifier(ShadowTrustPolicy policy, long hostVersionCode) {
        this.policy = policy;
        this.hostVersionCode = hostVersionCode;
    }

    public ShadowVerificationResult verify(File bundle) throws Exception {
        if (!bundle.isFile() || !bundle.canRead() || bundle.length() == 0) {
            throw new IOException("Shadow package is not readable: " + bundle);
        }
        if (bundle.length() > policy.maxBundleBytes()) {
            throw new IOException("Shadow package exceeds maxBundleBytes: " + bundle.length());
        }

        try (ZipFile zip = new ZipFile(bundle)) {
            Map<String, ZipEntry> entries = indexEntries(zip);
            JSONObject metadata = readJson(zip, requiredEntry(entries, METADATA_ENTRY));
            JSONObject config = readJson(zip, requiredEntry(entries, CONFIG_ENTRY));
            ShadowPluginManifest manifest = ShadowPluginManifest.parse(metadata, config);

            if (hostVersionCode < manifest.minHostVersionCode
                    || hostVersionCode > manifest.maxHostVersionCode) {
                throw new IllegalStateException(
                        "Plugin host compatibility mismatch: host=" + hostVersionCode
                                + " required=" + manifest.minHostVersionCode
                                + ".." + manifest.maxHostVersionCode
                );
            }

            List<String> requiredFiles = new ArrayList<>();
            requiredFiles.add(CONFIG_ENTRY);
            requiredFiles.add(METADATA_ENTRY);
            if (manifest.loaderApkName != null) {
                requiredFiles.add(manifest.loaderApkName);
            }
            if (manifest.runtimeApkName != null) {
                requiredFiles.add(manifest.runtimeApkName);
            }
            requiredFiles.add(manifest.pluginApkName);
            for (String required : requiredFiles) {
                ZipEntry entry = requiredEntry(entries, required);
                if (entry.isDirectory() || entry.getSize() == 0) {
                    throw new IOException("Invalid required Shadow package entry: " + required);
                }
            }

            verifyShadowConfigHashes(zip, entries, config, manifest);

            ZipEntry checksumsEntry = entries.get(CHECKSUMS_ENTRY);
            if (checksumsEntry == null) {
                throw new SecurityException("checksums.sha256 is required");
            }
            byte[] checksumsBytes = readBounded(zip, checksumsEntry, MAX_METADATA_BYTES);
            verifyChecksums(zip, entries, checksumsBytes, requiredFiles);
            ZipEntry signatureEntry = entries.get(SIGNATURE_ENTRY);
            JSONObject signature = signatureEntry == null ? null : readJson(zip, signatureEntry);
            ShadowTrustLevel trustLevel = policy.verifySignature(checksumsBytes, signature);

            String bundleSha256 = ShadowFileOps.sha256(bundle);
            String generation = manifest.versionCode + "-" + bundleSha256.substring(0, 16);
            return new ShadowVerificationResult(manifest, bundleSha256, generation, trustLevel);
        }
    }

    private static Map<String, ZipEntry> indexEntries(ZipFile zip) throws IOException {
        Map<String, ZipEntry> entries = new HashMap<>();
        Set<String> canonicalNames = new HashSet<>();
        long totalSize = 0;
        int count = 0;
        Enumeration<? extends ZipEntry> enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            count++;
            if (count > MAX_ENTRIES) {
                throw new IOException("Shadow package contains too many entries");
            }
            String name = entry.getName();
            validateEntryName(name);
            String canonical = name.toLowerCase(Locale.US);
            if (!canonicalNames.add(canonical) || entries.put(name, entry) != null) {
                throw new IOException("Duplicate Shadow package entry: " + name);
            }
            long size = entry.getSize();
            if (size > MAX_ENTRY_BYTES) {
                throw new IOException("Shadow package entry is too large: " + name);
            }
            if (size > 0) {
                totalSize += size;
                if (totalSize > MAX_UNCOMPRESSED_BYTES) {
                    throw new IOException("Shadow package expands beyond the allowed size");
                }
            }
        }
        return entries;
    }

    private static void validateEntryName(String name) throws IOException {
        if (name == null || name.length() == 0 || name.length() > 512
                || name.startsWith("/") || name.startsWith("\\")
                || name.indexOf('\0') >= 0 || name.contains("\\")) {
            throw new IOException("Unsafe Shadow package entry name: " + name);
        }
        String[] segments = name.split("/");
        for (String segment : segments) {
            if (segment.equals("..") || segment.equals(".")) {
                throw new IOException("Unsafe Shadow package entry path: " + name);
            }
        }
    }

    private static void verifyShadowConfigHashes(
            ZipFile zip,
            Map<String, ZipEntry> entries,
            JSONObject config,
            ShadowPluginManifest manifest
    ) throws Exception {
        JSONObject loader = config.optJSONObject("pluginLoader");
        if (loader != null) {
            verifyMd5(zip, entries, loader);
        }
        JSONObject runtime = config.optJSONObject("runtime");
        if (runtime != null) {
            verifyMd5(zip, entries, runtime);
        }
        verifyMd5(zip, entries, ShadowPluginManifest.pluginConfig(config, manifest.partKey));
    }

    private static void verifyMd5(ZipFile zip, Map<String, ZipEntry> entries, JSONObject item)
            throws Exception {
        String name = item.getString("apkName");
        String expected = item.getString("hash");
        String actual = digestEntry(zip, requiredEntry(entries, name), "MD5");
        if (!expected.equalsIgnoreCase(actual)) {
            throw new SecurityException("Shadow config hash mismatch for " + name);
        }
    }

    private static void verifyChecksums(
            ZipFile zip,
            Map<String, ZipEntry> entries,
            byte[] checksumBytes,
            List<String> requiredFiles
    ) throws Exception {
        String text = new String(checksumBytes, StandardCharsets.UTF_8);
        Map<String, String> expected = new HashMap<>();
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.length() == 0) {
                continue;
            }
            int separator = line.indexOf("  ");
            if (separator != 64) {
                throw new SecurityException("Malformed checksums.sha256 line");
            }
            String digest = line.substring(0, separator).toLowerCase(Locale.US);
            String name = line.substring(separator + 2);
            if (!digest.matches("[0-9a-f]{64}")) {
                throw new SecurityException("Malformed SHA-256 digest for " + name);
            }
            validateEntryName(name);
            if (expected.put(name, digest) != null) {
                throw new SecurityException("Duplicate checksum entry: " + name);
            }
        }
        for (String required : requiredFiles) {
            if (!expected.containsKey(required)) {
                throw new SecurityException("Missing checksum for " + required);
            }
        }
        for (Map.Entry<String, String> checksum : expected.entrySet()) {
            ZipEntry entry = requiredEntry(entries, checksum.getKey());
            String actual = digestEntry(zip, entry, "SHA-256");
            if (!checksum.getValue().equals(actual)) {
                throw new SecurityException("SHA-256 mismatch for " + checksum.getKey());
            }
        }
    }

    private static JSONObject readJson(ZipFile zip, ZipEntry entry) throws Exception {
        return new JSONObject(new String(readBounded(zip, entry, MAX_METADATA_BYTES), StandardCharsets.UTF_8));
    }

    private static byte[] readBounded(ZipFile zip, ZipEntry entry, long maxBytes) throws IOException {
        InputStream input = null;
        try {
            input = zip.getInputStream(entry);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Shadow package entry exceeds limit: " + entry.getName());
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            if (input != null) {
                input.close();
            }
        }
    }

    private static String digestEntry(ZipFile zip, ZipEntry entry, String algorithm) throws Exception {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        InputStream input = null;
        try {
            input = zip.getInputStream(entry);
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_ENTRY_BYTES) {
                    throw new IOException("Shadow package entry exceeds limit: " + entry.getName());
                }
                digest.update(buffer, 0, read);
            }
        } finally {
            if (input != null) {
                input.close();
            }
        }
        return ShadowFileOps.toHex(digest.digest());
    }

    private static ZipEntry requiredEntry(Map<String, ZipEntry> entries, String name)
            throws IOException {
        if (name == null) {
            throw new IOException("Required Shadow package entry name is null");
        }
        validateEntryName(name);
        ZipEntry entry = entries.get(name);
        if (entry == null) {
            throw new IOException("Missing Shadow package entry: " + name);
        }
        return entry;
    }
}
