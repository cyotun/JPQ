package com.cyotun.jpq

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/** Reference-style broad gold track and tall bar thumb. */
class GoldSlider(context: Context,private val minimum: Float,private val maximum: Float,
                 initial: Float,private val changed: (Float)->Unit): View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val d=resources.displayMetrics.density
    var value=initial; private set
    init { isFocusable=true; contentDescription="${(initial*100).roundToInt()}%" }
    override fun onMeasure(w: Int,h: Int) { setMeasuredDimension(resolveSize((180*d).toInt(),w),resolveSize((48*d).toInt(),h)) }
    override fun onDraw(c: Canvas) {
        val pad=7*d; val x=pad+(width-2*pad)*(value-minimum)/(maximum-minimum); val y=height/2f
        paint.color=if(isEnabled) Color.rgb(137,123,80) else Color.DKGRAY
        c.drawRoundRect(pad,y-8*d,width-pad,y+8*d,8*d,8*d,paint)
        paint.color=if(isEnabled) Color.rgb(255,227,149) else Color.GRAY
        c.drawRoundRect(pad,y-8*d,x,y+8*d,8*d,8*d,paint)
        c.drawRoundRect(x-2*d,y-15*d,x+2*d,y+15*d,2*d,2*d,paint)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if(!isEnabled) return false
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP -> { performClick(); parent.requestDisallowInterceptTouchEvent(false) }
            MotionEvent.ACTION_CANCEL -> { parent.requestDisallowInterceptTouchEvent(false); return true }
        }
        value=(minimum+(maximum-minimum)*((e.x-7*d)/(width-14*d)).coerceIn(0f,1f)).coerceIn(minimum,maximum)
        contentDescription="${(value*100).roundToInt()}%"; changed(value); invalidate(); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
