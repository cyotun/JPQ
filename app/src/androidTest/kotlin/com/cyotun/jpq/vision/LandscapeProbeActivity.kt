package com.cyotun.jpq.vision

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.widget.TextView

/** Blank test-only landscape host; never packaged in the user's application. */
class LandscapeProbeActivity : Activity() {
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    private var sequence=0
    private lateinit var label: TextView
    private val tick=object: Runnable {
        override fun run() {
            label.text="记牌器界面测试（非真实对局）\n采集序列 ${++sequence}"
            main.postDelayed(this,200)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        label=TextView(this).apply {
            text="记牌器界面测试（非真实对局）"
            setBackgroundColor(Color.rgb(19,20,19)); setTextColor(Color.GRAY)
            gravity=android.view.Gravity.CENTER
        }
        setContentView(label)
    }
    override fun onResume() { super.onResume(); main.post(tick) }
    override fun onPause() { main.removeCallbacks(tick); super.onPause() }
}
