package com.cyotun.jpq.core

/** One frame can be consumed and one can wait; newer frames release an older pending frame. */
class LatestFrameDispatcher<T : AutoCloseable>(private val post: (Runnable)->Boolean,
                                             private val consume: (T)->Unit,
                                             private val failed: (Exception)->Unit = {}): AutoCloseable {
    private val lock=Any()
    private var pending: T?=null
    private var pendingDiagnostic=false
    private var scheduled=false
    private var closed=false
    private val drain=Runnable { drainOne() }

    fun offer(frame: T) = offer(frame,false)

    /** Explicit sparse lab probes keep the same bounded slot until their model pass. */
    fun offerDiagnostic(frame: T) = offer(frame,true)

    private fun offer(frame: T,diagnostic: Boolean) {
        var enqueue=false
        val retired=synchronized(lock) {
            if(closed) frame else {
                val previous=pending
                if(previous!=null && pendingDiagnostic) frame else {
                    pending=frame
                    pendingDiagnostic=diagnostic
                    if(!scheduled) { scheduled=true; enqueue=true }
                    previous
                }
            }
        }
        retired?.close()
        if(enqueue && !post(drain)) close()
    }

    private fun drainOne() {
        val frame=synchronized(lock) { pending.also { pending=null; pendingDiagnostic=false } }
        if(frame!=null) {
            try { consume(frame) } catch(error: Exception) { failed(error) } finally { frame.close() }
        }
        val again=synchronized(lock) {
            if(!closed && pending!=null) true else { scheduled=false; false }
        }
        if(again && !post(drain)) close()
    }

    override fun close() {
        val retired=synchronized(lock) {
            closed=true
            pending.also { pending=null; pendingDiagnostic=false }
        }
        retired?.close()
    }
}
