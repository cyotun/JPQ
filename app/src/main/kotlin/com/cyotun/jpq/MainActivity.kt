package com.cyotun.jpq

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import com.cyotun.jpq.core.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var store: ConfigStore
    private lateinit var root: LinearLayout
    private var page="home"
    private var draft=DisplayConfig()
    private var restorePage="settings"
    private var stateText: TextView?=null
    private var capturePending=false
    private var preview: OverlayController?=null
    private val listener: () -> Unit = { updateState() }
    private val gold=Color.rgb(255,227,149)
    private val muted=Color.rgb(156,156,156)
    private val captureResult=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        capturePending=false
        val data=result.data
        if(result.resultCode==RESULT_OK && data!=null) {
            startForegroundService(Intent(this,CaptureService::class.java).putExtra("code",result.resultCode).putExtra("data",data))
            moveTaskToBack(true)
        }
    }
    private val notificationResult=registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestProjection() }
    private val exportResult=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) runCatching { contentResolver.openOutputStream(uri,"wt")!!.use { it.write(store.export().toByteArray(Charsets.UTF_8)) } }
            .onFailure { Toast.makeText(this,"导出失败，请重新选择文件",Toast.LENGTH_LONG).show() }
    }
    private val importResult=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) importConfig(uri)
    }
    private fun dp(v: Int)=(v*resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store=ConfigStore(this); page=savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        if(page in listOf("size","order")) draft=store.load()
        savedInstanceState?.getString("draft")?.let { draft=store.readDraft(it) }
        restorePage=savedInstanceState?.getString("restorePage") ?: "settings"
        onBackPressedDispatcher.addCallback(this,object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(page=="home") finish() else leave() }
        })
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page",page); outState.putString("draft",store.draftJson(draft)); outState.putString("restorePage",restorePage); super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); AppState.subscribe(listener); if(page in listOf("permissions","home")) render(); updateState() }
    override fun onPause() { AppState.unsubscribe(listener); super.onPause() }
    private fun bg(fill: Int,radius: Int=16,stroke: Boolean=false)=GradientDrawable().apply {
        setColor(fill); cornerRadius=dp(radius).toFloat(); if(stroke) setStroke(dp(1),gold)
    }
    private fun text(value: String,size: Float=17f,color: Int=Color.WHITE)=TextView(this).apply {
        text=value; textSize=size; setTextColor(color); setPadding(dp(4),dp(10),dp(4),dp(10))
    }
    private fun row(label: String,value: String="",action: (() -> Unit)?=null): LinearLayout {
        val r=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            background=bg(Color.rgb(38,38,38)); setPadding(dp(14),dp(4),dp(14),dp(4)) }
        r.addView(text(label),LinearLayout.LayoutParams(0,dp(56),1f))
        r.addView(text(value,15f,gold)); if(action!=null) r.setOnClickListener { action() }
        return r
    }
    private fun button(label: String,action: () -> Unit)=Button(this).apply {
        text=label; textSize=18f; setTextColor(Color.BLACK); background=bg(gold,26)
        isAllCaps=false; setOnClickListener { action() }
    }
    private fun add(parent: LinearLayout,view: View,height: Int=ViewGroup.LayoutParams.WRAP_CONTENT) {
        parent.addView(view,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,if(height<0) height else dp(height)).apply { bottomMargin=dp(10) })
    }
    private fun render() {
        val layoutOrientation=if(page in listOf("size","order")) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if(requestedOrientation!=layoutOrientation) requestedOrientation=layoutOrientation
        stateText=null
        preview=null
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(19,20,19)); setPadding(dp(18),dp(8),dp(18),dp(12)) }
        root.setOnApplyWindowInsetsListener { v,insets ->
            @Suppress("DEPRECATION") v.setPadding(dp(18),insets.systemWindowInsetTop+dp(8),dp(18),insets.systemWindowInsetBottom+dp(12)); insets
        }
        setContentView(root)
        val title=when(page) { "settings" -> "设置"; "permissions" -> "权限设置"; "size" -> "尺寸调整"; "order" -> "牌序调整"; "update" -> "应用更新"; "backup" -> "配置备份"; else -> "记牌器" }
        val head=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        if(page !in listOf("home","settings")) head.addView(text("‹",32f,gold).apply { setOnClickListener { leave() } })
        head.addView(text(title,26f,gold),LinearLayout.LayoutParams(0,dp(62),1f))
        if(page=="home") head.addView(text("权限",15f,gold).apply { setOnClickListener { navigate("permissions") } })
        root.addView(head)
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val scroll=ScrollView(this).apply { isFillViewport=true; addView(content) }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        when(page) {
            "home" -> home(content)
            "settings" -> settings(content)
            "permissions" -> permissions(content)
            "size" -> size(content)
            "order" -> order(content)
            "update" -> updates(content)
            "backup" -> backup(content)
        }
        if(page in listOf("home","settings")) {
            val nav=LinearLayout(this)
            listOf("home" to "记牌","settings" to "设置").forEach { (key,label) ->
                nav.addView(text(label,18f,if(page==key) gold else muted).apply { gravity=Gravity.CENTER; setOnClickListener { navigate(key) } },LinearLayout.LayoutParams(0,dp(52),1f))
            }; root.addView(nav)
        }
    }
    private fun navigate(next: String) {
        if(next in listOf("size","order") && page !in listOf("size","order")) { draft=store.load(); restorePage=page }
        page=next; render()
    }
    private fun home(c: LinearLayout) {
        add(c,text("本地运行 · 个人使用",14f,muted))
        add(c,text("1、选择游戏平台")); add(c,row("大掼蛋（腾讯）","适配开发中"))
        add(c,text("2、选择玩法")); add(c,row("掼蛋","默认"))
        add(c,text("3、选择玩家人数")); add(c,row("4 人","✓"))
        add(c,text("4、几副牌")); add(c,row("2 副","✓"))
        val cards=LinearLayout(this)
        guandanOrder.forEach { rank -> cards.addView(text("${rank.label}\n${rank.capacity}",13f,gold).apply { gravity=Gravity.CENTER; background=bg(Color.rgb(38,38,38),4) },LinearLayout.LayoutParams(0,dp(64),1f)) }
        add(c,cards)
        stateText=text("",14f,muted); add(c,stateText!!); updateState()
        add(c,button(if(AppState.running) "返回记牌" else "开始记牌") { startCapture() },58)
        if(AppState.running) add(c,row("停止记牌","›") { stopCapture() })
        add(c,text("此开发版已接入权限、屏幕采集和悬浮操作。自动牌面识别尚未接通，请勿用于实际计数。",14f,muted))
    }
    private fun settings(c: LinearLayout) {
        val d=store.load()
        add(c,text("显示设置",15f,muted))
        switch(c,"显示三家出牌记录",d.showHistory) { store.save(store.load().copy(showHistory=it)); changed() }
        switch(c,"显示重置按钮",d.showReset) { store.save(store.load().copy(showReset=it)); changed() }
        switch(c,"双击记牌条隐藏",d.doubleTapHide) { store.save(store.load().copy(doubleTapHide=it)); changed() }
        add(c,text("双击开启后移除隐藏按钮；隐藏后只显示恢复按钮。",13f,muted))
        add(c,row("尺寸调整","›") { navigate("size") }); add(c,row("牌序调整","›") { navigate("order") })
        add(c,text("应用设置",15f,muted))
        add(c,row("权限设置","›") { navigate("permissions") }); add(c,row("应用更新","›") { navigate("update") })
        add(c,row("配置备份","›") { navigate("backup") })
        add(c,row("关于记牌器",BuildConfig.VERSION_NAME) {
            AlertDialog.Builder(this).setTitle("记牌器").setMessage("个人本地使用，无账号、会员或收费。当前为开发版，识别适配尚未完成。\n\n版本：${BuildConfig.VERSION_NAME}") .setPositiveButton("知道了",null).show()
        })
    }
    private fun switch(c: LinearLayout,label: String,on: Boolean,change: (Boolean)->Unit) {
        val v=Switch(this).apply { text=label; textSize=17f; setTextColor(Color.WHITE); isChecked=on; setPadding(dp(18),dp(14),dp(18),dp(14)); background=bg(Color.rgb(38,38,38)); setOnCheckedChangeListener { _,checked -> change(checked) } }
        add(c,v)
    }
    private fun permissions(c: LinearLayout) {
        add(c,row("悬浮窗权限",if(Settings.canDrawOverlays(this)) "已开启" else "去设置") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
        })
        add(c,row("通知权限",if(Build.VERSION.SDK_INT<33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) "已允许" else "去开启") {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
        })
        add(c,text("屏幕共享在每次开始记牌时由系统请求授权。",14f,muted))
        add(c,row("后台运行与耗电","去设置") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))) })
        add(c,row("锁定后台应用","查看指引") {
            AlertDialog.Builder(this).setMessage("在澎湃系统的最近任务中长按记牌器，按手机实际菜单锁定后台。再进入应用设置检查后台运行和耗电限制。具体菜单以你的系统为准。").setPositiveButton("知道了",null).show()
        })
    }
    private fun size(c: LinearLayout) {
        adjustmentPreview(c)
        add(c,text("掼蛋默认 · 4人 · 2副",15f,muted))
        add(c,text("设置基础宽度；记录过长时只自动扩展右侧。退出不保存，保存后应用于记牌条。",14f,muted))
        val links=LinearLayout(this)
        links.addView(button("牌序调整") { navigate("order") },LinearLayout.LayoutParams(-1,dp(50)))
        add(c,links)
        val columns=LinearLayout(this)
        val groups=List(4) { LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(6),0,dp(6),0) } }
        groups.forEach { columns.addView(it,LinearLayout.LayoutParams(0,-2,1f)) }
        add(c,columns)
        add(groups[0],text("窗口",16f,gold)); add(groups[1],text("行高",16f,gold)); add(groups[2],text("左侧字体",16f,gold)); add(groups[3],text("右侧字体",16f,gold))
        slider(groups[0],"总宽度",draft.widthScale,.8f,1.35f) { draft=draft.copy(widthScale=it) }
        slider(groups[0],"左右比例",draft.gridRatio,.3f,.6f,draft.showHistory) { draft=draft.copy(gridRatio=it) }
        slider(groups[1],"牌格高度",draft.gridHeight,.85f,1.3f) { draft=draft.copy(gridHeight=it) }
        slider(groups[1],"记录行高",draft.recordHeight,.85f,1.3f,draft.showHistory) { draft=draft.copy(recordHeight=it) }
        slider(groups[2],"标题字号",draft.headerText,.8f,1.35f) { draft=draft.copy(headerText=it) }
        slider(groups[2],"数值字号",draft.valueText,.8f,1.35f) { draft=draft.copy(valueText=it) }
        slider(groups[3],"计数字号",draft.counterText,.8f,1.35f,draft.showHistory) { draft=draft.copy(counterText=it) }
        slider(groups[3],"详情字号",draft.detailText,.8f,1.35f,draft.showHistory) { draft=draft.copy(detailText=it) }
        saveBar(c)
    }
    private fun slider(c: LinearLayout,label: String,value: Float,min: Float,max: Float,enabled: Boolean=true,change: (Float)->Unit) {
        val title=text("$label  ${(value*100).toInt()}%",17f,if(enabled) Color.WHITE else muted); add(c,title)
        val seek=SeekBar(this).apply { this.max=1000; progress=((value-min)/(max-min)*1000).toInt(); isEnabled=enabled
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?,progress: Int,fromUser: Boolean) { if(fromUser) { val next=min+(max-min)*progress/1000; change(next); title.text="$label  ${(next*100).toInt()}%"; preview?.refresh() } }
                override fun onStartTrackingTouch(bar: SeekBar?)=Unit
                override fun onStopTrackingTouch(bar: SeekBar?)=Unit
            }) }; add(c,seek)
    }
    private fun order(c: LinearLayout) {
        adjustmentPreview(c)
        add(c,text("长按牌面开始拖动调整顺序",15f,muted))
        add(c,button("尺寸调整") { navigate("size") },50)
        val grid=GridLayout(this).apply { columnCount=8 }
        draft.order.forEach { rank ->
            val v=text(rank.label,23f,gold).apply { gravity=Gravity.CENTER; background=bg(Color.rgb(38,38,38),12,true) }
            v.setOnLongClickListener { v.startDragAndDrop(ClipData.newPlainText("rank",rank.name),View.DragShadowBuilder(v),rank,0); true }
            v.setOnDragListener { _,e ->
                if(e.action==DragEvent.ACTION_DROP) {
                    val from=e.localState as? Rank
                    if(from!=null && from!=rank) {
                        val next=draft.order.toMutableList(); next.remove(from); next.add(draft.order.indexOf(rank),from)
                        draft=draft.copy(order=next); render()
                    }
                }; true
            }
            val cell=((resources.displayMetrics.widthPixels-dp(84))/8).coerceAtLeast(dp(44))
            grid.addView(v,GridLayout.LayoutParams().apply { width=cell; height=dp(54); setMargins(dp(3),dp(3),dp(3),dp(3)) })
        }; add(c,grid); saveBar(c)
    }
    private fun adjustmentPreview(c: LinearLayout) {
        add(c,text("调整预览 · 下方数据为示例，不计入牌局",13f,muted))
        preview=OverlayController(this,true) { draft }
        val holder=FrameLayout(this).apply { addView(preview!!.previewView()) }
        add(c,holder,60)
    }
    private fun saveBar(c: LinearLayout) {
        add(c,row("重置当前调整","恢复默认") {
            val defaults=DisplayConfig()
            draft=if(page=="order") draft.copy(order=guandanOrder) else defaults.copy(showHistory=draft.showHistory,showReset=draft.showReset,doubleTapHide=draft.doubleTapHide,order=draft.order,x=draft.x,y=draft.y)
            render()
        })
        add(c,button("保存并退出") { store.save(draft.validate()); changed(); page=restorePage; render() },56)
        add(c,row("退出","不保存") { leave() })
    }
    private fun updates(c: LinearLayout) {
        add(c,text("当前版本：${BuildConfig.VERSION_NAME}",17f,gold))
        add(c,text("更新不打断记牌。安装包与手机更新流程正在接入。",15f,muted))
        val status=text("",16f); add(c,status)
        add(c,button("检查更新") {
            status.text="正在检查更新"
            Thread {
                val message=runCatching {
                    val connection=java.net.URL("https://jpq.240438457.workers.dev/v1/releases/latest").openConnection() as javax.net.ssl.HttpsURLConnection
                    connection.connectTimeout=12000; connection.readTimeout=12000
                    try { when(connection.responseCode) { 204 -> "暂无发布版本"; 200 -> "发现发布清单；客户端签名验证和下载尚在开发，暂不安装"; else -> "暂时无法检查，稍后重试" } } finally { connection.disconnect() }
                }.getOrDefault("暂时无法检查，稍后重试")
                runOnUiThread { if(!isFinishing) status.text=message }
            }.start()
        },56)
    }
    private fun backup(c: LinearLayout) {
        add(c,text("只备份显示配置，不含权限、录屏、牌局或密钥。",15f,muted))
        add(c,button("导出配置") { exportResult.launch("记牌器配置.json") },56)
        add(c,button("导入配置") { importResult.launch(arrayOf("application/json")) },56)
    }
    private fun changed() { if(AppState.running) startService(Intent(this,CaptureService::class.java).setAction("CONFIG")) }
    private fun startCapture() {
        if(AppState.running) { moveTaskToBack(true); return }
        if(capturePending) return
        if(!Settings.canDrawOverlays(this)) { navigate("permissions"); Toast.makeText(this,"请先开启悬浮窗权限",Toast.LENGTH_LONG).show(); return }
        capturePending=true
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            notificationResult.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else requestProjection()
    }
    private fun requestProjection() { captureResult.launch((getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()) }
    private fun stopCapture() { startService(Intent(this,CaptureService::class.java).setAction("STOP")); Handler(Looper.getMainLooper()).postDelayed({ render() },300) }
    private fun updateState() { stateText?.text=if(AppState.running) "屏幕采集运行中 · ${AppState.capturedWidth} × ${AppState.capturedHeight}\n${AppState.capturedFrames} 帧，自动识别适配尚未接通" else AppState.message }
    private fun leave() { page=if(page in listOf("size","order")) restorePage else "settings"; render() }
    private fun importConfig(uri: Uri) {
        runCatching {
                val bytes=contentResolver.openInputStream(uri)!!.use { input ->
                    val output=java.io.ByteArrayOutputStream(); val block=ByteArray(4096)
                    while(true) { val n=input.read(block); if(n<0) break; require(output.size()+n<=32768); output.write(block,0,n) }; output.toByteArray()
                }
                val text=bytes.toString(Charsets.UTF_8)
                AlertDialog.Builder(this).setMessage("导入会覆盖当前显示设置，是否继续？").setPositiveButton("导入") { _,_ ->
                    runCatching { store.import(text); changed() }.onFailure { Toast.makeText(this,"配置不符合要求，原设置未改变",Toast.LENGTH_LONG).show() }
                }.setNegativeButton("取消",null).show()
        }.onFailure { Toast.makeText(this,"配置处理失败，原设置未改变",Toast.LENGTH_LONG).show() }
    }
}
