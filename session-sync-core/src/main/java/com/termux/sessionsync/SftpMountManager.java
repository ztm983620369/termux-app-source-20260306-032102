package com.termux.sessionsync;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Centralized SFTP mount orchestration.
 * Keep shell/FUSE/SSHFS lifecycle logic here instead of scattering in UI modules.
 */
public final class SftpMountManager {

    private static final SftpMountManager INSTANCE = new SftpMountManager();
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final Set<String> SSH_OPTIONS_WITH_VALUE = new HashSet<>(Arrays.asList(
        "-b", "-c", "-D", "-E", "-F", "-I", "-i", "-J", "-L", "-l",
        "-m", "-O", "-o", "-p", "-Q", "-R", "-S", "-W", "-w"
    ));

    private final Object mLock = new Object();
    private final Map<String, Object> mSessionLockById = new HashMap<>();

    private SftpMountManager() {
    }

    @NonNull
    public static SftpMountManager getInstance() {
        return INSTANCE;
    }

    @NonNull
    public String getMountPath(@NonNull Context context, @NonNull SessionEntry entry) {
        return FileRootResolver.resolveBrowseRoot(context, entry);
    }

    public boolean isMounted(@NonNull Context context, @NonNull SessionEntry entry) {
        return isMountedPath(getMountPath(context, entry));
    }

    @NonNull
    public SftpMountResult ensureMounted(@NonNull Context context, @NonNull SessionEntry entry) {
        String mountPath = getMountPath(context, entry);
        if (entry.transport == SessionTransport.LOCAL) {
            return SftpMountResult.fail(mountPath, -1, false, "", "",
                "", "当前会话是本地会话，不需要 SFTP 挂载。");
        }
        if (TextUtils.isEmpty(entry.sshCommand)) {
            return SftpMountResult.fail(mountPath, -1, false, "", "",
                "", "挂载失败：缺少 SSH 启动命令。");
        }

        Object lock = getSessionLock(entry.id);
        synchronized (lock) {
            if (isMountedPath(mountPath)) {
                return SftpMountResult.ok(mountPath, "SFTP 已挂载。");
            }

            if (!new File("/dev/fuse").exists()) {
                return SftpMountResult.fail(mountPath, -1, false, "", "",
                    "", "挂载失败：系统未提供 /dev/fuse，无法使用 sshfs。");
            }
            if (!hasFuseAccess()) {
                return SftpMountResult.fail(mountPath, -1, false, "", "",
                    "", "挂载失败：当前系统拒绝访问 /dev/fuse（Permission denied），无法进行 sshfs 挂载。");
            }

            ParsedSshCommand parsed = parseSshCommand(entry.sshCommand);
            if (!parsed.valid) {
                return SftpMountResult.fail(mountPath, -1, false, "", "",
                    "", "挂载失败：无法从 SSH 命令解析目标主机。请检查命令格式。");
            }

            File prefixDir = new File(context.getFilesDir(), "usr");
            File binDir = new File(prefixDir, "bin");
            File sshfsBin = new File(binDir, "sshfs");
            if (!sshfsBin.canExecute()) {
                return SftpMountResult.fail(mountPath, -1, false, "", "",
                    "", "挂载失败：未检测到 sshfs。请先在 Termux 安装：pkg install sshfs");
            }

            File sshpassBin = new File(binDir, "sshpass");
            if (!TextUtils.isEmpty(parsed.password) && !sshpassBin.canExecute()) {
                return SftpMountResult.fail(mountPath, -1, false, "", "",
                    "", "挂载失败：检测到密码登录，但未安装 sshpass。请先安装：pkg install sshpass");
            }

            File mountDir = new File(mountPath);
            if (!mountDir.exists() && !mountDir.mkdirs()) {
                return SftpMountResult.fail(mountPath, -1, false, "", "",
                    "", "挂载失败：无法创建挂载目录。");
            }

            String command = buildMountCommand(sshfsBin, sshpassBin, parsed, mountPath);
            CommandResult result = runShellCommand(command, DEFAULT_TIMEOUT_MS);
            if (result.timedOut) {
                return SftpMountResult.fail(mountPath, result.exitCode, true, result.stdout, result.stderr,
                    command, "挂载失败：连接超时，请检查网络、端口和服务器可达性。");
            }

            if (result.exitCode == 0 && isMountedPath(mountPath)) {
                return SftpMountResult.ok(mountPath, "SFTP 挂载成功。");
            }

            String diagnostic = classifyMountFailure(result.stdout, result.stderr);
            return SftpMountResult.fail(mountPath, result.exitCode, false, result.stdout, result.stderr,
                command, diagnostic);
        }
    }

    @NonNull
    public SftpMountResult unmount(@NonNull Context context, @NonNull SessionEntry entry, boolean force) {
        String mountPath = getMountPath(context, entry);
        Object lock = getSessionLock(entry.id);
        synchronized (lock) {
            if (!isMountedPath(mountPath)) {
                return SftpMountResult.ok(mountPath, "挂载点已是未挂载状态。");
            }

            File prefixDir = new File(context.getFilesDir(), "usr");
            File binDir = new File(prefixDir, "bin");
            File fusermount3 = new File(binDir, "fusermount3");
            File fusermount = new File(binDir, "fusermount");

            String command = buildUnmountCommand(fusermount3, fusermount, mountPath, force);
            CommandResult result = runShellCommand(command, 12_000L);
            if (result.timedOut) {
                return SftpMountResult.fail(mountPath, result.exitCode, true, result.stdout, result.stderr,
                    command, "卸载失败：操作超时。");
            }

            if (!isMountedPath(mountPath)) {
                return SftpMountResult.ok(mountPath, "SFTP 卸载成功。");
            }

            return SftpMountResult.fail(mountPath, result.exitCode, false, result.stdout, result.stderr,
                command, "卸载失败：挂载点仍然存在，可能被占用。");
        }
    }

    @NonNull
    private Object getSessionLock(@NonNull String sessionId) {
        synchronized (mLock) {
            Object lock = mSessionLockById.get(sessionId);
            if (lock == null) {
                lock = new Object();
                mSessionLockById.put(sessionId, lock);
            }
            return lock;
        }
    }

    private boolean isMountedPath(@NonNull String mountPath) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new FileInputStream("/proc/mounts"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(" ");
                if (fields.length < 2) continue;
                String target = unescapeMountField(fields[1]);
                if (mountPath.equals(target)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    @NonNull
    private static String unescapeMountField(@NonNull String value) {
        return value.replace("\\040", " ")
            .replace("\\011", "\t")
            .replace("\\012", "\n")
            .replace("\\134", "\\");
    }

    @NonNull
    private static String buildMountCommand(@NonNull File sshfsBin, @NonNull File sshpassBin,
                                            @NonNull ParsedSshCommand parsed, @NonNull String mountPath) {
        ArrayList<String> args = new ArrayList<>();
        if (!TextUtils.isEmpty(parsed.password)) {
            args.add(sshpassBin.getAbsolutePath());
            args.add("-p");
            args.add(parsed.password);
        }

        args.add(sshfsBin.getAbsolutePath());
        args.add(parsed.remoteSpec);
        args.add(mountPath);
        args.add("-p");
        args.add(String.valueOf(parsed.port));
        args.add("-o");
        args.add("reconnect");
        args.add("-o");
        args.add("ServerAliveInterval=15");
        args.add("-o");
        args.add("ServerAliveCountMax=3");
        args.add("-o");
        args.add("follow_symlinks");

        for (String opt : parsed.sshOptions) {
            if (TextUtils.isEmpty(opt)) continue;
            args.add("-o");
            args.add(opt);
        }

        return "mkdir -p " + quoteArg(mountPath) + " && " + joinShellArgs(args);
    }

    @NonNull
    private static String buildUnmountCommand(@NonNull File fusermount3, @NonNull File fusermount,
                                              @NonNull String mountPath, boolean force) {
        String quotedMount = quoteArg(mountPath);
        StringBuilder sb = new StringBuilder();
        sb.append("if [ -x ").append(quoteArg(fusermount3.getAbsolutePath())).append(" ]; then ")
            .append(quoteArg(fusermount3.getAbsolutePath())).append(" -u ").append(quotedMount)
            .append("; ");
        if (force) {
            sb.append(quoteArg(fusermount3.getAbsolutePath())).append(" -z -u ").append(quotedMount).append(" >/dev/null 2>&1 || true; ");
        }
        sb.append("elif [ -x ").append(quoteArg(fusermount.getAbsolutePath())).append(" ]; then ")
            .append(quoteArg(fusermount.getAbsolutePath())).append(" -u ").append(quotedMount)
            .append("; ");
        if (force) {
            sb.append(quoteArg(fusermount.getAbsolutePath())).append(" -z -u ").append(quotedMount).append(" >/dev/null 2>&1 || true; ");
        }
        sb.append("else /system/bin/umount ");
        if (force) sb.append("-l ");
        sb.append(quotedMount).append("; fi");
        return sb.toString();
    }

    @NonNull
    private static String classifyMountFailure(@Nullable String stdout, @Nullable String stderr) {
        String text = (stdout == null ? "" : stdout) + "\n" + (stderr == null ? "" : stderr);
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("/dev/fuse") || lower.contains("fuse: failed to open /dev/fuse")) {
            return "挂载失败：系统拒绝访问 /dev/fuse，当前设备/ROM 不支持应用侧 sshfs 挂载。";
        } else if (lower.contains("permission denied")) {
            return "挂载失败：认证失败（用户名/密码/密钥错误，或服务器拒绝访问）。";
        } else if (lower.contains("connection timed out") || lower.contains("operation timed out")) {
            return "挂载失败：连接超时（网络不可达、端口被拦截或服务器响应过慢）。";
        } else if (lower.contains("connection refused")) {
            return "挂载失败：连接被拒绝（端口未开放或 sshd 未启动）。";
        } else if (lower.contains("could not resolve hostname") || lower.contains("name or service not known")) {
            return "挂载失败：域名解析失败（主机名错误或 DNS 不可用）。";
        } else if (lower.contains("host key verification failed")) {
            return "挂载失败：主机指纹校验失败，请先在终端手动确认主机指纹。";
        } else if (lower.contains("no such file or directory")) {
            return "挂载失败：远端路径不存在，或本地挂载路径异常。";
        } else if (lower.contains("read-only file system")) {
            return "挂载失败：文件系统只读，无法写入或建立挂载。";
        } else if (lower.contains("fuse")) {
            return "挂载失败：FUSE 层异常，请检查设备与 Termux FUSE 支持。";
        } else {
            return "挂载失败：sshfs 执行异常，请查看终端原始输出。";
        }
    }

    private static boolean hasFuseAccess() {
        File fuse = new File("/dev/fuse");
        if (!fuse.exists()) return false;
        CommandResult check = runShellCommand("test -r /dev/fuse -a -w /dev/fuse", 3_000L);
        return check.exitCode == 0 && !check.timedOut;
    }

    @NonNull
    private static CommandResult runShellCommand(@NonNull String command, long timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command).start();

            StreamCollector out = new StreamCollector(process.getInputStream());
            StreamCollector err = new StreamCollector(process.getErrorStream());
            Thread outThread = new Thread(out, "sftp-mount-stdout");
            Thread errThread = new Thread(err, "sftp-mount-stderr");
            outThread.start();
            errThread.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                process.waitFor(1, TimeUnit.SECONDS);
                if (process.isAlive()) process.destroyForcibly();
                outThread.join(500);
                errThread.join(500);
                return new CommandResult(-1, out.getOutput(), err.getOutput(), true);
            }

            outThread.join(2_000);
            errThread.join(2_000);
            return new CommandResult(process.exitValue(), out.getOutput(), err.getOutput(), false);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new CommandResult(-1, "", msg, false);
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @NonNull
    private static String joinShellArgs(@NonNull List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(quoteArg(args.get(i)));
        }
        return sb.toString();
    }

    @NonNull
    private static String quoteArg(@NonNull String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @NonNull
    private static ParsedSshCommand parseSshCommand(@Nullable String rawCommand) {
        if (TextUtils.isEmpty(rawCommand)) return ParsedSshCommand.invalid();
        List<String> tokens = splitShell(rawCommand.trim());
        if (tokens.isEmpty()) return ParsedSshCommand.invalid();

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
        if (sshIdx < 0) return ParsedSshCommand.invalid();

        int port = 22;
        String user = null;
        String destination = null;
        ArrayList<String> options = new ArrayList<>();

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

            if ("-o".equals(token) && i + 1 < tokens.size()) {
                i++;
                options.add(tokens.get(i));
                continue;
            }
            if (token.startsWith("-o") && token.length() > 2) {
                options.add(token.substring(2));
                continue;
            }

            if (token.startsWith("-")) {
                if (SSH_OPTIONS_WITH_VALUE.contains(token) && i + 1 < tokens.size()) {
                    i++;
                }
                continue;
            }

            destination = token;
            break;
        }

        if (TextUtils.isEmpty(destination)) return ParsedSshCommand.invalid();

        String host = destination;
        String remotePath = "/";
        if (destination.contains("@")) {
            int at = destination.indexOf('@');
            if (at > 0) user = destination.substring(0, at);
            host = destination.substring(at + 1);
        }

        if (host.contains(":") && host.indexOf(':') == host.lastIndexOf(':')) {
            int idx = host.indexOf(':');
            String maybeHost = host.substring(0, idx);
            String maybePath = host.substring(idx + 1);
            if (!TextUtils.isEmpty(maybeHost) && maybePath.startsWith("/")) {
                host = maybeHost;
                remotePath = maybePath;
            }
        }

        if (TextUtils.isEmpty(host)) return ParsedSshCommand.invalid();

        StringBuilder remote = new StringBuilder();
        if (!TextUtils.isEmpty(user)) remote.append(user).append("@");
        remote.append(host);
        remote.append(":");
        remote.append(TextUtils.isEmpty(remotePath) ? "/" : remotePath);

        return new ParsedSshCommand(true, remote.toString(), port <= 0 ? 22 : port, password, options);
    }

    private static boolean isSshExecutable(@Nullable String token) {
        if (TextUtils.isEmpty(token)) return false;
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        return "ssh".equals(normalized) || normalized.endsWith("/ssh") || "ssh.exe".equals(normalized);
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

        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    private static final class ParsedSshCommand {
        final boolean valid;
        @NonNull
        final String remoteSpec;
        final int port;
        @Nullable
        final String password;
        @NonNull
        final List<String> sshOptions;

        ParsedSshCommand(boolean valid, @NonNull String remoteSpec, int port,
                         @Nullable String password, @NonNull List<String> sshOptions) {
            this.valid = valid;
            this.remoteSpec = remoteSpec;
            this.port = port;
            this.password = password;
            this.sshOptions = sshOptions;
        }

        @NonNull
        static ParsedSshCommand invalid() {
            return new ParsedSshCommand(false, "", 22, null, new ArrayList<>());
        }
    }

    private static final class CommandResult {
        final int exitCode;
        @NonNull
        final String stdout;
        @NonNull
        final String stderr;
        final boolean timedOut;

        CommandResult(int exitCode, @NonNull String stdout, @NonNull String stderr, boolean timedOut) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.timedOut = timedOut;
        }
    }

    private static final class StreamCollector implements Runnable {
        @NonNull
        private final InputStream mInputStream;
        @NonNull
        private final ByteArrayOutputStream mBuffer = new ByteArrayOutputStream();

        StreamCollector(@NonNull InputStream inputStream) {
            this.mInputStream = inputStream;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[4096];
            int read;
            try {
                while ((read = mInputStream.read(chunk)) > 0) {
                    mBuffer.write(chunk, 0, read);
                }
            } catch (Exception ignored) {
            }
        }

        @NonNull
        String getOutput() {
            try {
                return mBuffer.toString(StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                return "";
            }
        }
    }
}
