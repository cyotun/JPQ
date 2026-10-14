package com.cyotun.jpq.vision

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.widget.TextView

/** Blank test-only landscape host; never packaged in the user's application. */
class LandscapeProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply {
            text="记牌器界面测试（非真实对局）"
            setBackgroundColor(Color.rgb(19,20,19)); setTextColor(Color.GRAY)
            gravity=android.view.Gravity.CENTER
        })
    }
}
