package com.cyotun.jpq

import android.os.Handler
import android.os.Looper
import com.cyotun.jpq.core.*

object AppState {
    val ledger = RoundLedger()
    @Volatile var running = false
    @Volatile var capturedFrames = 0L
    @Volatile var capturedWidth = 0
    @Volatile var capturedHeight = 0
    var message = "等待开始记牌"
    var recognitionReady = false
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<() -> Unit>()
    fun subscribe(listener: () -> Unit) { listeners.add(listener) }
    fun unsubscribe(listener: () -> Unit) { listeners.remove(listener) }
    fun notifyChanged() { main.post { listeners.toList().forEach { it() } } }
}
