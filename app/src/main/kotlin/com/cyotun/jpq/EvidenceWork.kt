package com.cyotun.jpq

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Maintenance excludes capture startup and waits for all retired session writers. */
object EvidenceWork {
    private val lock = Object()
    private var capture = false
    private var maintenance = false
    private var writers = 0
    fun beginCapture(): Boolean = synchronized(lock) {
        if (maintenance) false else { capture = true; true }
    }
    fun endCapture() = synchronized(lock) { capture = false; lock.notifyAll() }
    fun writer(): AutoCloseable {
        synchronized(lock) { writers++ }
        val closed = AtomicBoolean(false)
        return AutoCloseable { if (closed.compareAndSet(false,true)) synchronized(lock) { writers--; lock.notifyAll() } }
    }
    fun <T> maintain(action: () -> T): T {
        val deadline = System.nanoTime() + 15_000_000_000L
        synchronized(lock) {
            while(maintenance) {
                val left=(deadline-System.nanoTime())/1_000_000L
                check(left>0) { "Evidence maintenance is busy" }
                lock.wait(left)
            }
            check(!capture && !AppState.running) { "Stop capture before maintaining evidence" }
            maintenance = true
        }
        try {
            synchronized(lock) {
                while (writers != 0) {
                    val left = (deadline-System.nanoTime())/1_000_000L
                    check(left > 0) { "Evidence is still being saved; retry shortly" }
                    lock.wait(left)
                }
            }
            return action()
        } finally { synchronized(lock) { maintenance = false; lock.notifyAll() } }
    }
}

/** Keeps export/clear behind the final queued task without waiting on the UI thread. */
class EvidenceExecutor(name: String) {
    private val lease = EvidenceWork.writer()
    private val executor = Executors.newSingleThreadExecutor { Thread(it,name) }
    private var closed = false
    @Synchronized fun execute(task: Runnable) { check(!closed); executor.execute(task) }
    @Synchronized fun shutdown() {
        if (closed) return
        closed = true
        executor.execute { lease.close() }
        executor.shutdown()
    }
}
