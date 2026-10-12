package com.cyotun.jpq

import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import com.cyotun.jpq.core.*

/** Adjusts the existing bar over the game without launching an opaque Activity. */
class AdjustmentOverlay(private val context: Context,initialPage: String,private val preview: (DisplayConfig?)->Unit) {
    private val wm=context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val store=ConfigStore(context)
    private var draft=store.load()
    private var page=initialPage
    private var attached=false
    private var disposed=false
    private var compactHeader=false
    private val d=context.resources.displayMetrics.density
    private fun dp(n: Int)=(n*d).toInt()
    private val gold=Color.rgb(255,227,149)
    private val root=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(10),dp(12),dp(10)) }
    private val params=WindowManager.LayoutParams(-1,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.BOTTOM }
    private fun label(text: String,size: Float=15f)=TextView(context).apply { this.text=text; textSize=size; setTextColor(gold); gravity=Gravity.CENTER_VERTICAL }
    private fun button(text: String,action: ()->Unit)=Button(context).apply {
        this.text=text; textSize=14f; isAllCaps=false; setTextColor(Color.BLACK)
        background=GradientDrawable().apply { setColor(gold); cornerRadius=dp(24).toFloat() }; setOnClickListener { action() }
    }
    fun show() {
        check(!disposed)
        if(attached) return
        AppState.beginOverlayOcclusion(this)
        try {
            render(); wm.addView(root,params); attached=true
            root.addOnLayoutChangeListener { _,left,_,right,_,_,_,_,_ ->
                publishMask()
                if(right>left && (right-left<dp(620))!=compactHeader) root.post { if(attached && !disposed) render() }
            }
            root.post { publishMask() }
        } catch(error: Exception) { AppState.endOverlayOcclusion(this); throw error }
    }
    private fun publishMask() {
        if(!attached || disposed || root.width<=0 || root.height<=0) return
        val xy=IntArray(2); root.getLocationOnScreen(xy)
        val screen=if(android.os.Build.VERSION.SDK_INT>=30) wm.currentWindowMetrics.bounds else Rect(0,0,context.resources.displayMetrics.widthPixels,context.resources.displayMetrics.heightPixels)
        AppState.adjustmentMask=CaptureMask(Rect(xy[0],xy[1],xy[0]+root.width,xy[1]+root.height),screen.width(),screen.height())
    }
    fun close() {
        if(disposed) return
        disposed=true
        val wasAttached=attached; attached=false
        if(wasAttached) runCatching { wm.removeView(root) }
        AppState.endOverlayOcclusion(this); AppState.adjustmentMask=null; preview(null)
    }
    private fun render() {
        if(disposed) return
        root.removeAllViews()
        root.background=GradientDrawable().apply { setColor(Color.argb(245,38,38,38)); cornerRadii=floatArrayOf(dp(24).toFloat(),dp(24).toFloat(),dp(24).toFloat(),dp(24).toFloat(),0f,0f,0f,0f) }
        val screenWidth=if(attached && root.width>0) root.width else if(android.os.Build.VERSION.SDK_INT>=30) wm.currentWindowMetrics.bounds.width() else context.resources.displayMetrics.widthPixels
        compactHeader=screenWidth<dp(620)
        val head=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        fun action(row: LinearLayout,text: String,width: Int,weight: Float=0f,endMargin: Int=8,click: ()->Unit) {
            row.addView(button(text,click),LinearLayout.LayoutParams(if(weight>0f) 0 else dp(width),dp(38),weight).apply { marginEnd=dp(endMargin) })
        }
        action(head,"尺寸调整",90) { page="size"; render() }; action(head,"牌序调整",90) { page="order"; render() }
        head.addView(label("掼蛋默认 · 4人 · 2副"),LinearLayout.LayoutParams(0,dp(38),1f))
        root.addView(head)
        val controls=if(compactHeader) LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL
            root.addView(this,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
        } else head
        action(controls,"退出",54,if(compactHeader) 1f else 0f) { close() }
        action(controls,"重置",54,if(compactHeader) 1f else 0f) { val defaults=DisplayConfig(); draft=if(page=="order") draft.copy(order=guandanOrder) else defaults.copy(showHistory=draft.showHistory,showReset=draft.showReset,doubleTapHide=draft.doubleTapHide,order=draft.order,x=draft.x,y=draft.y); preview(draft); render() }
        action(controls,"保存并退出",100,if(compactHeader) 1.8f else 0f,if(compactHeader) 0 else 8) { store.save(draft); close() }
        val scroll=ScrollView(context)
        val body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(0,dp(8),0,0) }; scroll.addView(body)
        root.addView(scroll,LinearLayout.LayoutParams(-1,dp(144)))
        if(page=="order") {
            body.addView(label("长按牌面，拖动调整顺序",13f))
            val grid=GridLayout(context).apply { columnCount=8 }
            draft.order.forEach { rank ->
                val view=button(rank.label) {}
                view.setOnLongClickListener { view.startDragAndDrop(ClipData.newPlainText("rank",rank.name),View.DragShadowBuilder(view),rank,0); true }
                view.setOnDragListener { _,event ->
                    if(event.action==DragEvent.ACTION_DROP) {
                        val from=event.localState as? Rank
                        if(from!=null && from!=rank) { val next=draft.order.toMutableList(); next.remove(from); next.add(draft.order.indexOf(rank),from); draft=draft.copy(order=next); preview(draft); render() }
                    }; true
                }
                grid.addView(view,GridLayout.LayoutParams().apply { width=dp(70); height=dp(42); columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f); setMargins(dp(3),dp(3),dp(3),dp(3)) })
            }; body.addView(grid,LinearLayout.LayoutParams(-1,-2))
        } else {
            val columns=LinearLayout(context); val groups=List(4) { LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(5),0,dp(5),0) } }
            groups.forEach { columns.addView(it,LinearLayout.LayoutParams(0,-2,1f)) }; body.addView(columns)
            fun slider(column: Int,name: String,value: Float,min: Float,max: Float,enabled: Boolean=true,change: (Float)->Unit) {
                val title=label("$name ${(value*100).toInt()}%",13f); groups[column].addView(title)
                groups[column].addView(GoldSlider(context,min,max,value) { next -> change(next); title.text="$name ${(next*100).toInt()}%"; preview(draft) }.apply { isEnabled=enabled },LinearLayout.LayoutParams(-1,dp(44)))
            }
            slider(0,"总宽度",if(draft.showHistory) draft.widthScale else draft.countWidthScale,.8f,1.35f) { draft=if(draft.showHistory) draft.copy(widthScale=it) else draft.copy(countWidthScale=it) }
            slider(0,"左右比例",draft.gridRatio,.3f,.6f,draft.showHistory) { draft=draft.copy(gridRatio=it) }
            slider(1,"牌格高度",draft.gridHeight,.85f,1.3f) { draft=draft.copy(gridHeight=it) }
            slider(1,"记录行高",draft.recordHeight,.85f,1.3f,draft.showHistory) { draft=draft.copy(recordHeight=it) }
            slider(2,"标题字号",draft.headerText,.8f,1.35f) { draft=draft.copy(headerText=it) }
            slider(2,"数值字号",draft.valueText,.8f,1.35f) { draft=draft.copy(valueText=it) }
            slider(3,"计数字号",draft.counterText,.8f,1.35f,draft.showHistory) { draft=draft.copy(counterText=it) }
            slider(3,"详情字号",draft.detailText,.8f,1.35f,draft.showHistory) { draft=draft.copy(detailText=it) }
        }
        root.post { publishMask() }
    }
}
