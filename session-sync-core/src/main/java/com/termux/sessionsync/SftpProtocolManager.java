package com.termux.sessionsync;

import android.content.Context;
import android.os.OperationCanceledException;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;

import java.io.File;
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

            String localPath = withReconnectRetry(context, target.entry, channel -> {
                SftpATTRS attrs = channel.stat(target.remotePath);
                if (attrs == null) {
                    throw new IllegalStateException("\u8fdc\u7aef\u6587\u4ef6\u4e0d\u5b58\u5728\u3002");
                }
                if (attrs.isDir()) {
                    throw new IllegalStateException("\u5f53\u524d\u8def\u5f84\u662f\u76ee\u5f55\uff0c\u65e0\u6cd5\u76f4\u63a5\u6253\u5f00\u4e3a\u6587\u4ef6\u3002");
                }

                try (OutputStream outputStream = new FileOutputStream(targetFile, false)) {
                    channel.get(target.remotePath, outputStream);
                }
                return targetFile.getAbsolutePath();
            });
            if (TextUtils.isEmpty(localPath)) {
                return MaterializeResult.fail("\u4e0b\u8f7d\u5931\u8d25\uff1a\u672a\u77e5\u9519\u8bef\u3002");
            }
            return MaterializeResult.ok(localPath);
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
            String localPath = target.virtualRoot + ("/".equals(target.remotePath) ? "" : target.remotePath);
            return DeleteResult.ok(localPath);
        } catch (Exception e) {
            clearSessionByEntry(target.entry);
            return DeleteResult.fail("\u5220\u9664\u5931\u8d25\uff1a" + classifyExceptionMessage(e));
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
        String firstBuildError = null;

        for (String rawVirtualPath : virtualPaths) {
            if (isCancelled(control)) {
                return DownloadResult.cancelled(tasks.size(), 0, 0, 0L, 0L);
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

            try {
                collectDownloadTasks(context, target, topLevelLocal, tasks,
                    visitedRemoteDirectories, visitedRemoteFiles, preparedLocalDirectories, control);
            } catch (Exception e) {
                if (e instanceof OperationCanceledException) {
                    return DownloadResult.cancelled(tasks.size(), 0, 0, 0L, 0L);
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
            return DownloadResult.ok(0, 0, 0, 0L, 0L);
        }

        long totalBytes = 0L;
        for (DownloadFileTask task : tasks) {
            if (isCancelled(control)) {
                return DownloadResult.cancelled(tasks.size(), 0, 0, totalBytes, 0L);
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
                        progressState.settledBytes()
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
                progressState.settledBytes()
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
            DownloadResult result = DownloadResult.ok(tasks.size(), downloadedFiles, 0, totalBytes, downloadedBytes);
            finishDownloadJournal(context, journalHandle, result);
            return result;
        }

        String reason = TextUtils.isEmpty(firstDownloadError)
            ? "\u8bf7\u68c0\u67e5\u7f51\u7edc\u548c\u8ba4\u8bc1\u540e\u91cd\u8bd5\u3002"
            : firstDownloadError;
        if (downloadedFiles > 0) {
            DownloadResult result = DownloadResult.partial(tasks.size(), downloadedFiles, failedFiles, totalBytes, downloadedBytes,
                "\u90e8\u5206\u6587\u4ef6\u4e0b\u8f7d\u5931\u8d25\uff1a" + reason);
            finishDownloadJournal(context, journalHandle, result);
            return result;
        }
        DownloadResult result = DownloadResult.failWithStats(tasks.size(), 0, failedFiles, totalBytes, downloadedBytes,
            "\u4e0b\u8f7d\u5931\u8d25\uff1a" + reason);
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

        String firstBuildError = null;
        for (String rawLocalPath : localPaths) {
            if (isCancelled(control)) {
                return UploadResult.cancelled(tasks.size(), 0, 0, 0L, 0L);
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

            try {
                collectUploadTasksRecursive(localFile, uniqueRemoteTop, tasks, remoteDirectories, control);
            } catch (Exception e) {
                if (e instanceof OperationCanceledException) {
                    return UploadResult.cancelled(tasks.size(), 0, 0, 0L, 0L);
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
                return UploadResult.cancelled(tasks.size(), 0, 0, 0L, 0L);
            }
            clearSessionByEntry(destination.entry);
            return UploadResult.fail("\u4e0a\u4f20\u5931\u8d25\uff1a" + classifyExceptionMessage(e));
        }

        if (tasks.isEmpty()) {
            synchronized (mLock) {
                clearDirectoryCacheByClientKeyLocked(clientKeyForEntry(destination.entry));
            }
            return UploadResult.ok(0, 0, 0, 0L, 0L);
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
                        progressState.settledBytes()
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
                progressState.settledBytes()
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
            UploadResult result = UploadResult.ok(tasks.size(), uploadedFiles, 0, totalBytes, uploadedBytes);
            finishUploadJournal(context, journalHandle, result);
            return result;
        }

        String reason = TextUtils.isEmpty(firstUploadError)
            ? "\u8bf7\u68c0\u67e5\u7f51\u7edc\u548c\u8ba4\u8bc1\u540e\u91cd\u8bd5\u3002"
            : firstUploadError;
        if (uploadedFiles > 0) {
            UploadResult result = UploadResult.partial(tasks.size(), uploadedFiles, failedFiles, totalBytes, uploadedBytes,
                "\u90e8\u5206\u6587\u4ef6\u4e0a\u4f20\u5931\u8d25\uff1a" + reason);
            finishUploadJournal(context, journalHandle, result);
            return result;
        }
        UploadResult result = UploadResult.failWithStats(tasks.size(), 0, failedFiles, totalBytes, uploadedBytes,
            "\u4e0a\u4f20\u5931\u8d25\uff1a" + reason);
        finishUploadJournal(context, journalHandle, result);
        return result;
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
                finalResult = RemoteTransferResult.cancelled(0, 0, 0, 0L, 0L);
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
                    downloadResult.downloadedBytes
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
                    downloadResult.messageCn
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
                    uploadResult.uploadedBytes
                );
                return finalResult;
            }

            if (uploadResult.success) {
                finalResult = RemoteTransferResult.ok(
                    uploadResult.totalFiles,
                    uploadResult.uploadedFiles,
                    uploadResult.failedFiles,
                    uploadResult.totalBytes,
                    uploadResult.uploadedBytes
                );
                return finalResult;
            }

            finalResult = RemoteTransferResult.failWithStats(
                uploadResult.totalFiles,
                uploadResult.uploadedFiles,
                uploadResult.failedFiles,
                uploadResult.totalBytes,
                uploadResult.uploadedBytes,
                uploadResult.messageCn
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

        if (!attrs.isDir()) {
            deleteRemoteFileIfExists(channel, normalized);
            return;
        }

        Vector<?> rows = channel.ls(normalized);
        for (Object row : rows) {
            if (!(row instanceof ChannelSftp.LsEntry)) continue;
            ChannelSftp.LsEntry item = (ChannelSftp.LsEntry) row;
            String name = item.getFilename();
            if (TextUtils.isEmpty(name) || ".".equals(name) || "..".equals(name)) continue;
            deleteRemotePathRecursive(channel, joinRemotePath(normalized, name));
        }

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

    private static boolean isCancelledMessage(@Nullable String messageCn) {
        return !TextUtils.isEmpty(messageCn) && messageCn.contains("\u5df2\u53d6\u6d88");
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

            JSch jsch = new JSch();
            File homeDir = new File(context.getFilesDir(), "home");
            addSshIdentities(jsch, homeDir, parsed);
            SshHostTrustStore trustStore = SshHostTrustStore.getInstance();
            trustStore.initialize(context);
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
            session.connect(15_000);

            ClientHolder newHolder = new ClientHolder(clientKey, entry.id, session);
            mClients.put(clientKey, newHolder);
            cleanupPendingRemoteTempPaths(context, entry, newHolder);
            return newHolder;
        }
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
            return "\u4e3b\u673a\u6307\u7eb9\u6821\u9a8c\u5931\u8d25\uff08\u670d\u52a1\u5668\u5bc6\u94a5\u53ef\u80fd\u53d1\u751f\u53d8\u66f4\uff09\u3002";
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

    private static void safeDisconnect(@Nullable ChannelSftp channel) {
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
        @NonNull
        public final String messageCn;

        private MaterializeResult(boolean success, @NonNull String localPath, @NonNull String messageCn) {
            this.success = success;
            this.localPath = localPath;
            this.messageCn = messageCn;
        }

        @NonNull
        static MaterializeResult ok(@NonNull String localPath) {
            return new MaterializeResult(true, localPath, "");
        }

        @NonNull
        static MaterializeResult fail(@NonNull String messageCn) {
            return new MaterializeResult(false, "", messageCn);
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
        public final String messageCn;

        private DownloadResult(boolean success,
                               int totalFiles,
                               int downloadedFiles,
                               int failedFiles,
                               long totalBytes,
                               long downloadedBytes,
                               @NonNull String messageCn) {
            this.success = success;
            this.totalFiles = Math.max(0, totalFiles);
            this.downloadedFiles = Math.max(0, downloadedFiles);
            this.failedFiles = Math.max(0, failedFiles);
            this.totalBytes = Math.max(0L, totalBytes);
            this.downloadedBytes = Math.max(0L, downloadedBytes);
            this.messageCn = messageCn;
        }

        @NonNull
        static DownloadResult ok(int totalFiles, int downloadedFiles, int failedFiles,
                                 long totalBytes, long downloadedBytes) {
            return new DownloadResult(true, totalFiles, downloadedFiles, failedFiles,
                totalBytes, downloadedBytes, "");
        }

        @NonNull
        static DownloadResult partial(int totalFiles, int downloadedFiles, int failedFiles,
                                      long totalBytes, long downloadedBytes, @NonNull String messageCn) {
            return new DownloadResult(false, totalFiles, downloadedFiles, failedFiles,
                totalBytes, downloadedBytes, messageCn);
        }

        @NonNull
        static DownloadResult fail(@NonNull String messageCn) {
            return new DownloadResult(false, 0, 0, 0, 0L, 0L, messageCn);
        }

        @NonNull
        static DownloadResult cancelled(int totalFiles, int downloadedFiles, int failedFiles,
                                        long totalBytes, long downloadedBytes) {
            return new DownloadResult(false, totalFiles, downloadedFiles, failedFiles,
                totalBytes, downloadedBytes, "\u4e0b\u8f7d\u5df2\u53d6\u6d88");
        }

        @NonNull
        static DownloadResult failWithStats(int totalFiles, int downloadedFiles, int failedFiles,
                                            long totalBytes, long downloadedBytes, @NonNull String messageCn) {
            return new DownloadResult(false, totalFiles, downloadedFiles, failedFiles,
                totalBytes, downloadedBytes, messageCn);
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
        @NonNull
        public final String messageCn;

        private UploadResult(boolean success,
                             int totalFiles,
                             int uploadedFiles,
                             int failedFiles,
                             long totalBytes,
                             long uploadedBytes,
                             @NonNull String messageCn) {
            this.success = success;
            this.totalFiles = Math.max(0, totalFiles);
            this.uploadedFiles = Math.max(0, uploadedFiles);
            this.failedFiles = Math.max(0, failedFiles);
            this.totalBytes = Math.max(0L, totalBytes);
            this.uploadedBytes = Math.max(0L, uploadedBytes);
            this.messageCn = messageCn;
        }

        @NonNull
        static UploadResult ok(int totalFiles, int uploadedFiles, int failedFiles,
                               long totalBytes, long uploadedBytes) {
            return new UploadResult(true, totalFiles, uploadedFiles, failedFiles,
                totalBytes, uploadedBytes, "");
        }

        @NonNull
        static UploadResult partial(int totalFiles, int uploadedFiles, int failedFiles,
                                    long totalBytes, long uploadedBytes, @NonNull String messageCn) {
            return new UploadResult(false, totalFiles, uploadedFiles, failedFiles,
                totalBytes, uploadedBytes, messageCn);
        }

        @NonNull
        static UploadResult fail(@NonNull String messageCn) {
            return new UploadResult(false, 0, 0, 0, 0L, 0L, messageCn);
        }

        @NonNull
        static UploadResult cancelled(int totalFiles, int uploadedFiles, int failedFiles,
                                      long totalBytes, long uploadedBytes) {
            return new UploadResult(false, totalFiles, uploadedFiles, failedFiles,
                totalBytes, uploadedBytes, "\u4e0a\u4f20\u5df2\u53d6\u6d88");
        }

        @NonNull
        static UploadResult failWithStats(int totalFiles, int uploadedFiles, int failedFiles,
                                          long totalBytes, long uploadedBytes, @NonNull String messageCn) {
            return new UploadResult(false, totalFiles, uploadedFiles, failedFiles,
                totalBytes, uploadedBytes, messageCn);
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
        public final String messageCn;

        private RemoteTransferResult(boolean success,
                                     int totalFiles,
                                     int transferredFiles,
                                     int failedFiles,
                                     long totalBytes,
                                     long transferredBytes,
                                     @NonNull String messageCn) {
            this.success = success;
            this.totalFiles = Math.max(0, totalFiles);
            this.transferredFiles = Math.max(0, transferredFiles);
            this.failedFiles = Math.max(0, failedFiles);
            this.totalBytes = Math.max(0L, totalBytes);
            this.transferredBytes = Math.max(0L, transferredBytes);
            this.messageCn = messageCn;
        }

        @NonNull
        static RemoteTransferResult ok(int totalFiles,
                                       int transferredFiles,
                                       int failedFiles,
                                       long totalBytes,
                                       long transferredBytes) {
            return new RemoteTransferResult(true, totalFiles, transferredFiles, failedFiles,
                totalBytes, transferredBytes, "");
        }

        @NonNull
        static RemoteTransferResult fail(@NonNull String messageCn) {
            return new RemoteTransferResult(false, 0, 0, 0, 0L, 0L, messageCn);
        }

        @NonNull
        static RemoteTransferResult cancelled(int totalFiles,
                                              int transferredFiles,
                                              int failedFiles,
                                              long totalBytes,
                                              long transferredBytes) {
            return new RemoteTransferResult(false, totalFiles, transferredFiles, failedFiles,
                totalBytes, transferredBytes, "\u670d\u52a1\u5668\u4e92\u4f20\u5df2\u53d6\u6d88");
        }

        @NonNull
        static RemoteTransferResult failWithStats(int totalFiles,
                                                  int transferredFiles,
                                                  int failedFiles,
                                                  long totalBytes,
                                                  long transferredBytes,
                                                  @NonNull String messageCn) {
            return new RemoteTransferResult(false, totalFiles, transferredFiles, failedFiles,
                totalBytes, transferredBytes, messageCn);
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
