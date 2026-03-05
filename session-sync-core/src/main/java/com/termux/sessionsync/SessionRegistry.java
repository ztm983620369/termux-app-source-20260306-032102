package com.termux.sessionsync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Central registry for terminal/file-manager session synchronization.
 * Keep all cross-feature session state updates here.
 */
public final class SessionRegistry {

    public interface Listener {
        void onSnapshotChanged(@NonNull SessionSnapshot snapshot);
    }

    private static final SessionRegistry INSTANCE = new SessionRegistry();

    private final Object lock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new HashSet<>();
    private final ExecutorService persistenceExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "termux-session-sync-store");
        t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return t;
    });

    @Nullable
    private SessionStore store;
    @NonNull
    private SessionSnapshot snapshot = SessionSnapshot.empty();
    @Nullable
    private SessionSnapshot pendingPersistSnapshot;
    private boolean persistDrainScheduled;

    private SessionRegistry() {
    }

    @NonNull
    public static SessionRegistry getInstance() {
        return INSTANCE;
    }

    public void initialize(@NonNull Context context) {
        synchronized (lock) {
            if (store != null) return;
            store = new SharedPrefsSessionStore(context);
            snapshot = store.load();
        }
        SessionSyncTracer.getInstance().initialize(context);
        SessionSyncTracer.getInstance().debug(context, "SessionRegistry", "initialize",
            null, "会话注册中心初始化完成", "entries=" + snapshot.getEntries().size());
    }

    public void publish(@NonNull Context context, @NonNull SessionSnapshot nextSnapshot) {
        initialize(context);
        final SessionSnapshot normalized = normalize(nextSnapshot);
        synchronized (lock) {
            if (snapshot.equals(normalized)) return;
            snapshot = normalized;
        }
        enqueuePersistSnapshot(normalized);
        SessionSyncTracer.getInstance().info(context, "SessionRegistry", "publish",
            normalized.getActiveSessionId(), "会话快照已发布",
            "entries=" + normalized.getEntries().size());
        notifyListeners(normalized);
    }

    @NonNull
    public SessionSnapshot getSnapshot(@NonNull Context context) {
        initialize(context);
        synchronized (lock) {
            return snapshot;
        }
    }

    public void addListener(@NonNull Context context, @NonNull Listener listener, boolean emitImmediately) {
        initialize(context);
        final SessionSnapshot current;
        synchronized (lock) {
            listeners.add(listener);
            current = snapshot;
        }
        if (emitImmediately) {
            dispatchSnapshot(listener, current);
        }
    }

    public void removeListener(@NonNull Listener listener) {
        synchronized (lock) {
            listeners.remove(listener);
        }
    }

    public void clear(@NonNull Context context) {
        initialize(context);
        SessionSnapshot empty = SessionSnapshot.empty();
        synchronized (lock) {
            snapshot = empty;
        }
        enqueuePersistSnapshot(empty);
        SessionSyncTracer.getInstance().warn(context, "SessionRegistry", "clear",
            null, "会话快照已清空", null);
        notifyListeners(empty);
    }

    @NonNull
    private SessionSnapshot normalize(@NonNull SessionSnapshot in) {
        long ts = in.getUpdatedAtMs() <= 0 ? System.currentTimeMillis() : in.getUpdatedAtMs();
        return new SessionSnapshot(in.getEntries(), in.getActiveSessionId(), ts);
    }

    private void notifyListeners(@NonNull SessionSnapshot snapshot) {
        final Listener[] copy;
        synchronized (lock) {
            copy = listeners.toArray(new Listener[0]);
        }
        for (Listener listener : copy) {
            dispatchSnapshot(listener, snapshot);
        }
    }

    private void dispatchSnapshot(@NonNull Listener listener, @NonNull SessionSnapshot snapshot) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener.onSnapshotChanged(snapshot);
        } else {
            mainHandler.post(() -> listener.onSnapshotChanged(snapshot));
        }
    }

    private void enqueuePersistSnapshot(@NonNull SessionSnapshot snapshotToPersist) {
        synchronized (lock) {
            pendingPersistSnapshot = snapshotToPersist;
            if (persistDrainScheduled) return;
            persistDrainScheduled = true;
        }
        persistenceExecutor.execute(this::drainPersistQueue);
    }

    private void drainPersistQueue() {
        while (true) {
            final SessionStore currentStore;
            final SessionSnapshot toSave;
            synchronized (lock) {
                toSave = pendingPersistSnapshot;
                currentStore = store;
                pendingPersistSnapshot = null;
                if (toSave == null) {
                    persistDrainScheduled = false;
                    return;
                }
            }

            if (currentStore != null) {
                currentStore.save(toSave);
            }
        }
    }
}
