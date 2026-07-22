package com.termux.sessionsync;

import android.content.Context;
import android.os.OperationCanceledException;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import com.termux.sshconnectioncore.ResolvedSshEndpoint;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.security.MessageDigest;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FUSE-free SFTP protocol engine used when sshfs mounting is unavailable.
 */
public final class SftpProtocolManager {

    private static final SftpProtocolManager INSTANCE = new SftpProtocolManager();
    // Keep short-lived directory cache to absorb repeated UI refresh/open cycles
    // without adding extra network RTT for every gesture.
    private static final long DIRECTORY_CACHE_TTL_MS = 2_500L;
    private static final int DIRECTORY_CACHE_MAX_ENTRIES = 384;
    private static final int RECOVERABLE_RETRY_COUNT = 1;
    private static final int MAX_CHANNEL_POOL_PER_CLIENT = 3;
    private static final int MAX_TRANSFER_WORKERS = 3;
    private static final long CHANNEL_IDLE_TTL_MS = 18_000L;
    private static final int PREWARM_MAX_THREADS = 2;
    private static final AtomicInteger PREWARM_THREAD_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger TRANSFER_THREAD_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger TRANSFER_TEMP_COUNTER = new AtomicInteger(1);
    private static final long FULL_DIGEST_VERIFY_MAX_BYTES = 2L * 1024L * 1024L;
    private static final int SAMPLE_DIGEST_VERIFY_BYTES = 256 * 1024;
    private static final int TRANSFER_DIGEST_BUFFER_BYTES = 32 * 1024;
    private static final String CODEX_ATTACHMENT_REMOTE_RELATIVE_DIR = ".codex/termux-images";
    private static final long CODEX_ATTACHMENT_TEMP_RETENTION_MS = 24L * 60L * 60L * 1000L;
    private static final long DOWNLOAD_PROGRESS_MIN_INTERVAL_MS = 120L;
    private static final ThreadLocal<Integer> SUPPRESS_TRANSFER_JOURNAL_DEPTH = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };
    private static final Set<String> SSH_OPTIONS_WITH_VALUE = new HashSet<>(Arrays.asList(
        "-b", "-c", "-D", "-E", "-F", "-I", "-i", "-J", "-L", "-l",
        "-m", "-O", "-o", "-p", "-Q", "-R", "-S", "-W", "-w"
    ));

    private final Object mLock = new Object();
    private final Map<String, ClientHolder> mClients = new HashMap<>();
    private final Map<String, CachedDirectory> mDirectoryCache = new HashMap<>();
    private final Set<String> mPrewarmingClientKeys = new HashSet<>();
    private final ExecutorService mPrewarmExecutor = createPrewarmExecutor();

    private SftpProtocolManager() {
    }

    @NonNull
    public static SftpProtocolManager getInstance() {
        return INSTANCE;
    }

    @NonNull
    private static ExecutorService createPrewarmExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable,
                "sftp-prewarm-" + PREWARM_THREAD_COUNTER.getAndIncrement());
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            0,
            PREWARM_MAX_THREADS,
            20L,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            threadFactory
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @NonNull
    private static ExecutorService createTransferExecutor(@NonNull String prefix, int workerCount) {
        int safeWorkers = Math.max(1, workerCount);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(
                runnable,
                prefix + "-" + TRANSFER_THREAD_COUNTER.getAndIncrement()
            );
            thread.setPriority(Math.max(Thread.MIN_PRIORITY + 1, Thread.NORM_PRIORITY - 1));
            return thread;
        };
        return new ThreadPoolExecutor(
            safeWorkers,
            safeWorkers,
            20L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            threadFactory
        );
    }

    public boolean isVirtualPath(@NonNull Context context, @Nullable String path) {
        return resolveVirtualTarget(context, path) != null;
    }

    @NonNull
    public String getVirtualRoot(@NonNull Context context, @NonNull SessionEntry entry) {
        return FileRootResolver.resolveVirtualRoot(context, entry);
    }

    @NonNull
    public String getDisplayPath(@NonNull Context context, @Nullable String path) {
        VirtualTarget target = resolveVirtualTarget(context, path);
        if (target == null) return path == null ? "" : path;
        return "sftp://" + target.entry.displayName + target.remotePath;
    }

    @NonNull
    public VirtualPathInfo describeVirtualPath(@NonNull Context context, @Nullable String path) {
        VirtualTarget target = resolveVirtualTarget(context, path);
        if (target == null) {
            return VirtualPathInfo.fail("\u8def\u5f84\u4e0d\u662f\u6709\u6548\u7684 SFTP \u865a\u62df\u8def\u5f84\u3002");
        }
        return VirtualPathInfo.ok(
            target.virtualRoot,
            target.remotePath,
            target.entry.displayName,
            buildRemoteAuthorityLabel(target.entry)
        );
    }

    public void invalidateVirtualDirectoryCache(@NonNull Context context, @Nullable String path) {
        VirtualTarget target = resolveVirtualTarget(context, path);
        if (target == null) return;
        synchronized (mLock) {
            clearDirectoryCacheByClientKeyLocked(clientKeyForEntry(target.entry));
        }
    }

    @NonNull
    public LocalRealizationResult findReusableLocalForVirtualFile(@NonNull Context context, @Nullable String virtualFilePath) {
        VirtualTarget target = resolveVirtualTarget(context, virtualFilePath);
        if (target == null) {
            return LocalRealizationResult.fail("不是有效的 SFTP 文件路径。");
        }
        VirtualLocalFileRegistry.Entry entry = VirtualLocalFileRegistry.read(
            context,
            target.virtualRoot + ("/".equals(target.remotePath) ? "" : target.remotePath),
            VirtualLocalFileRegistry.KIND_DURABLE_DOWNLOAD
        );
        if (entry == null) {
            return LocalRealizationResult.missing("没有可复用的本地文件。");
        }
        File registeredLocal = new File(entry.localPath);
        if (!registeredLocal.exists() || !registeredLocal.isFile()) {
            VirtualLocalFileRegistry.clear(context, entry.virtualPath, entry.kind);
            return LocalRealizationResult.missing("本地文件已不存在，需要重新下载。");
        }

        try {
            return withReconnectRetry(context, target.entry, channel -> {
                SftpATTRS attrs = channel.stat(target.remotePath);
                if (attrs == null || attrs.isDir()) {
                    VirtualLocalFileRegistry.clear(context, entry.virtualPath, entry.kind);
                    return LocalRealizationResult.stale("远程文件不可用或已变为目录。");
                }
                long remoteModifiedMs = attrsModifiedMs(attrs);
                long remoteSize = Math.max(0L, attrs.getSize());
                if (!VirtualLocalFileRegistry.isReusable(entry, remoteModifiedMs, remoteSize)) {
                    return LocalRealizationResult.stale("远程文件已更新，需要重新下载。", remoteModifiedMs, remoteSize);
                }
                String remoteSha256 = computeRemoteSha256PreferNative(context, target.entry, channel, target.remotePath, null);
                String localSha256 = computeLocalSha256(registeredLocal, null);
                if (VirtualLocalFileRegistry.strongContentMatches(entry, remoteSha256, localSha256)) {
                    return LocalRealizationResult.reusable(
                        entry.localPath,
                        remoteModifiedMs,
                        remoteSize,
                        remoteSha256,
                        localSha256,
                        VirtualLocalFileRegistry.LEVEL_STRONG_CONTENT,
                        "remote-native-or-sftp-sha256"
                    );
                }
                if (TextUtils.equals(remoteSha256, localSha256)) {
                    VirtualLocalFileRegistry.register(
                        context,
                        entry.virtualPath,
                        entry.localPath,
                        entry.kind,
                        entry.authorityKey,
                        entry.remotePath,
                        remoteModifiedMs,
                        remoteSize,
                        remoteSha256,
                        "remote-native-or-sftp-sha256",
                        VirtualLocalFileRegistry.LEVEL_STRONG_CONTENT,
                        localSha256,
                        VirtualLocalFileRegistry.METHOD_LOCAL_SHA256,
                        VirtualLocalFileRegistry.LEVEL_STRONG_CONTENT
                    );
                    return LocalRealizationResult.reusable(
                        entry.localPath,
                        remoteModifiedMs,
                        remoteSize,
                        remoteSha256,
                        localSha256,
                        VirtualLocalFileRegistry.LEVEL_STRONG_CONTENT,
                        "remote-native-or-sftp-sha256"
                    );
                }
                return LocalRealizationResult.stale("远端或本地文件内容已变化，需要重新下载。", remoteModifiedMs, remoteSize);
            });
        } catch (Exception e) {
            return LocalRealizationResult.fail("检查本地文件状态失败：" + classifyExceptionMessage(e));
        }
    }

    @NonNull
    public LocalRealizationResult registerDownloadedLocalForVirtualFile(@NonNull Context context,
                                                                        @Nullable String virtualFilePath,
                                                                        @Nullable String localPath) {
        VirtualTarget target = resolveVirtualTarget(context, virtualFilePath);
        if (target == null) {
            return LocalRealizationResult.fail("不是有效的 SFTP 文件路径。");
        }
        if (TextUtils.isEmpty(localPath)) {
            return LocalRealizationResult.fail("本地文件路径为空。");
        }
        File localFile = new File(localPath);
        if (!localFile.exists() || !localFile.isFile()) {
            return LocalRealizationResult.stale("本地文件不存在，无法登记。");
        }

        try {
            return withReconnectRetry(context, target.entry, channel -> {
                SftpATTRS attrs = channel.stat(target.remotePath);
                if (attrs == null || attrs.isDir()) {
                    return LocalRealizationResult.fail("远程文件不可用，无法登记本地副本。");
                }
                long remoteModifiedMs = attrsModifiedMs(attrs);
                long remoteSize = Math.max(0L, attrs.getSize());
                StrongFingerprint fingerprint = computeStrongFingerprint(context, target.entry, channel, target.remotePath, localFile);
                VirtualLocalFileRegistry.Entry entry = VirtualLocalFileRegistry.register(
                    context,
                    target.virtualRoot + ("/".equals(target.remotePath) ? "" : target.remotePath),
                    localFile.getAbsolutePath(),
                    VirtualLocalFileRegistry.KIND_DURABLE_DOWNLOAD,
                    FileRootResolver.sessionPathKey(target.entry),
                    target.remotePath,
                    remoteModifiedMs,
                    remoteSize,
                    fingerprint.remoteSha256,
                    fingerprint.method,
                    fingerprint.level,
                    fingerprint.localSha256,
                    VirtualLocalFileRegistry.METHOD_LOCAL_SHA256,
                    fingerprint.level
                );
                return LocalRealizationResult.reusable(
                    entry.localPath,
                    remoteModifiedMs,
                    remoteSize,
                    fingerprint.remoteSha256,
                    fingerprint.localSha256,
                    fingerprint.level,
                    fingerprint.method
                );
            });
        } catch (Exception e) {
            return LocalRealizationResult.fail("登记本地文件失败：" + classifyExceptionMessage(e));
        }
    }

    @NonNull
    public RemoteCommandResult executeRemoteCommand(@NonNull Context context,
                                                    @Nullable String virtualAnchorPath,
                                                    @NonNull String shellCommand,
                                                    @Nullable RemoteCommandControl control) {
        VirtualTarget target = resolveVirtualTarget(context, virtualAnchorPath);
        if (target == null) {
            return RemoteCommandResult.fail("\u8fdc\u7a0b\u6267\u884c\u5931\u8d25\uff1a\u76ee\u6807\u4e0d\u662f\u6709\u6548\u7684 SFTP \u8def\u5f84\u3002");
        }
        if (TextUtils.isEmpty(shellCommand)) {
            return RemoteCommandResult.fail("\u8fdc\u7a0b\u6267\u884c\u5931\u8d25\uff1a\u547d\u4ee4\u4e3a\u7a7a\u3002");
        }

        try {
            RemoteCommandResult result = withExecReconnectRetry(context, target.entry, shellCommand, control);
            if (!result.success) {
                return result;
            }
            synchronized (mLock) {
                clearDirectoryCacheByClientKeyLocked(clientKeyForEntry(target.entry));
            }
            return result;
        } catch (OperationCanceledException e) {
            return RemoteCommandResult.cancelled();
        } catch (Exception e) {
            clearSessionByEntry(target.entry);
            return RemoteCommandResult.fail("\u8fdc\u7a0b\u6267\u884c\u5931\u8d25\uff1a" + classifyExceptionMessage(e));
        }
    }

    public void requestPrewarmSession(@NonNull Context context, @NonNull SessionEntry entry) {
        final Context appContext = context.getApplicationContext();
        final String clientKey = clientKeyForEntry(entry);
        synchronized (mLock) {
            if (mPrewarmingClientKeys.contains(clientKey)) return;
            mPrewarmingClientKeys.add(clientKey);
        }

        Runnable task = () -> {
            try {
                withReconnectRetry(appContext, entry, channel -> {
                    channel.stat("/");
                    return null;
                });
            } catch (Throwable ignored) {
            } finally {
                synchronized (mLock) {
                    mPrewarmingClientKeys.remove(clientKey);
                }
            }
        };

        try {
            mPrewarmExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            Thread fallback = new Thread(task, "sftp-prewarm-fallback");
            fallback.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            fallback.start();
        }
    }

    @NonNull
    public ProbeResult probeSession(@NonNull Context context, @NonNull SessionEntry entry) {
        try {
            SftpATTRS attrs = withReconnectRetry(context, entry, channel -> channel.stat("/"));
            if (attrs == null || !attrs.isDir()) {
                return ProbeResult.fail("\u0053\u0046\u0054\u0050\u0020\u8fde\u63a5\u5931\u8d25\uff1a\u8fdc\u7aef\u6839\u76ee\u5f55\u4e0d\u53ef\u8bbf\u95ee\u3002");
            }
            return ProbeResult.ok(FileRootResolver.resolveVirtualRoot(context, entry),
                "\u0053\u0046\u0054\u0050\u0020\u534f\u8bae\u8fde\u63a5\u6210\u529f\uff08\u65e0\u0020\u0046\u0055\u0053\u0045\u0020\u6a21\u5f0f\uff09\u3002");
        } catch (Exception e) {
            return ProbeResult.fail("\u0053\u0046\u0054\u0050\u0020\u534f\u8bae\u8fde\u63a5\u5931\u8d25\uff1a" + classifyExceptionMessage(e));
        }
    }

    @NonNull
    public ListResult listVirtualPath(@NonNull Context context, @Nullable String virtualPath) {
        VirtualTarget target = resolveVirtualTarget(context, virtualPath);
        if (target == null) {
            return ListResult.fail("\u8def\u5f84\u4e0d\u662f\u6709\u6548\u7684\u0020\u0053\u0046\u0054\u0050\u0020\u865a\u62df\u76ee\u5f55\u3002");
        }

        try {
            String cacheKey = directoryCacheKey(target.entry, target.remotePath);
            synchronized (mLock) {
                CachedDirectory cached = getValidDirectoryCacheLocked(cacheKey);
                if (cached != null) {
                    return ListResult.ok(copyRemoteEntries(cached.entries), cached.displayPath);
                }
            }

            Vector<?> rows = withReconnectRetry(context, target.entry, channel -> channel.ls(target.remotePath));
            ArrayList<RemoteEntry> entries = new ArrayList<>();
            for (Object row : rows) {
                if (!(row instanceof ChannelSftp.LsEntry)) continue;
                ChannelSftp.LsEntry item = (ChannelSftp.LsEntry) row;
                if (item == null) continue;
                String name = item.getFilename();
                if (TextUtils.isEmpty(name) || ".".equals(name) || "..".equals(name)) continue;
                SftpATTRS attrs = item.getAttrs();
                if (attrs == null) continue;

                String childRemote = joinRemotePath(target.remotePath, name);
                String childLocal = target.virtualRoot + ("/".equals(childRemote) ? "" : childRemote);
                entries.add(new RemoteEntry(
                    childLocal,
                    name,
                    attrs.isDir(),
                    attrs.getSize(),
                    ((long) attrs.getMTime()) * 1000L
                ));
            }

            String displayPath = getDisplayPath(context, target.virtualRoot + target.remotePath);
            synchronized (mLock) {
                mDirectoryCache.put(cacheKey, new CachedDirectory(
                    System.currentTimeMillis(),
                    copyRemoteEntries(entries),
                    displayPath
                ));
                trimDirectoryCacheLocked();
            }
            return ListResult.ok(entries, displayPath);
        } catch (Exception e) {
            clearSessionByEntry(target.entry);
            return ListResult.fail("\u8bfb\u53d6\u0020\u0053\u0046\u0054\u0050\u0020\u76ee\u5f55\u5931\u8d25\uff1a" + classifyExceptionMessage(e));
        }
    }

    @NonNull
    public MaterializeResult materializeFile(@NonNull Context context, @Nullable String virtualFilePath) {
        VirtualTarget target = resolveVirtualTarget(context, virtualFilePath);
        if (target == null) {
            return MaterializeResult.fail("\u4e0d\u662f\u6709\u6548\u7684\u0020\u0053\u0046\u0054\u0050\u0020\u6587\u4ef6\u8def\u5f84\u3002");
        }

        try {
            File cacheRoot = new File(FileRootResolver.resolveCacheRoot(context, target.entry));
            String relative = target.remotePath.startsWith("/") ? target.remotePath.substring(1) : target.remotePath;
            File targetFile = new File(cacheRoot, relative);
            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return MaterializeResult.fail("\u4e0b\u8f7d\u5931\u8d25\uff1a\u65e0\u6cd5\u521b\u5efa\u672c\u5730\u7f13\u5b58\u76ee\u5f55\u3002");
            }

            MaterializeResult materialized = withReconnectRetry(context, target.entry, channel -> {
                SftpATTRS attrs = channel.stat(target.remotePath);
                if (attrs == null) {
                    throw new IllegalStateException("\u8fdc\u7aef\u6587\u4ef6\u4e0d\u5b58\u5728\u3002");
                }
                if (attrs.isDir()) {
                    throw new IllegalStateException("\u5f53\u524d\u8def\u5f84\u662f\u76ee\u5f55\uff0c\u65e0\u6cd5\u76f4\u63a5\u6253\u5f00\u4e3a\u6587\u4ef6\u3002");
                }

                long remoteModifiedMs = attrsModifiedMs(attrs);
                long remoteSize = Math.max(0L, attrs.getSize());
                String virtualPath = target.virtualRoot + ("/".equals(target.remotePath) ? "" : target.remotePath);
                VirtualLocalFileRegistry.Entry cached = VirtualLocalFileRegistry.read(
                    context,
                    virtualPath,
                    VirtualLocalFileRegistry.KIND_MATERIALIZED_CACHE
                );
                if (VirtualLocalFileRegistry.isReusable(cached, remoteModifiedMs, remoteSize)) {
                    File cachedFile = new File(cached.localPath);
                    if (cachedFile.exists() && cachedFile.isFile()) {
                        String remoteSha256 = computeRemoteSha256PreferNative(context, target.entry, channel, target.remotePath, null);
                        String localSha256 = computeLocalSha256(cachedFile, null);
                        if (VirtualLocalFileRegistry.strongContentMatches(cached, remoteSha256, localSha256)) {
                            return MaterializeResult.ok(
                                cached.localPath,
                                remoteModifiedMs,
                                remoteSize,
                                remoteSha256,
                                localSha256,
                                true
                            );
                        }
                        if (TextUtils.equals(remoteSha256, localSha256)) {
                            VirtualLocalFileRegistry.register(
                                context,
                                virtualPath,
                                cached.localPath,
                                VirtualLocalFileRegistry.KIND_MATERIALIZED_CACHE,
                                FileRootResolver.sessionPathKey(target.entry),
                                target.remotePath,
                                remoteModifiedMs,
                                remoteSize,
                                remoteSha256,
                                "remote-native-or-sftp-sha256",
                                VirtualLocalFileRegistry.LEVEL_STRONG_CONTENT,
                                localSha256,
                                VirtualLocalFileRegistry.METHOD_LOCAL_SHA256,
                                VirtualLocalFileRegistry.LEVEL_STRONG_CONTENT
                            );
                            return MaterializeResult.ok(
                                cached.localPath,
                                remoteModifiedMs,
                                remoteSize,
                                remoteSha256,
                                localSha256,
                                true
                            );
                        }
                    }
                }

                File tempFile = new File(targetFile.getAbsolutePath() + ".download-" + System.currentTimeMillis() + ".tmp");
                try (OutputStream outputStream = new FileOutputStream(tempFile, false)) {
                    channel.get(target.remotePath, outputStream);
                }
                if (targetFile.exists() && !targetFile.delete()) {
                    throw new IllegalStateException("\u65e0\u6cd5\u66ff\u6362\u65e7\u7684\u672c\u5730\u7f13\u5b58\u6587\u4ef6\u3002");
                }
                if (!tempFile.renameTo(targetFile)) {
                    try (InputStream inputStream = new FileInputStream(tempFile);
                         OutputStream outputStream = new FileOutputStream(targetFile, false)) {
                        byte[] buffer = new byte[32 * 1024];
                        int read;
                        while ((read = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, read);
                        }
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        tempFile.delete();
                    }
                }
                StrongFingerprint fingerprint = computeStrongFingerprint(context, target.entry, channel, target.remotePath, targetFile);
                VirtualLocalFileRegistry.register(
                    context,
                    virtualPath,
                    targetFile.getAbsolutePath(),
                    VirtualLocalFileRegistry.KIND_MATERIALIZED_CACHE,
                    FileRootResolver.sessionPathKey(target.entry),
                    target.remotePath,
                    remoteModifiedMs,
                    remoteSize,
                    fingerprint.remoteSha256,
                    fingerprint.method,
                    fingerprint.level,
                    fingerprint.localSha256,
                    VirtualLocalFileRegistry.METHOD_LOCAL_SHA256,
                    fingerprint.level
                );
                return MaterializeResult.ok(
                    targetFile.getAbsolutePath(),
                    remoteModifiedMs,
                    remoteSize,
                    fingerprint.remoteSha256,
                    fingerprint.localSha256,
                    false
                );
            });
            if (materialized == null || TextUtils.isEmpty(materialized.localPath)) {
                return MaterializeResult.fail("\u4e0b\u8f7d\u5931\u8d25\uff1a\u672a\u77e5\u9519\u8bef\u3002");
            }
            return materialized;
        } catch (Exception e) {
            clearSessionByEntry(target.entry);
            return MaterializeResult.fail("\u4e0b\u8f7d\u5931\u8d25\uff1a" + classifyExceptionMessage(e));
        }
    }

    @NonNull
    public CreateResult createVirtualItem(@NonNull Context context,
                                          @Nullable String virtualDirectoryPath,
                                          @Nullable String rawName,
                                          boolean directory) {
        VirtualTarget target = resolveVirtualTarget(context, virtualDirectoryPath);
        if (target == null) {
            return CreateResult.fail("\u521b\u5efa\u5931\u8d25\uff1a\u76ee\u6807\u4e0d\u662f\u6709\u6548\u7684\u0020\u0053\u0046\u0054\u0050\u0020\u76ee\u5f55\u3002");
        }

        String name = rawName == null ? "" : rawName.trim();
        if (TextUtils.isEmpty(name)
            || ".".equals(name)
            || "..".equals(name)
            || name.contains("/")
            || name.contains("\\")) {
            return CreateResult.fail("\u521b\u5efa\u5931\u8d25\uff1a\u65e0\u6548\u7684\u540d\u79f0\u3002");
        }

        String remotePath = joinRemotePath(target.remotePath, name);
        try {
            final String finalRemotePath = remotePath;
            withReconnectRetry(context, target.entry, channel -> {
                SftpATTRS parentAttrs = channel.stat(target.remotePath);
                if (parentAttrs == null || !parentAttrs.isDir()) {
                    throw new IllegalStateException("\u8fdc\u7a0b\u76ee\u6807\u76ee\u5f55\u4e0d\u5b58\u5728\u6216\u4e0d\u53ef\u7528\u3002");
                }

                try {
                    SftpATTRS existing = channel.stat(finalRemotePath);
                    if (existing != null) {
                        throw new IllegalStateException("\u5df2\u5b58\u5728\u540c\u540d\u6587\u4ef6\u6216\u76ee\u5f55\u3002");
                    }
                } catch (SftpException e) {
                    if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                        throw e;
                    }
                }

                if (directory) {
                    channel.mkdir(finalRemotePath);
                } else {
                    ensureRemoteDirectoryExists(channel, parentRemotePath(finalRemotePath));
                    try (OutputStream outputStream = channel.put(finalRemotePath, ChannelSftp.OVERWRITE)) {
                        outputStream.flush();
                    }
                }
                return null;
            });

            synchronized (mLock) {
                clearDirectoryCacheByClientKeyLocked(clientKeyForEntry(target.entry));
            }

            String localPath = target.virtualRoot + ("/".equals(remotePath) ? "" : remotePath);
            return CreateResult.ok(localPath);
        } catch (Exception e) {
            clearSessionByEntry(target.entry);
            return CreateResult.fail("\u521b\u5efa\u5931\u8d25\uff1a" + classifyExceptionMessage(e));
        }
    }

    @NonNull
    public DeleteResult deleteVirtualPath(@NonNull Context context, @Nullable String virtualPath) {
        VirtualTarget target = resolveVirtualTarget(context, virtualPath);
        if (target == null) {
            return DeleteResult.fail("\u5220\u9664\u5931\u8d25\uff1a\u76ee\u6807\u4e0d\u662f\u6709\u6548\u7684 SFTP \u8def\u5f84\u3002");
        }
        if ("/".equals(target.remotePath)) {
            return DeleteResult.fail("\u5220\u9664\u5931\u8d25\uff1a\u4e0d\u5141\u8bb8\u5220\u9664\u8fdc\u7a0b\u6839\u76ee\u5f55\u3002");
        }

        try {
            withReconnectRetry(context, target.entry, channel -> {
                deleteRemotePathRecursive(channel, target.remotePath);
                return null;
            });

            synchronized (mLock) {
                clearDirectoryCacheByClientKeyLocked(clientKeyForEntry(target.entry));
            }
            cleanupVirtualArtifacts(context, target);
            String localPath = target.virtualRoot + ("/".equals(target.remotePath) ? "" : target.remotePath);
            return DeleteResult.ok(localPath);
        } catch (Exception e) {
            clearSessionByEntry(target.entry);
            return DeleteResult.fail("\u5220\u9664\u5931\u8d25\uff1a" + classifyExceptionMessage(e));
        }
    }

    @NonNull
    public RemoteDeleteResult deleteVirtualPaths(@NonNull Context context,
                                                 @NonNull List<String> virtualPaths,
                                                 @Nullable RemoteDeleteProgressListener listener,
                                                 @Nullable RemoteDeleteControl control) {
        long startedAt = System.currentTimeMillis();
        int totalItems = virtualPaths.size();
        ArrayList<RemoteDeleteItemResult> itemResults = new ArrayList<>();
        LinkedHashMap<String, ArrayList<VirtualTarget>> groups = new LinkedHashMap<>();

        emitRemoteDeleteProgress(listener, "planning", "\u6b63\u5728\u89c4\u5212", totalItems, itemResults, "", "", startedAt, "\u6b63\u5728\u89e3\u6790\u8fdc\u7aef\u8def\u5f84...");
        for (String virtualPath : virtualPaths) {
            if (isRemoteDeleteCancelled(control)) {
                return RemoteDeleteResult.fromItems(totalItems, true, System.currentTimeMillis() - startedAt, itemResults);
            }
            VirtualTarget target = resolveVirtualTarget(context, virtualPath);
            if (target == null) {
                itemResults.add(RemoteDeleteItemResult.fail(
                    virtualPath == null ? "" : virtualPath,
                    "",
                    displayNameForPath(virtualPath),
                    false,
                    false,
                    false,
                    "\u76ee\u6807\u4e0d\u662f\u6709\u6548\u7684 SFTP \u8def\u5f84"
                ));
                continue;
            }
            String clientKey = clientKeyForEntry(target.entry);
            ArrayList<VirtualTarget> group = groups.get(clientKey);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(clientKey, group);
            }
            group.add(target);
        }

        try {
            for (ArrayList<VirtualTarget> group : groups.values()) {
                if (group == null || group.isEmpty()) continue;
                if (isRemoteDeleteCancelled(control)) {
                    return RemoteDeleteResult.fromItems(totalItems, true, System.currentTimeMillis() - startedAt, itemResults);
                }
                deleteVirtualTargetGroup(context, group, totalItems, itemResults, listener, control, startedAt);
            }
        } catch (OperationCanceledException e) {
            return RemoteDeleteResult.fromItems(totalItems, true, System.currentTimeMillis() - startedAt, itemResults);
        }

        return RemoteDeleteResult.fromItems(totalItems, false, System.currentTimeMillis() - startedAt, itemResults);
    }

    private void deleteVirtualTargetGroup(@NonNull Context context,
                                          @NonNull ArrayList<VirtualTarget> targets,
                                          int totalItems,
                                          @NonNull ArrayList<RemoteDeleteItemResult> itemResults,
                                          @Nullable RemoteDeleteProgressListener listener,
                                          @Nullable RemoteDeleteControl control,
                                          long startedAt) {
        SessionEntry entry = targets.get(0).entry;
        ArrayList<RemoteDeletePlanner.ResolvedDeleteTarget> existingTargets = new ArrayList<>();
        Map<String, VirtualTarget> targetByRemotePath = new HashMap<>();

        for (VirtualTarget target : targets) {
            if (isRemoteDeleteCancelled(control)) {
                throw new OperationCanceledException();
            }
            String virtualPath = target.virtualRoot + ("/".equals(target.remotePath) ? "" : target.remotePath);
            String displayName = topLevelNameForTarget(target);
            String normalized = RemoteDeletePlanner.normalizeStrict(target.remotePath);
            String rejectReason = RemoteDeletePlanner.rejectionReason(normalized, target.remotePath);
            if (!TextUtils.isEmpty(rejectReason)) {
                itemResults.add(RemoteDeleteItemResult.fail(virtualPath, normalized, displayName, false, false, false, rejectReason));
                continue;
            }
            emitRemoteDeleteProgress(listener, "planning", "正在规划", totalItems, itemResults,
                virtualPath, target.remotePath, startedAt, "正在检查 " + displayName);
            try {
                SftpATTRS attrs = withReconnectRetry(context, entry, channel -> channel.stat(target.remotePath));
                boolean directory = attrs != null && attrs.isDir();
                existingTargets.add(new RemoteDeletePlanner.ResolvedDeleteTarget(virtualPath, target.remotePath, displayName, directory));
                targetByRemotePath.put(target.remotePath, target);
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    itemResults.add(RemoteDeleteItemResult.success(virtualPath, target.remotePath, displayName, false, true, false, false, "远端路径已不存在"));
                } else {
                    itemResults.add(RemoteDeleteItemResult.fail(virtualPath, target.remotePath, displayName, false, false, false,
                        "检查远端路径失败：" + classifyExceptionMessage(e)));
                }
            } catch (Exception e) {
                clearSessionByEntry(entry);
                itemResults.add(RemoteDeleteItemResult.fail(virtualPath, target.remotePath, displayName, false, false, false,
                    "检查远端路径失败：" + classifyExceptionMessage(e)));
            }
        }

        RemoteDeletePlanner.DeletePlan plan = RemoteDeletePlanner.build(existingTargets);
        for (RemoteDeletePlanner.DeletePlanItem item : plan.rejectedItems) {
            itemResults.add(RemoteDeleteItemResult.fail(item.virtualPath, item.remotePath, item.displayName, item.directory, false, false, item.messageCn));
        }
        for (RemoteDeletePlanner.DeletePlanItem item : plan.skippedItems) {
            itemResults.add(RemoteDeleteItemResult.skipped(item.virtualPath, item.remotePath, item.displayName, item.directory, item.messageCn));
        }
        if (plan.executableItems.isEmpty()) {
            return;
        }

        if (plan.preferExec) {
            deletePlanPreferExec(context, entry, plan.executableItems, targetByRemotePath, totalItems, itemResults, listener, control, startedAt);
        } else {
            deletePlanViaSftp(context, entry, plan.executableItems, targetByRemotePath, totalItems, itemResults, listener, control, startedAt, "deleting", "正在删除");
        }
    }

    private void deletePlanPreferExec(@NonNull Context context,
                                      @NonNull SessionEntry entry,
                                      @NonNull ArrayList<RemoteDeletePlanner.DeletePlanItem> items,
                                      @NonNull Map<String, VirtualTarget> targetByRemotePath,
                                      int totalItems,
                                      @NonNull ArrayList<RemoteDeleteItemResult> itemResults,
                                      @Nullable RemoteDeleteProgressListener listener,
                                      @Nullable RemoteDeleteControl control,
                                      long startedAt) {
        boolean execFailed = false;
        ArrayList<String> commands = new ArrayList<>();
        commands.addAll(RemoteDeletePlanner.buildRmCommands(items, false));
        for (String command : commands) {
            if (isRemoteDeleteCancelled(control)) {
                throw new OperationCanceledException();
            }
            emitRemoteDeleteProgress(listener, "deleting", "正在删除", totalItems, itemResults,
                "", "", startedAt, "正在通过远端原生命令删除文件...");
            try {
                RemoteCommandResult result = withExecReconnectRetry(context, entry, command, control == null ? null : control::isCancelled);
                if (!result.success) {
                    execFailed = true;
                    break;
                }
            } catch (OperationCanceledException e) {
                throw e;
            } catch (Exception e) {
                execFailed = true;
                clearSessionByEntry(entry);
                break;
            }
        }

        for (RemoteDeletePlanner.DeletePlanItem item : items) {
            if (!item.directory) continue;
            if (isRemoteDeleteCancelled(control)) {
                throw new OperationCanceledException();
            }
            emitRemoteDeleteProgress(listener, "deleting", "正在删除", totalItems, itemResults,
                item.virtualPath, item.remotePath, startedAt, "正在实时删除目录：" + item.displayName);
            try {
                RemoteCommandResult result = deleteDirectoryViaFindWithProgress(context, entry, item, totalItems, itemResults, listener, control, startedAt);
                if (!result.success) {
                    execFailed = true;
                }
            } catch (OperationCanceledException e) {
                throw e;
            } catch (Exception e) {
                execFailed = true;
                clearSessionByEntry(entry);
            }
        }

        ArrayList<RemoteDeletePlanner.DeletePlanItem> remaining = new ArrayList<>();
        for (RemoteDeletePlanner.DeletePlanItem item : items) {
            if (isRemoteDeleteCancelled(control)) {
                throw new OperationCanceledException();
            }
            emitRemoteDeleteProgress(listener, "verifying", "正在校验", totalItems, itemResults,
                item.virtualPath, item.remotePath, startedAt, "正在确认删除结果：" + item.displayName);
            try {
                boolean missing = withReconnectRetry(context, entry, channel -> !remotePathExists(channel, item.remotePath));
                if (missing) {
                    markRemoteDeleteSuccess(context, item, targetByRemotePath, itemResults, true, false, "删除完成");
                } else {
                    remaining.add(item);
                }
            } catch (Exception e) {
                remaining.add(item);
            }
        }

        if (execFailed || !remaining.isEmpty()) {
            deletePlanViaSftp(context, entry, remaining, targetByRemotePath, totalItems, itemResults, listener, control, startedAt, "fallback", "正在回退");
        }
    }

    @NonNull
    private RemoteCommandResult deleteDirectoryViaFindWithProgress(@NonNull Context context,
                                                                   @NonNull SessionEntry entry,
                                                                   @NonNull RemoteDeletePlanner.DeletePlanItem item,
                                                                   int totalItems,
                                                                   @NonNull ArrayList<RemoteDeleteItemResult> itemResults,
                                                                   @Nullable RemoteDeleteProgressListener listener,
                                                                   @Nullable RemoteDeleteControl control,
                                                                   long startedAt) throws Exception {
        String quotedPath = RemoteDeletePlanner.shellQuoteSingle(item.remotePath);
        String command = "p=" + quotedPath + "; "
            + "printf '__FM_DELETE_TOTAL:0\\n'; "
            + "find \"$p\" -depth -print 2>/dev/null | ("
            + "n=0; "
            + "while IFS= read -r x; do "
            + "rm -rf -- \"$x\" || exit 23; "
            + "n=$((n+1)); "
            + "if [ $((n % 25)) -eq 0 ]; then printf '__FM_DELETE_PROGRESS:%s/0\\n' \"$n\"; fi; "
            + "done; "
            + "printf '__FM_DELETE_PROGRESS:%s/0\\n' \"$n\"; "
            + "exit 0)";
        final long[] entryTotal = new long[] {0L};
        final long[] entryDone = new long[] {0L};
        return withExecStreamingReconnectRetry(context, entry, command, control == null ? null : control::isCancelled, line -> {
            if (line.startsWith("__FM_DELETE_TOTAL:")) {
                entryTotal[0] = parseLongSafely(line.substring("__FM_DELETE_TOTAL:".length()), 0L);
                emitRemoteDeleteProgress(listener, "deleting", "正在删除", totalItems, itemResults,
                    item.virtualPath, item.remotePath, startedAt,
                    "正在扫描目录：" + item.displayName + "，共 " + entryTotal[0] + " 项",
                    entryDone[0], entryTotal[0]);
            } else if (line.startsWith("__FM_DELETE_PROGRESS:")) {
                String raw = line.substring("__FM_DELETE_PROGRESS:".length());
                int slash = raw.indexOf('/');
                long done = slash >= 0 ? parseLongSafely(raw.substring(0, slash), entryDone[0]) : parseLongSafely(raw, entryDone[0]);
                long total = slash >= 0 ? parseLongSafely(raw.substring(slash + 1), entryTotal[0]) : entryTotal[0];
                entryDone[0] = Math.max(entryDone[0], done);
                entryTotal[0] = Math.max(entryTotal[0], total);
                String countText = entryTotal[0] > 0L ? entryDone[0] + "/" + entryTotal[0] : entryDone[0] + " 项";
                emitRemoteDeleteProgress(listener, "deleting", "正在删除", totalItems, itemResults,
                    item.virtualPath, item.remotePath, startedAt,
                    "正在删除目录条目：" + countText + "（" + item.displayName + "）",
                    entryDone[0], entryTotal[0]);
            }
        });
    }

    private void deletePlanViaSftp(@NonNull Context context,
                                   @NonNull SessionEntry entry,
                                   @NonNull ArrayList<RemoteDeletePlanner.DeletePlanItem> items,
                                   @NonNull Map<String, VirtualTarget> targetByRemotePath,
                                   int totalItems,
                                   @NonNull ArrayList<RemoteDeleteItemResult> itemResults,
                                   @Nullable RemoteDeleteProgressListener listener,
                                   @Nullable RemoteDeleteControl control,
                                   long startedAt,
                                   @NonNull String stage,
                                   @NonNull String stageLabelCn) {
        for (RemoteDeletePlanner.DeletePlanItem item : items) {
            if (isRemoteDeleteCancelled(control)) {
                throw new OperationCanceledException();
            }
            emitRemoteDeleteProgress(listener, stage, stageLabelCn, totalItems, itemResults,
                item.virtualPath, item.remotePath, startedAt, stageLabelCn + "：" + item.displayName);
            try {
                withReconnectRetry(context, entry, channel -> {
                    deleteRemotePathRecursive(channel, item.remotePath, control);
                    return null;
                });
                boolean missing = withReconnectRetry(context, entry, channel -> !remotePathExists(channel, item.remotePath));
                if (missing) {
                    markRemoteDeleteSuccess(context, item, targetByRemotePath, itemResults, false, true, "删除完成");
                } else {
                    itemResults.add(RemoteDeleteItemResult.fail(item.virtualPath, item.remotePath, item.displayName,
                        item.directory, false, true, "删除后校验失败：远端路径仍存在"));
                }
            } catch (OperationCanceledException e) {
                throw e;
            } catch (Exception e) {
                clearSessionByEntry(entry);
                itemResults.add(RemoteDeleteItemResult.fail(item.virtualPath, item.remotePath, item.displayName,
                    item.directory, false, true, "删除失败：" + classifyExceptionMessage(e)));
            }
        }
    }

    private void markRemoteDeleteSuccess(@NonNull Context context,
                                         @NonNull RemoteDeletePlanner.DeletePlanItem item,
                                         @NonNull Map<String, VirtualTarget> targetByRemotePath,
                                         @NonNull ArrayList<RemoteDeleteItemResult> itemResults,
                                         boolean usedExec,
                                         boolean usedSftpFallback,
                                         @NonNull String messageCn) {
        VirtualTarget target = targetByRemotePath.get(item.remotePath);
        if (target != null) {
            synchronized (mLock) {
                clearDirectoryCacheByClientKeyLocked(clientKeyForEntry(target.entry));
            }
            cleanupVirtualArtifacts(context, target);
        }
        itemResults.add(RemoteDeleteItemResult.success(item.virtualPath, item.remotePath, item.displayName,
            item.directory, false, usedExec, usedSftpFallback, messageCn));
    }

    private static void emitRemoteDeleteProgress(@Nullable RemoteDeleteProgressListener listener,
                                                 @NonNull String stage,
                                                 @NonNull String stageLabelCn,
                                                 int totalItems,
                                                 @NonNull ArrayList<RemoteDeleteItemResult> itemResults,
                                                 @Nullable String currentVirtualPath,
                                                 @Nullable String currentRemotePath,
                                                 long startedAt,
                                                 @NonNull String messageCn) {
        emitRemoteDeleteProgress(listener, stage, stageLabelCn, totalItems, itemResults,
            currentVirtualPath, currentRemotePath, startedAt, messageCn, 0L, 0L);
    }

    private static void emitRemoteDeleteProgress(@Nullable RemoteDeleteProgressListener listener,
                                                 @NonNull String stage,
                                                 @NonNull String stageLabelCn,
                                                 int totalItems,
                                                 @NonNull ArrayList<RemoteDeleteItemResult> itemResults,
                                                 @Nullable String currentVirtualPath,
                                                 @Nullable String currentRemotePath,
                                                 long startedAt,
                                                 @NonNull String messageCn,
                                                 long currentEntryDone,
                                                 long currentEntryTotal) {
        if (listener == null) return;
        int success = 0;
        int failed = 0;
        int skipped = 0;
        for (RemoteDeleteItemResult item : itemResults) {
            if (item == null) continue;
            if (item.skipped) skipped++;
            else if (item.success) success++;
            else failed++;
        }
        listener.onProgress(new RemoteDeleteProgress(
            stage,
            stageLabelCn,
            Math.max(0, totalItems),
            success + failed + skipped,
            success,
            failed,
            skipped,
            currentVirtualPath == null ? "" : currentVirtualPath,
            currentRemotePath == null ? "" : currentRemotePath,
            displayNameForPath(TextUtils.isEmpty(currentRemotePath) ? currentVirtualPath : currentRemotePath),
            Math.max(0L, System.currentTimeMillis() - startedAt),
            messageCn,
            Math.max(0L, currentEntryDone),
            Math.max(0L, currentEntryTotal)
        ));
    }

    private static long parseLongSafely(@Nullable String raw, long fallback) {
        if (TextUtils.isEmpty(raw)) return fallback;
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @NonNull
    private static String displayNameForPath(@Nullable String path) {
        if (TextUtils.isEmpty(path)) return "";
        String value = path.trim();
        int slash = value.lastIndexOf('/');
        String name = slash >= 0 ? value.substring(slash + 1) : value;
        return TextUtils.isEmpty(name) ? value : name;
    }

    @NonNull
    public RenameResult renameVirtualPath(@NonNull Context context,
                                          @Nullable String virtualPath,
                                          @Nullable String newNameRaw) {
        VirtualTarget target = resolveVirtualTarget(context, virtualPath);
        if (target == null) {
            return RenameResult.fail("\u91cd\u547d\u540d\u5931\u8d25\uff1a\u76ee\u6807\u4e0d\u662f\u6709\u6548\u7684 SFTP \u8def\u5f84\u3002");
        }
        if ("/".equals(target.remotePath)) {
            return RenameResult.fail("\u91cd\u547d\u540d\u5931\u8d25\uff1a\u4e0d\u5141\u8bb8\u91cd\u547d\u540d\u8fdc\u7a0b\u6839\u76ee\u5f55\u3002");
        }

        String newName = newNameRaw == null ? "" : newNameRaw.trim();
        if (TextUtils.isEmpty(newName)
            || ".".equals(newName)
            || "..".equals(newName)
            || newName.contains("/")
            || newName.contains("\\")) {
            return RenameResult.fail("\u91cd\u547d\u540d\u5931\u8d25\uff1a\u65e0\u6548\u7684\u540d\u79f0\u3002");
        }

        String sourceRemotePath = normalizeRemotePath(target.remotePath);
        String renamedRemotePath = joinRemotePath(parentRemotePath(sourceRemotePath), newName);
        if (sourceRemotePath.equals(renamedRemotePath)) {
            return RenameResult.fail("\u91cd\u547d\u540d\u5931\u8d25\uff1a\u65b0\u540d\u79f0\u4e0e\u539f\u540d\u79f0\u76f8\u540c\u3002");
        }

        try {
            final String finalRenamedRemotePath = renamedRemotePath;
            withReconnectRetry(context, target.entry, channel -> {
                SftpATTRS sourceAttrs = channel.stat(sourceRemotePath);
                if (sourceAttrs == null) {
                    throw new IllegalStateException("\u8fdc\u7a0b\u6e90\u6587\u4ef6\u4e0d\u5b58\u5728\u3002");
                }
                try {
                    SftpATTRS existing = channel.stat(finalRenamedRemotePath);
                    if (existing != null) {
                        throw new IllegalStateException("\u5df2\u5b58\u5728\u540c\u540d\u6587\u4ef6\u6216\u76ee\u5f55\u3002");
                    }
                } catch (SftpException e) {
                    if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                        throw e;
                    }
                }
                channel.rename(sourceRemotePath, finalRenamedRemotePath);
                return null;
            });
            synchronized (mLock) {
                clearDirectoryCacheByClientKeyLocked(clientKeyForEntry(target.entry));
            }
            cleanupVirtualArtifacts(context, target);
            cleanupVirtualArtifacts(context, target.entry, target.virtualRoot, renamedRemotePath);
            String localPath = target.virtualRoot + ("/".equals(renamedRemotePath) ? "" : renamedRemotePath);
            return RenameResult.ok(localPath);
        } catch (Exception e) {
            clearSessionByEntry(target.entry);
            return RenameResult.fail("\u91cd\u547d\u540d\u5931\u8d25\uff1a" + classifyExceptionMessage(e));
        }
    }

    @NonNull
    public MoveResult moveVirtualPaths(@NonNull Context context,
                                       @NonNull List<String> sourceVirtualPaths,
                                       @Nullable String destinationVirtualDir) {
        if (sourceVirtualPaths.isEmpty()) {
            return MoveResult.fail("\u79fb\u52a8\u5931\u8d25\uff1a\u672a\u9009\u62e9\u9700\u8981\u79fb\u52a8\u7684\u9879\u76ee\u3002");
        }

        VirtualTarget destination = resolveVirtualTarget(context, destinationVirtualDir);
        if (destination == null) {
            return MoveResult.fail("\u79fb\u52a8\u5931\u8d25\uff1a\u76ee\u6807\u4e0d\u662f\u6709\u6548\u7684 SFTP \u76ee\u5f55\u3002");
        }

        final ArrayList<VirtualTarget> sources = new ArrayList<>();
        for (String sourceVirtualPath : sourceVirtualPaths) {
            VirtualTarget source = resolveVirtualTarget(context, sourceVirtualPath);
            if (source == null) {
                return MoveResult.fail("\u79fb\u52a8\u5931\u8d25\uff1a\u9009\u4e2d\u9879\u76ee\u4e2d\u5305\u542b\u65e0\u6548\u7684 SFTP \u8def\u5f84\u3002");
            }
            if (!source.virtualRoot.equals(destination.virtualRoot)) {
                return MoveResult.fail("\u79fb\u52a8\u5931\u8d25\uff1a\u6682\u4ec5\u652f\u6301\u5728\u540c\u4e00\u670d\u52a1\u5668\u4f1a\u8bdd\u5185\u79fb\u52a8\u3002");
            }
            if ("/".equals(source.remotePath)) {
                return MoveResult.fail("\u79fb\u52a8\u5931\u8d25\uff1a\u4e0d\u5141\u8bb8\u79fb\u52a8\u8fdc\u7a0b\u6839\u76ee\u5f55\u3002");
            }
            sources.add(source);
        }

        final ArrayList<String> movedVirtualPaths = new ArrayList<>(sources.size());
        try {
            withReconnectRetry(context, destination.entry, channel -> {
                SftpATTRS destinationAttrs = channel.stat(destination.remotePath);
                if (destinationAttrs == null || !destinationAttrs.isDir()) {
                    throw new IllegalStateException("\u8fdc\u7a0b\u76ee\u6807\u76ee\u5f55\u4e0d\u5b58\u5728\u6216\u4e0d\u53ef\u7528\u3002");
                }
                LinkedHashSet<String> reservedDestinations = new LinkedHashSet<>();
                for (VirtualTarget source : sources) {
                    String normalizedSource = normalizeRemotePath(source.remotePath);
                    String normalizedDestinationDir = normalizeRemotePath(destination.remotePath);
                    String desiredDestination = joinRemotePath(
                        normalizedDestinationDir,
                        normalizedSource.substring(normalizedSource.lastIndexOf('/') + 1)
                    );

                    if (normalizedDestinationDir.equals(parentRemotePath(normalizedSource))) {
                        movedVirtualPaths.add(source.virtualRoot + normalizedSource);
                        continue;
                    }
                    if (normalizedDestinationDir.equals(normalizedSource)
                        || normalizedDestinationDir.startsWith(normalizedSource + "/")) {
                        throw new IllegalStateException("\u79fb\u52a8\u5931\u8d25\uff1a\u4e0d\u80fd\u5c06\u6587\u4ef6\u5939\u79fb\u52a8\u5230\u81ea\u8eab\u6216\u5b50\u76ee\u5f55\u4e0b\u3002");
                    }

                    String resolvedDestination = resolveUniqueRemotePath(channel, desiredDestination, reservedDestinations);
                    channel.rename(normalizedSource, resolvedDestination);
                    movedVirtualPaths.add(source.virtualRoot + resolvedDestination);
                }
                return null;
            });
            synchronized (mLock) {
                clearDirectoryCacheByClientKeyLocked(clientKeyForEntry(destination.entry));
            }
            for (VirtualTarget source : sources) {
                cleanupVirtualArtifacts(context, source);
            }
            return MoveResult.ok(movedVirtualPaths);
        } catch (Exception e) {
            clearSessionByEntry(destination.entry);
            return MoveResult.fail("\u79fb\u52a8\u5931\u8d25\uff1a" + classifyExceptionMessage(e));
        }
    }

    @NonNull
    public DownloadResult downloadVirtualPaths(@NonNull Context context,
                                               @NonNull List<String> virtualPaths,
                                               @NonNull String destinationDir,
                                               @Nullable DownloadProgressListener listener,
                                               @Nullable DownloadControl control) {
        return downloadVirtualPathsInternal(
            context,
            virtualPaths,
            destinationDir,
            listener,
            control,
            false
        );
    }

    @NonNull
    DownloadResult resumeDownloadVirtualPaths(@NonNull Context context,
                                              @NonNull List<String> virtualPaths,
                                              @NonNull String destinationDir,
                                              @Nullable DownloadProgressListener listener,
                                              @Nullable DownloadControl control) {
        return downloadVirtualPathsInternal(
            context,
            virtualPaths,
            destinationDir,
            listener,
            control,
            true
        );
    }

    @NonNull
    private DownloadResult downloadVirtualPathsInternal(@NonNull Context context,
                                                        @NonNull List<String> virtualPaths,
                                                        @NonNull String destinationDir,
                                                        @Nullable DownloadProgressListener listener,
                                                        @Nullable DownloadControl control,
                                                        boolean resumeExistingOutputs) {
        if (virtualPaths.isEmpty()) {
            return DownloadResult.fail("\u672a\u9009\u62e9\u9700\u8981\u4e0b\u8f7d\u7684\u6587\u4ef6\u3002");
        }

        File destinationRoot = new File(destinationDir);
        if (destinationRoot.exists() && !destinationRoot.isDirectory()) {
            return DownloadResult.fail("\u4e0b\u8f7d\u5931\u8d25\uff1a\u76ee\u6807\u8def\u5f84\u4e0d\u662f\u76ee\u5f55\u3002");
        }
        if (!destinationRoot.exists() && !destinationRoot.mkdirs()) {
            return DownloadResult.fail("\u4e0b\u8f7d\u5931\u8d25\uff1a\u65e0\u6cd5\u521b\u5efa\u76ee\u6807\u76ee\u5f55\u3002");
        }

        ArrayList<DownloadFileTask> tasks = new ArrayList<>();
        Set<String> reservedTopLevelPaths = new HashSet<>();
        Set<String> visitedRemoteDirectories = new HashSet<>();
        Set<String> visitedRemoteFiles = new HashSet<>();
        Set<String> preparedLocalDirectories = new HashSet<>();
        ArrayList<String> downloadedLocalPaths = new ArrayList<>();
        String firstBuildError = null;

        for (String rawVirtualPath : virtualPaths) {
            if (isCancelled(control)) {
                return DownloadResult.cancelled(tasks.size(), 0, 0, 0L, 0L, downloadedLocalPaths);
            }
            VirtualTarget target = resolveVirtualTarget(context, rawVirtualPath);
            if (target == null) {
                return DownloadResult.fail("\u4e0b\u8f7d\u5931\u8d25\uff1a\u9009\u62e9\u4e2d\u5305\u542b\u65e0\u6548\u7684\u8fdc\u7a0b\u8def\u5f84\u3002");
            }

            String topName = topLevelNameForTarget(target);
            if (TextUtils.isEmpty(topName)) topName = target.entry.displayName;
            File desiredTopLevel = new File(destinationRoot, topName);
            File topLevelLocal = resumeExistingOutputs
                ? reserveDestinationRoot(desiredTopLevel, reservedTopLevelPaths)
                : ensureUniqueDestinationRoot(desiredTopLevel, reservedTopLevelPaths);
            downloadedLocalPaths.add(topLevelLocal.getAbsolutePath().replace('\\', '/'));

            try {
                collectDownloadTasks(context, target, topLevelLocal, tasks,
                    visitedRemoteDirectories, visitedRemoteFiles, preparedLocalDirectories, control);
            } catch (Exception e) {
                if (e instanceof OperationCanceledException) {
                    return DownloadResult.cancelled(tasks.size(), 0, 0, 0L, 0L, downloadedLocalPaths);
                }
                clearSessionByEntry(target.entry);
                firstBuildError = classifyExceptionMessage(e);
                break;
            }
        }

        if (firstBuildError != null) {
            return DownloadResult.fail("\u4e0b\u8f7d\u5931\u8d25\uff1a" + firstBuildError);
        }

        for (String directoryPath : preparedLocalDirectories) {
            if (TextUtils.isEmpty(directoryPath)) continue;
            File directory = new File(directoryPath);
            if (directory.exists()) continue;
            if (!directory.mkdirs() && !directory.exists()) {
                return DownloadResult.fail("\u4e0b\u8f7d\u5931\u8d25\uff1a\u65e0\u6cd5\u521b\u5efa\u76ee\u6807\u76ee\u5f55\u3002");
            }
        }

        if (tasks.isEmpty()) {
            return DownloadResult.ok(0, 0, 0, 0L, 0L, downloadedLocalPaths);
        }

        long totalBytes = 0L;
        for (DownloadFileTask task : tasks) {
            if (isCancelled(control)) {
                return DownloadResult.cancelled(tasks.size(), 0, 0, totalBytes, 0L, downloadedLocalPaths);
            }
            if (task == null) continue;
            if (task.size > 0) totalBytes += task.size;
        }

        String journalSessionKey = null;
        if (!tasks.isEmpty() && tasks.get(0) != null) {
            journalSessionKey = clientKeyForEntry(tasks.get(0).entry);
        }
        SftpTransferJournal.TaskHandle journalHandle = isTransferJournalSuppressed() ? null
            : SftpTransferJournal.getInstance().startTask(
                context,
                SftpTransferJournal.TaskKind.DOWNLOAD,
                journalSessionKey,
                destinationDir,
                tasks.size(),
                totalBytes
            );
        SftpTransferJournal.getInstance().configureTask(context, journalHandle, virtualPaths, destinationDir);
        DownloadProgressListener effectiveListener = wrapDownloadProgressListener(context, journalHandle, listener);

        ConcurrentTransferProgressState progressState = new ConcurrentTransferProgressState(tasks.size(), totalBytes);
        emitDownloadProgress(effectiveListener, progressState, true);

        String firstDownloadError = null;
        ArrayList<PreparedDownloadTask> preparedTasks = new ArrayList<>(tasks.size());

        for (DownloadFileTask task : tasks) {
            if (task == null) continue;

            File outputFile = resumeExistingOutputs
                ? task.localFile
                : resolveNonConflictingFile(task.localFile);
            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                progressState.onFileFailed(outputFile.getAbsolutePath(), outputFile.getName(), task.size);
                emitDownloadProgress(listener, progressState, true);
                if (firstDownloadError == null) {
                    firstDownloadError = "\u65e0\u6cd5\u521b\u5efa\u672c\u5730\u76ee\u5f55\u3002";
                }
                continue;
            }

            preparedTasks.add(new PreparedDownloadTask(task, outputFile));
        }

        if (!preparedTasks.isEmpty()) {
            AtomicBoolean internalCancelled = new AtomicBoolean(false);
            DownloadControl effectiveControl = () -> internalCancelled.get() || isCancelled(control);
            ExecutorService executor = createTransferExecutor("sftp-download",
                resolveTransferWorkerCount(preparedTasks.size()));
            ExecutorCompletionService<DownloadTaskResult> completionService =
                new ExecutorCompletionService<>(executor);
            ArrayList<Future<DownloadTaskResult>> futures = new ArrayList<>(preparedTasks.size());
            int submittedCount = 0;

            try {
                for (PreparedDownloadTask task : preparedTasks) {
                    futures.add(completionService.submit(() ->
                        performDownloadTask(context, task, progressState, effectiveListener, effectiveControl, journalHandle)));
                    submittedCount++;
                }
            } catch (RejectedExecutionException e) {
                internalCancelled.set(true);
                cancelTransferFutures(futures, executor);
                DownloadResult result = DownloadResult.fail("\u4e0b\u8f7d\u5931\u8d25\uff1a\u4f20\u8f93\u7ebf\u7a0b\u6c60\u521d\u59cb\u5316\u5931\u8d25\u3002");
                finishDownloadJournal(context, journalHandle, result);
                return result;
            }

            try {
                for (int index = 0; index < submittedCount; index++) {
                    DownloadTaskResult result = completionService.take().get();
                if (result.cancelled) {
                    internalCancelled.set(true);
                    cancelTransferFutures(futures, executor);
                    DownloadResult cancelled = DownloadResult.cancelled(
                        tasks.size(),
                        progressState.completedFiles(),
                        progressState.failedFiles(),
                        totalBytes,
                        progressState.settledBytes(),
                        downloadedLocalPaths
                    );
                    finishDownloadJournal(context, journalHandle, cancelled);
                    return cancelled;
                }
                if (!result.success && firstDownloadError == null && !TextUtils.isEmpty(result.errorMessage)) {
                    firstDownloadError = result.errorMessage;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            internalCancelled.set(true);
            cancelTransferFutures(futures, executor);
            DownloadResult cancelled = DownloadResult.cancelled(
                tasks.size(),
                progressState.completedFiles(),
                progressState.failedFiles(),
                totalBytes,
                progressState.settledBytes(),
                downloadedLocalPaths
            );
            finishDownloadJournal(context, journalHandle, cancelled);
            return cancelled;
        } catch (Exception e) {
            internalCancelled.set(true);
            cancelTransferFutures(futures, executor);
            if (firstDownloadError == null) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                    firstDownloadError = classifyExceptionMessage(cause);
                }
            } finally {
                shutdownTransferExecutor(executor);
            }
        }

        progressState.onTransferFinished();
        emitDownloadProgress(effectiveListener, progressState, true);

        int downloadedFiles = progressState.completedFiles();
        int failedFiles = progressState.failedFiles();
        long downloadedBytes = progressState.settledBytes();

        if (failedFiles == 0) {
            DownloadResult result = DownloadResult.ok(tasks.size(), downloadedFiles, 0, totalBytes, downloadedBytes, downloadedLocalPaths);
            finishDownloadJournal(context, journalHandle, result);
            return result;
        }

        String reason = TextUtils.isEmpty(firstDownloadError)
            ? "\u8bf7\u68c0\u67e5\u7f51\u7edc\u548c\u8ba4\u8bc1\u540e\u91cd\u8bd5\u3002"
            : firstDownloadError;
        if (downloadedFiles > 0) {
            DownloadResult result = DownloadResult.partial(tasks.size(), downloadedFiles, failedFiles, totalBytes, downloadedBytes,
                "\u90e8\u5206\u6587\u4ef6\u4e0b\u8f7d\u5931\u8d25\uff1a" + reason, downloadedLocalPaths);
            finishDownloadJournal(context, journalHandle, result);
            return result;
        }
        DownloadResult result = DownloadResult.failWithStats(tasks.size(), 0, failedFiles, totalBytes, downloadedBytes,
            "\u4e0b\u8f7d\u5931\u8d25\uff1a" + reason, downloadedLocalPaths);
        finishDownloadJournal(context, journalHandle, result);
        return result;
    }

    @NonNull
    public UploadResult uploadLocalPathsToVirtual(@NonNull Context context,
                                                  @NonNull List<String> localPaths,
                                                  @NonNull String destinationVirtualDir,
                                                  @Nullable UploadProgressListener listener,
                                                  @Nullable UploadControl control) {
        if (localPaths.isEmpty()) {
            return UploadResult.fail("\u672a\u9009\u62e9\u9700\u8981\u4e0a\u4f20\u7684\u6587\u4ef6\u3002");
        }

        VirtualTarget destination = resolveVirtualTarget(context, destinationVirtualDir);
        if (destination == null) {
            return UploadResult.fail("\u4e0a\u4f20\u5931\u8d25\uff1a\u76ee\u6807\u4e0d\u662f\u6709\u6548\u7684\u0020\u0053\u0046\u0054\u0050\u0020\u76ee\u5f55\u3002");
        }

        try {
            withReconnectRetry(context, destination.entry, channel -> {
                SftpATTRS attrs = channel.stat(destination.remotePath);
                if (attrs == null) {
                    throw new IllegalStateException("\u8fdc\u7a0b\u76ee\u6807\u76ee\u5f55\u4e0d\u5b58\u5728\u3002");
                }
                if (!attrs.isDir()) {
                    throw new IllegalStateException("\u8fdc\u7a0b\u76ee\u6807\u8def\u5f84\u4e0d\u662f\u76ee\u5f55\u3002");
                }
                return null;
            });
        } catch (Exception e) {
            clearSessionByEntry(destination.entry);
            return UploadResult.fail("\u4e0a\u4f20\u5931\u8d25\uff1a" + classifyExceptionMessage(e));
        }

        ArrayList<UploadFileTask> tasks = new ArrayList<>();
        LinkedHashSet<String> remoteDirectories = new LinkedHashSet<>();
        Set<String> reservedRemoteTopLevels = new HashSet<>();
        ArrayList<String> uploadedVirtualPaths = new ArrayList<>();

        String firstBuildError = null;
        for (String rawLocalPath : localPaths) {
            if (isCancelled(control)) {
                return UploadResult.cancelled(tasks.size(), 0, 0, 0L, 0L, uploadedVirtualPaths);
            }
            if (TextUtils.isEmpty(rawLocalPath)) continue;

            File localFile = new File(rawLocalPath);
            if (!localFile.exists()) {
                return UploadResult.fail("\u4e0a\u4f20\u5931\u8d25\uff1a\u672c\u5730\u8def\u5f84\u4e0d\u5b58\u5728\u3002");
            }

            String topName = localFile.getName();
            if (TextUtils.isEmpty(topName)) {
                topName = "upload-item";
            }
            String desiredRemoteTop = joinRemotePath(destination.remotePath, topName);

            final String finalDesiredRemoteTop = desiredRemoteTop;
            final String uniqueRemoteTop;
            try {
                uniqueRemoteTop = withReconnectRetry(context, destination.entry, channel ->
                    resolveUniqueRemotePath(channel, finalDesiredRemoteTop, reservedRemoteTopLevels));
            } catch (Exception e) {
                clearSessionByEntry(destination.entry);
                return UploadResult.fail("\u4e0a\u4f20\u5931\u8d25\uff1a" + classifyExceptionMessage(e));
            }
            uploadedVirtualPaths.add(destination.virtualRoot + ("/".equals(uniqueRemoteTop) ? "" : uniqueRemoteTop));

            try {
                collectUploadTasksRecursive(localFile, uniqueRemoteTop, tasks, remoteDirectories, control);
            } catch (Exception e) {
                if (e instanceof OperationCanceledException) {
                    return UploadResult.cancelled(tasks.size(), 0, 0, 0L, 0L, uploadedVirtualPaths);
                }
                firstBuildError = classifyExceptionMessage(e);
                break;
            }
        }

        if (!TextUtils.isEmpty(firstBuildError)) {
            return UploadResult.fail("\u4e0a\u4f20\u5931\u8d25\uff1a" + firstBuildError);
        }

        try {
            withReconnectRetry(context, destination.entry, channel -> {
                for (String remoteDir : remoteDirectories) {
                    if (isCancelled(control)) {
                        throw new OperationCanceledException();
                    }
                    ensureRemoteDirectoryExists(channel, remoteDir);
                }
                return null;
            });
        } catch (Exception e) {
            if (e instanceof OperationCanceledException) {
                return UploadResult.cancelled(tasks.size(), 0, 0, 0L, 0L, uploadedVirtualPaths);
            }
            clearSessionByEntry(destination.entry);
            return UploadResult.fail("\u4e0a\u4f20\u5931\u8d25\uff1a" + classifyExceptionMessage(e));
        }

        if (tasks.isEmpty()) {
            synchronized (mLock) {
                clearDirectoryCacheByClientKeyLocked(clientKeyForEntry(destination.entry));
            }
            return UploadResult.ok(0, 0, 0, 0L, 0L, uploadedVirtualPaths);
        }

        long totalBytes = 0L;
        for (UploadFileTask task : tasks) {
            if (task == null) continue;
            if (task.size > 0) totalBytes += task.size;
        }

        String journalSessionKey = clientKeyForEntry(destination.entry);
        SftpTransferJournal.TaskHandle journalHandle = isTransferJournalSuppressed() ? null
            : SftpTransferJournal.getInstance().startTask(
                context,
                SftpTransferJournal.TaskKind.UPLOAD,
                journalSessionKey,
                destinationVirtualDir,
                tasks.size(),
                totalBytes
            );
        SftpTransferJournal.getInstance().configureTask(context, journalHandle, localPaths, destinationVirtualDir);
        UploadProgressListener effectiveListener = wrapUploadProgressListener(context, journalHandle, listener);

        ConcurrentTransferProgressState progressState = new ConcurrentTransferProgressState(tasks.size(), totalBytes);
        emitUploadProgress(effectiveListener, progressState, true);

        String firstUploadError = null;
        AtomicBoolean internalCancelled = new AtomicBoolean(false);
        UploadControl effectiveControl = () -> internalCancelled.get() || isCancelled(control);
        ExecutorService executor = createTransferExecutor("sftp-upload", resolveTransferWorkerCount(tasks.size()));
        ExecutorCompletionService<UploadTaskResult> completionService =
            new ExecutorCompletionService<>(executor);
        ArrayList<Future<UploadTaskResult>> futures = new ArrayList<>(tasks.size());
        int submittedCount = 0;

        try {
            for (UploadFileTask task : tasks) {
                if (task == null) continue;
                futures.add(completionService.submit(() ->
                    performUploadTask(context, destination.entry, task, progressState, effectiveListener, effectiveControl, journalHandle)));
                submittedCount++;
            }
        } catch (RejectedExecutionException e) {
            internalCancelled.set(true);
            cancelTransferFutures(futures, executor);
            UploadResult result = UploadResult.fail("\u4e0a\u4f20\u5931\u8d25\uff1a\u4f20\u8f93\u7ebf\u7a0b\u6c60\u521d\u59cb\u5316\u5931\u8d25\u3002");
            finishUploadJournal(context, journalHandle, result);
            return result;
        }

        try {
            for (int index = 0; index < submittedCount; index++) {
                UploadTaskResult result = completionService.take().get();
                if (result.cancelled) {
                    internalCancelled.set(true);
                    cancelTransferFutures(futures, executor);
                    UploadResult cancelled = UploadResult.cancelled(
                        tasks.size(),
                        progressState.completedFiles(),
                        progressState.failedFiles(),
                        totalBytes,
                        progressState.settledBytes(),
                        uploadedVirtualPaths
                    );
                    finishUploadJournal(context, journalHandle, cancelled);
                    return cancelled;
                }
                if (!result.success && firstUploadError == null && !TextUtils.isEmpty(result.errorMessage)) {
                    firstUploadError = result.errorMessage;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            internalCancelled.set(true);
            cancelTransferFutures(futures, executor);
            UploadResult cancelled = UploadResult.cancelled(
                tasks.size(),
                progressState.completedFiles(),
                progressState.failedFiles(),
                totalBytes,
                progressState.settledBytes(),
                uploadedVirtualPaths
            );
            finishUploadJournal(context, journalHandle, cancelled);
            return cancelled;
        } catch (Exception e) {
            internalCancelled.set(true);
            cancelTransferFutures(futures, executor);
            if (firstUploadError == null) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                firstUploadError = classifyExceptionMessage(cause);
            }
        } finally {
            shutdownTransferExecutor(executor);
        }

        synchronized (mLock) {
            clearDirectoryCacheByClientKeyLocked(clientKeyForEntry(destination.entry));
        }

        progressState.onTransferFinished();
        emitUploadProgress(effectiveListener, progressState, true);

        int uploadedFiles = progressState.completedFiles();
        int failedFiles = progressState.failedFiles();
        long uploadedBytes = progressState.settledBytes();

        if (failedFiles == 0) {
            UploadResult result = UploadResult.ok(tasks.size(), uploadedFiles, 0, totalBytes, uploadedBytes, uploadedVirtualPaths);
            finishUploadJournal(context, journalHandle, result);
            return result;
        }

        String reason = TextUtils.isEmpty(firstUploadError)
            ? "\u8bf7\u68c0\u67e5\u7f51\u7edc\u548c\u8ba4\u8bc1\u540e\u91cd\u8bd5\u3002"
            : firstUploadError;
        if (uploadedFiles > 0) {
            UploadResult result = UploadResult.partial(tasks.size(), uploadedFiles, failedFiles, totalBytes, uploadedBytes,
                "\u90e8\u5206\u6587\u4ef6\u4e0a\u4f20\u5931\u8d25\uff1a" + reason, uploadedVirtualPaths);
            finishUploadJournal(context, journalHandle, result);
            return result;
        }
        UploadResult result = UploadResult.failWithStats(tasks.size(), 0, failedFiles, totalBytes, uploadedBytes,
            "\u4e0a\u4f20\u5931\u8d25\uff1a" + reason, uploadedVirtualPaths);
        finishUploadJournal(context, journalHandle, result);
        return result;
    }

    @NonNull
    public UploadResult uploadLocalFileToVirtualPath(@NonNull Context context,
                                                     @NonNull String localFilePath,
                                                     @NonNull String targetVirtualPath,
                                                     long expectedRemoteModifiedMs,
                                                     long expectedRemoteSize) {
        return uploadLocalFileToVirtualPath(
            context,
            localFilePath,
            targetVirtualPath,
            expectedRemoteModifiedMs,
            expectedRemoteSize,
            ""
        );
    }

    @NonNull
    public UploadResult uploadLocalFileToVirtualPath(@NonNull Context context,
                                                     @NonNull String localFilePath,
                                                     @NonNull String targetVirtualPath,
                                                     long expectedRemoteModifiedMs,
                                                     long expectedRemoteSize,
                                                     @Nullable String expectedRemoteSha256) {
        return uploadLocalFileToVirtualPathInternal(
            context,
            localFilePath,
            targetVirtualPath,
            expectedRemoteModifiedMs,
            expectedRemoteSize,
            expectedRemoteSha256,
            true
        );
    }

    /**
     * Fast path upload used by editor auto-save pipelines.
     * <p>
     * This skips the expensive post-upload digest verification (which may re-download the uploaded
     * content) and only validates the remote size. The transport is still atomic: the upload lands
     * in a temp remote file and is then renamed into place.
     */
    @NonNull
    public UploadResult uploadLocalFileToVirtualPathFast(@NonNull Context context,
                                                         @NonNull String localFilePath,
                                                         @NonNull String targetVirtualPath,
                                                         long expectedRemoteModifiedMs,
                                                         long expectedRemoteSize) {
        return uploadLocalFileToVirtualPathFast(
            context,
            localFilePath,
            targetVirtualPath,
            expectedRemoteModifiedMs,
            expectedRemoteSize,
            ""
        );
    }

    @NonNull
    public UploadResult uploadLocalFileToVirtualPathFast(@NonNull Context context,
                                                         @NonNull String localFilePath,
                                                         @NonNull String targetVirtualPath,
                                                         long expectedRemoteModifiedMs,
                                                         long expectedRemoteSize,
                                                         @Nullable String expectedRemoteSha256) {
        return uploadLocalFileToVirtualPathInternal(
            context,
            localFilePath,
            targetVirtualPath,
            expectedRemoteModifiedMs,
            expectedRemoteSize,
            expectedRemoteSha256,
            false
        );
    }

    @NonNull
    private UploadResult uploadLocalFileToVirtualPathInternal(@NonNull Context context,
                                                              @NonNull String localFilePath,
                                                              @NonNull String targetVirtualPath,
                                                              long expectedRemoteModifiedMs,
                                                              long expectedRemoteSize,
                                                              @Nullable String expectedRemoteSha256,
                                                              boolean verifyDigest) {
        if (TextUtils.isEmpty(localFilePath)) {
            return UploadResult.fail("\u8986\u76d6\u5931\u8d25\uff1a\u672c\u5730\u6587\u4ef6\u8def\u5f84\u4e3a\u7a7a\u3002");
        }

        File localFile = new File(localFilePath);
        if (!localFile.exists() || !localFile.isFile()) {
            return UploadResult.fail("\u8986\u76d6\u5931\u8d25\uff1a\u672c\u5730\u6587\u4ef6\u4e0d\u5b58\u5728\u6216\u4e0d\u53ef\u7528\u3002");
        }

        VirtualTarget target = resolveVirtualTarget(context, targetVirtualPath);
        if (target == null) {
            return UploadResult.fail("\u8986\u76d6\u5931\u8d25\uff1a\u76ee\u6807\u4e0d\u662f\u6709\u6548\u7684 SFTP \u6587\u4ef6\u8def\u5f84\u3002");
        }

        String normalizedTarget = normalizeRemotePath(target.remotePath);
        long localSize = Math.max(0L, localFile.length());
        final long[] remoteModifiedMsHolder = new long[]{-1L};
        final long[] remoteSizeHolder = new long[]{-1L};

        try {
            withReconnectRetry(context, target.entry, channel -> {
                String parentRemotePath = parentRemotePath(normalizedTarget);
                SftpATTRS parentAttrs = channel.stat(parentRemotePath);
                if (parentAttrs == null || !parentAttrs.isDir()) {
                    throw new IllegalStateException("\u8fdc\u7a0b\u76ee\u6807\u76ee\u5f55\u4e0d\u5b58\u5728\u6216\u4e0d\u53ef\u7528\u3002");
                }

                SftpATTRS currentTargetAttrs = null;
                try {
                    currentTargetAttrs = channel.stat(normalizedTarget);
                } catch (SftpException e) {
                    if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                        throw e;
                    }
                }
                if (currentTargetAttrs != null && currentTargetAttrs.isDir()) {
                    throw new IllegalStateException("\u8fdc\u7a0b\u76ee\u6807\u662f\u76ee\u5f55\uff0c\u65e0\u6cd5\u8986\u76d6\uff1a" + normalizedTarget);
                }
                if (!matchesExpectedRemoteVersion(
                    expectedRemoteModifiedMs,
                    expectedRemoteSize,
                    currentTargetAttrs
                )) {
                    throw new IllegalStateException("\u8fdc\u7a0b\u6587\u4ef6\u5df2\u88ab\u5176\u4ed6\u7f16\u8f91\u6216\u66ff\u6362\uff0c\u8bf7\u5148\u91cd\u65b0\u6253\u5f00\u518d\u4fdd\u5b58\u3002");
                }

                String tempRemotePath = buildRemoteTransferTempPath(normalizedTarget);
                deleteRemoteFileIfExists(channel, tempRemotePath);
                try {
                    try (InputStream inputStream = new FileInputStream(localFile);
                         OutputStream outputStream = channel.put(tempRemotePath, ChannelSftp.OVERWRITE)) {
                        byte[] buffer = new byte[16 * 1024];
                        int read;
                        while ((read = inputStream.read(buffer)) >= 0) {
                            if (read == 0) continue;
                            outputStream.write(buffer, 0, read);
                        }
                        outputStream.flush();
                    }
                    if (verifyDigest) {
                        verifyUploadedTempFile(channel, tempRemotePath, localFile, localSize, null);
                    } else {
                        verifyUploadedTempFileSizeOnly(channel, tempRemotePath, localSize);
                    }
                    replaceRemoteFile(channel, tempRemotePath, normalizedTarget);
                } catch (Exception e) {
                    try {
                        deleteRemoteFileIfExists(channel, tempRemotePath);
                    } catch (Throwable ignored) {
                    }
                    throw e;
                }
                SftpATTRS committedAttrs = channel.stat(normalizedTarget);
                remoteModifiedMsHolder[0] = attrsModifiedMs(committedAttrs);
                remoteSizeHolder[0] = committedAttrs == null ? -1L : Math.max(0L, committedAttrs.getSize());
                return null;
            });
        } catch (Exception e) {
            clearSessionByEntry(target.entry);
            return UploadResult.fail("\u8986\u76d6\u5931\u8d25\uff1a" + classifyExceptionMessage(e));
        }

        synchronized (mLock) {
            clearDirectoryCacheByClientKeyLocked(clientKeyForEntry(target.entry));
        }
        return UploadResult.okWithRemote(
            1,
            1,
            0,
            localSize,
            localSize,
            remoteModifiedMsHolder[0],
            remoteSizeHolder[0],
            new ArrayList<>()
        );
    }

    /**
     * Uploads one content-addressed image to the HOME of the SSH session backing the active terminal.
     * This endpoint deliberately does not depend on the file manager's selected virtual target.
     */
    @NonNull
    public CodexAttachmentUploadResult uploadCodexAttachment(@NonNull Context context,
                                                             @NonNull SessionEntry entry,
                                                             @NonNull String operationId,
                                                             @NonNull String localFilePath,
                                                             @NonNull String expectedSha256,
                                                             @NonNull String extension,
                                                             @Nullable UploadControl control) {
        String safeOperationId = trimToEmpty(operationId);
        String digest = trimToEmpty(expectedSha256).toLowerCase(Locale.US);
        String safeExtension = normalizeCodexAttachmentExtension(extension);
        File localFile = new File(localFilePath);
        if (entry.transport == SessionTransport.LOCAL || TextUtils.isEmpty(entry.sshCommand)) {
            return CodexAttachmentUploadResult.fail("当前终端没有可用于附件上传的 SSH 连接。");
        }
        if (!localFile.isFile() || localFile.length() <= 0L) {
            return CodexAttachmentUploadResult.fail("待上传的本地图片不存在或为空。");
        }
        if (!isSha256Hex(digest) || safeExtension.isEmpty()) {
            return CodexAttachmentUploadResult.fail("附件内容标识或图片格式无效。");
        }
        try {
            String actualLocalDigest = computeLocalSha256(
                localFile, control == null ? null : control::isCancelled);
            if (!digest.equals(actualLocalDigest)) {
                return CodexAttachmentUploadResult.fail("本地图片 SHA-256 与内容标识不一致。");
            }
        } catch (OperationCanceledException e) {
            return CodexAttachmentUploadResult.cancelled();
        } catch (Exception e) {
            return CodexAttachmentUploadResult.fail("本地图片完整性校验失败：" + classifyExceptionMessage(e));
        }

        SessionSyncTracer.getInstance().info(context, "SftpProtocolManager", "codexAttachmentStart",
            safeOperationId, "开始上传 Codex 图片附件",
            "bytes=" + localFile.length() + " digest=" + digest.substring(0, 12));

        final String[] committedPath = new String[]{""};
        final boolean[] reused = new boolean[]{false};
        try {
            withReconnectRetry(context, entry, channel -> {
                if (isCancelled(control)) throw new OperationCanceledException();
                String targetPath = buildCodexAttachmentRemotePath(channel.pwd(), digest, safeExtension);
                if (TextUtils.isEmpty(targetPath)) {
                    throw new IllegalStateException("无法解析远端 Codex 附件目录。");
                }
                String targetDirectory = parentRemotePath(targetPath);
                ensureRemoteDirectoryExists(channel, targetDirectory);
                tryChmod(channel, 0700, targetDirectory);
                cleanupStaleCodexAttachmentTemps(channel, targetDirectory);

                SftpATTRS existing = statRemoteFileIfPresent(channel, targetPath);
                if (existing != null) {
                    if (existing.isDir()) {
                        throw new IllegalStateException("远端附件路径不是普通文件。");
                    }
                    if (existing.isLink()) {
                        quarantineRemoteCodexAttachment(channel, targetPath);
                        existing = null;
                    }
                }
                if (existing != null) {
                    String existingDigest = computeRemoteSha256PreferNative(
                        context, entry, channel, targetPath, control == null ? null : control::isCancelled);
                    if (digest.equals(existingDigest) && existing.getSize() == localFile.length()) {
                        tryChmod(channel, 0600, targetPath);
                        committedPath[0] = targetPath;
                        reused[0] = true;
                        return null;
                    }
                    String corruptPath = targetPath + ".corrupt-" + System.currentTimeMillis();
                    channel.rename(targetPath, corruptPath);
                }

                String fileName = targetPath.substring(targetPath.lastIndexOf('/') + 1);
                String tempPath = joinRemotePath(
                    targetDirectory,
                    "." + fileName + ".termux-codex-" + TRANSFER_TEMP_COUNTER.getAndIncrement() + ".part");
                deleteRemoteFileIfExists(channel, tempPath);
                try {
                    try (InputStream input = new FileInputStream(localFile);
                         OutputStream output = channel.put(tempPath, ChannelSftp.OVERWRITE)) {
                        byte[] buffer = new byte[64 * 1024];
                        while (true) {
                            if (isCancelled(control)) throw new OperationCanceledException();
                            int read = input.read(buffer);
                            if (read < 0) break;
                            if (read == 0) continue;
                            output.write(buffer, 0, read);
                        }
                        output.flush();
                    }

                    SftpATTRS stagedAttrs = channel.stat(tempPath);
                    if (stagedAttrs == null || stagedAttrs.isDir() || stagedAttrs.getSize() != localFile.length()) {
                        throw new IllegalStateException("远端附件大小校验失败。");
                    }
                    String stagedDigest = computeRemoteSha256PreferNative(
                        context, entry, channel, tempPath, control == null ? null : control::isCancelled);
                    if (!digest.equals(stagedDigest)) {
                        throw new IllegalStateException("远端附件 SHA-256 校验失败。");
                    }
                    replaceRemoteFile(channel, tempPath, targetPath);
                    tryChmod(channel, 0600, targetPath);
                    SftpATTRS committedAttrs = channel.lstat(targetPath);
                    if (committedAttrs == null || committedAttrs.isDir() || committedAttrs.isLink() ||
                        committedAttrs.getSize() != localFile.length()) {
                        quarantineRemoteCodexAttachment(channel, targetPath);
                        throw new IllegalStateException("远端附件提交后大小校验失败。");
                    }
                    String committedDigest = computeRemoteSha256PreferNative(
                        context, entry, channel, targetPath, control == null ? null : control::isCancelled);
                    if (!digest.equals(committedDigest)) {
                        quarantineRemoteCodexAttachment(channel, targetPath);
                        throw new IllegalStateException("远端附件提交后 SHA-256 校验失败。");
                    }
                    committedPath[0] = targetPath;
                } catch (Exception e) {
                    try {
                        deleteRemoteFileIfExists(channel, tempPath);
                    } catch (Throwable ignored) {
                    }
                    throw e;
                }
                return null;
            });
        } catch (OperationCanceledException e) {
            SessionSyncTracer.getInstance().warn(context, "SftpProtocolManager", "codexAttachmentCancelled",
                safeOperationId, "Codex 图片附件上传已取消", null);
            return CodexAttachmentUploadResult.cancelled();
        } catch (Exception e) {
            clearSessionByEntry(entry);
            String message = classifyExceptionMessage(e);
            SessionSyncTracer.getInstance().error(context, "SftpProtocolManager", "codexAttachmentFailed",
                safeOperationId, "Codex 图片附件上传失败", message);
            return CodexAttachmentUploadResult.fail("远端图片上传失败：" + message);
        }

        if (TextUtils.isEmpty(committedPath[0])) {
            return CodexAttachmentUploadResult.fail("远端图片提交完成但未返回可用路径。");
        }
        SessionSyncTracer.getInstance().info(context, "SftpProtocolManager", "codexAttachmentCommitted",
            safeOperationId, "Codex 图片附件已原子提交",
            "reused=" + reused[0] +
                " uploaded_bytes=" + codexAttachmentUploadedBytes(reused[0], localFile.length()) +
                " digest=" + digest.substring(0, 12));
        return CodexAttachmentUploadResult.ok(
            committedPath[0], reused[0], codexAttachmentUploadedBytes(reused[0], localFile.length()));
    }

    @NonNull
    public RemoteTransferResult transferVirtualPaths(@NonNull Context context,
                                                     @NonNull List<String> sourceVirtualPaths,
                                                     @NonNull String destinationVirtualDir,
                                                     @Nullable RemoteTransferProgressListener listener,
                                                     @Nullable RemoteTransferControl control) {
        return transferVirtualPathsInternal(
            context,
            sourceVirtualPaths,
            destinationVirtualDir,
            null,
            listener,
            control
        );
    }

    @NonNull
    RemoteTransferResult resumeTransferVirtualPaths(@NonNull Context context,
                                                    @NonNull List<String> sourceVirtualPaths,
                                                    @NonNull String destinationVirtualDir,
                                                    @Nullable String stageDirectoryPath,
                                                    @Nullable RemoteTransferProgressListener listener,
                                                    @Nullable RemoteTransferControl control) {
        return transferVirtualPathsInternal(
            context,
            sourceVirtualPaths,
            destinationVirtualDir,
            stageDirectoryPath,
            listener,
            control
        );
    }

    @NonNull
    private RemoteTransferResult transferVirtualPathsInternal(@NonNull Context context,
                                                              @NonNull List<String> sourceVirtualPaths,
                                                              @NonNull String destinationVirtualDir,
                                                              @Nullable String stageDirectoryPath,
                                                              @Nullable RemoteTransferProgressListener listener,
                                                              @Nullable RemoteTransferControl control) {
        if (sourceVirtualPaths.isEmpty()) {
            return RemoteTransferResult.fail("\u672a\u9009\u62e9\u9700\u8981\u4e92\u4f20\u7684\u670d\u52a1\u5668\u6587\u4ef6\u3002");
        }

        VirtualTarget destinationTarget = resolveVirtualTarget(context, destinationVirtualDir);
        String journalSessionKey = destinationTarget == null ? null : clientKeyForEntry(destinationTarget.entry);
        SftpTransferJournal.TaskHandle journalHandle = isTransferJournalSuppressed() ? null
            : SftpTransferJournal.getInstance().startTask(
                context,
                SftpTransferJournal.TaskKind.RELAY,
                journalSessionKey,
                destinationVirtualDir,
                sourceVirtualPaths.size(),
                0L
            );
        SftpTransferJournal.getInstance().configureTask(context, journalHandle, sourceVirtualPaths, destinationVirtualDir);
        RemoteTransferProgressListener effectiveListener =
            wrapRemoteTransferProgressListener(context, journalHandle, listener);

        RemoteTransferWorkflowStateMachine workflow = new RemoteTransferWorkflowStateMachine();
        workflow.beginPreparing();
        emitRemoteTransferProgress(effectiveListener, workflow.snapshot());

        RemoteTransferResult finalResult = null;
        File stagingDirectory = null;
        boolean resumeExistingStage = !TextUtils.isEmpty(stageDirectoryPath);
        try {
            if (isCancelled(control)) {
                finalResult = RemoteTransferResult.cancelled(0, 0, 0, 0L, 0L, new ArrayList<>());
                return finalResult;
            }

            stagingDirectory = resumeExistingStage
                ? ensureRecoveryStageDirectory(stageDirectoryPath)
                : createRemoteTransferStagingDirectory(context);
            SftpTransferJournal.getInstance().attachStageDirectory(
                context,
                journalHandle,
                stagingDirectory.getAbsolutePath()
            );
            pushTransferJournalSuppressed();
            DownloadResult downloadResult;
            try {
                DownloadProgressListener relayDownloadListener = progress -> {
                    workflow.bindDownload(progress);
                    emitRemoteTransferProgress(effectiveListener, workflow.snapshot());
                };
                DownloadControl relayDownloadControl = () -> isCancelled(control);
                downloadResult = resumeExistingStage
                    ? resumeDownloadVirtualPaths(
                        context,
                        sourceVirtualPaths,
                        stagingDirectory.getAbsolutePath(),
                        relayDownloadListener,
                        relayDownloadControl
                    )
                    : downloadVirtualPaths(
                        context,
                        sourceVirtualPaths,
                        stagingDirectory.getAbsolutePath(),
                        relayDownloadListener,
                        relayDownloadControl
                    );
            } finally {
                popTransferJournalSuppressed();
            }

            if (isCancelled(control) || isCancelledMessage(downloadResult.messageCn)) {
                finalResult = RemoteTransferResult.cancelled(
                    downloadResult.totalFiles,
                    downloadResult.downloadedFiles,
                    downloadResult.failedFiles,
                    downloadResult.totalBytes,
                    downloadResult.downloadedBytes,
                    new ArrayList<>()
                );
                return finalResult;
            }

            if (!downloadResult.success) {
                finalResult = RemoteTransferResult.failWithStats(
                    downloadResult.totalFiles,
                    downloadResult.downloadedFiles,
                    downloadResult.failedFiles,
                    downloadResult.totalBytes,
                    downloadResult.downloadedBytes,
                    downloadResult.messageCn,
                    new ArrayList<>()
                );
                return finalResult;
            }

            ArrayList<String> stagedLocalPaths = collectTopLevelStagedPaths(stagingDirectory);
            pushTransferJournalSuppressed();
            UploadResult uploadResult;
            try {
                uploadResult = uploadLocalPathsToVirtual(
                    context,
                    stagedLocalPaths,
                    destinationVirtualDir,
                    progress -> {
                        workflow.bindUpload(progress);
                        emitRemoteTransferProgress(effectiveListener, workflow.snapshot());
                    },
                    () -> isCancelled(control)
                );
            } finally {
                popTransferJournalSuppressed();
            }

            if (isCancelled(control) || isCancelledMessage(uploadResult.messageCn)) {
                finalResult = RemoteTransferResult.cancelled(
                    uploadResult.totalFiles,
                    uploadResult.uploadedFiles,
                    uploadResult.failedFiles,
                    uploadResult.totalBytes,
                    uploadResult.uploadedBytes,
                    uploadResult.uploadedVirtualPaths
                );
                return finalResult;
            }

            if (uploadResult.success) {
                finalResult = RemoteTransferResult.ok(
                    uploadResult.totalFiles,
                    uploadResult.uploadedFiles,
                    uploadResult.failedFiles,
                    uploadResult.totalBytes,
                    uploadResult.uploadedBytes,
                    uploadResult.uploadedVirtualPaths
                );
                return finalResult;
            }

            finalResult = RemoteTransferResult.failWithStats(
                uploadResult.totalFiles,
                uploadResult.uploadedFiles,
                uploadResult.failedFiles,
                uploadResult.totalBytes,
                uploadResult.uploadedBytes,
                uploadResult.messageCn,
                uploadResult.uploadedVirtualPaths
            );
            return finalResult;
        } catch (Exception e) {
            finalResult = RemoteTransferResult.fail("\u670d\u52a1\u5668\u4e92\u4f20\u5931\u8d25\uff1a" + classifyExceptionMessage(e));
            return finalResult;
        } finally {
            workflow.beginCleanup(finalResult == null ? "" : finalResult.messageCn);
            emitRemoteTransferProgress(effectiveListener, workflow.snapshot());
            deleteDirectoryContents(stagingDirectory);

            if (finalResult == null) {
                finalResult = RemoteTransferResult.fail("\u670d\u52a1\u5668\u4e92\u4f20\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002");
            }

            if (finalResult.success) {
                workflow.markCompleted(
                    finalResult.totalFiles,
                    finalResult.transferredFiles,
                    finalResult.failedFiles,
                    finalResult.totalBytes,
                    finalResult.transferredBytes,
                    finalResult.messageCn
                );
            } else if (isCancelledMessage(finalResult.messageCn)) {
                workflow.markCancelled(
                    finalResult.totalFiles,
                    finalResult.transferredFiles,
                    finalResult.failedFiles,
                    finalResult.totalBytes,
                    finalResult.transferredBytes,
                    finalResult.messageCn
                );
            } else {
                workflow.markFailed(
                    finalResult.totalFiles,
                    finalResult.transferredFiles,
                    finalResult.failedFiles,
                    finalResult.totalBytes,
                    finalResult.transferredBytes,
                    finalResult.messageCn
                );
            }
            emitRemoteTransferProgress(effectiveListener, workflow.snapshot());
            finishRemoteTransferJournal(context, journalHandle, finalResult);
        }
    }

    private UploadTaskResult performUploadTask(@NonNull Context context,
                                               @NonNull SessionEntry entry,
                                               @NonNull UploadFileTask task,
                                               @NonNull ConcurrentTransferProgressState progressState,
                                               @Nullable UploadProgressListener listener,
                                               @Nullable UploadControl control,
                                               @Nullable SftpTransferJournal.TaskHandle journalHandle) {
        String localDisplayName = task.localFile.getName();
        final String displayName = TextUtils.isEmpty(localDisplayName) ? task.remotePath : localDisplayName;
        final long declaredSize = Math.max(0L, task.size);
        final String taskKey = task.remotePath;

        UploadProgressState workerProgress = new UploadProgressState(1, declaredSize);
        workerProgress.currentFile = displayName;
        workerProgress.currentFileSize = declaredSize;

        progressState.onFileStarted(taskKey, displayName, declaredSize);
        emitUploadProgress(listener, progressState, true);

        UploadProgressListener relayListener = progress -> {
            String currentDisplayName = TextUtils.isEmpty(progress.currentFile)
                ? displayName
                : progress.currentFile;
            progressState.onFileProgress(
                taskKey,
                currentDisplayName,
                Math.max(workerProgress.currentFileSize, progress.currentFileSize),
                progress.currentFileTransferred
            );
            emitUploadProgress(listener, progressState, false);
        };

        try {
            long fileBytes = uploadSingleFile(context, entry, task, workerProgress, relayListener, control, journalHandle);
            long finalSize = workerProgress.currentFileSize > 0
                ? workerProgress.currentFileSize
                : Math.max(declaredSize, fileBytes);
            progressState.onFileSucceeded(taskKey, displayName, finalSize, fileBytes);
            emitUploadProgress(listener, progressState, true);
            return UploadTaskResult.success(fileBytes);
        } catch (Exception e) {
            if (e instanceof OperationCanceledException || isCancelled(control)) {
                progressState.onFileCancelled(taskKey, displayName, workerProgress.currentFileSize);
                emitUploadProgress(listener, progressState, true);
                return UploadTaskResult.cancelled();
            }
            progressState.onFileFailed(taskKey, displayName, workerProgress.currentFileSize);
            emitUploadProgress(listener, progressState, true);
            return UploadTaskResult.failure(classifyExceptionMessage(e));
        }
    }

    private DownloadTaskResult performDownloadTask(@NonNull Context context,
                                                   @NonNull PreparedDownloadTask preparedTask,
                                                   @NonNull ConcurrentTransferProgressState progressState,
                                                   @Nullable DownloadProgressListener listener,
                                                   @Nullable DownloadControl control,
                                                   @Nullable SftpTransferJournal.TaskHandle journalHandle) {
        DownloadFileTask task = preparedTask.task;
        File outputFile = preparedTask.outputFile;
        final String displayName = outputFile.getName();
        final long declaredSize = Math.max(0L, task.size);
        final String taskKey = outputFile.getAbsolutePath();

        DownloadProgressState workerProgress = new DownloadProgressState(1, declaredSize);
        workerProgress.currentFile = displayName;
        workerProgress.currentFileSize = declaredSize;

        progressState.onFileStarted(taskKey, displayName, declaredSize);
        emitDownloadProgress(listener, progressState, true);

        DownloadProgressListener relayListener = progress -> {
            String currentDisplayName = TextUtils.isEmpty(progress.currentFile)
                ? displayName
                : progress.currentFile;
            progressState.onFileProgress(
                taskKey,
                currentDisplayName,
                Math.max(workerProgress.currentFileSize, progress.currentFileSize),
                progress.currentFileTransferred
            );
            emitDownloadProgress(listener, progressState, false);
        };

        try {
            long fileBytes = downloadSingleFile(
                context,
                task.entry,
                task.remotePath,
                outputFile,
                workerProgress,
                relayListener,
                control,
                journalHandle
            );

            if (task.modifiedMs > 0L) {
                try {
                    outputFile.setLastModified(task.modifiedMs);
                } catch (Throwable ignored) {
                }
            }

            long finalSize = workerProgress.currentFileSize > 0
                ? workerProgress.currentFileSize
                : Math.max(declaredSize, fileBytes);
            progressState.onFileSucceeded(taskKey, displayName, finalSize, fileBytes);
            emitDownloadProgress(listener, progressState, true);
            return DownloadTaskResult.success(fileBytes);
        } catch (Exception e) {
            try {
                if (outputFile.exists()) {
                    outputFile.delete();
                }
            } catch (Throwable ignored) {
            }

            if (e instanceof OperationCanceledException || isCancelled(control)) {
                progressState.onFileCancelled(taskKey, displayName, workerProgress.currentFileSize);
                emitDownloadProgress(listener, progressState, true);
                return DownloadTaskResult.cancelled();
            }

            progressState.onFileFailed(taskKey, displayName, workerProgress.currentFileSize);
            emitDownloadProgress(listener, progressState, true);
            return DownloadTaskResult.failure(classifyExceptionMessage(e));
        }
    }

    private static int resolveTransferWorkerCount(int taskCount) {
        if (taskCount <= 1) {
            return 1;
        }
        return Math.max(1, Math.min(taskCount, Math.min(MAX_TRANSFER_WORKERS, MAX_CHANNEL_POOL_PER_CLIENT)));
    }

    private static void cancelTransferFutures(@NonNull List<? extends Future<?>> futures,
                                              @Nullable ExecutorService executor) {
        for (Future<?> future : futures) {
            if (future == null) continue;
            try {
                future.cancel(true);
            } catch (Throwable ignored) {
            }
        }
        shutdownTransferExecutor(executor);
    }

    private static void shutdownTransferExecutor(@Nullable ExecutorService executor) {
        if (executor == null) return;
        try {
            executor.shutdownNow();
            executor.awaitTermination(300L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
        }
    }

    private void collectUploadTasksRecursive(@NonNull File localFile,
                                             @NonNull String remotePath,
                                             @NonNull ArrayList<UploadFileTask> tasks,
                                             @NonNull LinkedHashSet<String> remoteDirectories,
                                             @Nullable UploadControl control) {
        if (isCancelled(control)) {
            throw new OperationCanceledException();
        }
        String normalizedRemote = normalizeRemotePath(remotePath);

        if (localFile.isDirectory()) {
            remoteDirectories.add(normalizedRemote);
            File[] children = localFile.listFiles();
            if (children == null || children.length == 0) {
                return;
            }
            for (File child : children) {
                if (child == null) continue;
                String childName = child.getName();
                if (TextUtils.isEmpty(childName)) continue;
                String childRemote = joinRemotePath(normalizedRemote, childName);
                collectUploadTasksRecursive(child, childRemote, tasks, remoteDirectories, control);
            }
            return;
        }

        if (!localFile.isFile()) return;
        tasks.add(new UploadFileTask(
            localFile,
            normalizedRemote,
            Math.max(0L, localFile.length()),
            localFile.lastModified()
        ));
    }

    private long uploadSingleFile(@NonNull Context context,
                                  @NonNull SessionEntry entry,
                                  @NonNull UploadFileTask task,
                                  @NonNull UploadProgressState progressState,
                                  @Nullable UploadProgressListener listener,
                                  @Nullable UploadControl control,
                                  @Nullable SftpTransferJournal.TaskHandle journalHandle) throws Exception {
        Exception lastError = null;
        int maxAttempts = RECOVERABLE_RETRY_COUNT + 1;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            ClientHolder holder = null;
            ChannelSftp channel = null;
            boolean channelBroken = false;
            final long[] currentTransferred = new long[]{0L};
            String tempRemotePath = null;
            try {
                if (isCancelled(control)) {
                    throw new OperationCanceledException();
                }
                holder = ensureClient(context, entry);
                channel = holder.borrowChannel();
                ensureRemoteDirectoryExists(channel, parentRemotePath(task.remotePath));
                long localSize = task.size > 0 ? task.size : task.localFile.length();

                try {
                    SftpATTRS finalAttrs = channel.stat(normalizeRemotePath(task.remotePath));
                    if (finalAttrs != null) {
                        if (finalAttrs.isDir()) {
                            throw new IllegalStateException("\u8fdc\u7a0b\u76ee\u6807\u8def\u5f84\u88ab\u76ee\u5f55\u5360\u7528\uff1a" + task.remotePath);
                        }
                        progressState.currentFile = buildTransferStageLabel(task.localFile.getName(), task.remotePath, "\u6821\u9a8c\u5df2\u5b58\u5728\u6587\u4ef6");
                        emitUploadProgress(listener, progressState, true);
                        verifyUploadedTempFile(channel, task.remotePath, task.localFile, task.size, control);
                        if (progressState.currentFileSize <= 0 && localSize > 0L) {
                            progressState.currentFileSize = localSize;
                        }
                        progressState.currentFileTransferred =
                            progressState.currentFileSize > 0
                                ? progressState.currentFileSize
                                : localSize;
                        emitUploadProgress(listener, progressState, true);
                        return Math.max(0L, localSize);
                    }
                } catch (SftpException e) {
                    if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                        throw e;
                    }
                } catch (OperationCanceledException e) {
                    throw e;
                } catch (Exception ignored) {
                }

                tempRemotePath = buildRemoteTransferTempPath(task.remotePath);
                SftpTransferJournal.getInstance().addRemoteTempPath(
                    context,
                    journalHandle,
                    clientKeyForEntry(entry),
                    tempRemotePath
                );
                long resumeOffset = 0L;
                int putMode = ChannelSftp.OVERWRITE;
                try {
                    SftpATTRS tempAttrs = channel.stat(normalizeRemotePath(tempRemotePath));
                    if (tempAttrs != null) {
                        if (tempAttrs.isDir()) {
                            throw new IllegalStateException("\u8fdc\u7a0b\u4e34\u65f6\u8def\u5f84\u88ab\u76ee\u5f55\u5360\u7528\uff1a" + tempRemotePath);
                        }
                        resumeOffset = Math.max(0L, tempAttrs.getSize());
                    }
                } catch (SftpException e) {
                    if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                        throw e;
                    }
                }
                if (resumeOffset > localSize) {
                    deleteRemoteFileIfExists(channel, tempRemotePath);
                    resumeOffset = 0L;
                }
                if (resumeOffset > 0L) {
                    currentTransferred[0] = resumeOffset;
                    progressState.currentFileTransferred = resumeOffset;
                    emitUploadProgress(listener, progressState, true);
                }
                if (localSize > 0L && resumeOffset == localSize) {
                    progressState.currentFile = buildTransferStageLabel(task.localFile.getName(), task.remotePath, "\u6821\u9a8c\u4e34\u65f6\u6587\u4ef6");
                    emitUploadProgress(listener, progressState, true);
                    verifyUploadedTempFile(channel, tempRemotePath, task.localFile, task.size, control);
                    progressState.currentFile = buildTransferStageLabel(task.localFile.getName(), task.remotePath, "\u63d0\u4ea4\u8fdc\u7a0b\u66ff\u6362");
                    emitUploadProgress(listener, progressState, true);
                    replaceRemoteFile(channel, tempRemotePath, task.remotePath);
                    SftpTransferJournal.getInstance().clearRemoteTempPath(
                        context,
                        journalHandle,
                        clientKeyForEntry(entry),
                        tempRemotePath
                    );
                    tempRemotePath = null;
                    return localSize;
                }
                if (resumeOffset > 0L) {
                    putMode = ChannelSftp.RESUME;
                }

                SftpProgressMonitor monitor = new SftpProgressMonitor() {
                    @Override
                    public void init(int op, String src, String dest, long max) {
                        if (max > 0 && progressState.currentFileSize <= 0) {
                            progressState.currentFileSize = max;
                        }
                    }

                    @Override
                    public boolean count(long bytes) {
                        if (isCancelled(control)) {
                            return false;
                        }
                        if (bytes <= 0) return true;
                        currentTransferred[0] += bytes;
                        if (progressState.currentFileSize > 0) {
                            progressState.currentFileTransferred =
                                Math.min(progressState.currentFileSize, currentTransferred[0]);
                        } else {
                            progressState.currentFileTransferred = currentTransferred[0];
                        }
                        emitUploadProgress(listener, progressState, false);
                        return true;
                    }

                    @Override
                    public void end() {
                    }
                };
                channel.put(task.localFile.getAbsolutePath(), tempRemotePath, monitor, putMode);

                if (task.modifiedMs > 0L) {
                    try {
                        channel.setMtime(tempRemotePath, (int) (task.modifiedMs / 1000L));
                    } catch (Throwable ignored) {
                    }
                }

                progressState.currentFile = buildTransferStageLabel(task.localFile.getName(), task.remotePath, "\u6821\u9a8c\u8fdc\u7a0b\u4e34\u65f6\u6587\u4ef6");
                emitUploadProgress(listener, progressState, true);
                verifyUploadedTempFile(channel, tempRemotePath, task.localFile, task.size, control);
                progressState.currentFile = buildTransferStageLabel(task.localFile.getName(), task.remotePath, "\u63d0\u4ea4\u8fdc\u7a0b\u66ff\u6362");
                emitUploadProgress(listener, progressState, true);
                replaceRemoteFile(channel, tempRemotePath, task.remotePath);
                SftpTransferJournal.getInstance().clearRemoteTempPath(
                    context,
                    journalHandle,
                    clientKeyForEntry(entry),
                    tempRemotePath
                );
                tempRemotePath = null;

                long fileBytes = task.size > 0 ? task.size : task.localFile.length();
                if (fileBytes <= 0) {
                    fileBytes = currentTransferred[0];
                }
                progressState.currentFileTransferred =
                    progressState.currentFileSize > 0
                        ? progressState.currentFileSize
                        : Math.max(fileBytes, currentTransferred[0]);
                return Math.max(0L, fileBytes);
            } catch (Exception e) {
                if (isCancelled(control)) {
                    throw new OperationCanceledException();
                }
                lastError = e;
                channelBroken = true;
                progressState.currentFileTransferred = Math.max(0L, currentTransferred[0]);
                emitUploadProgress(listener, progressState, true);

                if (attempt >= maxAttempts - 1 || !isRecoverableTransportException(e)) {
                    throw e;
                }
                clearSessionByEntry(entry);
                try {
                    Thread.sleep(160L * (attempt + 1));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                if (holder != null && channel != null) {
                    holder.releaseChannel(channel, channelBroken);
                }
            }
        }
        throw lastError == null ? new IllegalStateException("SFTP upload failed") : lastError;
    }

    private void collectDownloadTasks(@NonNull Context context,
                                      @NonNull VirtualTarget target,
                                      @NonNull File topLevelLocal,
                                      @NonNull ArrayList<DownloadFileTask> tasks,
                                      @NonNull Set<String> visitedRemoteDirectories,
                                      @NonNull Set<String> visitedRemoteFiles,
                                      @NonNull Set<String> preparedLocalDirectories,
                                      @Nullable DownloadControl control) throws Exception {
        withReconnectRetry(context, target.entry, channel -> {
            if (isCancelled(control)) {
                throw new OperationCanceledException();
            }
            collectDownloadTasksRecursive(channel, target.entry, target.remotePath, topLevelLocal, null,
                tasks, visitedRemoteDirectories, visitedRemoteFiles, preparedLocalDirectories, control);
            return null;
        });
    }

    private void collectDownloadTasksRecursive(@NonNull ChannelSftp channel,
                                               @NonNull SessionEntry entry,
                                               @NonNull String remotePath,
                                               @NonNull File localPath,
                                               @Nullable SftpATTRS knownAttrs,
                                               @NonNull ArrayList<DownloadFileTask> tasks,
                                               @NonNull Set<String> visitedRemoteDirectories,
                                               @NonNull Set<String> visitedRemoteFiles,
                                               @NonNull Set<String> preparedLocalDirectories,
                                               @Nullable DownloadControl control) throws Exception {
        if (isCancelled(control)) {
            throw new OperationCanceledException();
        }
        SftpATTRS attrs = knownAttrs != null ? knownAttrs : channel.stat(remotePath);
        if (attrs == null) {
            throw new IllegalStateException("\u8fdc\u7aef\u8def\u5f84\u4e0d\u5b58\u5728\u3002");
        }

        String clientKey = clientKeyForEntry(entry);
        String normalizedRemote = normalizeRemotePath(remotePath);
        if (attrs.isDir()) {
            String dirKey = clientKey + "|" + normalizedRemote;
            if (!visitedRemoteDirectories.add(dirKey)) return;

            preparedLocalDirectories.add(localPath.getAbsolutePath());

            Vector<?> rows = channel.ls(normalizedRemote);
            for (Object row : rows) {
                if (!(row instanceof ChannelSftp.LsEntry)) continue;
                ChannelSftp.LsEntry item = (ChannelSftp.LsEntry) row;
                if (item == null) continue;
                String name = item.getFilename();
                if (TextUtils.isEmpty(name) || ".".equals(name) || "..".equals(name)) continue;
                SftpATTRS childAttrs = item.getAttrs();
                if (childAttrs == null) continue;

                String childRemote = joinRemotePath(normalizedRemote, name);
                File childLocal = new File(localPath, name);
                collectDownloadTasksRecursive(channel, entry, childRemote, childLocal, childAttrs,
                    tasks, visitedRemoteDirectories, visitedRemoteFiles, preparedLocalDirectories, control);
            }
            return;
        }

        String fileKey = clientKey + "|" + normalizedRemote;
        if (!visitedRemoteFiles.add(fileKey)) return;
        tasks.add(new DownloadFileTask(
            entry,
            normalizedRemote,
            localPath,
            Math.max(0L, attrs.getSize()),
            ((long) attrs.getMTime()) * 1000L
        ));
    }

    private long downloadSingleFile(@NonNull Context context,
                                    @NonNull SessionEntry entry,
                                    @NonNull String remotePath,
                                    @NonNull File outputFile,
                                    @NonNull DownloadProgressState progressState,
                                    @Nullable DownloadProgressListener listener,
                                    @Nullable DownloadControl control,
                                    @Nullable SftpTransferJournal.TaskHandle journalHandle) throws Exception {
        Exception lastError = null;
        int maxAttempts = RECOVERABLE_RETRY_COUNT + 1;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            ClientHolder holder = null;
            ChannelSftp channel = null;
            boolean channelBroken = false;
            final long[] currentTransferred = new long[]{0L};
            File tempFile = buildLocalTransferTempFile(outputFile);
            try {
                if (isCancelled(control)) {
                    throw new OperationCanceledException();
                }
                holder = ensureClient(context, entry);
                channel = holder.borrowChannel();
                SftpATTRS remoteAttrs = channel.stat(normalizeRemotePath(remotePath));
                if (remoteAttrs == null || remoteAttrs.isDir()) {
                    throw new IllegalStateException("\u8fdc\u7a0b\u6587\u4ef6\u4e0d\u53ef\u4e0b\u8f7d\uff1a" + remotePath);
                }
                long remoteSize = Math.max(0L, remoteAttrs.getSize());
                if (progressState.currentFileSize <= 0 && remoteSize > 0L) {
                    progressState.currentFileSize = remoteSize;
                }

                if (outputFile.exists() && outputFile.isDirectory()) {
                    throw new IllegalStateException("\u672c\u5730\u76ee\u6807\u8def\u5f84\u88ab\u76ee\u5f55\u5360\u7528\uff1a" + outputFile.getAbsolutePath());
                }
                if (outputFile.exists()) {
                    try {
                        progressState.currentFile = buildTransferStageLabel(outputFile.getName(), remotePath, "\u6821\u9a8c\u672c\u5730\u5df2\u5b58\u5728\u6587\u4ef6");
                        emitDownloadProgress(listener, progressState, true);
                        verifyDownloadedTempFile(channel, remotePath, outputFile, control);
                        progressState.currentFileTransferred =
                            progressState.currentFileSize > 0
                                ? progressState.currentFileSize
                                : remoteSize;
                        emitDownloadProgress(listener, progressState, true);
                        return Math.max(0L, outputFile.length());
                    } catch (OperationCanceledException e) {
                        throw e;
                    } catch (Exception ignored) {
                    }
                }

                if (tempFile.exists() && tempFile.isDirectory()) {
                    throw new IllegalStateException("\u672c\u5730\u4e34\u65f6\u6587\u4ef6\u8def\u5f84\u88ab\u76ee\u5f55\u5360\u7528\uff1a" + tempFile.getAbsolutePath());
                }
                SftpTransferJournal.getInstance().addLocalTempPath(
                    context,
                    journalHandle,
                    tempFile.getAbsolutePath()
                );
                long resumeOffset = tempFile.exists() ? Math.max(0L, tempFile.length()) : 0L;
                if (resumeOffset > remoteSize) {
                    try {
                        tempFile.delete();
                    } catch (Throwable ignored) {
                    }
                    resumeOffset = 0L;
                }
                if (resumeOffset > 0L) {
                    currentTransferred[0] = resumeOffset;
                    progressState.currentFileTransferred = resumeOffset;
                    emitDownloadProgress(listener, progressState, true);
                }
                if (remoteSize > 0L && resumeOffset == remoteSize) {
                    progressState.currentFile = buildTransferStageLabel(outputFile.getName(), remotePath, "\u6821\u9a8c\u4e34\u65f6\u6587\u4ef6");
                    emitDownloadProgress(listener, progressState, true);
                    verifyDownloadedTempFile(channel, remotePath, tempFile, control);
                    progressState.currentFile = buildTransferStageLabel(outputFile.getName(), remotePath, "\u63d0\u4ea4\u672c\u5730\u66ff\u6362");
                    emitDownloadProgress(listener, progressState, true);
                    moveLocalTransferFile(tempFile, outputFile);
                    SftpTransferJournal.getInstance().clearLocalTempPath(
                        context,
                        journalHandle,
                        tempFile.getAbsolutePath()
                    );
                    progressState.currentFileTransferred =
                        progressState.currentFileSize > 0
                            ? progressState.currentFileSize
                            : remoteSize;
                    emitDownloadProgress(listener, progressState, true);
                    return Math.max(0L, outputFile.length());
                }

                boolean append = resumeOffset > 0L;
                int getMode = append ? ChannelSftp.RESUME : ChannelSftp.OVERWRITE;
                try (FileOutputStream outputStream = new FileOutputStream(tempFile, append)) {
                    SftpProgressMonitor monitor = new SftpProgressMonitor() {
                        @Override
                        public void init(int op, String src, String dest, long max) {
                            if (max > 0 && progressState.currentFileSize <= 0) {
                                progressState.currentFileSize = max;
                            }
                        }

                        @Override
                        public boolean count(long bytes) {
                            if (isCancelled(control)) {
                                return false;
                            }
                            if (bytes <= 0) return true;
                            currentTransferred[0] += bytes;
                            if (progressState.currentFileSize > 0) {
                                progressState.currentFileTransferred =
                                    Math.min(progressState.currentFileSize, currentTransferred[0]);
                            } else {
                                progressState.currentFileTransferred = currentTransferred[0];
                            }
                            emitDownloadProgress(listener, progressState, false);
                            return true;
                        }

                        @Override
                        public void end() {
                        }
                    };
                    channel.get(remotePath, outputStream, monitor, getMode, resumeOffset);
                    outputStream.flush();
                    try {
                        outputStream.getFD().sync();
                    } catch (Throwable ignored) {
                    }
                }
                progressState.currentFile = buildTransferStageLabel(outputFile.getName(), remotePath, "\u6821\u9a8c\u4e0b\u8f7d\u6587\u4ef6");
                emitDownloadProgress(listener, progressState, true);
                verifyDownloadedTempFile(channel, remotePath, tempFile, control);
                progressState.currentFile = buildTransferStageLabel(outputFile.getName(), remotePath, "\u63d0\u4ea4\u672c\u5730\u66ff\u6362");
                emitDownloadProgress(listener, progressState, true);
                moveLocalTransferFile(tempFile, outputFile);
                SftpTransferJournal.getInstance().clearLocalTempPath(
                    context,
                    journalHandle,
                    tempFile.getAbsolutePath()
                );

                long fileBytes = outputFile.length();
                if (fileBytes <= 0) {
                    fileBytes = currentTransferred[0];
                }
                if (progressState.currentFileSize > 0 && fileBytes <= 0) {
                    fileBytes = progressState.currentFileSize;
                }
                progressState.currentFileTransferred =
                    progressState.currentFileSize > 0
                        ? progressState.currentFileSize
                        : Math.max(fileBytes, currentTransferred[0]);
                return Math.max(0L, fileBytes);
            } catch (Exception e) {
                if (isCancelled(control)) {
                    throw new OperationCanceledException();
                }
                lastError = e;
                channelBroken = true;
                progressState.currentFileTransferred = Math.max(0L, currentTransferred[0]);
                emitDownloadProgress(listener, progressState, true);

                if (attempt >= maxAttempts - 1 || !isRecoverableTransportException(e)) {
                    throw e;
                }
                clearSessionByEntry(entry);
                try {
                    Thread.sleep(160L * (attempt + 1));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                if (holder != null && channel != null) {
                    holder.releaseChannel(channel, channelBroken);
                }
            }
        }
        throw lastError == null ? new IllegalStateException("SFTP download failed") : lastError;
    }

    private static void emitDownloadProgress(@Nullable DownloadProgressListener listener,
                                             @NonNull DownloadProgressState state,
                                             boolean force) {
        if (listener == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - state.lastDispatchAtMs < DOWNLOAD_PROGRESS_MIN_INTERVAL_MS) {
            return;
        }
        state.lastDispatchAtMs = now;

        long transferredBytes = state.downloadedBytes + Math.max(0L, state.currentFileTransferred);
        if (state.totalBytes > 0 && transferredBytes > state.totalBytes) {
            transferredBytes = state.totalBytes;
        }
        if (transferredBytes < 0) transferredBytes = 0;

        DownloadProgress progress = new DownloadProgress(
            state.totalFiles,
            state.completedFiles,
            state.failedFiles,
            state.totalBytes,
            transferredBytes,
            state.currentFile == null ? "" : state.currentFile,
            Math.max(0L, state.currentFileTransferred),
            Math.max(0L, state.currentFileSize)
        );
        try {
            listener.onProgress(progress);
        } catch (Throwable ignored) {
        }
    }

    private static void emitUploadProgress(@Nullable UploadProgressListener listener,
                                           @NonNull UploadProgressState state,
                                           boolean force) {
        if (listener == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - state.lastDispatchAtMs < DOWNLOAD_PROGRESS_MIN_INTERVAL_MS) {
            return;
        }
        state.lastDispatchAtMs = now;

        long transferredBytes = state.uploadedBytes + Math.max(0L, state.currentFileTransferred);
        if (state.totalBytes > 0 && transferredBytes > state.totalBytes) {
            transferredBytes = state.totalBytes;
        }
        if (transferredBytes < 0) transferredBytes = 0;

        UploadProgress progress = new UploadProgress(
            state.totalFiles,
            state.completedFiles,
            state.failedFiles,
            state.totalBytes,
            transferredBytes,
            state.currentFile == null ? "" : state.currentFile,
            Math.max(0L, state.currentFileTransferred),
            Math.max(0L, state.currentFileSize)
        );
        try {
            listener.onProgress(progress);
        } catch (Throwable ignored) {
        }
    }

    private static void emitDownloadProgress(@Nullable DownloadProgressListener listener,
                                             @NonNull ConcurrentTransferProgressState state,
                                             boolean force) {
        if (listener == null) return;
        ConcurrentTransferProgressSnapshot snapshot = state.snapshot(force);
        if (snapshot == null) return;

        DownloadProgress progress = new DownloadProgress(
            snapshot.totalFiles,
            snapshot.completedFiles,
            snapshot.failedFiles,
            snapshot.totalBytes,
            snapshot.transferredBytes,
            snapshot.currentFile,
            snapshot.currentFileTransferred,
            snapshot.currentFileSize
        );
        try {
            listener.onProgress(progress);
        } catch (Throwable ignored) {
        }
    }

    private static void emitUploadProgress(@Nullable UploadProgressListener listener,
                                           @NonNull ConcurrentTransferProgressState state,
                                           boolean force) {
        if (listener == null) return;
        ConcurrentTransferProgressSnapshot snapshot = state.snapshot(force);
        if (snapshot == null) return;

        UploadProgress progress = new UploadProgress(
            snapshot.totalFiles,
            snapshot.completedFiles,
            snapshot.failedFiles,
            snapshot.totalBytes,
            snapshot.transferredBytes,
            snapshot.currentFile,
            snapshot.currentFileTransferred,
            snapshot.currentFileSize
        );
        try {
            listener.onProgress(progress);
        } catch (Throwable ignored) {
        }
    }

    private static void emitRemoteTransferProgress(@Nullable RemoteTransferProgressListener listener,
                                                   @NonNull RemoteTransferWorkflowStateMachine.Snapshot snapshot) {
        if (listener == null) return;
        RemoteTransferProgress progress = new RemoteTransferProgress(
            snapshot.stage.name(),
            RemoteTransferWorkflowStateMachine.stageLabelCn(snapshot.stage),
            snapshot.totalFiles,
            snapshot.completedFiles,
            snapshot.failedFiles,
            snapshot.totalBytes,
            snapshot.transferredBytes,
            snapshot.currentFile,
            snapshot.currentFileTransferred,
            snapshot.currentFileSize,
            snapshot.messageCn
        );
        try {
            listener.onProgress(progress);
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    private static DownloadProgressListener wrapDownloadProgressListener(@NonNull Context context,
                                                                        @Nullable SftpTransferJournal.TaskHandle handle,
                                                                        @Nullable DownloadProgressListener delegate) {
        if (handle == null && delegate == null) return null;
        return progress -> {
            if (handle != null) {
                SftpTransferJournal.getInstance().updateProgress(
                    context,
                    handle,
                    progress.totalFiles,
                    progress.completedFiles,
                    progress.failedFiles,
                    progress.totalBytes,
                    progress.transferredBytes,
                    progress.currentFile
                );
            }
            if (delegate != null) {
                delegate.onProgress(progress);
            }
        };
    }

    @Nullable
    private static UploadProgressListener wrapUploadProgressListener(@NonNull Context context,
                                                                    @Nullable SftpTransferJournal.TaskHandle handle,
                                                                    @Nullable UploadProgressListener delegate) {
        if (handle == null && delegate == null) return null;
        return progress -> {
            if (handle != null) {
                SftpTransferJournal.getInstance().updateProgress(
                    context,
                    handle,
                    progress.totalFiles,
                    progress.completedFiles,
                    progress.failedFiles,
                    progress.totalBytes,
                    progress.transferredBytes,
                    progress.currentFile
                );
            }
            if (delegate != null) {
                delegate.onProgress(progress);
            }
        };
    }

    @Nullable
    private static RemoteTransferProgressListener wrapRemoteTransferProgressListener(@NonNull Context context,
                                                                                    @Nullable SftpTransferJournal.TaskHandle handle,
                                                                                    @Nullable RemoteTransferProgressListener delegate) {
        if (handle == null && delegate == null) return null;
        return progress -> {
            if (handle != null) {
                String currentFile = progress.currentFile;
                if (!TextUtils.isEmpty(progress.stageLabelCn)) {
                    currentFile = progress.stageLabelCn + (TextUtils.isEmpty(currentFile) ? "" : (": " + currentFile));
                }
                SftpTransferJournal.getInstance().updateProgress(
                    context,
                    handle,
                    progress.totalFiles,
                    progress.completedFiles,
                    progress.failedFiles,
                    progress.totalBytes,
                    progress.transferredBytes,
                    currentFile
                );
            }
            if (delegate != null) {
                delegate.onProgress(progress);
            }
        };
    }

    private static void finishDownloadJournal(@NonNull Context context,
                                              @Nullable SftpTransferJournal.TaskHandle handle,
                                              @NonNull DownloadResult result) {
        if (handle == null) return;
        SftpTransferJournal.getInstance().finishTask(
            context,
            handle,
            result.success
                ? SftpTransferJournal.TaskStatus.COMPLETED
                : (isCancelledMessage(result.messageCn)
                    ? SftpTransferJournal.TaskStatus.CANCELLED
                    : SftpTransferJournal.TaskStatus.FAILED),
            result.messageCn,
            result.totalFiles,
            result.downloadedFiles,
            result.failedFiles,
            result.totalBytes,
            result.downloadedBytes
        );
    }

    private static void finishUploadJournal(@NonNull Context context,
                                            @Nullable SftpTransferJournal.TaskHandle handle,
                                            @NonNull UploadResult result) {
        if (handle == null) return;
        SftpTransferJournal.getInstance().finishTask(
            context,
            handle,
            result.success
                ? SftpTransferJournal.TaskStatus.COMPLETED
                : (isCancelledMessage(result.messageCn)
                    ? SftpTransferJournal.TaskStatus.CANCELLED
                    : SftpTransferJournal.TaskStatus.FAILED),
            result.messageCn,
            result.totalFiles,
            result.uploadedFiles,
            result.failedFiles,
            result.totalBytes,
            result.uploadedBytes
        );
    }

    private static void finishRemoteTransferJournal(@NonNull Context context,
                                                    @Nullable SftpTransferJournal.TaskHandle handle,
                                                    @NonNull RemoteTransferResult result) {
        if (handle == null) return;
        SftpTransferJournal.getInstance().finishTask(
            context,
            handle,
            result.success
                ? SftpTransferJournal.TaskStatus.COMPLETED
                : (isCancelledMessage(result.messageCn)
                    ? SftpTransferJournal.TaskStatus.CANCELLED
                    : SftpTransferJournal.TaskStatus.FAILED),
            result.messageCn,
            result.totalFiles,
            result.transferredFiles,
            result.failedFiles,
            result.totalBytes,
            result.transferredBytes
        );
    }

    @NonNull
    private static String buildRemoteTransferTempPath(@NonNull String remotePath) {
        String normalized = normalizeRemotePath(remotePath);
        String parent = parentRemotePath(normalized);
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (TextUtils.isEmpty(name)) {
            name = "upload-item";
        }
        return joinRemotePath(
            parent,
            "." + name + ".termux-upload-" + shortSha1Hex(normalized) + ".part"
        );
    }

    private static void deleteRemoteFileIfExists(@NonNull ChannelSftp channel,
                                                 @NonNull String remotePath) throws Exception {
        String normalized = normalizeRemotePath(remotePath);
        try {
            SftpATTRS attrs = channel.stat(normalized);
            if (attrs == null) {
                return;
            }
            if (attrs.isDir()) {
                throw new IllegalStateException("\u8fdc\u7a0b\u4e34\u65f6\u8def\u5f84\u88ab\u76ee\u5f55\u5360\u7528\uff1a" + normalized);
            }
            channel.rm(normalized);
        } catch (SftpException e) {
            if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                throw e;
            }
        }
    }

    private static void deleteRemotePathRecursive(@NonNull ChannelSftp channel,
                                                  @NonNull String remotePath) throws Exception {
        deleteRemotePathRecursive(channel, remotePath, null);
    }

    private static void deleteRemotePathRecursive(@NonNull ChannelSftp channel,
                                                  @NonNull String remotePath,
                                                  @Nullable RemoteDeleteControl control) throws Exception {
        if (isRemoteDeleteCancelled(control)) throw new OperationCanceledException();
        String normalized = normalizeRemotePath(remotePath);
        SftpATTRS attrs;
        try {
            attrs = channel.stat(normalized);
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return;
            }
            throw e;
        }
        if (attrs == null) return;

        if (isRemoteDeleteCancelled(control)) throw new OperationCanceledException();
        if (!attrs.isDir()) {
            deleteRemoteFileIfExists(channel, normalized);
            return;
        }

        Vector<?> rows = channel.ls(normalized);
        for (Object row : rows) {
            if (isRemoteDeleteCancelled(control)) throw new OperationCanceledException();
            if (!(row instanceof ChannelSftp.LsEntry)) continue;
            ChannelSftp.LsEntry item = (ChannelSftp.LsEntry) row;
            String name = item.getFilename();
            if (TextUtils.isEmpty(name) || ".".equals(name) || "..".equals(name)) continue;
            deleteRemotePathRecursive(channel, joinRemotePath(normalized, name), control);
        }

        if (isRemoteDeleteCancelled(control)) throw new OperationCanceledException();
        try {
            channel.rmdir(normalized);
        } catch (SftpException e) {
            if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                throw e;
            }
        }
    }

    private static void replaceRemoteFile(@NonNull ChannelSftp channel,
                                          @NonNull String stagedRemotePath,
                                          @NonNull String targetRemotePath) throws Exception {
        String normalizedTarget = normalizeRemotePath(targetRemotePath);
        String backupRemotePath = null;
        try {
            SftpATTRS existing = channel.stat(normalizedTarget);
            if (existing != null) {
                if (existing.isDir()) {
                    throw new IllegalStateException("\u8fdc\u7a0b\u76ee\u6807\u662f\u76ee\u5f55\uff0c\u65e0\u6cd5\u8986\u76d6\uff1a" + normalizedTarget);
                }
                backupRemotePath = buildRemoteBackupPath(normalizedTarget);
                deleteRemoteFileIfExists(channel, backupRemotePath);
                channel.rename(normalizedTarget, backupRemotePath);
            }
        } catch (SftpException e) {
            if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                throw e;
            }
        }
        boolean committed = false;
        try {
            channel.rename(normalizeRemotePath(stagedRemotePath), normalizedTarget);
            committed = true;
        } finally {
            if (!committed && !TextUtils.isEmpty(backupRemotePath)) {
                try {
                    channel.rename(backupRemotePath, normalizedTarget);
                } catch (Throwable ignored) {
                }
            }
        }
        if (!TextUtils.isEmpty(backupRemotePath)) {
            deleteRemoteFileIfExists(channel, backupRemotePath);
        }
    }

    @NonNull
    private static String buildRemoteBackupPath(@NonNull String remotePath) {
        String normalized = normalizeRemotePath(remotePath);
        String parent = parentRemotePath(normalized);
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (TextUtils.isEmpty(name)) {
            name = "target-item";
        }
        return joinRemotePath(
            parent,
            "." + name + ".termux-backup-" + TRANSFER_TEMP_COUNTER.getAndIncrement() + ".bak"
        );
    }

    @NonNull
    private static File buildLocalTransferTempFile(@NonNull File outputFile) {
        File parent = outputFile.getParentFile();
        String name = outputFile.getName();
        if (TextUtils.isEmpty(name)) {
            name = "download-item";
        }
        String tempName = "." + name + ".termux-download-" + shortSha1Hex(outputFile.getAbsolutePath()) + ".part";
        return parent == null ? new File(tempName) : new File(parent, tempName);
    }

    private static void moveLocalTransferFile(@NonNull File tempFile,
                                              @NonNull File outputFile) throws Exception {
        if (outputFile.exists() && !outputFile.delete()) {
            throw new IllegalStateException("\u65e0\u6cd5\u66ff\u6362\u672c\u5730\u76ee\u6807\u6587\u4ef6\uff1a" + outputFile.getAbsolutePath());
        }
        if (tempFile.renameTo(outputFile)) {
            return;
        }
        copyLocalFileContents(tempFile, outputFile);
        if (!tempFile.delete()) {
            try {
                tempFile.deleteOnExit();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void copyLocalFileContents(@NonNull File source,
                                              @NonNull File target) throws Exception {
        try (InputStream inputStream = new FileInputStream(source);
             FileOutputStream outputStream = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                if (read == 0) continue;
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();
            try {
                outputStream.getFD().sync();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void verifyUploadedTempFile(@NonNull ChannelSftp channel,
                                               @NonNull String remoteTempPath,
                                               @NonNull File sourceFile,
                                               long declaredSize,
                                               @Nullable UploadControl control) throws Exception {
        SftpATTRS attrs = channel.stat(normalizeRemotePath(remoteTempPath));
        if (attrs == null || attrs.isDir()) {
            throw new IllegalStateException("\u8fdc\u7a0b\u4e34\u65f6\u6587\u4ef6\u7f3a\u5931\u6216\u7c7b\u578b\u9519\u8bef\u3002");
        }
        long expectedSize = declaredSize > 0 ? declaredSize : sourceFile.length();
        long remoteSize = Math.max(0L, attrs.getSize());
        if (expectedSize >= 0L && remoteSize != expectedSize) {
            throw new IllegalStateException(
                "\u4e0a\u4f20\u540e\u8fdc\u7a0b\u5927\u5c0f\u6821\u9a8c\u5931\u8d25\uff1aexpected=" + expectedSize + " actual=" + remoteSize
            );
        }
        if (expectedSize > 0L && expectedSize <= FULL_DIGEST_VERIFY_MAX_BYTES) {
            String localDigest = computeLocalSha256(sourceFile, control == null ? null : control::isCancelled);
            String remoteDigest = computeRemoteSha256(channel, remoteTempPath, control == null ? null : control::isCancelled);
            if (!TextUtils.equals(localDigest, remoteDigest)) {
                throw new IllegalStateException("\u4e0a\u4f20\u540e\u8fdc\u7a0b SHA-256 \u6821\u9a8c\u5931\u8d25\u3002");
            }
        } else if (expectedSize > FULL_DIGEST_VERIFY_MAX_BYTES) {
            String localDigest = computeLocalSampleDigest(sourceFile, expectedSize, control == null ? null : control::isCancelled);
            String remoteDigest = computeRemoteSampleDigest(channel, remoteTempPath, expectedSize, control == null ? null : control::isCancelled);
            if (!TextUtils.equals(localDigest, remoteDigest)) {
                throw new IllegalStateException("\u4e0a\u4f20\u540e\u8fdc\u7a0b\u91c7\u6837\u6821\u9a8c\u5931\u8d25\u3002");
            }
        }
    }

    private static void verifyUploadedTempFileSizeOnly(@NonNull ChannelSftp channel,
                                                       @NonNull String remoteTempPath,
                                                       long expectedSize) throws Exception {
        SftpATTRS attrs = channel.stat(normalizeRemotePath(remoteTempPath));
        if (attrs == null || attrs.isDir()) {
            throw new IllegalStateException("\u8fdc\u7a0b\u4e34\u65f6\u6587\u4ef6\u7f3a\u5931\u6216\u7c7b\u578b\u9519\u8bef\u3002");
        }
        long remoteSize = Math.max(0L, attrs.getSize());
        if (expectedSize >= 0L && remoteSize != expectedSize) {
            throw new IllegalStateException(
                "\u4e0a\u4f20\u540e\u8fdc\u7a0b\u5927\u5c0f\u6821\u9a8c\u5931\u8d25\uff1aexpected=" + expectedSize + " actual=" + remoteSize
            );
        }
    }

    private static void verifyDownloadedTempFile(@NonNull ChannelSftp channel,
                                                 @NonNull String remotePath,
                                                 @NonNull File tempFile,
                                                 @Nullable DownloadControl control) throws Exception {
        SftpATTRS attrs = channel.stat(normalizeRemotePath(remotePath));
        if (attrs == null || attrs.isDir()) {
            throw new IllegalStateException("\u8fdc\u7a0b\u6e90\u6587\u4ef6\u7f3a\u5931\u6216\u7c7b\u578b\u9519\u8bef\u3002");
        }
        long remoteSize = Math.max(0L, attrs.getSize());
        long localSize = Math.max(0L, tempFile.length());
        if (remoteSize != localSize) {
            throw new IllegalStateException(
                "\u4e0b\u8f7d\u540e\u672c\u5730\u5927\u5c0f\u6821\u9a8c\u5931\u8d25\uff1aexpected=" + remoteSize + " actual=" + localSize
            );
        }
        if (remoteSize > 0L && remoteSize <= FULL_DIGEST_VERIFY_MAX_BYTES) {
            String localDigest = computeLocalSha256(tempFile, control == null ? null : control::isCancelled);
            String remoteDigest = computeRemoteSha256(channel, remotePath, control == null ? null : control::isCancelled);
            if (!TextUtils.equals(localDigest, remoteDigest)) {
                throw new IllegalStateException("\u4e0b\u8f7d\u540e SHA-256 \u6821\u9a8c\u5931\u8d25\u3002");
            }
        } else if (remoteSize > FULL_DIGEST_VERIFY_MAX_BYTES) {
            String localDigest = computeLocalSampleDigest(tempFile, remoteSize, control == null ? null : control::isCancelled);
            String remoteDigest = computeRemoteSampleDigest(channel, remotePath, remoteSize, control == null ? null : control::isCancelled);
            if (!TextUtils.equals(localDigest, remoteDigest)) {
                throw new IllegalStateException("\u4e0b\u8f7d\u540e\u91c7\u6837\u6821\u9a8c\u5931\u8d25\u3002");
            }
        }
    }

    @NonNull
    private static String computeLocalSha256(@NonNull File file,
                                             @Nullable CancelProbe cancelProbe) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream inputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[TRANSFER_DIGEST_BUFFER_BYTES];
            while (true) {
                if (cancelProbe != null && cancelProbe.isCancelled()) {
                    throw new OperationCanceledException();
                }
                int read = inputStream.read(buffer);
                if (read < 0) break;
                if (read == 0) continue;
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    @NonNull
    private String computeRemoteSha256PreferNative(@NonNull Context context,
                                                   @NonNull SessionEntry entry,
                                                   @NonNull ChannelSftp channel,
                                                   @NonNull String remotePath,
                                                   @Nullable CancelProbe cancelProbe) throws Exception {
        try {
            String nativeDigest = computeRemoteSha256ViaNativeCommand(context, entry, remotePath, cancelProbe);
            if (!TextUtils.isEmpty(nativeDigest)) {
                return nativeDigest;
            }
        } catch (OperationCanceledException e) {
            throw e;
        } catch (Exception ignored) {
        }
        return computeRemoteSha256(channel, remotePath, cancelProbe);
    }

    @NonNull
    private String computeRemoteSha256ViaNativeCommand(@NonNull Context context,
                                                       @NonNull SessionEntry entry,
                                                       @NonNull String remotePath,
                                                       @Nullable CancelProbe cancelProbe) throws Exception {
        String quoted = shellQuoteSingle(normalizeRemotePath(remotePath));
        String[] commands = new String[] {
            "sha256sum -- " + quoted,
            "shasum -a 256 -- " + quoted,
            "openssl dgst -sha256 -r -- " + quoted,
            "openssl dgst -sha256 -- " + quoted
        };
        for (String command : commands) {
            if (cancelProbe != null && cancelProbe.isCancelled()) {
                throw new OperationCanceledException();
            }
            RemoteCommandResult result;
            try {
                result = withExecReconnectRetry(context, entry, command, cancelProbe == null ? null : cancelProbe::isCancelled);
            } catch (OperationCanceledException e) {
                throw e;
            } catch (Exception ignored) {
                continue;
            }
            if (result.exitCode == 126 || result.exitCode == 127) {
                continue;
            }
            if (!result.success) {
                continue;
            }
            String digest = extractSha256Hex(result.stdout);
            if (!TextUtils.isEmpty(digest)) {
                return digest;
            }
        }
        return "";
    }

    @NonNull
    private static String computeRemoteSha256(@NonNull ChannelSftp channel,
                                              @NonNull String remotePath,
                                              @Nullable CancelProbe cancelProbe) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream inputStream = channel.get(normalizeRemotePath(remotePath))) {
            byte[] buffer = new byte[TRANSFER_DIGEST_BUFFER_BYTES];
            while (true) {
                if (cancelProbe != null && cancelProbe.isCancelled()) {
                    throw new OperationCanceledException();
                }
                int read = inputStream.read(buffer);
                if (read < 0) break;
                if (read == 0) continue;
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    @NonNull
    private StrongFingerprint computeStrongFingerprint(@NonNull Context context,
                                                       @NonNull SessionEntry entry,
                                                       @NonNull ChannelSftp channel,
                                                       @NonNull String remotePath,
                                                       @NonNull File localFile) throws Exception {
        String remoteSha256 = computeRemoteSha256PreferNative(context, entry, channel, remotePath, null);
        String localSha256 = computeLocalSha256(localFile, null);
        if (TextUtils.isEmpty(remoteSha256) || TextUtils.isEmpty(localSha256)) {
            throw new IllegalStateException("无法计算文件 SHA-256 指纹。");
        }
        if (!TextUtils.equals(remoteSha256, localSha256)) {
            throw new IllegalStateException("远端和本地 SHA-256 不一致。");
        }
        return new StrongFingerprint(
            remoteSha256,
            localSha256,
            VirtualLocalFileRegistry.LEVEL_STRONG_CONTENT,
            "remote-native-or-sftp-sha256"
        );
    }

    @NonNull
    private static String extractSha256Hex(@Nullable String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        String value = raw.trim().toLowerCase(Locale.US);
        int length = value.length();
        for (int start = 0; start + 64 <= length; start++) {
            boolean ok = true;
            for (int i = 0; i < 64; i++) {
                char ch = value.charAt(start + i);
                boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
                if (!hex) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return value.substring(start, start + 64);
            }
        }
        return "";
    }

    @NonNull
    private static String shellQuoteSingle(@NonNull String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    @NonNull
    private static String computeLocalSampleDigest(@NonNull File file,
                                                   long fileSize,
                                                   @Nullable CancelProbe cancelProbe) throws Exception {
        if (fileSize <= 0L || fileSize <= FULL_DIGEST_VERIFY_MAX_BYTES || fileSize <= SAMPLE_DIGEST_VERIFY_BYTES * 2L) {
            return computeLocalSha256(file, cancelProbe);
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(("sample:" + fileSize + ":" + SAMPLE_DIGEST_VERIFY_BYTES).getBytes(StandardCharsets.UTF_8));
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r")) {
            updateRandomAccessDigest(randomAccessFile, digest, 0L, SAMPLE_DIGEST_VERIFY_BYTES, cancelProbe);
            long tailOffset = Math.max((long) SAMPLE_DIGEST_VERIFY_BYTES, fileSize - SAMPLE_DIGEST_VERIFY_BYTES);
            updateRandomAccessDigest(randomAccessFile, digest, tailOffset, fileSize - tailOffset, cancelProbe);
        }
        return toHex(digest.digest());
    }

    @NonNull
    private static String computeRemoteSampleDigest(@NonNull ChannelSftp channel,
                                                    @NonNull String remotePath,
                                                    long remoteSize,
                                                    @Nullable CancelProbe cancelProbe) throws Exception {
        if (remoteSize <= 0L || remoteSize <= FULL_DIGEST_VERIFY_MAX_BYTES || remoteSize <= SAMPLE_DIGEST_VERIFY_BYTES * 2L) {
            return computeRemoteSha256(channel, remotePath, cancelProbe);
        }

        String normalizedRemotePath = normalizeRemotePath(remotePath);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(("sample:" + remoteSize + ":" + SAMPLE_DIGEST_VERIFY_BYTES).getBytes(StandardCharsets.UTF_8));
        try (InputStream inputStream = channel.get(normalizedRemotePath, null, 0L)) {
            updateInputStreamDigest(inputStream, digest, SAMPLE_DIGEST_VERIFY_BYTES, cancelProbe);
        }
        long tailOffset = Math.max((long) SAMPLE_DIGEST_VERIFY_BYTES, remoteSize - SAMPLE_DIGEST_VERIFY_BYTES);
        try (InputStream inputStream = channel.get(normalizedRemotePath, null, tailOffset)) {
            updateInputStreamDigest(inputStream, digest, remoteSize - tailOffset, cancelProbe);
        }
        return toHex(digest.digest());
    }

    private static void updateRandomAccessDigest(@NonNull RandomAccessFile randomAccessFile,
                                                 @NonNull MessageDigest digest,
                                                 long offset,
                                                 long maxBytes,
                                                 @Nullable CancelProbe cancelProbe) throws Exception {
        if (maxBytes <= 0L) return;
        randomAccessFile.seek(Math.max(0L, offset));
        byte[] buffer = new byte[TRANSFER_DIGEST_BUFFER_BYTES];
        long remaining = maxBytes;
        while (remaining > 0L) {
            if (cancelProbe != null && cancelProbe.isCancelled()) {
                throw new OperationCanceledException();
            }
            int read = randomAccessFile.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) break;
            if (read == 0) continue;
            digest.update(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void updateInputStreamDigest(@NonNull InputStream inputStream,
                                                @NonNull MessageDigest digest,
                                                long maxBytes,
                                                @Nullable CancelProbe cancelProbe) throws Exception {
        if (maxBytes <= 0L) return;
        byte[] buffer = new byte[TRANSFER_DIGEST_BUFFER_BYTES];
        long remaining = maxBytes;
        while (remaining > 0L) {
            if (cancelProbe != null && cancelProbe.isCancelled()) {
                throw new OperationCanceledException();
            }
            int read = inputStream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) break;
            if (read == 0) continue;
            digest.update(buffer, 0, read);
            remaining -= read;
        }
    }

    @NonNull
    private static String toHex(@NonNull byte[] data) {
        StringBuilder hex = new StringBuilder(data.length * 2);
        for (byte b : data) {
            hex.append(String.format(Locale.US, "%02x", b));
        }
        return hex.toString();
    }

    @NonNull
    private static String shortSha1Hex(@NonNull String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] data = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            String hex = toHex(data);
            return hex.length() > 12 ? hex.substring(0, 12) : hex;
        } catch (Throwable ignored) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    @NonNull
    private static String buildTransferStageLabel(@Nullable String preferredName,
                                                  @NonNull String fallbackPath,
                                                  @NonNull String stageLabelCn) {
        String baseName = trimToEmpty(preferredName);
        if (TextUtils.isEmpty(baseName)) {
            int slashIndex = fallbackPath.lastIndexOf('/');
            baseName = slashIndex >= 0 ? fallbackPath.substring(slashIndex + 1) : fallbackPath;
        }
        if (TextUtils.isEmpty(baseName)) {
            baseName = "transfer-item";
        }
        return baseName + "（" + trimToEmpty(stageLabelCn) + "）";
    }

    @NonNull
    private static String trimToEmpty(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isCancelled(@Nullable DownloadControl control) {
        if (control == null) return false;
        try {
            return control.isCancelled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isTransferJournalSuppressed() {
        Integer depth = SUPPRESS_TRANSFER_JOURNAL_DEPTH.get();
        return depth != null && depth > 0;
    }

    private static void pushTransferJournalSuppressed() {
        Integer depth = SUPPRESS_TRANSFER_JOURNAL_DEPTH.get();
        SUPPRESS_TRANSFER_JOURNAL_DEPTH.set(depth == null ? 1 : depth + 1);
    }

    private static void popTransferJournalSuppressed() {
        Integer depth = SUPPRESS_TRANSFER_JOURNAL_DEPTH.get();
        if (depth == null || depth <= 1) {
            SUPPRESS_TRANSFER_JOURNAL_DEPTH.set(0);
        } else {
            SUPPRESS_TRANSFER_JOURNAL_DEPTH.set(depth - 1);
        }
    }

    private static boolean isCancelled(@Nullable UploadControl control) {
        if (control == null) return false;
        try {
            return control.isCancelled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isCancelled(@Nullable RemoteTransferControl control) {
        if (control == null) return false;
        try {
            return control.isCancelled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isRemoteCommandCancelled(@Nullable RemoteCommandControl control) {
        if (control == null) return false;
        try {
            return control.isCancelled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isRemoteDeleteCancelled(@Nullable RemoteDeleteControl control) {
        if (control == null) return false;
        try {
            return control.isCancelled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @NonNull
    private static ArrayList<String> collectTopLevelStagedPaths(@Nullable File stagingDirectory) {
        ArrayList<String> out = new ArrayList<>();
        if (stagingDirectory == null || !stagingDirectory.exists()) {
            return out;
        }

        File[] children = stagingDirectory.listFiles();
        if (children == null || children.length == 0) {
            return out;
        }

        Arrays.sort(children, (left, right) -> {
            if (left == right) return 0;
            if (left == null) return 1;
            if (right == null) return -1;
            boolean leftDir = left.isDirectory();
            boolean rightDir = right.isDirectory();
            if (leftDir != rightDir) {
                return leftDir ? -1 : 1;
            }
            return left.getName().compareToIgnoreCase(right.getName());
        });

        for (File child : children) {
            if (child == null || !child.exists()) continue;
            out.add(child.getAbsolutePath());
        }
        return out;
    }

    @NonNull
    private static File ensureRecoveryStageDirectory(@Nullable String stageDirectoryPath) {
        if (TextUtils.isEmpty(stageDirectoryPath)) {
            throw new IllegalStateException("\u7f3a\u5c11\u53ef\u6062\u590d\u7684\u4e2d\u8f6c\u76ee\u5f55\u8def\u5f84\u3002");
        }
        File stageDirectory = new File(stageDirectoryPath);
        if (stageDirectory.exists()) {
            if (!stageDirectory.isDirectory()) {
                throw new IllegalStateException("\u6062\u590d\u4e2d\u8f6c\u76ee\u5f55\u5931\u8d25\uff1a\u8def\u5f84\u4e0d\u662f\u76ee\u5f55\u3002");
            }
            return stageDirectory;
        }
        if (!stageDirectory.mkdirs() && !stageDirectory.exists()) {
            throw new IllegalStateException("\u65e0\u6cd5\u521b\u5efa\u53ef\u6062\u590d\u7684\u4e2d\u8f6c\u76ee\u5f55\u3002");
        }
        return stageDirectory;
    }

    @NonNull
    private static File createRemoteTransferStagingDirectory(@NonNull Context context) {
        File root = new File(FileRootResolver.resolveTransferRoot(context));
        if (!root.exists() && !root.mkdirs() && !root.exists()) {
            throw new IllegalStateException("\u65e0\u6cd5\u521b\u5efa\u670d\u52a1\u5668\u4e92\u4f20\u6839\u76ee\u5f55\u3002");
        }

        for (int index = 1; index <= 24; index++) {
            File candidate = new File(root, "relay-" + System.currentTimeMillis() + "-" + index);
            if (!candidate.exists() && candidate.mkdirs()) {
                return candidate;
            }
        }
        throw new IllegalStateException("\u65e0\u6cd5\u521b\u5efa\u670d\u52a1\u5668\u4e92\u4f20\u4e2d\u8f6c\u76ee\u5f55\u3002");
    }

    private static void deleteDirectoryContents(@Nullable File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectoryContents(child);
                }
            }
        }
        try {
            file.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void cleanupVirtualArtifacts(@NonNull Context context, @NonNull VirtualTarget target) {
        cleanupVirtualArtifacts(context, target.entry, target.virtualRoot, target.remotePath);
    }

    private static void cleanupVirtualArtifacts(@NonNull Context context,
                                                @NonNull SessionEntry entry,
                                                @NonNull String virtualRoot,
                                                @NonNull String remotePath) {
        String normalizedRemote = normalizeRemotePath(remotePath);
        String relative = normalizedRemote.startsWith("/") ? normalizedRemote.substring(1) : normalizedRemote;
        File cacheRoot = new File(FileRootResolver.resolveCacheRoot(context, entry));
        File cacheFile = TextUtils.isEmpty(relative) ? cacheRoot : new File(cacheRoot, relative);
        deleteDirectoryContents(cacheFile);

        String localVirtualPath = virtualRoot + ("/".equals(normalizedRemote) ? "" : normalizedRemote);
        deleteDirectoryContents(new File(localVirtualPath));
    }

    private static boolean isCancelledMessage(@Nullable String messageCn) {
        return !TextUtils.isEmpty(messageCn) && messageCn.contains("\u5df2\u53d6\u6d88");
    }

    static long attrsModifiedMs(@Nullable SftpATTRS attrs) {
        if (attrs == null) return -1L;
        return Math.max(0L, ((long) attrs.getMTime()) * 1000L);
    }

    static boolean matchesExpectedRemoteVersion(long expectedRemoteModifiedMs,
                                                long expectedRemoteSize,
                                                long actualModifiedMs,
                                                long actualSize) {
        if (expectedRemoteModifiedMs < 0L && expectedRemoteSize < 0L) {
            return true;
        }
        if (actualModifiedMs < 0L && actualSize < 0L) {
            return false;
        }
        if (expectedRemoteModifiedMs >= 0L && actualModifiedMs >= 0L && expectedRemoteModifiedMs != actualModifiedMs) {
            return false;
        }
        if (expectedRemoteSize >= 0L && actualSize >= 0L && expectedRemoteSize != actualSize) {
            return false;
        }
        return true;
    }

    static boolean matchesExpectedRemoteVersion(long expectedRemoteModifiedMs,
                                                long expectedRemoteSize,
                                                @Nullable SftpATTRS actualAttrs) {
        return matchesExpectedRemoteVersion(
            expectedRemoteModifiedMs,
            expectedRemoteSize,
            actualAttrs == null ? -1L : attrsModifiedMs(actualAttrs),
            actualAttrs == null ? -1L : Math.max(0L, actualAttrs.getSize())
        );
    }

    @NonNull
    private static String resolveUniqueRemotePath(@NonNull ChannelSftp channel,
                                                  @NonNull String desiredRemotePath,
                                                  @NonNull Set<String> reservedPaths) throws Exception {
        String out = normalizeRemotePath(desiredRemotePath);
        int index = 1;
        while (reservedPaths.contains(out) || remotePathExists(channel, out)) {
            out = appendNumberSuffixToRemotePath(desiredRemotePath, index++);
        }
        reservedPaths.add(out);
        return out;
    }

    private static boolean remotePathExists(@NonNull ChannelSftp channel,
                                            @NonNull String remotePath) throws Exception {
        try {
            return channel.stat(normalizeRemotePath(remotePath)) != null;
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return false;
            }
            throw e;
        }
    }

    @NonNull
    private static String appendNumberSuffixToRemotePath(@NonNull String remotePath, int index) {
        String normalized = normalizeRemotePath(remotePath);
        if ("/".equals(normalized)) return normalized;
        int slash = normalized.lastIndexOf('/');
        String parent = slash <= 0 ? "/" : normalized.substring(0, slash);
        String name = normalized.substring(slash + 1);
        if (TextUtils.isEmpty(name)) name = "item";
        return joinRemotePath(parent, appendNumberSuffix(name, index));
    }

    @NonNull
    private static String parentRemotePath(@NonNull String remotePath) {
        String normalized = normalizeRemotePath(remotePath);
        if ("/".equals(normalized)) return "/";
        int slash = normalized.lastIndexOf('/');
        if (slash <= 0) return "/";
        return normalized.substring(0, slash);
    }

    @Nullable
    private static SftpATTRS statRemoteFileIfPresent(@NonNull ChannelSftp channel,
                                                     @NonNull String remotePath) throws Exception {
        try {
            return channel.lstat(normalizeRemotePath(remotePath));
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return null;
            throw e;
        }
    }

    private static void tryChmod(@NonNull ChannelSftp channel, int permissions,
                                 @NonNull String remotePath) {
        try {
            channel.chmod(permissions, normalizeRemotePath(remotePath));
        } catch (Throwable ignored) {
            // Some SFTP servers do not expose POSIX modes. Their account boundary remains authoritative.
        }
    }

    private static void cleanupStaleCodexAttachmentTemps(@NonNull ChannelSftp channel,
                                                         @NonNull String remoteDirectory) {
        long cutoff = System.currentTimeMillis() - CODEX_ATTACHMENT_TEMP_RETENTION_MS;
        try {
            Vector<?> entries = channel.ls(normalizeRemotePath(remoteDirectory));
            for (Object row : entries) {
                if (!(row instanceof ChannelSftp.LsEntry)) continue;
                ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) row;
                String name = entry.getFilename();
                SftpATTRS attrs = entry.getAttrs();
                if (TextUtils.isEmpty(name) || attrs == null || attrs.isDir()) continue;
                if (!name.startsWith(".") || !name.contains(".termux-codex-") || !name.endsWith(".part")) {
                    continue;
                }
                long modifiedMs = Math.max(0L, attrs.getMTime()) * 1000L;
                if (modifiedMs > 0L && modifiedMs < cutoff) {
                    channel.rm(joinRemotePath(normalizeRemotePath(remoteDirectory), name));
                }
            }
        } catch (Throwable ignored) {
            // Cleanup is best-effort and must never block a new verified attachment commit.
        }
    }

    private static void quarantineRemoteCodexAttachment(@NonNull ChannelSftp channel,
                                                        @NonNull String remotePath) {
        try {
            String corruptPath = normalizeRemotePath(remotePath) +
                ".corrupt-" + System.currentTimeMillis() + "-" + TRANSFER_TEMP_COUNTER.getAndIncrement();
            channel.rename(normalizeRemotePath(remotePath), corruptPath);
        } catch (Throwable ignored) {
            try {
                deleteRemoteFileIfExists(channel, remotePath);
            } catch (Throwable ignoredAgain) {
            }
        }
    }

    @NonNull
    static String buildCodexAttachmentRemotePath(@Nullable String remoteHome,
                                                 @Nullable String sha256,
                                                 @Nullable String extension) {
        String digest = trimToEmpty(sha256).toLowerCase(Locale.US);
        String safeExtension = normalizeCodexAttachmentExtension(extension);
        if (!isSha256Hex(digest) || safeExtension.isEmpty()) return "";
        String home = normalizeRemotePath(remoteHome);
        String directory = home;
        for (String component : CODEX_ATTACHMENT_REMOTE_RELATIVE_DIR.split("/")) {
            directory = joinRemotePath(directory, component);
        }
        return joinRemotePath(directory, digest + "." + safeExtension);
    }

    @NonNull
    private static String normalizeCodexAttachmentExtension(@Nullable String extension) {
        String value = trimToEmpty(extension).toLowerCase(Locale.US);
        if ("jpeg".equals(value)) value = "jpg";
        return "png".equals(value) || "jpg".equals(value) ||
            "gif".equals(value) || "webp".equals(value) ? value : "";
    }

    private static boolean isSha256Hex(@Nullable String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) return false;
        }
        return true;
    }

    static long codexAttachmentUploadedBytes(boolean reused, long contentBytes) {
        return reused ? 0L : Math.max(0L, contentBytes);
    }

    private static void ensureRemoteDirectoryExists(@NonNull ChannelSftp channel,
                                                    @NonNull String remoteDirectory) throws Exception {
        String normalized = normalizeRemotePath(remoteDirectory);
        if ("/".equals(normalized)) return;
        String[] parts = normalized.substring(1).split("/");
        String current = "/";
        for (String part : parts) {
            if (TextUtils.isEmpty(part)) continue;
            current = "/".equals(current) ? "/" + part : current + "/" + part;
            try {
                SftpATTRS attrs = channel.stat(current);
                if (attrs == null) {
                    throw new IllegalStateException("\u8fdc\u7a0b\u76ee\u5f55\u72b6\u6001\u672a\u77e5\uff1a" + current);
                }
                if (!attrs.isDir()) {
                    throw new IllegalStateException("\u8fdc\u7a0b\u5df2\u5b58\u5728\u540c\u540d\u6587\u4ef6\uff1a" + current);
                }
            } catch (SftpException e) {
                if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    throw e;
                }
                channel.mkdir(current);
            }
        }
    }

    @NonNull
    private static File ensureUniqueDestinationRoot(@NonNull File desired,
                                                    @NonNull Set<String> reservedAbsolutePaths) {
        File out = desired;
        int index = 1;
        String abs = out.getAbsolutePath();
        while (reservedAbsolutePaths.contains(abs) || out.exists()) {
            out = new File(desired.getParentFile(), appendNumberSuffix(desired.getName(), index++));
            abs = out.getAbsolutePath();
        }
        reservedAbsolutePaths.add(abs);
        return out;
    }

    @NonNull
    private static File reserveDestinationRoot(@NonNull File desired,
                                               @NonNull Set<String> reservedAbsolutePaths) {
        File out = desired;
        int index = 1;
        String abs = out.getAbsolutePath();
        while (reservedAbsolutePaths.contains(abs)) {
            out = new File(desired.getParentFile(), appendNumberSuffix(desired.getName(), index++));
            abs = out.getAbsolutePath();
        }
        reservedAbsolutePaths.add(abs);
        return out;
    }

    @NonNull
    private static File resolveNonConflictingFile(@NonNull File desired) {
        if (!desired.exists()) return desired;
        File out = desired;
        int index = 1;
        while (out.exists()) {
            out = new File(desired.getParentFile(), appendNumberSuffix(desired.getName(), index++));
        }
        return out;
    }

    @NonNull
    private static String appendNumberSuffix(@NonNull String filename, int index) {
        if (index <= 0) return filename;
        int dot = filename.lastIndexOf('.');
        if (dot > 0 && dot < filename.length() - 1) {
            return filename.substring(0, dot) + "(" + index + ")" + filename.substring(dot);
        }
        return filename + "(" + index + ")";
    }

    @NonNull
    private static String topLevelNameForTarget(@NonNull VirtualTarget target) {
        if ("/".equals(target.remotePath)) {
            return TextUtils.isEmpty(target.entry.displayName) ? "remote-root" : target.entry.displayName;
        }
        String normalized = normalizeRemotePath(target.remotePath);
        int slashIndex = normalized.lastIndexOf('/');
        String name = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        if (TextUtils.isEmpty(name)) {
            return TextUtils.isEmpty(target.entry.displayName) ? "remote-item" : target.entry.displayName;
        }
        return name;
    }

    public void clearSession(@Nullable String sessionId) {
        if (TextUtils.isEmpty(sessionId)) return;
        synchronized (mLock) {
            String removedClientKey = sessionId;
            ClientHolder holder = mClients.remove(sessionId);
            if (holder == null) {
                String matchedKey = null;
                for (Map.Entry<String, ClientHolder> item : mClients.entrySet()) {
                    ClientHolder candidate = item.getValue();
                    if (sessionId.equals(candidate.legacySessionId)) {
                        matchedKey = item.getKey();
                        break;
                    }
                }
                if (!TextUtils.isEmpty(matchedKey)) {
                    holder = mClients.remove(matchedKey);
                    removedClientKey = matchedKey;
                }
            }
            if (holder != null) holder.close();
            clearDirectoryCacheByClientKeyLocked(removedClientKey);
            if (holder != null) clearDirectoryCacheByClientKeyLocked(holder.clientKey);
        }
    }

    private void clearSessionByEntry(@NonNull SessionEntry entry) {
        clearSession(clientKeyForEntry(entry));
    }

    @Nullable
    private VirtualTarget resolveVirtualTarget(@NonNull Context context, @Nullable String rawPath) {
        if (TextUtils.isEmpty(rawPath)) return null;
        String path = normalizeLocalPath(rawPath);

        List<SessionEntry> entries = SavedSshProfileStore.loadSessionEntries(context);
        if (entries == null || entries.isEmpty()) return null;

        for (SessionEntry entry : entries) {
            if (entry == null || entry.transport == SessionTransport.LOCAL) continue;
            String root = normalizeLocalPath(FileRootResolver.resolveVirtualRoot(context, entry));
            if (!(path.equals(root) || path.startsWith(root + "/"))) continue;

            String relative = path.length() <= root.length() ? "" : path.substring(root.length());
            String remotePath = normalizeRemotePath(relative);
            return new VirtualTarget(entry, root, remotePath);
        }
        return null;
    }

    @NonNull
    private ClientHolder ensureClient(@NonNull Context context, @NonNull SessionEntry entry) throws Exception {
        synchronized (mLock) {
            String clientKey = clientKeyForEntry(entry);
            ClientHolder holder = mClients.get(clientKey);
            if (holder != null && holder.isAlive()) return holder;
            if (holder != null) holder.close();
            clearDirectoryCacheByClientKeyLocked(clientKey);

            ParsedTarget parsed = parseSshCommand(entry.sshCommand);
            if (!parsed.valid) {
                throw new IllegalStateException(parsed.errorMessage);
            }

            ResolvedSshEndpoint resolvedEndpoint = SessionEntrySshEndpointResolver.resolve(entry);
            if (resolvedEndpoint == null) {
                resolvedEndpoint = SessionEntrySshEndpointResolver.fallback(
                    entry, parsed.host, parsed.port, parsed.user, parsed.identityPath);
            }

            JSch jsch = new JSch();
            File homeDir = new File(context.getFilesDir(), "home");
            addSshIdentities(jsch, homeDir, parsed);
            SshHostTrustStore trustStore = SshHostTrustStore.getInstance();
            trustStore.initialize(context);
            trustStore.setActiveEndpoint(resolvedEndpoint);
            jsch.setHostKeyRepository(trustStore);

            com.jcraft.jsch.Session session = jsch.getSession(parsed.user, parsed.host, parsed.port);
            if (!TextUtils.isEmpty(parsed.password)) session.setPassword(parsed.password);
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "yes");
            config.put("PreferredAuthentications",
                TextUtils.isEmpty(parsed.password)
                    ? "publickey,keyboard-interactive,password"
                    : "password,keyboard-interactive,publickey");
            session.setConfig(config);
            try {
                session.setServerAliveInterval(15_000);
                session.setServerAliveCountMax(3);
                session.setTimeout(20_000);
            } catch (Throwable ignored) {
            }
            try {
                session.connect(15_000);
            } catch (Exception connectError) {
                throw decorateTrustFailure(trustStore, resolvedEndpoint, connectError);
            } finally {
                trustStore.clearActiveEndpoint();
            }

            ClientHolder newHolder = new ClientHolder(clientKey, entry.id, session);
            mClients.put(clientKey, newHolder);
            cleanupPendingRemoteTempPaths(context, entry, newHolder);
            return newHolder;
        }
    }

    @NonNull
    private static Exception decorateTrustFailure(@NonNull SshHostTrustStore trustStore,
                                                  @NonNull ResolvedSshEndpoint endpoint,
                                                  @NonNull Exception error) {
        com.termux.sshconnectioncore.SshPendingTrustRecord pending =
            trustStore.findPendingByAuthority(endpoint.authorityKey);
        if (pending == null) return error;

        String message = pending.replacementRequired
            ? "检测到主机指纹发生变化，需先在“指纹管理”中替换后再重试。"
            : "首次检测到该服务器主机指纹，需先在“指纹管理”中批准后再重试。";
        return new IllegalStateException(message, error);
    }

    @NonNull
    private static String clientKeyForEntry(@NonNull SessionEntry entry) {
        return FileRootResolver.sessionPathKey(entry);
    }
    @NonNull
    private static String directoryCacheKey(@NonNull SessionEntry entry, @NonNull String remotePath) {
        return clientKeyForEntry(entry) + "|" + remotePath;
    }

    @Nullable
    private CachedDirectory getValidDirectoryCacheLocked(@NonNull String cacheKey) {
        CachedDirectory cached = mDirectoryCache.get(cacheKey);
        if (cached == null) return null;
        if (System.currentTimeMillis() - cached.cachedAtMs <= DIRECTORY_CACHE_TTL_MS) {
            return cached;
        }
        mDirectoryCache.remove(cacheKey);
        return null;
    }

    private void clearDirectoryCacheByClientKeyLocked(@Nullable String clientKey) {
        if (TextUtils.isEmpty(clientKey)) return;
        String prefix = clientKey + "|";
        mDirectoryCache.entrySet().removeIf(item -> item.getKey() != null && item.getKey().startsWith(prefix));
    }

    private void trimDirectoryCacheLocked() {
        while (mDirectoryCache.size() > DIRECTORY_CACHE_MAX_ENTRIES) {
            String oldestKey = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, CachedDirectory> item : mDirectoryCache.entrySet()) {
                CachedDirectory cached = item.getValue();
                if (cached == null) continue;
                if (cached.cachedAtMs < oldestTime) {
                    oldestTime = cached.cachedAtMs;
                    oldestKey = item.getKey();
                }
            }
            if (oldestKey == null) break;
            mDirectoryCache.remove(oldestKey);
        }
    }

    @NonNull
    private static ArrayList<RemoteEntry> copyRemoteEntries(@NonNull List<RemoteEntry> source) {
        ArrayList<RemoteEntry> copied = new ArrayList<>(source.size());
        for (RemoteEntry entry : source) {
            if (entry == null) continue;
            copied.add(new RemoteEntry(entry.localPath, entry.name, entry.directory, entry.size, entry.modifiedMs));
        }
        return copied;
    }

    private <T> T withReconnectRetry(@NonNull Context context, @NonNull SessionEntry entry,
                                     @NonNull SftpClientAction<T> action) throws Exception {
        Exception lastError = null;
        int maxAttempts = RECOVERABLE_RETRY_COUNT + 1;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            ClientHolder holder = ensureClient(context, entry);
            ChannelSftp channel = null;
            boolean channelBroken = false;
            try {
                channel = holder.borrowChannel();
                return action.run(channel);
            } catch (Exception e) {
                lastError = e;
                channelBroken = true;
                if (attempt >= maxAttempts - 1 || !isRecoverableTransportException(e)) {
                    throw e;
                }
                clearSessionByEntry(entry);
                try {
                    Thread.sleep(120L * (attempt + 1));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                if (channel != null) {
                    holder.releaseChannel(channel, channelBroken);
                }
            }
        }
        throw lastError == null ? new IllegalStateException("SFTP operation failed") : lastError;
    }

    @NonNull
    private RemoteCommandResult withExecReconnectRetry(@NonNull Context context,
                                                       @NonNull SessionEntry entry,
                                                       @NonNull String shellCommand,
                                                       @Nullable RemoteCommandControl control) throws Exception {
        Exception lastError = null;
        int maxAttempts = RECOVERABLE_RETRY_COUNT + 1;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (isRemoteCommandCancelled(control)) {
                throw new OperationCanceledException();
            }
            ClientHolder holder = ensureClient(context, entry);
            ChannelExec channel = null;
            boolean channelBroken = false;
            try {
                channel = holder.openExecChannel(shellCommand);
                RemoteCommandResult result = readExecChannel(channel, control);
                if (result.exitCode == 127 || result.exitCode == 126) {
                    return result;
                }
                if (!result.success && attempt < maxAttempts - 1 && isRecoverableRemoteCommandResult(result)) {
                    channelBroken = true;
                    clearSessionByEntry(entry);
                    sleepQuietly(120L * (attempt + 1));
                    continue;
                }
                return result;
            } catch (Exception e) {
                lastError = e;
                channelBroken = true;
                if (e instanceof OperationCanceledException) {
                    throw e;
                }
                if (attempt >= maxAttempts - 1 || !isRecoverableTransportException(e)) {
                    throw e;
                }
                clearSessionByEntry(entry);
                sleepQuietly(120L * (attempt + 1));
            } finally {
                holder.closeExecChannel(channel, channelBroken);
            }
        }
        throw lastError == null ? new IllegalStateException("SSH exec operation failed") : lastError;
    }

    @NonNull
    private RemoteCommandResult withExecStreamingReconnectRetry(@NonNull Context context,
                                                                @NonNull SessionEntry entry,
                                                                @NonNull String shellCommand,
                                                                @Nullable RemoteCommandControl control,
                                                                @Nullable ExecOutputLineListener lineListener) throws Exception {
        Exception lastError = null;
        int maxAttempts = RECOVERABLE_RETRY_COUNT + 1;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (isRemoteCommandCancelled(control)) {
                throw new OperationCanceledException();
            }
            ClientHolder holder = ensureClient(context, entry);
            ChannelExec channel = null;
            boolean channelBroken = false;
            try {
                channel = holder.openExecChannel(shellCommand);
                RemoteCommandResult result = readExecChannel(channel, control, lineListener);
                if (result.exitCode == 127 || result.exitCode == 126) {
                    return result;
                }
                if (!result.success && attempt < maxAttempts - 1 && isRecoverableRemoteCommandResult(result)) {
                    channelBroken = true;
                    clearSessionByEntry(entry);
                    sleepQuietly(120L * (attempt + 1));
                    continue;
                }
                return result;
            } catch (Exception e) {
                lastError = e;
                channelBroken = true;
                if (e instanceof OperationCanceledException) {
                    throw e;
                }
                if (attempt >= maxAttempts - 1 || !isRecoverableTransportException(e)) {
                    throw e;
                }
                clearSessionByEntry(entry);
                sleepQuietly(120L * (attempt + 1));
            } finally {
                holder.closeExecChannel(channel, channelBroken);
            }
        }
        throw lastError == null ? new IllegalStateException("SSH exec operation failed") : lastError;
    }

    @NonNull
    private static RemoteCommandResult readExecChannel(@NonNull ChannelExec channel,
                                                       @Nullable RemoteCommandControl control) throws Exception {
        return readExecChannel(channel, control, null);
    }

    @NonNull
    private static RemoteCommandResult readExecChannel(@NonNull ChannelExec channel,
                                                       @Nullable RemoteCommandControl control,
                                                       @Nullable ExecOutputLineListener lineListener) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        byte[] outBuffer = new byte[16 * 1024];
        byte[] errBuffer = new byte[16 * 1024];
        InputStream out = channel.getInputStream();
        InputStream err = channel.getErrStream();
        StringBuilder stdoutLineBuffer = new StringBuilder();

        while (true) {
            if (isRemoteCommandCancelled(control)) {
                try {
                    channel.disconnect();
                } catch (Throwable ignored) {
                }
                throw new OperationCanceledException();
            }

            boolean readAny = false;
            while (out.available() > 0) {
                int read = out.read(outBuffer, 0, Math.min(out.available(), outBuffer.length));
                if (read < 0) break;
                if (read > 0) {
                    stdout.write(outBuffer, 0, read);
                    dispatchExecOutputLines(outBuffer, read, stdoutLineBuffer, lineListener);
                    readAny = true;
                }
            }
            while (err.available() > 0) {
                int read = err.read(errBuffer, 0, Math.min(err.available(), errBuffer.length));
                if (read < 0) break;
                if (read > 0) {
                    stderr.write(errBuffer, 0, read);
                    readAny = true;
                }
            }

            if (channel.isClosed()) {
                drainAvailable(out, stdout, outBuffer, stdoutLineBuffer, lineListener);
                drainAvailable(err, stderr, errBuffer);
                int exitCode = channel.getExitStatus();
                flushExecOutputLine(stdoutLineBuffer, lineListener);
                String stdoutText = stdout.toString(StandardCharsets.UTF_8.name());
                String stderrText = stderr.toString(StandardCharsets.UTF_8.name());
                if (exitCode == 0) {
                    return RemoteCommandResult.ok(exitCode, stdoutText, stderrText);
                }
                return RemoteCommandResult.fail(exitCode, stdoutText, stderrText,
                    buildRemoteCommandFailureMessage(exitCode, stdoutText, stderrText));
            }

            if (!readAny) {
                try {
                    Thread.sleep(80L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new OperationCanceledException();
                }
            }
        }
    }

    private static void drainAvailable(@NonNull InputStream input,
                                       @NonNull ByteArrayOutputStream output,
                                       @NonNull byte[] buffer) throws Exception {
        drainAvailable(input, output, buffer, null, null);
    }

    private static void drainAvailable(@NonNull InputStream input,
                                       @NonNull ByteArrayOutputStream output,
                                       @NonNull byte[] buffer,
                                       @Nullable StringBuilder lineBuffer,
                                       @Nullable ExecOutputLineListener lineListener) throws Exception {
        while (input.available() > 0) {
            int read = input.read(buffer, 0, Math.min(input.available(), buffer.length));
            if (read < 0) return;
            if (read > 0) {
                output.write(buffer, 0, read);
                dispatchExecOutputLines(buffer, read, lineBuffer, lineListener);
            }
        }
    }

    private static void dispatchExecOutputLines(@NonNull byte[] buffer,
                                                int length,
                                                @Nullable StringBuilder lineBuffer,
                                                @Nullable ExecOutputLineListener lineListener) {
        if (lineBuffer == null || lineListener == null || length <= 0) return;
        String chunk = new String(buffer, 0, length, StandardCharsets.UTF_8);
        for (int i = 0; i < chunk.length(); i++) {
            char ch = chunk.charAt(i);
            if (ch == '\n') {
                String line = lineBuffer.toString().trim();
                lineBuffer.setLength(0);
                if (!TextUtils.isEmpty(line)) lineListener.onLine(line);
            } else if (ch != '\r') {
                lineBuffer.append(ch);
            }
        }
    }

    private static void flushExecOutputLine(@Nullable StringBuilder lineBuffer,
                                            @Nullable ExecOutputLineListener lineListener) {
        if (lineBuffer == null || lineListener == null || lineBuffer.length() == 0) return;
        String line = lineBuffer.toString().trim();
        lineBuffer.setLength(0);
        if (!TextUtils.isEmpty(line)) lineListener.onLine(line);
    }

    @NonNull
    private static String buildRemoteCommandFailureMessage(int exitCode,
                                                           @Nullable String stdout,
                                                           @Nullable String stderr) {
        String details = stderr == null ? "" : stderr.trim();
        if (TextUtils.isEmpty(details)) {
            details = stdout == null ? "" : stdout.trim();
        }
        if (TextUtils.isEmpty(details)) {
            details = "\u8fdc\u7a0b\u547d\u4ee4\u9000\u51fa\u7801 " + exitCode;
        }
        if (details.length() > 800) {
            details = details.substring(0, 800).trim();
        }
        return "\u8fdc\u7a0b\u6267\u884c\u5931\u8d25\uff1a" + details;
    }

    private static boolean isRecoverableRemoteCommandResult(@NonNull RemoteCommandResult result) {
        String message = (result.stderr + "\n" + result.stdout + "\n" + result.messageCn).toLowerCase(Locale.ROOT);
        return message.contains("connection reset")
            || message.contains("broken pipe")
            || message.contains("connection closed")
            || message.contains("session is down")
            || message.contains("channel is not opened")
            || message.contains("socket");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void cleanupPendingRemoteTempPaths(@NonNull Context context,
                                               @NonNull SessionEntry entry,
                                               @NonNull ClientHolder holder) {
        String sessionKey = clientKeyForEntry(entry);
        List<String> pendingPaths = SftpTransferJournal.getInstance().listPendingRemoteTempPaths(context, sessionKey);
        if (pendingPaths.isEmpty()) {
            return;
        }

        ChannelSftp channel = null;
        boolean broken = false;
        try {
            channel = holder.borrowChannel();
            for (String pendingPath : pendingPaths) {
                if (TextUtils.isEmpty(pendingPath)) continue;
                try {
                    deleteRemoteFileIfExists(channel, pendingPath);
                    SftpTransferJournal.getInstance().acknowledgeRemoteTempPath(context, sessionKey, pendingPath);
                } catch (Exception cleanupError) {
                    broken = true;
                    SessionSyncTracer.getInstance().warn(
                        context,
                        "SftpProtocolManager",
                        "cleanupPendingRemoteTempPaths",
                        sessionKey,
                        "\u6e05\u7406\u8fdc\u7a0b\u4e34\u65f6\u6587\u4ef6\u5931\u8d25",
                        cleanupError.getMessage()
                    );
                    break;
                }
            }
        } catch (Exception borrowError) {
            SessionSyncTracer.getInstance().warn(
                context,
                "SftpProtocolManager",
                "cleanupPendingRemoteTempPaths",
                sessionKey,
                "\u65e0\u6cd5\u6253\u5f00\u901a\u9053\u6e05\u7406\u8fdc\u7a0b\u4e34\u65f6\u6587\u4ef6",
                borrowError.getMessage()
            );
        } finally {
            if (channel != null) {
                holder.releaseChannel(channel, broken);
            }
        }
    }

    private static boolean isRecoverableTransportException(@Nullable Throwable throwable) {
        if (throwable == null) return false;
        String text = throwable.getMessage();
        if (TextUtils.isEmpty(text)) text = throwable.toString();
        if (TextUtils.isEmpty(text)) return false;

        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("inputstream is closed")
            || lower.contains("socket is closed")
            || lower.contains("session is down")
            || lower.contains("connection is closed")
            || lower.contains("channel is not opened")
            || lower.contains("end of io stream read")
            || lower.contains("broken pipe")
            || lower.contains("connection reset")
            || lower.contains("read timed out");
    }

    private static void addSshIdentities(@NonNull JSch jsch, @NonNull File homeDir,
                                         @NonNull ParsedTarget parsed) {
        if (!TextUtils.isEmpty(parsed.identityPath)) {
            File idFile = resolveIdentityFile(homeDir, parsed.identityPath);
            if (idFile != null && idFile.exists()) {
                try {
                    jsch.addIdentity(idFile.getAbsolutePath());
                    return;
                } catch (Exception ignored) {
                }
            }
        }

        File sshDir = new File(homeDir, ".ssh");
        String[] defaults = new String[]{"id_ed25519", "id_rsa", "id_ecdsa", "id_dsa"};
        for (String name : defaults) {
            File key = new File(sshDir, name);
            if (!key.exists()) continue;
            try {
                jsch.addIdentity(key.getAbsolutePath());
                return;
            } catch (Exception ignored) {
            }
        }
    }

    @Nullable
    private static File resolveIdentityFile(@NonNull File homeDir, @Nullable String rawPath) {
        if (TextUtils.isEmpty(rawPath)) return null;
        String p = rawPath.trim();
        if (p.startsWith("~/")) {
            return new File(homeDir, p.substring(2));
        }
        File absolute = new File(p);
        if (absolute.isAbsolute()) return absolute;
        return new File(homeDir, p);
    }

    @NonNull
    private static ParsedTarget parseSshCommand(@Nullable String rawCommand) {
        if (TextUtils.isEmpty(rawCommand)) return ParsedTarget.invalid("\u7f3a\u5c11\u0020\u0053\u0053\u0048\u0020\u547d\u4ee4\u3002");
        List<String> tokens = splitShell(rawCommand.trim());
        if (tokens.isEmpty()) return ParsedTarget.invalid("\u0053\u0053\u0048\u0020\u547d\u4ee4\u4e3a\u7a7a\u3002");

        String password = null;
        int sshIdx = -1;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if ("sshpass".equals(token) && i + 1 < tokens.size()) {
                for (int j = i + 1; j < tokens.size(); j++) {
                    String t = tokens.get(j);
                    if ("-p".equals(t) && j + 1 < tokens.size()) {
                        password = tokens.get(j + 1);
                        j++;
                    } else if (t.startsWith("-p") && t.length() > 2) {
                        password = t.substring(2);
                    } else if (isSshExecutable(t)) {
                        sshIdx = j;
                        break;
                    }
                }
                if (sshIdx >= 0) break;
            } else if (isSshExecutable(token)) {
                sshIdx = i;
                break;
            }
        }
        if (sshIdx < 0) return ParsedTarget.invalid("\u547d\u4ee4\u4e2d\u672a\u627e\u5230\u0020\u0073\u0073\u0068\u0020\u53ef\u6267\u884c\u5165\u53e3\u3002");

        int port = 22;
        String user = null;
        String host = null;
        String identity = null;

        for (int i = sshIdx + 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (TextUtils.isEmpty(token)) continue;

            if ("-p".equals(token) && i + 1 < tokens.size()) {
                i++;
                try {
                    port = Integer.parseInt(tokens.get(i));
                } catch (Exception ignored) {
                }
                continue;
            }
            if (token.startsWith("-p") && token.length() > 2) {
                try {
                    port = Integer.parseInt(token.substring(2));
                } catch (Exception ignored) {
                }
                continue;
            }

            if ("-l".equals(token) && i + 1 < tokens.size()) {
                i++;
                user = tokens.get(i);
                continue;
            }
            if (token.startsWith("-l") && token.length() > 2) {
                user = token.substring(2);
                continue;
            }

            if ("-i".equals(token) && i + 1 < tokens.size()) {
                i++;
                identity = tokens.get(i);
                continue;
            }
            if (token.startsWith("-i") && token.length() > 2) {
                identity = token.substring(2);
                continue;
            }

            if ("-o".equals(token) && i + 1 < tokens.size()) {
                i++;
                String opt = tokens.get(i);
                if (opt.startsWith("IdentityFile=")) {
                    identity = opt.substring("IdentityFile=".length());
                }
                continue;
            }
            if (token.startsWith("-o") && token.length() > 2) {
                String opt = token.substring(2);
                if (opt.startsWith("IdentityFile=")) {
                    identity = opt.substring("IdentityFile=".length());
                }
                continue;
            }

            if (token.startsWith("-")) {
                if (SSH_OPTIONS_WITH_VALUE.contains(token) && i + 1 < tokens.size()) i++;
                continue;
            }

            host = token;
            break;
        }

        if (TextUtils.isEmpty(host)) {
            return ParsedTarget.invalid("\u7f3a\u5c11\u76ee\u6807\u4e3b\u673a\u3002");
        }

        if (host.contains("@")) {
            int at = host.indexOf('@');
            if (at > 0 && TextUtils.isEmpty(user)) user = host.substring(0, at);
            host = host.substring(at + 1);
        }

        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1);
        }

        if (TextUtils.isEmpty(user)) {
            return ParsedTarget.invalid("\u7f3a\u5c11\u7528\u6237\u540d\uff0c\u8bf7\u4f7f\u7528\u0020\u0075\u0073\u0065\u0072\u0040\u0068\u006f\u0073\u0074\u0020\u6216\u0020\u002d\u006c\u0020\u0075\u0073\u0065\u0072\u3002");
        }
        if (TextUtils.isEmpty(host)) {
            return ParsedTarget.invalid("\u76ee\u6807\u4e3b\u673a\u4e3a\u7a7a\u3002");
        }
        return ParsedTarget.valid(host, user, port <= 0 ? 22 : port, password, identity);
    }

    @NonNull
    private static String buildRemoteAuthorityLabel(@NonNull SessionEntry entry) {
        ResolvedSshEndpoint endpoint = SessionEntrySshEndpointResolver.resolve(entry);
        if (endpoint != null) {
            String label = buildRemoteAuthorityLabel(endpoint.user, endpoint.hostIdentity, endpoint.port);
            if (!label.isEmpty()) return label;
            label = buildRemoteAuthorityLabel(endpoint.user, endpoint.host, endpoint.port);
            if (!label.isEmpty()) return label;
        }

        ParsedTarget parsed = parseSshCommand(entry.sshCommand);
        if (parsed.valid) {
            String label = buildRemoteAuthorityLabel(parsed.user, parsed.host, parsed.port);
            if (!label.isEmpty()) return label;
        }

        return sanitizeRemoteDisplayName(entry.displayName);
    }

    @NonNull
    private static String buildRemoteAuthorityLabel(@Nullable String user, @Nullable String host, int port) {
        String normalizedHost = host == null ? "" : host.trim();
        if (normalizedHost.isEmpty()) return "";
        String normalizedUser = user == null ? "" : user.trim();
        String formattedHost = formatHostForAuthority(normalizedHost);
        StringBuilder label = new StringBuilder();
        if (!normalizedUser.isEmpty()) {
            label.append(normalizedUser).append('@');
        }
        label.append(formattedHost);
        if (port > 0 && port != 22) {
            label.append(':').append(port);
        }
        return label.toString();
    }

    @NonNull
    private static String formatHostForAuthority(@NonNull String host) {
        String normalized = host.trim();
        if (normalized.indexOf(':') >= 0 && !normalized.startsWith("[") && !normalized.endsWith("]")) {
            return "[" + normalized + "]";
        }
        return normalized;
    }

    @NonNull
    private static String sanitizeRemoteDisplayName(@Nullable String displayName) {
        String value = displayName == null ? "" : displayName.trim();
        while (value.startsWith("ssh ")) value = value.substring(4).trim();
        if (value.startsWith("sftp://")) value = value.substring("sftp://".length()).trim();
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1).trim();
        return value.isEmpty() ? "server" : value;
    }

    @NonNull
    private static String classifyExceptionMessage(@Nullable Throwable throwable) {
        if (throwable == null) return "\u672a\u77e5\u9519\u8bef\u3002";
        String raw = throwable.getMessage();
        String text = raw == null ? throwable.getClass().getSimpleName() : raw;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("auth fail") || lower.contains("permission denied")) {
            return "\u8ba4\u8bc1\u5931\u8d25\uff08\u7528\u6237\u540d\u002f\u5bc6\u7801\u002f\u5bc6\u94a5\u9519\u8bef\u6216\u670d\u52a1\u5668\u62d2\u7edd\uff09\u3002";
        }
        if (lower.contains("reject hostkey")
            || lower.contains("hostkey has been changed")
            || lower.contains("host key has been changed")) {
            return "\u4e3b\u673a\u6307\u7eb9\u5f85\u6279\u51c6\u6216\u5f85\u66ff\u6362\uff0c\u8bf7\u5148\u5728\u201c\u6307\u7eb9\u7ba1\u7406\u201d\u4e2d\u5904\u7406\u540e\u518d\u91cd\u8bd5\u3002";
        }
        if (lower.contains("connection refused")) {
            return "\u8fde\u63a5\u88ab\u62d2\u7edd\uff08\u7aef\u53e3\u672a\u5f00\u653e\u6216\u0020\u0073\u0073\u0068\u0064\u0020\u672a\u542f\u52a8\uff09\u3002";
        }
        if (lower.contains("inputstream is closed")
            || lower.contains("socket is closed")
            || lower.contains("session is down")
            || lower.contains("connection is closed")
            || lower.contains("channel is not opened")) {
            return "\u8fde\u63a5\u901a\u9053\u5df2\u4e2d\u65ad\uff08\u5e95\u5c42\u6d41\u5df2\u5173\u95ed\uff09\u3002";
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "\u8fde\u63a5\u8d85\u65f6\uff08\u7f51\u7edc\u4e0d\u53ef\u8fbe\u6216\u670d\u52a1\u5668\u54cd\u5e94\u8fc7\u6162\uff09\u3002";
        }
        if (lower.contains("unknownhost") || lower.contains("name or service not known")) {
            return "\u4e3b\u673a\u540d\u89e3\u6790\u5931\u8d25\u3002";
        }
        if (lower.contains("algorithm negotiation fail")) {
            return "\u52a0\u5bc6\u534f\u5546\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u670d\u52a1\u5668\u0020\u0053\u0053\u0048\u0020\u914d\u7f6e\u3002";
        }
        if (lower.contains("no route to host")) {
            return "\u7f51\u7edc\u4e0d\u53ef\u8fbe\u3002";
        }
        if (lower.contains("no such file")) {
            return "\u8fdc\u7aef\u8def\u5f84\u4e0d\u5b58\u5728\u3002";
        }
        if (lower.contains("sha-256") || lower.contains("\u6821\u9a8c\u5931\u8d25")) {
            return "\u4f20\u8f93\u5b8c\u6210\u540e\u6587\u4ef6\u6821\u9a8c\u5931\u8d25\uff0c\u5df2\u62d2\u7edd\u63d0\u4ea4\u7ed3\u679c\u3002";
        }
        return text;
    }
    @NonNull
    private static String joinRemotePath(@NonNull String base, @NonNull String name) {
        if ("/".equals(base)) return "/" + name;
        return base + "/" + name;
    }

    @NonNull
    private static String normalizeLocalPath(@Nullable String rawPath) {
        if (rawPath == null) return "/";
        String p = rawPath.trim().replace('\\', '/');
        while (p.contains("//")) p = p.replace("//", "/");
        if (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "/" : p;
    }

    @NonNull
    private static String normalizeRemotePath(@Nullable String rawRelativePath) {
        String relative = rawRelativePath == null ? "" : rawRelativePath.trim().replace('\\', '/');
        ArrayDeque<String> stack = new ArrayDeque<>();
        for (String token : relative.split("/")) {
            if (token == null || token.isEmpty() || ".".equals(token)) continue;
            if ("..".equals(token)) {
                if (!stack.isEmpty()) stack.removeLast();
            } else {
                stack.addLast(token);
            }
        }

        if (stack.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (String part : stack) {
            sb.append('/').append(part);
        }
        return sb.toString();
    }

    private static boolean isSshExecutable(@Nullable String token) {
        if (TextUtils.isEmpty(token)) return false;
        String n = token.trim().toLowerCase(Locale.ROOT);
        return "ssh".equals(n) || n.endsWith("/ssh") || "ssh.exe".equals(n);
    }

    @NonNull
    private static List<String> splitShell(@NonNull String input) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\' && !inSingle) {
                escaped = true;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }

        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    private interface SftpClientAction<T> {
        T run(@NonNull ChannelSftp channel) throws Exception;
    }

    private interface CancelProbe {
        boolean isCancelled();
    }

    private interface ExecOutputLineListener {
        void onLine(@NonNull String line);
    }

    private static final class ClientHolder {
        @NonNull
        final String clientKey;
        @NonNull
        final String legacySessionId;
        @NonNull
        final com.jcraft.jsch.Session session;
        @NonNull
        final Object channelLock = new Object();
        @NonNull
        final ArrayDeque<PooledChannel> idleChannels = new ArrayDeque<>();
        int openedChannels;

        ClientHolder(@NonNull String clientKey, @NonNull String legacySessionId,
                     @NonNull com.jcraft.jsch.Session session) {
            this.clientKey = clientKey;
            this.legacySessionId = legacySessionId;
            this.session = session;
            this.openedChannels = 0;
        }

        boolean isAlive() {
            return session.isConnected();
        }

        @NonNull
        ChannelSftp borrowChannel() throws Exception {
            synchronized (channelLock) {
                pruneIdleChannelsLocked();
                while (!idleChannels.isEmpty()) {
                    PooledChannel pooled = idleChannels.removeFirst();
                    ChannelSftp channel = pooled.channel;
                    if (channel != null && channel.isConnected()) return channel;
                    safeDisconnect(channel);
                    openedChannels = Math.max(0, openedChannels - 1);
                }

                if (openedChannels < MAX_CHANNEL_POOL_PER_CLIENT) {
                    return openNewChannelLocked();
                }

                long deadline = System.currentTimeMillis() + 2_500L;
                while (openedChannels >= MAX_CHANNEL_POOL_PER_CLIENT) {
                    long waitMs = deadline - System.currentTimeMillis();
                    if (waitMs <= 0) break;
                    try {
                        channelLock.wait(waitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    pruneIdleChannelsLocked();
                    while (!idleChannels.isEmpty()) {
                        PooledChannel pooled = idleChannels.removeFirst();
                        ChannelSftp channel = pooled.channel;
                        if (channel != null && channel.isConnected()) return channel;
                        safeDisconnect(channel);
                        openedChannels = Math.max(0, openedChannels - 1);
                    }
                    if (openedChannels < MAX_CHANNEL_POOL_PER_CLIENT) {
                        return openNewChannelLocked();
                    }
                }
                return openNewChannelLocked();
            }
        }

        void releaseChannel(@Nullable ChannelSftp channel, boolean broken) {
            if (channel == null) return;
            synchronized (channelLock) {
                if (broken || !session.isConnected() || !channel.isConnected()) {
                    safeDisconnect(channel);
                    openedChannels = Math.max(0, openedChannels - 1);
                    channelLock.notifyAll();
                    return;
                }

                pruneIdleChannelsLocked();
                if (idleChannels.size() >= MAX_CHANNEL_POOL_PER_CLIENT) {
                    safeDisconnect(channel);
                    openedChannels = Math.max(0, openedChannels - 1);
                } else {
                    idleChannels.addLast(new PooledChannel(channel, System.currentTimeMillis()));
                }
                channelLock.notifyAll();
            }
        }

        @NonNull
        private ChannelSftp openNewChannelLocked() throws Exception {
            Channel channel = session.openChannel("sftp");
            channel.connect(12_000);
            openedChannels++;
            return (ChannelSftp) channel;
        }

        @NonNull
        ChannelExec openExecChannel(@NonNull String command) throws Exception {
            Channel channel = session.openChannel("exec");
            ChannelExec exec = (ChannelExec) channel;
            exec.setCommand(command);
            exec.setInputStream(null);
            exec.connect(12_000);
            return exec;
        }

        void closeExecChannel(@Nullable ChannelExec channel, boolean broken) {
            safeDisconnect(channel);
            if (broken) {
                close();
            }
        }

        private void pruneIdleChannelsLocked() {
            long now = System.currentTimeMillis();
            while (!idleChannels.isEmpty()) {
                PooledChannel pooled = idleChannels.peekFirst();
                if (pooled == null) {
                    idleChannels.removeFirst();
                    continue;
                }
                ChannelSftp channel = pooled.channel;
                boolean expired = (now - pooled.idleSinceMs) > CHANNEL_IDLE_TTL_MS;
                boolean disconnected = channel == null || !channel.isConnected();
                if (!expired && !disconnected) break;
                idleChannels.removeFirst();
                safeDisconnect(channel);
                openedChannels = Math.max(0, openedChannels - 1);
            }
        }

        void close() {
            synchronized (channelLock) {
                while (!idleChannels.isEmpty()) {
                    PooledChannel pooled = idleChannels.removeFirst();
                    safeDisconnect(pooled == null ? null : pooled.channel);
                }
                openedChannels = 0;
                channelLock.notifyAll();
            }
            try {
                session.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void safeDisconnect(@Nullable Channel channel) {
        if (channel == null) return;
        try {
            channel.disconnect();
        } catch (Throwable ignored) {
        }
    }

    private static final class PooledChannel {
        @Nullable
        final ChannelSftp channel;
        final long idleSinceMs;

        PooledChannel(@Nullable ChannelSftp channel, long idleSinceMs) {
            this.channel = channel;
            this.idleSinceMs = idleSinceMs;
        }
    }

    private static final class StrongFingerprint {
        @NonNull
        final String remoteSha256;
        @NonNull
        final String localSha256;
        @NonNull
        final String level;
        @NonNull
        final String method;

        StrongFingerprint(@NonNull String remoteSha256,
                          @NonNull String localSha256,
                          @NonNull String level,
                          @NonNull String method) {
            this.remoteSha256 = remoteSha256;
            this.localSha256 = localSha256;
            this.level = level;
            this.method = method;
        }
    }

    private static final class DownloadFileTask {
        @NonNull
        final SessionEntry entry;
        @NonNull
        final String remotePath;
        @NonNull
        final File localFile;
        final long size;
        final long modifiedMs;

        DownloadFileTask(@NonNull SessionEntry entry,
                         @NonNull String remotePath,
                         @NonNull File localFile,
                         long size,
                         long modifiedMs) {
            this.entry = entry;
            this.remotePath = remotePath;
            this.localFile = localFile;
            this.size = size;
            this.modifiedMs = modifiedMs;
        }
    }

    private static final class PreparedDownloadTask {
        @NonNull
        final DownloadFileTask task;
        @NonNull
        final File outputFile;

        PreparedDownloadTask(@NonNull DownloadFileTask task, @NonNull File outputFile) {
            this.task = task;
            this.outputFile = outputFile;
        }
    }

    private static final class DownloadTaskResult {
        final boolean success;
        final boolean cancelled;
        final long transferredBytes;
        @Nullable
        final String errorMessage;

        private DownloadTaskResult(boolean success,
                                   boolean cancelled,
                                   long transferredBytes,
                                   @Nullable String errorMessage) {
            this.success = success;
            this.cancelled = cancelled;
            this.transferredBytes = Math.max(0L, transferredBytes);
            this.errorMessage = errorMessage;
        }

        @NonNull
        static DownloadTaskResult success(long transferredBytes) {
            return new DownloadTaskResult(true, false, transferredBytes, null);
        }

        @NonNull
        static DownloadTaskResult cancelled() {
            return new DownloadTaskResult(false, true, 0L, null);
        }

        @NonNull
        static DownloadTaskResult failure(@Nullable String errorMessage) {
            return new DownloadTaskResult(false, false, 0L, errorMessage);
        }
    }

    private static final class DownloadProgressState {
        final int totalFiles;
        final long totalBytes;
        int completedFiles;
        int failedFiles;
        long downloadedBytes;
        @Nullable
        String currentFile;
        long currentFileTransferred;
        long currentFileSize;
        long lastDispatchAtMs;

        DownloadProgressState(int totalFiles, long totalBytes) {
            this.totalFiles = Math.max(0, totalFiles);
            this.totalBytes = Math.max(0L, totalBytes);
            this.completedFiles = 0;
            this.failedFiles = 0;
            this.downloadedBytes = 0L;
            this.currentFile = "";
            this.currentFileTransferred = 0L;
            this.currentFileSize = 0L;
            this.lastDispatchAtMs = 0L;
        }
    }

    private static final class UploadFileTask {
        @NonNull
        final File localFile;
        @NonNull
        final String remotePath;
        final long size;
        final long modifiedMs;

        UploadFileTask(@NonNull File localFile,
                       @NonNull String remotePath,
                       long size,
                       long modifiedMs) {
            this.localFile = localFile;
            this.remotePath = remotePath;
            this.size = size;
            this.modifiedMs = modifiedMs;
        }
    }

    private static final class UploadTaskResult {
        final boolean success;
        final boolean cancelled;
        final long transferredBytes;
        @Nullable
        final String errorMessage;

        private UploadTaskResult(boolean success,
                                 boolean cancelled,
                                 long transferredBytes,
                                 @Nullable String errorMessage) {
            this.success = success;
            this.cancelled = cancelled;
            this.transferredBytes = Math.max(0L, transferredBytes);
            this.errorMessage = errorMessage;
        }

        @NonNull
        static UploadTaskResult success(long transferredBytes) {
            return new UploadTaskResult(true, false, transferredBytes, null);
        }

        @NonNull
        static UploadTaskResult cancelled() {
            return new UploadTaskResult(false, true, 0L, null);
        }

        @NonNull
        static UploadTaskResult failure(@Nullable String errorMessage) {
            return new UploadTaskResult(false, false, 0L, errorMessage);
        }
    }

    private static final class UploadProgressState {
        final int totalFiles;
        final long totalBytes;
        int completedFiles;
        int failedFiles;
        long uploadedBytes;
        @Nullable
        String currentFile;
        long currentFileTransferred;
        long currentFileSize;
        long lastDispatchAtMs;

        UploadProgressState(int totalFiles, long totalBytes) {
            this.totalFiles = Math.max(0, totalFiles);
            this.totalBytes = Math.max(0L, totalBytes);
            this.completedFiles = 0;
            this.failedFiles = 0;
            this.uploadedBytes = 0L;
            this.currentFile = "";
            this.currentFileTransferred = 0L;
            this.currentFileSize = 0L;
            this.lastDispatchAtMs = 0L;
        }
    }

    private static final class ActiveTransferProgress {
        @NonNull
        String fileName;
        long fileSize;
        long transferredBytes;

        ActiveTransferProgress(@NonNull String fileName, long fileSize, long transferredBytes) {
            this.fileName = fileName;
            this.fileSize = Math.max(0L, fileSize);
            this.transferredBytes = Math.max(0L, transferredBytes);
        }
    }

    private static final class ConcurrentTransferProgressSnapshot {
        final int totalFiles;
        final int completedFiles;
        final int failedFiles;
        final long totalBytes;
        final long transferredBytes;
        @NonNull
        final String currentFile;
        final long currentFileTransferred;
        final long currentFileSize;

        ConcurrentTransferProgressSnapshot(int totalFiles,
                                           int completedFiles,
                                           int failedFiles,
                                           long totalBytes,
                                           long transferredBytes,
                                           @NonNull String currentFile,
                                           long currentFileTransferred,
                                           long currentFileSize) {
            this.totalFiles = Math.max(0, totalFiles);
            this.completedFiles = Math.max(0, completedFiles);
            this.failedFiles = Math.max(0, failedFiles);
            this.totalBytes = Math.max(0L, totalBytes);
            this.transferredBytes = Math.max(0L, transferredBytes);
            this.currentFile = currentFile;
            this.currentFileTransferred = Math.max(0L, currentFileTransferred);
            this.currentFileSize = Math.max(0L, currentFileSize);
        }
    }

    private static final class ConcurrentTransferProgressState {
        final int totalFiles;
        final long totalBytes;
        private int completedFiles;
        private int failedFiles;
        private long settledBytes;
        @NonNull
        private String currentFile = "";
        private long currentFileTransferred;
        private long currentFileSize;
        private long lastDispatchAtMs;
        @NonNull
        private final Map<String, ActiveTransferProgress> activeTransfers = new HashMap<>();

        ConcurrentTransferProgressState(int totalFiles, long totalBytes) {
            this.totalFiles = Math.max(0, totalFiles);
            this.totalBytes = Math.max(0L, totalBytes);
        }

        synchronized void onFileStarted(@NonNull String key, @NonNull String fileName, long fileSize) {
            ActiveTransferProgress active = new ActiveTransferProgress(fileName, fileSize, 0L);
            activeTransfers.put(key, active);
            currentFile = fileName;
            currentFileSize = active.fileSize;
            currentFileTransferred = 0L;
        }

        synchronized void onFileProgress(@NonNull String key, @NonNull String fileName,
                                         long fileSize, long transferredBytes) {
            ActiveTransferProgress active = activeTransfers.get(key);
            if (active == null) {
                active = new ActiveTransferProgress(fileName, fileSize, transferredBytes);
                activeTransfers.put(key, active);
            } else {
                active.fileName = fileName;
                if (fileSize > 0) {
                    active.fileSize = fileSize;
                }
                active.transferredBytes = Math.max(0L, transferredBytes);
            }
            currentFile = active.fileName;
            currentFileSize = active.fileSize;
            currentFileTransferred = active.transferredBytes;
        }

        synchronized void onFileSucceeded(@NonNull String key, @NonNull String fileName,
                                          long fileSize, long transferredBytes) {
            activeTransfers.remove(key);
            completedFiles++;
            settledBytes += Math.max(0L, transferredBytes);
            currentFile = fileName;
            currentFileSize = Math.max(0L, fileSize);
            currentFileTransferred = currentFileSize > 0
                ? currentFileSize
                : Math.max(0L, transferredBytes);
        }

        synchronized void onFileFailed(@NonNull String key, @NonNull String fileName, long fileSize) {
            activeTransfers.remove(key);
            failedFiles++;
            currentFile = fileName;
            currentFileSize = Math.max(0L, fileSize);
            currentFileTransferred = 0L;
        }

        synchronized void onFileCancelled(@NonNull String key, @NonNull String fileName, long fileSize) {
            activeTransfers.remove(key);
            currentFile = fileName;
            currentFileSize = Math.max(0L, fileSize);
            currentFileTransferred = 0L;
        }

        synchronized void onTransferFinished() {
            activeTransfers.clear();
            currentFile = "";
            currentFileTransferred = 0L;
            currentFileSize = 0L;
        }

        @Nullable
        synchronized ConcurrentTransferProgressSnapshot snapshot(boolean force) {
            long now = System.currentTimeMillis();
            if (!force && now - lastDispatchAtMs < DOWNLOAD_PROGRESS_MIN_INTERVAL_MS) {
                return null;
            }
            lastDispatchAtMs = now;

            long transferredBytes = settledBytes;
            for (ActiveTransferProgress active : activeTransfers.values()) {
                if (active == null) continue;
                transferredBytes += Math.max(0L, active.transferredBytes);
            }
            if (totalBytes > 0 && transferredBytes > totalBytes) {
                transferredBytes = totalBytes;
            }

            return new ConcurrentTransferProgressSnapshot(
                totalFiles,
                completedFiles,
                failedFiles,
                totalBytes,
                transferredBytes,
                currentFile,
                currentFileTransferred,
                currentFileSize
            );
        }

        synchronized int completedFiles() {
            return completedFiles;
        }

        synchronized int failedFiles() {
            return failedFiles;
        }

        synchronized long settledBytes() {
            return settledBytes;
        }
    }

    private static final class CachedDirectory {
        final long cachedAtMs;
        @NonNull
        final ArrayList<RemoteEntry> entries;
        @NonNull
        final String displayPath;

        CachedDirectory(long cachedAtMs, @NonNull ArrayList<RemoteEntry> entries, @NonNull String displayPath) {
            this.cachedAtMs = cachedAtMs;
            this.entries = entries;
            this.displayPath = displayPath;
        }
    }
    private static final class VirtualTarget {
        @NonNull
        final SessionEntry entry;
        @NonNull
        final String virtualRoot;
        @NonNull
        final String remotePath;

        VirtualTarget(@NonNull SessionEntry entry, @NonNull String virtualRoot, @NonNull String remotePath) {
            this.entry = entry;
            this.virtualRoot = virtualRoot;
            this.remotePath = remotePath;
        }
    }

    private static final class ParsedTarget {
        final boolean valid;
        @NonNull
        final String host;
        @NonNull
        final String user;
        final int port;
        @Nullable
        final String password;
        @Nullable
        final String identityPath;
        @Nullable
        final String errorMessage;

        private ParsedTarget(boolean valid, @NonNull String host, @NonNull String user, int port,
                             @Nullable String password, @Nullable String identityPath, @Nullable String errorMessage) {
            this.valid = valid;
            this.host = host;
            this.user = user;
            this.port = port;
            this.password = password;
            this.identityPath = identityPath;
            this.errorMessage = errorMessage;
        }

        @NonNull
        static ParsedTarget valid(@NonNull String host, @NonNull String user, int port,
                                  @Nullable String password, @Nullable String identityPath) {
            return new ParsedTarget(true, host, user, port, password, identityPath, null);
        }

        @NonNull
        static ParsedTarget invalid(@NonNull String message) {
            return new ParsedTarget(false, "", "", 22, null, null, message);
        }
    }

    public static final class RemoteEntry {
        @NonNull
        public final String localPath;
        @NonNull
        public final String name;
        public final boolean directory;
        public final long size;
        public final long modifiedMs;

        RemoteEntry(@NonNull String localPath, @NonNull String name, boolean directory, long size, long modifiedMs) {
            this.localPath = localPath;
            this.name = name;
            this.directory = directory;
            this.size = size;
            this.modifiedMs = modifiedMs;
        }
    }

    public static final class VirtualPathInfo {
        public final boolean success;
        @NonNull
        public final String virtualRoot;
        @NonNull
        public final String remotePath;
        @NonNull
        public final String displayName;
        @NonNull
        public final String authorityLabel;
        @NonNull
        public final String messageCn;

        private VirtualPathInfo(boolean success,
                                @NonNull String virtualRoot,
                                @NonNull String remotePath,
                                @NonNull String displayName,
                                @NonNull String authorityLabel,
                                @NonNull String messageCn) {
            this.success = success;
            this.virtualRoot = virtualRoot;
            this.remotePath = remotePath;
            this.displayName = displayName;
            this.authorityLabel = authorityLabel;
            this.messageCn = messageCn;
        }

        @NonNull
        static VirtualPathInfo ok(@NonNull String virtualRoot,
                                  @NonNull String remotePath,
                                  @NonNull String displayName,
                                  @NonNull String authorityLabel) {
            return new VirtualPathInfo(true, virtualRoot, remotePath, displayName, authorityLabel, "");
        }

        @NonNull
        static VirtualPathInfo fail(@NonNull String messageCn) {
            return new VirtualPathInfo(false, "", "", "", "", messageCn);
        }
    }

    public interface RemoteCommandControl {
        boolean isCancelled();
    }

    public static final class LocalRealizationResult {
        public final boolean success;
        public final boolean reusable;
        public final boolean stale;
        @NonNull
        public final String localPath;
        public final long remoteModifiedMs;
        public final long remoteSize;
        @NonNull
        public final String remoteSha256;
        @NonNull
        public final String localSha256;
        @NonNull
        public final String freshnessLevel;
        @NonNull
        public final String freshnessMethod;
        @NonNull
        public final String messageCn;

        private LocalRealizationResult(boolean success,
                                       boolean reusable,
                                       boolean stale,
                                       @NonNull String localPath,
                                       long remoteModifiedMs,
                                       long remoteSize,
                                       @NonNull String remoteSha256,
                                       @NonNull String localSha256,
                                       @NonNull String freshnessLevel,
                                       @NonNull String freshnessMethod,
                                       @NonNull String messageCn) {
            this.success = success;
            this.reusable = reusable;
            this.stale = stale;
            this.localPath = localPath;
            this.remoteModifiedMs = remoteModifiedMs;
            this.remoteSize = remoteSize;
            this.remoteSha256 = remoteSha256;
            this.localSha256 = localSha256;
            this.freshnessLevel = freshnessLevel;
            this.freshnessMethod = freshnessMethod;
            this.messageCn = messageCn;
        }

        @NonNull
        static LocalRealizationResult reusable(@NonNull String localPath, long remoteModifiedMs, long remoteSize) {
            return reusable(localPath, remoteModifiedMs, remoteSize, "", "", VirtualLocalFileRegistry.LEVEL_WEAK_STAT, VirtualLocalFileRegistry.METHOD_WEAK_STAT);
        }

        @NonNull
        static LocalRealizationResult reusable(@NonNull String localPath,
                                               long remoteModifiedMs,
                                               long remoteSize,
                                               @NonNull String remoteSha256,
                                               @NonNull String localSha256,
                                               @NonNull String freshnessLevel,
                                               @NonNull String freshnessMethod) {
            return new LocalRealizationResult(true, true, false, localPath, remoteModifiedMs, remoteSize,
                remoteSha256, localSha256, freshnessLevel, freshnessMethod, "");
        }

        @NonNull
        static LocalRealizationResult missing(@NonNull String messageCn) {
            return new LocalRealizationResult(true, false, false, "", -1L, -1L, "", "",
                VirtualLocalFileRegistry.LEVEL_UNKNOWN, "", messageCn);
        }

        @NonNull
        static LocalRealizationResult stale(@NonNull String messageCn) {
            return stale(messageCn, -1L, -1L);
        }

        @NonNull
        static LocalRealizationResult stale(@NonNull String messageCn, long remoteModifiedMs, long remoteSize) {
            return new LocalRealizationResult(true, false, true, "", remoteModifiedMs, remoteSize, "", "",
                VirtualLocalFileRegistry.LEVEL_UNKNOWN, "", messageCn);
        }

        @NonNull
        static LocalRealizationResult fail(@NonNull String messageCn) {
            return new LocalRealizationResult(false, false, false, "", -1L, -1L, "", "",
                VirtualLocalFileRegistry.LEVEL_UNKNOWN, "", messageCn);
        }
    }

    public static final class RemoteCommandResult {
        public final boolean success;
        public final int exitCode;
        @NonNull
        public final String stdout;
        @NonNull
        public final String stderr;
        @NonNull
        public final String messageCn;

        private RemoteCommandResult(boolean success,
                                    int exitCode,
                                    @NonNull String stdout,
                                    @NonNull String stderr,
                                    @NonNull String messageCn) {
            this.success = success;
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.messageCn = messageCn;
        }

        @NonNull
        static RemoteCommandResult ok(int exitCode, @Nullable String stdout, @Nullable String stderr) {
            return new RemoteCommandResult(
                true,
                exitCode,
                stdout == null ? "" : stdout,
                stderr == null ? "" : stderr,
                ""
            );
        }

        @NonNull
        static RemoteCommandResult fail(@NonNull String messageCn) {
            return new RemoteCommandResult(false, -1, "", "", messageCn);
        }

        @NonNull
        static RemoteCommandResult fail(int exitCode,
                                        @Nullable String stdout,
                                        @Nullable String stderr,
                                        @NonNull String messageCn) {
            return new RemoteCommandResult(
                false,
                exitCode,
                stdout == null ? "" : stdout,
                stderr == null ? "" : stderr,
                messageCn
            );
        }

        @NonNull
        static RemoteCommandResult cancelled() {
            return new RemoteCommandResult(false, -1, "", "", "\u64cd\u4f5c\u5df2\u53d6\u6d88\u3002");
        }
    }

    public static final class ListResult {
        public final boolean success;
        @NonNull
        public final ArrayList<RemoteEntry> entries;
        @NonNull
        public final String displayPath;
        @NonNull
        public final String messageCn;

        private ListResult(boolean success, @NonNull ArrayList<RemoteEntry> entries,
                           @NonNull String displayPath, @NonNull String messageCn) {
            this.success = success;
            this.entries = entries;
            this.displayPath = displayPath;
            this.messageCn = messageCn;
        }

        @NonNull
        static ListResult ok(@NonNull ArrayList<RemoteEntry> entries, @NonNull String displayPath) {
            return new ListResult(true, entries, displayPath, "");
        }

        @NonNull
        static ListResult fail(@NonNull String messageCn) {
            return new ListResult(false, new ArrayList<>(), "", messageCn);
        }
    }

    public static final class MaterializeResult {
        public final boolean success;
        @NonNull
        public final String localPath;
        public final long remoteModifiedMs;
        public final long remoteSize;
        @NonNull
        public final String remoteSha256;
        @NonNull
        public final String localSha256;
        public final boolean reusedLocal;
        @NonNull
        public final String messageCn;

        private MaterializeResult(boolean success,
                                  @NonNull String localPath,
                                  long remoteModifiedMs,
                                  long remoteSize,
                                  @NonNull String remoteSha256,
                                  @NonNull String localSha256,
                                  boolean reusedLocal,
                                  @NonNull String messageCn) {
            this.success = success;
            this.localPath = localPath;
            this.remoteModifiedMs = remoteModifiedMs;
            this.remoteSize = remoteSize;
            this.remoteSha256 = remoteSha256;
            this.localSha256 = localSha256;
            this.reusedLocal = reusedLocal;
            this.messageCn = messageCn;
        }

        @NonNull
        static MaterializeResult ok(@NonNull String localPath, long remoteModifiedMs, long remoteSize) {
            return ok(localPath, remoteModifiedMs, remoteSize, "", "", false);
        }

        @NonNull
        static MaterializeResult ok(@NonNull String localPath,
                                    long remoteModifiedMs,
                                    long remoteSize,
                                    @NonNull String remoteSha256,
                                    @NonNull String localSha256,
                                    boolean reusedLocal) {
            return new MaterializeResult(true, localPath, remoteModifiedMs, remoteSize, remoteSha256, localSha256, reusedLocal, "");
        }

        @NonNull
        static MaterializeResult fail(@NonNull String messageCn) {
            return new MaterializeResult(false, "", -1L, -1L, "", "", false, messageCn);
        }
    }

    public static final class CreateResult {
        public final boolean success;
        @NonNull
        public final String virtualPath;
        @NonNull
        public final String messageCn;

        private CreateResult(boolean success, @NonNull String virtualPath, @NonNull String messageCn) {
            this.success = success;
            this.virtualPath = virtualPath;
            this.messageCn = messageCn;
        }

        @NonNull
        static CreateResult ok(@NonNull String virtualPath) {
            return new CreateResult(true, virtualPath, "");
        }

        @NonNull
        static CreateResult fail(@NonNull String messageCn) {
            return new CreateResult(false, "", messageCn);
        }
    }

    public static final class DeleteResult {
        public final boolean success;
        @NonNull
        public final String virtualPath;
        @NonNull
        public final String messageCn;

        private DeleteResult(boolean success, @NonNull String virtualPath, @NonNull String messageCn) {
            this.success = success;
            this.virtualPath = virtualPath;
            this.messageCn = messageCn;
        }

        @NonNull
        static DeleteResult ok(@NonNull String virtualPath) {
            return new DeleteResult(true, virtualPath, "");
        }

        @NonNull
        static DeleteResult fail(@NonNull String messageCn) {
            return new DeleteResult(false, "", messageCn);
        }
    }

    public interface RemoteDeleteProgressListener {
        void onProgress(@NonNull RemoteDeleteProgress progress);
    }

    public interface RemoteDeleteControl {
        boolean isCancelled();
    }

    public static final class RemoteDeleteProgress {
        @NonNull public final String stage;
        @NonNull public final String stageLabelCn;
        public final int totalItems;
        public final int completedItems;
        public final int successItems;
        public final int failedItems;
        public final int skippedItems;
        @NonNull public final String currentVirtualPath;
        @NonNull public final String currentRemotePath;
        @NonNull public final String currentDisplayName;
        public final long elapsedMs;
        @NonNull public final String messageCn;
        public final long currentEntryDone;
        public final long currentEntryTotal;

        private RemoteDeleteProgress(@NonNull String stage,
                                     @NonNull String stageLabelCn,
                                     int totalItems,
                                     int completedItems,
                                     int successItems,
                                     int failedItems,
                                     int skippedItems,
                                     @NonNull String currentVirtualPath,
                                     @NonNull String currentRemotePath,
                                     @NonNull String currentDisplayName,
                                     long elapsedMs,
                                     @NonNull String messageCn,
                                     long currentEntryDone,
                                     long currentEntryTotal) {
            this.stage = stage;
            this.stageLabelCn = stageLabelCn;
            this.totalItems = Math.max(0, totalItems);
            this.completedItems = Math.max(0, completedItems);
            this.successItems = Math.max(0, successItems);
            this.failedItems = Math.max(0, failedItems);
            this.skippedItems = Math.max(0, skippedItems);
            this.currentVirtualPath = currentVirtualPath;
            this.currentRemotePath = currentRemotePath;
            this.currentDisplayName = currentDisplayName;
            this.elapsedMs = Math.max(0L, elapsedMs);
            this.messageCn = messageCn;
            this.currentEntryDone = Math.max(0L, currentEntryDone);
            this.currentEntryTotal = Math.max(0L, currentEntryTotal);
        }
    }

    public static final class RemoteDeleteItemResult {
        public final boolean success;
        public final boolean skipped;
        public final boolean directory;
        public final boolean existedBefore;
        public final boolean verifiedMissing;
        public final boolean usedExec;
        public final boolean usedSftpFallback;
        @NonNull public final String virtualPath;
        @NonNull public final String remotePath;
        @NonNull public final String displayName;
        @NonNull public final String messageCn;

        private RemoteDeleteItemResult(boolean success,
                                       boolean skipped,
                                       boolean directory,
                                       boolean existedBefore,
                                       boolean verifiedMissing,
                                       boolean usedExec,
                                       boolean usedSftpFallback,
                                       @NonNull String virtualPath,
                                       @NonNull String remotePath,
                                       @NonNull String displayName,
                                       @NonNull String messageCn) {
            this.success = success;
            this.skipped = skipped;
            this.directory = directory;
            this.existedBefore = existedBefore;
            this.verifiedMissing = verifiedMissing;
            this.usedExec = usedExec;
            this.usedSftpFallback = usedSftpFallback;
            this.virtualPath = virtualPath;
            this.remotePath = remotePath;
            this.displayName = displayName;
            this.messageCn = messageCn;
        }

        @NonNull
        static RemoteDeleteItemResult success(@NonNull String virtualPath,
                                              @NonNull String remotePath,
                                              @NonNull String displayName,
                                              boolean directory,
                                              boolean missingBefore,
                                              boolean usedExec,
                                              boolean usedSftpFallback,
                                              @NonNull String messageCn) {
            return new RemoteDeleteItemResult(true, false, directory, !missingBefore, true, usedExec,
                usedSftpFallback, virtualPath, remotePath, displayName, messageCn);
        }

        @NonNull
        static RemoteDeleteItemResult skipped(@NonNull String virtualPath,
                                              @NonNull String remotePath,
                                              @NonNull String displayName,
                                              boolean directory,
                                              @NonNull String messageCn) {
            return new RemoteDeleteItemResult(true, true, directory, true, false, false, false,
                virtualPath, remotePath, displayName, messageCn);
        }

        @NonNull
        static RemoteDeleteItemResult fail(@NonNull String virtualPath,
                                           @NonNull String remotePath,
                                           @NonNull String displayName,
                                           boolean directory,
                                           boolean usedExec,
                                           boolean usedSftpFallback,
                                           @NonNull String messageCn) {
            return new RemoteDeleteItemResult(false, false, directory, true, false, usedExec,
                usedSftpFallback, virtualPath, remotePath, displayName, messageCn);
        }
    }

    public static final class RemoteDeleteResult {
        public final boolean success;
        public final boolean cancelled;
        public final int totalItems;
        public final int plannedItems;
        public final int deletedItems;
        public final int failedItems;
        public final int skippedItems;
        public final long elapsedMs;
        @NonNull public final ArrayList<String> deletedVirtualPaths;
        @NonNull public final ArrayList<RemoteDeleteItemResult> itemResults;
        @NonNull public final String messageCn;

        public RemoteDeleteResult(boolean success,
                                  boolean cancelled,
                                  int totalItems,
                                  int plannedItems,
                                  int deletedItems,
                                  int failedItems,
                                  int skippedItems,
                                  long elapsedMs,
                                  @NonNull ArrayList<String> deletedVirtualPaths,
                                  @NonNull ArrayList<RemoteDeleteItemResult> itemResults,
                                  @NonNull String messageCn) {
            this.success = success;
            this.cancelled = cancelled;
            this.totalItems = Math.max(0, totalItems);
            this.plannedItems = Math.max(0, plannedItems);
            this.deletedItems = Math.max(0, deletedItems);
            this.failedItems = Math.max(0, failedItems);
            this.skippedItems = Math.max(0, skippedItems);
            this.elapsedMs = Math.max(0L, elapsedMs);
            this.deletedVirtualPaths = new ArrayList<>(deletedVirtualPaths);
            this.itemResults = new ArrayList<>(itemResults);
            this.messageCn = messageCn;
        }

        @NonNull
        static RemoteDeleteResult fromItems(int totalItems,
                                            boolean cancelled,
                                            long elapsedMs,
                                            @NonNull ArrayList<RemoteDeleteItemResult> itemResults) {
            int deleted = 0;
            int failed = 0;
            int skipped = 0;
            int planned = 0;
            ArrayList<String> deletedVirtualPaths = new ArrayList<>();
            for (RemoteDeleteItemResult item : itemResults) {
                if (item == null) continue;
                if (item.skipped) {
                    skipped++;
                    continue;
                }
                planned++;
                if (item.success) {
                    deleted++;
                    if (!TextUtils.isEmpty(item.virtualPath)) deletedVirtualPaths.add(item.virtualPath);
                } else {
                    failed++;
                }
            }
            boolean success = !cancelled && failed == 0 && deleted + skipped >= totalItems;
            String message;
            if (cancelled) {
                message = "删除已取消";
            } else if (success) {
                message = "删除完成";
            } else if (deleted > 0) {
                message = "删除部分完成";
            } else if (failed > 0) {
                message = "删除失败";
            } else {
                message = "没有可删除的远端目标";
            }
            return new RemoteDeleteResult(success, cancelled, totalItems, planned, deleted, failed, skipped,
                elapsedMs, deletedVirtualPaths, itemResults, message);
        }
    }

    public static final class RenameResult {
        public final boolean success;
        @NonNull
        public final String virtualPath;
        @NonNull
        public final String messageCn;

        private RenameResult(boolean success, @NonNull String virtualPath, @NonNull String messageCn) {
            this.success = success;
            this.virtualPath = virtualPath;
            this.messageCn = messageCn;
        }

        @NonNull
        static RenameResult ok(@NonNull String virtualPath) {
            return new RenameResult(true, virtualPath, "");
        }

        @NonNull
        static RenameResult fail(@NonNull String messageCn) {
            return new RenameResult(false, "", messageCn);
        }
    }

    public static final class MoveResult {
        public final boolean success;
        @NonNull
        public final ArrayList<String> movedVirtualPaths;
        @NonNull
        public final String messageCn;

        private MoveResult(boolean success, @NonNull ArrayList<String> movedVirtualPaths, @NonNull String messageCn) {
            this.success = success;
            this.movedVirtualPaths = movedVirtualPaths;
            this.messageCn = messageCn;
        }

        @NonNull
        static MoveResult ok(@NonNull ArrayList<String> movedVirtualPaths) {
            return new MoveResult(true, movedVirtualPaths, "");
        }

        @NonNull
        static MoveResult fail(@NonNull String messageCn) {
            return new MoveResult(false, new ArrayList<>(), messageCn);
        }
    }

    public interface DownloadProgressListener {
        void onProgress(@NonNull DownloadProgress progress);
    }

    public interface DownloadControl {
        boolean isCancelled();
    }

    public static final class DownloadProgress {
        public final int totalFiles;
        public final int completedFiles;
        public final int failedFiles;
        public final long totalBytes;
        public final long transferredBytes;
        @NonNull
        public final String currentFile;
        public final long currentFileTransferred;
        public final long currentFileSize;

        private DownloadProgress(int totalFiles,
                                 int completedFiles,
                                 int failedFiles,
                                 long totalBytes,
                                 long transferredBytes,
                                 @NonNull String currentFile,
                                 long currentFileTransferred,
                                 long currentFileSize) {
            this.totalFiles = totalFiles;
            this.completedFiles = completedFiles;
            this.failedFiles = failedFiles;
            this.totalBytes = totalBytes;
            this.transferredBytes = transferredBytes;
            this.currentFile = currentFile;
            this.currentFileTransferred = currentFileTransferred;
            this.currentFileSize = currentFileSize;
        }
    }

    public static final class DownloadResult {
        public final boolean success;
        public final int totalFiles;
        public final int downloadedFiles;
        public final int failedFiles;
        public final long totalBytes;
        public final long downloadedBytes;
        @NonNull
        public final ArrayList<String> downloadedLocalPaths;
        @NonNull
        public final String messageCn;

        private DownloadResult(boolean success,
                               int totalFiles,
                               int downloadedFiles,
                               int failedFiles,
                               long totalBytes,
                               long downloadedBytes,
                               @NonNull ArrayList<String> downloadedLocalPaths,
                               @NonNull String messageCn) {
            this.success = success;
            this.totalFiles = Math.max(0, totalFiles);
            this.downloadedFiles = Math.max(0, downloadedFiles);
            this.failedFiles = Math.max(0, failedFiles);
            this.totalBytes = Math.max(0L, totalBytes);
            this.downloadedBytes = Math.max(0L, downloadedBytes);
            this.downloadedLocalPaths = new ArrayList<>(downloadedLocalPaths);
            this.messageCn = messageCn;
        }

        @NonNull
        static DownloadResult ok(int totalFiles, int downloadedFiles, int failedFiles,
                                 long totalBytes, long downloadedBytes,
                                 @NonNull ArrayList<String> downloadedLocalPaths) {
            return new DownloadResult(true, totalFiles, downloadedFiles, failedFiles,
                totalBytes, downloadedBytes, downloadedLocalPaths, "");
        }

        @NonNull
        static DownloadResult partial(int totalFiles, int downloadedFiles, int failedFiles,
                                      long totalBytes, long downloadedBytes, @NonNull String messageCn,
                                      @NonNull ArrayList<String> downloadedLocalPaths) {
            return new DownloadResult(false, totalFiles, downloadedFiles, failedFiles,
                totalBytes, downloadedBytes, downloadedLocalPaths, messageCn);
        }

        @NonNull
        static DownloadResult fail(@NonNull String messageCn) {
            return new DownloadResult(false, 0, 0, 0, 0L, 0L, new ArrayList<>(), messageCn);
        }

        @NonNull
        static DownloadResult cancelled(int totalFiles, int downloadedFiles, int failedFiles,
                                        long totalBytes, long downloadedBytes,
                                        @NonNull ArrayList<String> downloadedLocalPaths) {
            return new DownloadResult(false, totalFiles, downloadedFiles, failedFiles,
                totalBytes, downloadedBytes, downloadedLocalPaths, "\u4e0b\u8f7d\u5df2\u53d6\u6d88");
        }

        @NonNull
        static DownloadResult failWithStats(int totalFiles, int downloadedFiles, int failedFiles,
                                            long totalBytes, long downloadedBytes, @NonNull String messageCn,
                                            @NonNull ArrayList<String> downloadedLocalPaths) {
            return new DownloadResult(false, totalFiles, downloadedFiles, failedFiles,
                totalBytes, downloadedBytes, downloadedLocalPaths, messageCn);
        }
    }

    public interface UploadProgressListener {
        void onProgress(@NonNull UploadProgress progress);
    }

    public interface UploadControl {
        boolean isCancelled();
    }

    public static final class UploadProgress {
        public final int totalFiles;
        public final int completedFiles;
        public final int failedFiles;
        public final long totalBytes;
        public final long transferredBytes;
        @NonNull
        public final String currentFile;
        public final long currentFileTransferred;
        public final long currentFileSize;

        private UploadProgress(int totalFiles,
                               int completedFiles,
                               int failedFiles,
                               long totalBytes,
                               long transferredBytes,
                               @NonNull String currentFile,
                               long currentFileTransferred,
                               long currentFileSize) {
            this.totalFiles = totalFiles;
            this.completedFiles = completedFiles;
            this.failedFiles = failedFiles;
            this.totalBytes = totalBytes;
            this.transferredBytes = transferredBytes;
            this.currentFile = currentFile;
            this.currentFileTransferred = currentFileTransferred;
            this.currentFileSize = currentFileSize;
        }
    }

    public static final class UploadResult {
        public final boolean success;
        public final int totalFiles;
        public final int uploadedFiles;
        public final int failedFiles;
        public final long totalBytes;
        public final long uploadedBytes;
        public final long remoteModifiedMs;
        public final long remoteSize;
        @NonNull
        public final ArrayList<String> uploadedVirtualPaths;
        @NonNull
        public final String messageCn;

        private UploadResult(boolean success,
                             int totalFiles,
                             int uploadedFiles,
                             int failedFiles,
                             long totalBytes,
                             long uploadedBytes,
                             @NonNull ArrayList<String> uploadedVirtualPaths,
                             @NonNull String messageCn) {
            this(success, totalFiles, uploadedFiles, failedFiles, totalBytes, uploadedBytes, uploadedVirtualPaths, messageCn, -1L, -1L);
        }

        private UploadResult(boolean success,
                             int totalFiles,
                             int uploadedFiles,
                             int failedFiles,
                             long totalBytes,
                             long uploadedBytes,
                             @NonNull ArrayList<String> uploadedVirtualPaths,
                             @NonNull String messageCn,
                             long remoteModifiedMs,
                             long remoteSize) {
            this.success = success;
            this.totalFiles = Math.max(0, totalFiles);
            this.uploadedFiles = Math.max(0, uploadedFiles);
            this.failedFiles = Math.max(0, failedFiles);
            this.totalBytes = Math.max(0L, totalBytes);
            this.uploadedBytes = Math.max(0L, uploadedBytes);
            this.remoteModifiedMs = remoteModifiedMs;
            this.remoteSize = remoteSize;
            this.uploadedVirtualPaths = new ArrayList<>(uploadedVirtualPaths);
            this.messageCn = messageCn;
        }

        @NonNull
        static UploadResult ok(int totalFiles, int uploadedFiles, int failedFiles,
                               long totalBytes, long uploadedBytes,
                               @NonNull ArrayList<String> uploadedVirtualPaths) {
            return new UploadResult(true, totalFiles, uploadedFiles, failedFiles,
                totalBytes, uploadedBytes, uploadedVirtualPaths, "");
        }

        @NonNull
        static UploadResult okWithRemote(int totalFiles, int uploadedFiles, int failedFiles,
                                         long totalBytes, long uploadedBytes,
                                         long remoteModifiedMs, long remoteSize,
                                         @NonNull ArrayList<String> uploadedVirtualPaths) {
            return new UploadResult(true, totalFiles, uploadedFiles, failedFiles,
                totalBytes, uploadedBytes, uploadedVirtualPaths, "", remoteModifiedMs, remoteSize);
        }

        @NonNull
        static UploadResult partial(int totalFiles, int uploadedFiles, int failedFiles,
                                    long totalBytes, long uploadedBytes, @NonNull String messageCn,
                                    @NonNull ArrayList<String> uploadedVirtualPaths) {
            return new UploadResult(false, totalFiles, uploadedFiles, failedFiles,
                totalBytes, uploadedBytes, uploadedVirtualPaths, messageCn);
        }

        @NonNull
        static UploadResult fail(@NonNull String messageCn) {
            return new UploadResult(false, 0, 0, 0, 0L, 0L, new ArrayList<>(), messageCn);
        }

        @NonNull
        static UploadResult cancelled(int totalFiles, int uploadedFiles, int failedFiles,
                                      long totalBytes, long uploadedBytes,
                                      @NonNull ArrayList<String> uploadedVirtualPaths) {
            return new UploadResult(false, totalFiles, uploadedFiles, failedFiles,
                totalBytes, uploadedBytes, uploadedVirtualPaths, "\u4e0a\u4f20\u5df2\u53d6\u6d88");
        }

        @NonNull
        static UploadResult failWithStats(int totalFiles, int uploadedFiles, int failedFiles,
                                          long totalBytes, long uploadedBytes, @NonNull String messageCn,
                                          @NonNull ArrayList<String> uploadedVirtualPaths) {
            return new UploadResult(false, totalFiles, uploadedFiles, failedFiles,
                totalBytes, uploadedBytes, uploadedVirtualPaths, messageCn);
        }
    }

    public static final class CodexAttachmentUploadResult {
        public final boolean success;
        public final boolean cancelled;
        public final boolean reused;
        public final long uploadedBytes;
        @NonNull public final String remotePath;
        @NonNull public final String messageCn;

        private CodexAttachmentUploadResult(boolean success, boolean cancelled, boolean reused,
                                            long uploadedBytes, @NonNull String remotePath,
                                            @NonNull String messageCn) {
            this.success = success;
            this.cancelled = cancelled;
            this.reused = reused;
            this.uploadedBytes = Math.max(0L, uploadedBytes);
            this.remotePath = remotePath;
            this.messageCn = messageCn;
        }

        @NonNull
        static CodexAttachmentUploadResult ok(@NonNull String remotePath, boolean reused,
                                              long uploadedBytes) {
            return new CodexAttachmentUploadResult(true, false, reused, uploadedBytes, remotePath, "");
        }

        @NonNull
        static CodexAttachmentUploadResult fail(@NonNull String messageCn) {
            return new CodexAttachmentUploadResult(false, false, false, 0L, "", messageCn);
        }

        @NonNull
        static CodexAttachmentUploadResult cancelled() {
            return new CodexAttachmentUploadResult(false, true, false, 0L, "", "图片上传已取消。");
        }
    }

    public interface RemoteTransferProgressListener {
        void onProgress(@NonNull RemoteTransferProgress progress);
    }

    public interface RemoteTransferControl {
        boolean isCancelled();
    }

    public static final class RemoteTransferProgress {
        @NonNull
        public final String stage;
        @NonNull
        public final String stageLabelCn;
        public final int totalFiles;
        public final int completedFiles;
        public final int failedFiles;
        public final long totalBytes;
        public final long transferredBytes;
        @NonNull
        public final String currentFile;
        public final long currentFileTransferred;
        public final long currentFileSize;
        @NonNull
        public final String messageCn;

        private RemoteTransferProgress(@NonNull String stage,
                                       @NonNull String stageLabelCn,
                                       int totalFiles,
                                       int completedFiles,
                                       int failedFiles,
                                       long totalBytes,
                                       long transferredBytes,
                                       @NonNull String currentFile,
                                       long currentFileTransferred,
                                       long currentFileSize,
                                       @NonNull String messageCn) {
            this.stage = stage;
            this.stageLabelCn = stageLabelCn;
            this.totalFiles = Math.max(0, totalFiles);
            this.completedFiles = Math.max(0, completedFiles);
            this.failedFiles = Math.max(0, failedFiles);
            this.totalBytes = Math.max(0L, totalBytes);
            this.transferredBytes = Math.max(0L, transferredBytes);
            this.currentFile = currentFile;
            this.currentFileTransferred = Math.max(0L, currentFileTransferred);
            this.currentFileSize = Math.max(0L, currentFileSize);
            this.messageCn = messageCn;
        }
    }

    public static final class RemoteTransferResult {
        public final boolean success;
        public final int totalFiles;
        public final int transferredFiles;
        public final int failedFiles;
        public final long totalBytes;
        public final long transferredBytes;
        @NonNull
        public final ArrayList<String> transferredVirtualPaths;
        @NonNull
        public final String messageCn;

        private RemoteTransferResult(boolean success,
                                     int totalFiles,
                                     int transferredFiles,
                                     int failedFiles,
                                     long totalBytes,
                                     long transferredBytes,
                                     @NonNull ArrayList<String> transferredVirtualPaths,
                                     @NonNull String messageCn) {
            this.success = success;
            this.totalFiles = Math.max(0, totalFiles);
            this.transferredFiles = Math.max(0, transferredFiles);
            this.failedFiles = Math.max(0, failedFiles);
            this.totalBytes = Math.max(0L, totalBytes);
            this.transferredBytes = Math.max(0L, transferredBytes);
            this.transferredVirtualPaths = new ArrayList<>(transferredVirtualPaths);
            this.messageCn = messageCn;
        }

        @NonNull
        static RemoteTransferResult ok(int totalFiles,
                                       int transferredFiles,
                                       int failedFiles,
                                       long totalBytes,
                                       long transferredBytes,
                                       @NonNull ArrayList<String> transferredVirtualPaths) {
            return new RemoteTransferResult(true, totalFiles, transferredFiles, failedFiles,
                totalBytes, transferredBytes, transferredVirtualPaths, "");
        }

        @NonNull
        static RemoteTransferResult fail(@NonNull String messageCn) {
            return new RemoteTransferResult(false, 0, 0, 0, 0L, 0L, new ArrayList<>(), messageCn);
        }

        @NonNull
        static RemoteTransferResult cancelled(int totalFiles,
                                              int transferredFiles,
                                              int failedFiles,
                                              long totalBytes,
                                              long transferredBytes,
                                              @NonNull ArrayList<String> transferredVirtualPaths) {
            return new RemoteTransferResult(false, totalFiles, transferredFiles, failedFiles,
                totalBytes, transferredBytes, transferredVirtualPaths, "\u670d\u52a1\u5668\u4e92\u4f20\u5df2\u53d6\u6d88");
        }

        @NonNull
        static RemoteTransferResult failWithStats(int totalFiles,
                                                  int transferredFiles,
                                                  int failedFiles,
                                                  long totalBytes,
                                                  long transferredBytes,
                                                  @NonNull String messageCn,
                                                  @NonNull ArrayList<String> transferredVirtualPaths) {
            return new RemoteTransferResult(false, totalFiles, transferredFiles, failedFiles,
                totalBytes, transferredBytes, transferredVirtualPaths, messageCn);
        }
    }

    public static final class ProbeResult {
        public final boolean success;
        @NonNull
        public final String virtualRootPath;
        @NonNull
        public final String messageCn;

        private ProbeResult(boolean success, @NonNull String virtualRootPath, @NonNull String messageCn) {
            this.success = success;
            this.virtualRootPath = virtualRootPath;
            this.messageCn = messageCn;
        }

        @NonNull
        static ProbeResult ok(@NonNull String virtualRootPath, @NonNull String messageCn) {
            return new ProbeResult(true, virtualRootPath, messageCn);
        }

        @NonNull
        static ProbeResult fail(@NonNull String messageCn) {
            return new ProbeResult(false, "", messageCn);
        }
    }
}
