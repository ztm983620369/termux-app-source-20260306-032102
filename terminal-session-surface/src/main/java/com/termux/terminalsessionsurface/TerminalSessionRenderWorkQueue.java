package com.termux.terminalsessionsurface;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Identity-deduplicated FIFO for one-VSync terminal render work. */
final class TerminalSessionRenderWorkQueue<T> {

    private final ArrayDeque<T> queue = new ArrayDeque<>();
    private final Set<T> queued = Collections.newSetFromMap(new IdentityHashMap<>());

    boolean offer(T item) {
        if (item == null || !queued.add(item)) return false;
        queue.addLast(item);
        return true;
    }

    T poll() {
        T item = queue.pollFirst();
        if (item != null) queued.remove(item);
        return item;
    }

    boolean remove(T item) {
        if (item == null || !queued.remove(item)) return false;
        queue.removeFirstOccurrence(item);
        return true;
    }

    boolean isEmpty() {
        return queue.isEmpty();
    }

    int size() {
        return queue.size();
    }

    void clear() {
        queue.clear();
        queued.clear();
    }
}
