package com.cyotun.jpq

import android.app.AlertDialog
import android.content.*
import android.content.res.Configuration
import android.graphics.*
import android.os.Build
import android.view.*
import android.widget.*
import com.cyotun.jpq.core.*
import kotlin.math.*

class OverlayController(private val context: Context,private val preview: Boolean=false,private val configSource: (() -> DisplayConfig)?=null) {
    private val wm=context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val store=ConfigStore(context)
    private var config=store.load()
    private var hidden=false
    private var added=false
    private var disposed=false
    private var touch=false
    private var changedDuringTouch=false
    private var oldRound: String?=null
    private var oldBounds: Rect?=null
    private val width=HistoryWidth()
    private val font=Typeface.createFromAsset(context.assets,"overlay.ttf")
    private val view=BarView()
    private val params=WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.LEFT }
    private val listener: () -> Unit = { refresh() }
    private val d get()=context.resources.displayMetrics.density
    private fun sp(value: Float)=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,value,context.resources.displayMetrics)
    private fun dp(value: Float)=value*d
    private var size=OverlaySize(0f,0f,0f,0f)
    private var barHeight=44f
    private var left=0f
    private var adjustment: AdjustmentOverlay?=null
    private var draftPreview: DisplayConfig?=null
    private val dialogs=linkedSetOf<AlertDialog>()
    fun show() {
        check(!disposed)
        if(added) return
        refresh(); wm.addView(view,params); added=true; AppState.subscribe(listener)
        view.post { publishMask() }
    }
    fun previewView(): View { refresh(); return view }
    private fun snapshot()=if(preview) PreviewData.snapshot else AppState.ledger.snapshot()
    fun close() {
        if(disposed) return
        disposed=true
        AppState.unsubscribe(listener)
        dialogs.toList().forEach { runCatching { it.dismiss() }; AppState.endOverlayOcclusion(it) }
        dialogs.clear()
        adjustment?.close(); adjustment=null
        val wasAdded=added; added=false
        if(wasAdded) runCatching { wm.removeView(view) }
        if(!preview) AppState.barMask=null
    }
    @Suppress("DEPRECATION") private fun bounds(): Rect =
        if(Build.VERSION.SDK_INT>=30) wm.currentWindowMetrics.bounds
        else context.resources.displayMetrics.let { Rect(0,0,it.widthPixels,it.heightPixels) }
    fun refresh() {
        if(disposed) return
        if(touch) { changedDuringTouch=true; view.invalidate(); return }
        val previous=config
        config=draftPreview ?: configSource?.invoke() ?: store.load()
        val screen=bounds()
        val keepAnchor=added && oldBounds==screen && previous.x==config.x && previous.y==config.y
        oldBounds=Rect(screen)
        val snapshot=snapshot()
        if(snapshot.roundId!=oldRound) { oldRound=snapshot.roundId; width.newRound() }
        val space=bounds().width()/d
        if(hidden) {
            params.width=dp(44f).roundToInt(); params.height=params.width
        } else if(snapshot.remaining==null) {
            left=if(config.doubleTapHide) 0f else 48f
            val banner=min(370f,space-left)
            params.width=dp(left+banner).roundToInt(); params.height=dp(46f).roundToInt()
        } else {
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=font; textSize=sp(14*config.detailText) }
            val longest=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).maxOf { occupied(paint,snapshot.history(it))/d }
            size=width.measure(config,longest,space)
            left=if(config.doubleTapHide) 0f else 48f
            barHeight=maxOf(44f,44*config.gridHeight+3,if(config.showHistory) 42*config.recordHeight+4 else 0f)
            params.width=dp(size.total).roundToInt(); params.height=dp(barHeight).roundToInt()
        }
        val maxX=max(0,screen.width()-params.width); val maxY=max(0,screen.height()-params.height)
        params.x=if(keepAnchor) params.x.coerceIn(0,maxX) else (config.x*maxX).roundToInt()
        params.y=if(keepAnchor) params.y.coerceIn(0,maxY) else (config.y*maxY).roundToInt()
        if(added) wm.updateViewLayout(view,params)
        if(!preview) view.post { publishMask() }
        if(preview) view.layoutParams=(view.layoutParams ?: ViewGroup.LayoutParams(params.width,params.height)).apply {
            width=params.width; height=params.height
        }
        view.invalidate()
    }
    private fun occupied(paint: Paint,value: String): Float {
        val b=Rect(); paint.getTextBounds(value,0,value.length,b)
        return max(paint.measureText(value),b.right.toFloat())-min(0,b.left)
    }
    private fun publishMask() {
        if(!added || disposed || view.width<=0 || view.height<=0) return
        val location=IntArray(2); view.getLocationOnScreen(location); val screen=bounds()
        AppState.barMask=CaptureMask(Rect(location[0],location[1],location[0]+view.width,location[1]+view.height),screen.width(),screen.height())
    }
    private fun hide() { hidden=true; detectorCancel(); refresh() }
    private fun detectorCancel() { view.cancelGesture() }
    private fun showDialog(dialog: AlertDialog) {
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.setOnDismissListener {
            dialogs.remove(dialog)
            AppState.endOverlayOcclusion(dialog)
        }
        dialogs.add(dialog)
        AppState.beginOverlayOcclusion(dialog)
        try { dialog.show() } catch(error: Exception) {
            dialogs.remove(dialog); AppState.endOverlayOcclusion(dialog)
            throw error
        }
    }
    private fun reset() {
        val dialog=AlertDialog.Builder(context).setMessage("重新开始记牌？本局记录将清空；牌局已开始时需等待下一局。")
            .setPositiveButton("重新开始") { _,_ -> AppState.ledger.waitForNext(); width.newRound(); AppState.notifyChanged() }
            .setNegativeButton("取消",null).create()
        showDialog(dialog)
    }
    private fun tools() {
        val choices=arrayOf("尺寸调整","牌序调整",if(config.showHistory) "关闭三家出牌记录" else "显示三家出牌记录","重置本局","停止记牌")
        val dialog=AlertDialog.Builder(context).setTitle("记牌工具").setItems(choices) { _,which ->
            when(which) {
                0,1 -> {
                    adjustment?.close()
                    adjustment=AdjustmentOverlay(context,if(which==0) "size" else "order") { value -> draftPreview=value; refresh() }.also { it.show() }
                }
                2 -> { store.save(config.copy(showHistory=!config.showHistory)); refresh() }
                3 -> reset()
                4 -> context.startService(Intent(context,CaptureService::class.java).setAction("STOP"))
            }
        }.setNegativeButton("关闭",null).create()
        showDialog(dialog)
    }
    private inner class BarView: View(context) {
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        private var originX=0; private var originY=0
        private var downX=0f; private var downY=0f; private var moved=false
        private var downTarget=0
        private val slop=ViewConfiguration.get(context).scaledTouchSlop
        private var detector=makeDetector()
        private fun makeDetector()=GestureDetector(context,object: GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent)=true
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                performClick()
                if(hidden) { hidden=false; post { detectorCancel(); refresh() }; return true }
                if(downTarget==1) { post { hide() }; return true }
                if(downTarget==2) { post { reset() }; return true }
                return true
            }
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                if(!hidden && config.doubleTapHide && downTarget==0 && !moved) post { hide() }
                return true
            }
            override fun onLongPress(e: android.view.MotionEvent) {
                if(!hidden && downTarget==0 && !moved) { post { tools(); cancelGesture() } }
            }
        })
        fun cancelGesture() { detector=makeDetector(); touch=false; moved=false }
        override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
            super.onSizeChanged(w,h,oldw,oldh)
            if(!preview) post { publishMask() }
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if(preview) return false
            if(event.actionMasked==MotionEvent.ACTION_DOWN) {
                touch=true; moved=false; changedDuringTouch=false
                downX=event.rawX; downY=event.rawY; originX=params.x; originY=params.y
                downTarget=when {
                    hidden -> 3
                    !config.doubleTapHide && event.x<dp(44f) -> 1
                    snapshot().remaining!=null && config.showReset && event.x>this.width-dp(44f) -> 2
                    else -> 0
                }
            }
            if(event.actionMasked==MotionEvent.ACTION_MOVE && downTarget==0 && !hidden) {
                val dx=event.rawX-downX; val dy=event.rawY-downY
                if(abs(dx)+abs(dy)>slop) moved=true
                if(moved) {
                    params.x=(originX+dx).roundToInt().coerceIn(0,max(0,bounds().width()-params.width))
                    params.y=(originY+dy).roundToInt().coerceIn(0,max(0,bounds().height()-params.height))
                    if(added) wm.updateViewLayout(this,params)
                    post { publishMask() }
                }
            }
            if(!moved) detector.onTouchEvent(event)
            if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {
                touch=false
                if(moved) {
                    detectorCancel()
                    config=config.copy(x=params.x.toFloat()/max(1,bounds().width()-params.width),y=params.y.toFloat()/max(1,bounds().height()-params.height))
                    store.save(config)
                }
                if(changedDuringTouch) post { refresh() }
            }
            return true
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if(hidden) { button(canvas,0f,"显示"); return }
            val snap=snapshot()
            if(!config.doubleTapHide) button(canvas,0f,"隐藏")
            if(snap.remaining==null) { waiting(canvas,snap); return }
            val start=dp(this@OverlayController.left); val body=RectF(start,0f,start+dp(size.content),dp(barHeight))
            p.color=Color.BLACK; canvas.drawRoundRect(body,dp(12f),dp(12f),p)
            val inside=RectF(body).apply { inset(dp(1f),dp(1f)) }
            p.color=Color.rgb(38,38,38); canvas.drawRoundRect(inside,dp(11f),dp(11f),p)
            canvas.save(); val clip=Path().apply { addRoundRect(inside,dp(11f),dp(11f),Path.Direction.CW) }; canvas.clipPath(clip)
            val gridStart=start+dp(1f); val cell=(size.grid-14)/15
            val height=22*config.gridHeight
            config.order.forEachIndexed { index,rank ->
                val x=gridStart+dp(index*(cell+1))
                val header=RectF(x,dp(1f),x+dp(cell),dp(height+1))
                p.shader=LinearGradient(0f,header.top,0f,header.bottom,Color.rgb(109,110,110),Color.rgb(67,67,67),Shader.TileMode.CLAMP)
                canvas.drawRect(header,p); p.shader=null
                val value=RectF(x,dp(height+2),x+dp(cell),dp(height*2+2)); p.color=Color.rgb(67,67,67); canvas.drawRect(value,p)
                text(canvas,header,rank.label,16*config.headerText,Color.WHITE,true,false)
                val count=snap.remaining!![rank]!!
                val color=when(countTone(rank,count)) { CountTone.RED -> Color.rgb(255,49,64); CountTone.ZERO -> Color.rgb(95,95,95); else -> Color.WHITE }
                text(canvas,value,count.toString(),16*config.valueText,color)
            }
            if(config.showHistory) {
                val rx=gridStart+dp(size.grid+1)
                listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).forEachIndexed { i,seat ->
                    val y=dp(1+i*(14*config.recordHeight+1)); val h=dp(14*config.recordHeight)
                    val lb=RectF(rx,y,rx+dp(14f),y+h); p.color=Color.rgb(67,67,67); canvas.drawRect(lb,p); text(canvas,lb,seat.label,12f,Color.WHITE)
                    val nb=RectF(rx+dp(16f),y,rx+dp(30f),y+h); p.color=GOLD; canvas.drawRect(nb,p); text(canvas,nb,snap.played(seat).toString(),12*config.counterText,Color.BLACK)
                    val hb=RectF(rx+dp(32f),y,rx+dp(size.history-12),y+h)
                    p.typeface=font; p.textSize=sp(14*config.detailText)
                    var groups=snap.plays[seat].orEmpty().map { it.cards.joinToString("") { it.rank.label } }
                    var value=groups.joinToString("·"); var shortened=false
                    while(groups.isNotEmpty() && occupied(p,(if(shortened) "…" else "")+value)>max(0f,hb.width())) {
                        groups=groups.drop(1); shortened=true; value=groups.joinToString("·")
                    }
                    text(canvas,hb,(if(shortened && groups.isNotEmpty()) "…" else "")+value,14*config.detailText,Color.WHITE,false)
                }
            }
            canvas.restore()
            if(config.showReset) button(canvas,size.total-44,"重置")
        }
        private fun waiting(canvas: Canvas,snap: RoundSnapshot) {
            val b=RectF(dp(this@OverlayController.left),0f,this.width.toFloat(),dp(46f))
            p.color=Color.BLACK; canvas.drawRoundRect(b,dp(12f),dp(12f),p)
            b.inset(dp(1f),dp(1f)); p.color=GOLD; canvas.drawRoundRect(b,dp(11f),dp(11f),p)
            val title=if(snap.phase==RoundPhase.UNAVAILABLE) "本局画面不完整，等待下一局" else AppState.message
            val subtitle=when {
                snap.phase==RoundPhase.UNAVAILABLE -> snap.reason ?: "本局不显示推测数字，下一局自动恢复"
                !AppState.recognitionReady -> "正在准备识别，稍后会自动开始"
                else -> "请从游戏大厅进入房间；进行中的牌局需等待下一局"
            }
            text(canvas,RectF(b.left+dp(9f),dp(2f),b.right-dp(9f),dp(26f)),title,18f,Color.BLACK)
            text(canvas,RectF(b.left+dp(9f),dp(27f),b.right-dp(9f),dp(43f)),subtitle,10f,Color.BLACK)
        }
        private fun button(canvas: Canvas,x: Float,label: String) {
            p.color=GOLD; canvas.drawCircle(dp(x+22),dp(22f),dp(21f),p)
            p.color=Color.rgb(38,38,38); p.style=Paint.Style.STROKE; p.strokeWidth=dp(2f)
            canvas.drawCircle(dp(x+22),dp(22f),dp(21f),p); p.style=Paint.Style.FILL
            text(canvas,RectF(dp(x),0f,dp(x+44),dp(44f)),label,16f,Color.BLACK)
        }
        private fun text(canvas: Canvas,box: RectF,value: String,size: Float,color: Int,center: Boolean=true,clipHorizontal: Boolean=true) {
            p.typeface=font; p.textSize=sp(size); p.color=color; p.shader=null
            val ink=Rect(); p.getTextBounds(value,0,value.length,ink)
            val x=if(center) box.centerX()-(ink.left+ink.right)/2f else box.left-min(0,ink.left)
            val y=box.centerY()-(ink.top+ink.bottom)/2f
            canvas.save()
            if(clipHorizontal) canvas.clipRect(box) else canvas.clipRect(0f,box.top,this.width.toFloat(),box.bottom)
            canvas.drawText(value,x,y,p); canvas.restore()
        }
    }
    companion object { val GOLD=Color.rgb(255,227,149) }
}
