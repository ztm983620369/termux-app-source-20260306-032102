package com.termux.bridge

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

fun interface FileOpenListener {
    fun onOpenFile(request: FileOpenRequest)
}

object FileOpenBridge {
    private val listeners = CopyOnWriteArrayList<FileOpenListener>()
    private val seq = AtomicLong(0L)
    private val latest = AtomicReference<FileOpenRequest?>(null)
    private val latestSeq = AtomicLong(0L)

    @JvmStatic
    fun addListener(listener: FileOpenListener) {
        listeners.addIfAbsent(listener)
    }

    @JvmStatic
    fun removeListener(listener: FileOpenListener) {
        listeners.remove(listener)
    }

    @JvmStatic
    fun getLatestSequence(): Long = latestSeq.get()

    @JvmStatic
    fun getLatestRequest(): FileOpenRequest? = latest.get()

    @JvmStatic
    fun dispatch(request: FileOpenRequest) {
        val s = seq.incrementAndGet()
        latest.set(request)
        latestSeq.set(s)
        for (listener in listeners) {
            runCatching { listener.onOpenFile(request) }
        }
    }
}
