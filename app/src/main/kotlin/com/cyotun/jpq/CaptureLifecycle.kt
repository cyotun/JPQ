package com.cyotun.jpq

/** Retire a session before releasing resources; delayed callbacks may only close their owner. */
internal class CaptureLifecycle {
    @Volatile var generation=0L
        private set
    @Volatile var closed=false
        private set

    fun begin(): Long {
        closed=false
        return ++generation
    }

    fun close(expectedGeneration: Long): Boolean {
        if(closed || expectedGeneration!=generation) return false
        closed=true
        ++generation
        return true
    }
}

/** One failed platform release must not strand the projection or foreground service. */
internal class CaptureCleanup(private val report: (String,Throwable)->Unit) {
    fun release(name: String,action: ()->Unit) {
        runCatching(action).onFailure { error -> runCatching { report(name,error) } }
    }
}
