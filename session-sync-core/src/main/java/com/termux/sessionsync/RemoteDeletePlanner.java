package com.termux.sessionsync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class RemoteDeletePlanner {
    static final int SFTP_FIRST_FILE_THRESHOLD = 3;
    static final int COMMAND_BATCH_ITEM_LIMIT = 40;
    static final int COMMAND_BATCH_CHAR_LIMIT = 24 * 1024;

    private static final Set<String> DANGEROUS_PATHS = new LinkedHashSet<>(Arrays.asList(
        "/",
        "/home",
        "/root",
        "/tmp",
        "/var",
        "/usr",
        "/etc",
        "/bin",
        "/sbin",
        "/lib",
        "/lib64",
        "/opt",
        "/dev",
        "/proc",
        "/sys",
        "/run",
        "/mnt",
        "/media",
        "/storage",
        "/sdcard",
        "/data",
        "/data/data",
        "/data/user",
        "/data/user/0",
        "/system",
        "/vendor",
        "/product",
        "/apex"
    ));

    private RemoteDeletePlanner() {
    }

    @NonNull
    static DeletePlan build(@NonNull List<ResolvedDeleteTarget> targets) {
        ArrayList<DeletePlanItem> candidates = new ArrayList<>();
        ArrayList<DeletePlanItem> rejected = new ArrayList<>();
        ArrayList<DeletePlanItem> skipped = new ArrayList<>();

        for (ResolvedDeleteTarget target : targets) {
            if (target == null) continue;
            String normalized = normalizeStrict(target.remotePath);
            String rejectReason = rejectionReason(normalized, target.remotePath);
            DeletePlanItem item = new DeletePlanItem(
                target.virtualPath,
                normalized,
                target.displayName,
                target.directory,
                false,
                rejectReason
            );
            if (rejectReason.isEmpty()) {
                candidates.add(item);
            } else {
                rejected.add(item);
            }
        }

        Collections.sort(candidates, (left, right) -> {
            int depth = Integer.compare(depth(left.remotePath), depth(right.remotePath));
            if (depth != 0) return depth;
            int length = Integer.compare(left.remotePath.length(), right.remotePath.length());
            if (length != 0) return length;
            return left.remotePath.compareTo(right.remotePath);
        });

        ArrayList<DeletePlanItem> executable = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (DeletePlanItem item : candidates) {
            if (seen.contains(item.remotePath)) {
                skipped.add(item.asSkipped("重复路径，已合并"));
                continue;
            }
            DeletePlanItem coveringParent = findCoveringParent(executable, item.remotePath);
            if (coveringParent != null) {
                skipped.add(item.asSkipped("已由父目录删除覆盖"));
                seen.add(item.remotePath);
                continue;
            }
            executable.add(item);
            seen.add(item.remotePath);
        }

        boolean containsDirectory = false;
        for (DeletePlanItem item : executable) {
            if (item.directory) {
                containsDirectory = true;
                break;
            }
        }
        boolean preferExec = containsDirectory || executable.size() > SFTP_FIRST_FILE_THRESHOLD;
        return new DeletePlan(executable, skipped, rejected, containsDirectory, preferExec);
    }

    @Nullable
    private static DeletePlanItem findCoveringParent(@NonNull List<DeletePlanItem> executable, @NonNull String childPath) {
        for (DeletePlanItem parent : executable) {
            if (!parent.directory) continue;
            if (isChildOf(childPath, parent.remotePath)) return parent;
        }
        return null;
    }

    static boolean isChildOf(@NonNull String childPath, @NonNull String parentPath) {
        if ("/".equals(parentPath)) return !"/".equals(childPath);
        return childPath.startsWith(parentPath + "/");
    }

    @NonNull
    static String rejectionReason(@NonNull String normalizedPath, @Nullable String originalPath) {
        if (!isSafeAbsoluteLinuxPath(originalPath)) {
            return "路径不是安全的标准 Linux 绝对路径";
        }
        if (isDangerousPath(normalizedPath)) {
            return "危险路径，已拦截";
        }
        return "";
    }

    static boolean isSafeAbsoluteLinuxPath(@Nullable String path) {
        if (path == null) return false;
        String value = path.trim();
        if (value.isEmpty()) return false;
        if (!value.startsWith("/")) return false;
        if (value.indexOf('\0') >= 0) return false;
        if (value.indexOf('\\') >= 0) return false;
        if (value.contains("//")) return false;
        if (value.endsWith("/") && value.length() > 1) return false;
        String[] parts = value.split("/");
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            if (".".equals(part) || "..".equals(part)) return false;
        }
        return true;
    }

    static boolean isDangerousPath(@Nullable String path) {
        String normalized = normalizeStrict(path);
        if (DANGEROUS_PATHS.contains(normalized)) return true;
        String lower = normalized.toLowerCase(Locale.US);
        return DANGEROUS_PATHS.contains(lower);
    }

    @NonNull
    static String normalizeStrict(@Nullable String path) {
        if (path == null) return "";
        String value = path.trim();
        if (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    @NonNull
    static String shellQuoteSingle(@NonNull String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    @NonNull
    static List<String> buildRmCommands(@NonNull List<DeletePlanItem> items, boolean directories) {
        ArrayList<String> commands = new ArrayList<>();
        ArrayList<String> currentQuoted = new ArrayList<>();
        int currentLength = prefix(directories).length();
        for (DeletePlanItem item : items) {
            if (item == null || item.directory != directories) continue;
            String quoted = shellQuoteSingle(item.remotePath);
            int nextLength = currentLength + 1 + quoted.length();
            if (!currentQuoted.isEmpty()
                && (currentQuoted.size() >= COMMAND_BATCH_ITEM_LIMIT || nextLength > COMMAND_BATCH_CHAR_LIMIT)) {
                commands.add(prefix(directories) + joinQuoted(currentQuoted));
                currentQuoted.clear();
                currentLength = prefix(directories).length();
            }
            currentQuoted.add(quoted);
            currentLength += 1 + quoted.length();
        }
        if (!currentQuoted.isEmpty()) {
            commands.add(prefix(directories) + joinQuoted(currentQuoted));
        }
        return commands;
    }

    @NonNull
    private static String prefix(boolean directories) {
        return directories ? "rm -rf -- " : "rm -f -- ";
    }

    @NonNull
    private static String joinQuoted(@NonNull List<String> quoted) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < quoted.size(); i++) {
            if (i > 0) builder.append(' ');
            builder.append(quoted.get(i));
        }
        return builder.toString();
    }

    private static int depth(@NonNull String path) {
        if ("/".equals(path)) return 0;
        int count = 0;
        for (String part : path.split("/")) {
            if (!part.isEmpty()) count++;
        }
        return count;
    }

    static final class ResolvedDeleteTarget {
        @NonNull final String virtualPath;
        @NonNull final String remotePath;
        @NonNull final String displayName;
        final boolean directory;

        ResolvedDeleteTarget(@NonNull String virtualPath,
                             @NonNull String remotePath,
                             @NonNull String displayName,
                             boolean directory) {
            this.virtualPath = virtualPath;
            this.remotePath = remotePath;
            this.displayName = displayName;
            this.directory = directory;
        }
    }

    static final class DeletePlan {
        @NonNull final ArrayList<DeletePlanItem> executableItems;
        @NonNull final ArrayList<DeletePlanItem> skippedItems;
        @NonNull final ArrayList<DeletePlanItem> rejectedItems;
        final boolean containsDirectory;
        final boolean preferExec;

        DeletePlan(@NonNull ArrayList<DeletePlanItem> executableItems,
                   @NonNull ArrayList<DeletePlanItem> skippedItems,
                   @NonNull ArrayList<DeletePlanItem> rejectedItems,
                   boolean containsDirectory,
                   boolean preferExec) {
            this.executableItems = executableItems;
            this.skippedItems = skippedItems;
            this.rejectedItems = rejectedItems;
            this.containsDirectory = containsDirectory;
            this.preferExec = preferExec;
        }
    }

    static final class DeletePlanItem {
        @NonNull final String virtualPath;
        @NonNull final String remotePath;
        @NonNull final String displayName;
        final boolean directory;
        final boolean skipped;
        @NonNull final String messageCn;

        DeletePlanItem(@NonNull String virtualPath,
                       @NonNull String remotePath,
                       @NonNull String displayName,
                       boolean directory,
                       boolean skipped,
                       @NonNull String messageCn) {
            this.virtualPath = virtualPath;
            this.remotePath = remotePath;
            this.displayName = displayName;
            this.directory = directory;
            this.skipped = skipped;
            this.messageCn = messageCn;
        }

        @NonNull
        DeletePlanItem asSkipped(@NonNull String messageCn) {
            return new DeletePlanItem(virtualPath, remotePath, displayName, directory, true, messageCn);
        }
    }
}
