#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define TERMUX_UNUSED(x) x __attribute__((__unused__))
#ifdef __APPLE__
# define LACKS_PTSNAME_R
#endif

static int throw_runtime_exception(JNIEnv* env, const char* message)
{
    jclass exception_class = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (exception_class != NULL) (*env)->ThrowNew(env, exception_class, message);
    return -1;
}

static int throw_errno_exception(JNIEnv* env, const char* operation, int error_number)
{
    char message[256];
    snprintf(message, sizeof(message), "%s: %s", operation, strerror(error_number));
    return throw_runtime_exception(env, message);
}

static void free_string_array(char** values)
{
    if (values == NULL) return;
    for (char** value = values; *value != NULL; value++) free(*value);
    free(values);
}

static char** copy_java_string_array(JNIEnv* env, jobjectArray source, const char* label)
{
    jsize size = source == NULL ? 0 : (*env)->GetArrayLength(env, source);
    if (size == 0) return NULL;

    char** result = calloc((size_t) size + 1, sizeof(char*));
    if (result == NULL) {
        throw_runtime_exception(env, label);
        return NULL;
    }

    for (jsize i = 0; i < size; i++) {
        jstring java_value = (jstring) (*env)->GetObjectArrayElement(env, source, i);
        if (java_value == NULL) {
            free_string_array(result);
            throw_runtime_exception(env, "Null string in subprocess array");
            return NULL;
        }
        const char* utf_value = (*env)->GetStringUTFChars(env, java_value, NULL);
        if (utf_value == NULL) {
            (*env)->DeleteLocalRef(env, java_value);
            free_string_array(result);
            return NULL;
        }
        result[i] = strdup(utf_value);
        (*env)->ReleaseStringUTFChars(env, java_value, utf_value);
        (*env)->DeleteLocalRef(env, java_value);
        if (result[i] == NULL) {
            free_string_array(result);
            throw_runtime_exception(env, label);
            return NULL;
        }
    }
    return result;
}

static const char* find_environment_value(char* const envp[], const char* name)
{
    if (envp == NULL) return NULL;
    size_t name_length = strlen(name);
    for (size_t i = 0; envp[i] != NULL; i++) {
        if (strncmp(envp[i], name, name_length) == 0 && envp[i][name_length] == '=') {
            return envp[i] + name_length + 1;
        }
    }
    return NULL;
}

/* Resolve PATH before fork so the child only calls async-signal-safe functions. */
static char* resolve_executable(const char* command, const char* cwd, char* const envp[])
{
    if (strchr(command, '/') != NULL) return strdup(command);

    const char* path = find_environment_value(envp, "PATH");
    if (path == NULL || *path == '\0') path = "/system/bin:/system/xbin";
    char* path_copy = strdup(path);
    if (path_copy == NULL) return NULL;

    char* cursor = path_copy;
    while (cursor != NULL) {
        char* separator = strchr(cursor, ':');
        if (separator != NULL) *separator = '\0';
        const char* directory = *cursor == '\0' ? cwd : cursor;
        int directory_is_absolute = directory[0] == '/';
        size_t length = strlen(directory) + strlen(command) + 2;
        if (!directory_is_absolute) length += strlen(cwd) + 1;
        char* candidate = malloc(length);
        if (candidate == NULL) {
            free(path_copy);
            return NULL;
        }
        if (directory_is_absolute) {
            snprintf(candidate, length, "%s/%s", directory, command);
        } else {
            snprintf(candidate, length, "%s/%s/%s", cwd, directory, command);
        }
        if (access(candidate, X_OK) == 0) {
            free(path_copy);
            return candidate;
        }
        free(candidate);
        cursor = separator == NULL ? NULL : separator + 1;
    }

    free(path_copy);
    errno = ENOENT;
    return NULL;
}

static int open_fd_limit(void)
{
    struct rlimit limit;
    if (getrlimit(RLIMIT_NOFILE, &limit) == 0 && limit.rlim_cur != RLIM_INFINITY) {
        return limit.rlim_cur > INT_MAX ? INT_MAX : (int) limit.rlim_cur;
    }
    long value = sysconf(_SC_OPEN_MAX);
    return value > 3 && value <= INT_MAX ? (int) value : 65536;
}

static void close_child_file_descriptors(int maximum_fd)
{
#if defined(__NR_close_range)
    long result;
    do {
        result = syscall(__NR_close_range, (unsigned int) (STDERR_FILENO + 1),
                         UINT_MAX, 0U);
    } while (result < 0 && errno == EINTR);
    if (result == 0) return;
#endif
    for (int fd = STDERR_FILENO + 1; fd < maximum_fd; fd++) close(fd);
}

static void child_error(int fd, const char* message, size_t length)
{
    ssize_t ignored = write(fd, message, length);
    (void) ignored;
}

static void reset_child_signals(const struct sigaction* default_action,
        const sigset_t* empty_mask)
{
    for (int signal_number = 1; signal_number < NSIG; signal_number++) {
        if (signal_number == SIGKILL || signal_number == SIGSTOP) continue;
        sigaction(signal_number, default_action, NULL);
    }
    sigprocmask(SIG_SETMASK, empty_mask, NULL);
}

static int create_subprocess(JNIEnv* env, const char* command, const char* cwd,
        char* const argv[], char* const envp[], int* process_id, jint rows,
        jint columns, jint cell_width, jint cell_height)
{
    char* executable = resolve_executable(command, cwd, envp);
    if (executable == NULL) return throw_errno_exception(env, "Cannot resolve executable", errno);
    int maximum_fd = open_fd_limit();
    struct sigaction default_signal_action;
    memset(&default_signal_action, 0, sizeof(default_signal_action));
    default_signal_action.sa_handler = SIG_DFL;
    sigemptyset(&default_signal_action.sa_mask);
    sigset_t empty_signal_mask;
    sigemptyset(&empty_signal_mask);

    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) {
        int error_number = errno;
        free(executable);
        return throw_errno_exception(env, "Cannot open /dev/ptmx", error_number);
    }

#ifdef LACKS_PTSNAME_R
    char* device_name;
#else
    char device_name[64];
#endif
    if (grantpt(ptm) != 0 || unlockpt(ptm) != 0 ||
#ifdef LACKS_PTSNAME_R
        (device_name = ptsname(ptm)) == NULL
#else
        ptsname_r(ptm, device_name, sizeof(device_name)) != 0
#endif
    ) {
        int error_number = errno;
        close(ptm);
        free(executable);
        return throw_errno_exception(env, "Cannot initialize PTY", error_number);
    }

    struct termios terminal_attributes;
    if (tcgetattr(ptm, &terminal_attributes) != 0) {
        int error_number = errno;
        close(ptm);
        free(executable);
        return throw_errno_exception(env, "Cannot read PTY attributes", error_number);
    }
    terminal_attributes.c_iflag |= IUTF8;
    terminal_attributes.c_iflag &= ~(IXON | IXOFF);
    if (tcsetattr(ptm, TCSANOW, &terminal_attributes) != 0) {
        int error_number = errno;
        close(ptm);
        free(executable);
        return throw_errno_exception(env, "Cannot set PTY attributes", error_number);
    }

    struct winsize size = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) columns,
        .ws_xpixel = (unsigned short) (columns * cell_width),
        .ws_ypixel = (unsigned short) (rows * cell_height)
    };
    if (ioctl(ptm, TIOCSWINSZ, &size) != 0) {
        int error_number = errno;
        close(ptm);
        free(executable);
        return throw_errno_exception(env, "Cannot set PTY window size", error_number);
    }

    pid_t pid = fork();
    if (pid < 0) {
        int error_number = errno;
        close(ptm);
        free(executable);
        return throw_errno_exception(env, "fork() failed", error_number);
    }
    if (pid > 0) {
        *process_id = (int) pid;
        free(executable);
        return ptm;
    }

    close(ptm);
    if (setsid() < 0) _exit(126);

    int pts = open(device_name, O_RDWR | O_CLOEXEC | O_NOCTTY);
    if (pts < 0) _exit(126);
    if (ioctl(pts, TIOCSCTTY, 0) != 0) {
        static const char message[] = "termux: cannot acquire controlling PTY\r\n";
        child_error(pts, message, sizeof(message) - 1);
        _exit(126);
    }
    if (dup2(pts, STDIN_FILENO) < 0 || dup2(pts, STDOUT_FILENO) < 0 ||
        dup2(pts, STDERR_FILENO) < 0) {
        static const char message[] = "termux: cannot attach standard streams\r\n";
        child_error(pts, message, sizeof(message) - 1);
        _exit(126);
    }
    close_child_file_descriptors(maximum_fd);

    if (chdir(cwd) != 0) {
        static const char message[] = "termux: cannot enter working directory\r\n";
        child_error(STDERR_FILENO, message, sizeof(message) - 1);
        _exit(126);
    }
    reset_child_signals(&default_signal_action, &empty_signal_mask);

    char* const empty_environment[] = { NULL };
    execve(executable, argv, envp == NULL ? empty_environment : envp);
    static const char message[] = "termux: cannot execute requested program\r\n";
    child_error(STDERR_FILENO, message, sizeof(message) - 1);
    _exit(127);
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv* env, jclass TERMUX_UNUSED(clazz), jstring command_string,
        jstring cwd_string, jobjectArray args, jobjectArray environment,
        jintArray process_id_array, jint rows, jint columns, jint cell_width,
        jint cell_height)
{
    if (command_string == NULL || process_id_array == NULL ||
        (*env)->GetArrayLength(env, process_id_array) < 1) {
        return throw_runtime_exception(env, "Invalid subprocess arguments");
    }

    const char* command_utf = (*env)->GetStringUTFChars(env, command_string, NULL);
    if (command_utf == NULL) return -1;
    char* command = strdup(command_utf);
    (*env)->ReleaseStringUTFChars(env, command_string, command_utf);
    if (command == NULL) return throw_runtime_exception(env, "Cannot allocate command");

    char* cwd = NULL;
    if (cwd_string == NULL) {
        cwd = strdup("/");
    } else {
        const char* cwd_utf = (*env)->GetStringUTFChars(env, cwd_string, NULL);
        if (cwd_utf != NULL) {
            cwd = strdup(cwd_utf);
            (*env)->ReleaseStringUTFChars(env, cwd_string, cwd_utf);
        }
    }
    if (cwd == NULL) {
        free(command);
        return throw_runtime_exception(env, "Cannot allocate working directory");
    }

    char** argv = copy_java_string_array(env, args, "Cannot allocate argv");
    if (args != NULL && (*env)->GetArrayLength(env, args) > 0 && argv == NULL) {
        free(command);
        free(cwd);
        return -1;
    }
    char** envp = copy_java_string_array(env, environment, "Cannot allocate environment");
    if (environment != NULL && (*env)->GetArrayLength(env, environment) > 0 && envp == NULL) {
        free(command);
        free(cwd);
        free_string_array(argv);
        return -1;
    }

    char* fallback_argv[] = { command, NULL };
    int process_id = 0;
    int ptm = create_subprocess(env, command, cwd, argv == NULL ? fallback_argv : argv,
        envp, &process_id, rows, columns, cell_width, cell_height);

    if (ptm >= 0) {
        jint java_process_id = process_id;
        (*env)->SetIntArrayRegion(env, process_id_array, 0, 1, &java_process_id);
        if ((*env)->ExceptionCheck(env)) {
            close(ptm);
            kill(-process_id, SIGKILL);
            kill(process_id, SIGKILL);
            int status;
            while (waitpid(process_id, &status, 0) < 0 && errno == EINTR) {
            }
            ptm = -1;
        }
    }
    free(command);
    free(cwd);
    free_string_array(argv);
    free_string_array(envp);
    return ptm;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyWindowSize(
        JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd,
        jint rows, jint columns, jint cell_width, jint cell_height)
{
    if (fd < 0) return;
    struct winsize size = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) columns,
        .ws_xpixel = (unsigned short) (columns * cell_width),
        .ws_ypixel = (unsigned short) (rows * cell_height)
    };
    ioctl(fd, TIOCSWINSZ, &size);
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyUTF8Mode(
        JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd)
{
    struct termios attributes;
    if (fd < 0 || tcgetattr(fd, &attributes) != 0) return;
    if ((attributes.c_iflag & IUTF8) == 0) {
        attributes.c_iflag |= IUTF8;
        tcsetattr(fd, TCSANOW, &attributes);
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_waitFor(
        JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint pid)
{
    int status = 0;
    pid_t result;
    do {
        result = waitpid(pid, &status, 0);
    } while (result < 0 && errno == EINTR);
    if (result < 0) return -255;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return -255;
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_dup(
        JNIEnv* env, jclass TERMUX_UNUSED(clazz), jint file_descriptor)
{
    int result;
    do {
        result = fcntl(file_descriptor, F_DUPFD_CLOEXEC, 0);
    } while (result < 0 && errno == EINTR);
    if (result < 0) return throw_errno_exception(env, "Cannot duplicate PTY descriptor", errno);
    return result;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_close(
        JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint file_descriptor)
{
    if (file_descriptor >= 0) close(file_descriptor);
}
