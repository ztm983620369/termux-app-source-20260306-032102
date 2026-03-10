package com.termux.systemstatussurface;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;

public final class SystemStatusSurfaceEngine {

    @NonNull
    private final SystemStatusSurfaceStore store;
    @NonNull
    private final NotificationStatusSurfaceRenderer renderer;

    public SystemStatusSurfaceEngine(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        store = new SystemStatusSurfaceStore(appContext);
        renderer = new NotificationStatusSurfaceRenderer(appContext);
    }

    public void publish(@NonNull SystemStatusSurfaceState state) {
        store.save(state);
        renderer.render(state);
    }

    public void cancel(@NonNull String surfaceId) {
        store.remove(surfaceId);
        renderer.cancel(surfaceId);
    }

    public void cancelAll() {
        ArrayList<SystemStatusSurfaceState> states = store.list();
        store.clear();
        renderer.cancelAll(states);
    }

    @Nullable
    public SystemStatusSurfaceState get(@NonNull String surfaceId) {
        return store.load(surfaceId);
    }

    @NonNull
    public ArrayList<SystemStatusSurfaceState> list() {
        return store.list();
    }
}
