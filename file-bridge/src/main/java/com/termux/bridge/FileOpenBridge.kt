package com.termux.bridge

import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class FileOpenEvent(
    val sequence: Long,
    val request: FileOpenRequest
)

fun interface FileOpenListener {
    fun onOpenFile(event: FileOpenEvent)
}

object FileOpenBridge {
    private const val MAX_HISTORY = 128

    private val listeners = CopyOnWriteArrayList<FileOpenListener>()
    private val seq = AtomicLong(0L)
    private val latest = AtomicReference<FileOpenEvent?>(null)
    private val latestSeq = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val lock = Any()
    private val history = ArrayDeque<FileOpenEvent>()
    private val pendingDispatches = ArrayDeque<FileOpenEvent>()

    @Volatile
    private var dispatchScheduled = false

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
    fun getLatestEvent(): FileOpenEvent? = latest.get()

    @JvmStatic
    fun getLatestRequest(): FileOpenRequest? = latest.get()?.request

    @JvmStatic
    fun getEventsAfter(sequenceExclusive: Long): List<FileOpenEvent> {
        synchronized(lock) {
            if (history.isEmpty()) {
                return emptyList()
            }

            val out = ArrayList<FileOpenEvent>(history.size)
            for (event in history) {
                if (event.sequence > sequenceExclusive) {
                    out.add(event)
                }
            }
            return out
        }
    }

    @JvmStatic
    fun dispatch(request: FileOpenRequest): FileOpenEvent {
        val event = FileOpenEvent(
            sequence = seq.incrementAndGet(),
            request = request
        )

        var shouldDrain = false
        synchronized(lock) {
            history.addLast(event)
            while (history.size > MAX_HISTORY) {
                history.removeFirst()
            }

            pendingDispatches.addLast(event)
            latest.set(event)
            latestSeq.set(event.sequence)

            if (!dispatchScheduled) {
                dispatchScheduled = true
                shouldDrain = true
            }
        }

        if (shouldDrain) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                drainPendingDispatchesOnMain()
            } else {
                mainHandler.post(::drainPendingDispatchesOnMain)
            }
        }

        return event
    }

    private fun drainPendingDispatchesOnMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::drainPendingDispatchesOnMain)
            return
        }

        while (true) {
            val event: FileOpenEvent? = synchronized(lock) {
                val next = pendingDispatches.pollFirst()
                if (next == null) {
                    dispatchScheduled = false
                    null
                } else {
                    next
                }
            }
            if (event == null) return

            for (listener in listeners) {
                runCatching { listener.onOpenFile(event) }
            }
        }
    }
}
