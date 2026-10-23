package com.cyotun.jpq.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Nonblocking producers, one ordered consumer, bounded pending memory, drain on close. */
class BoundedWriter<T>(name: String, private val capacity: Int,
    private val key: (T) -> Any? = { null }, private val write: (T) -> Unit,
    private val dropped: (T) -> Unit = {}, private val failed: (Exception) -> Unit = {},
    private val finished: () -> Unit = {}) : AutoCloseable {
    private val lock = Object()
    private val pending = ArrayList<T>()
    private var closed = false
    private val done = CountDownLatch(1)
    init {
        require(capacity > 0)
        Thread({
            try {
                while (true) {
                    val item = synchronized(lock) {
                        while (pending.isEmpty() && !closed) lock.wait()
                        if (pending.isEmpty()) return@Thread
                        pending.removeAt(0)
                    }
                    try { write(item) } catch (error: Exception) { runCatching { failed(error) } }
                }
            } finally { try { finished() } finally { done.countDown() } }
        }, name).apply { isDaemon = true; start() }
    }
    fun offer(item: T): Boolean = synchronized(lock) {
        if (closed) return false
        val identity = key(item)
        if (identity != null) pending.removeAll { key(it) == identity }
        if (pending.size == capacity) dropped(pending.removeAt(0))
        pending.add(item); lock.notifyAll(); true
    }
    override fun close() = synchronized(lock) { closed = true; lock.notifyAll() }
    fun awaitClosed(timeoutMs: Long): Boolean = done.await(timeoutMs, TimeUnit.MILLISECONDS)
}
