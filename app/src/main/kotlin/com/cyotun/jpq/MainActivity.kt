package com.cyotun.jpq

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.TextUtils
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import com.cyotun.jpq.core.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.cyotun.jpq.updates.UpdateManager
import com.cyotun.jpq.updates.UpdateOperation
import com.cyotun.jpq.updates.UpdateRowAction

class MainActivity : ComponentActivity() {
    private lateinit var store: ConfigStore
    private lateinit var root: LinearLayout
    private var page="home"
    private var draft=DisplayConfig()
    private var restorePage="settings"
    private var permissionsReturnPage="settings"
    private var stateText: TextView?=null
    private var captureButton: Button?=null
    private var capturePending=false
    private var stopPending=false
    private var preview: OverlayController?=null
    private val updates by lazy { UpdateManager(applicationContext) }
    private var updateActionButton: Button?=null
    private var updateStatusText: TextView?=null
    private var updateRequested: UpdateOperation?=null
    private var updateConsent: AlertDialog?=null
    private var updateUiResumed=false
    private val updateHandler=Handler(Looper.getMainLooper())
    private val updateTick=object: Runnable {
        override fun run() {
            if(updateUiResumed && page=="settings") {
                refreshUpdateRow()
                updateHandler.postDelayed(this,500)
            }
        }
    }
    private val appFont by lazy { Typeface.createFromAsset(assets,"overlay.ttf") }
    private val homeControls=mutableListOf<View>()
    private var homeState: TextView?=null
    private val homePreferences get()=getSharedPreferences("jpq-home",MODE_PRIVATE)
    private fun selectedPlatform()=HomeGameCatalog.platform(homePreferences.getString("platform",HomeGameCatalog.DEFAULT_PLATFORM))
    private val listener: () -> Unit = { updateState() }
    private val gold=Color.rgb(255,227,149)
    private val muted=Color.rgb(156,156,156)
    private val captureResult=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        capturePending=false
        val data=result.data
        if(result.resultCode==RESULT_OK && data!=null) {
            startForegroundService(Intent(this,CaptureService::class.java).putExtra("code",result.resultCode).putExtra("data",data)
                .putExtra(ProjectionConsentRequest.SCOPE_EXTRA,ProjectionConsentRequest.scope.name))
            moveTaskToBack(true)
        }
        updateState()
    }
    private val notificationResult=registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestProjection() }
    private val exportResult=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) runCatching { contentResolver.openOutputStream(uri,"wt")!!.use { it.write(store.export().toByteArray(Charsets.UTF_8)) } }
            .onFailure { Toast.makeText(this,"导出失败，请重新选择文件",Toast.LENGTH_LONG).show() }
    }
    private val importResult=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) importConfig(uri)
    }
    private val diagnosticResult=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) exportDiagnostics(uri,false)
    }
    private val archiveResult=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri!=null) exportDiagnostics(uri,true)
    }
    private fun exportDiagnostics(uri: Uri,fullArchive: Boolean) {
        val appContext=applicationContext
        val resolver=contentResolver
        Thread({
            runCatching { DiagnosticArchive.export(appContext,fullArchive) { checkNotNull(resolver.openOutputStream(uri,"wt")) } }
                .onFailure { runOnUiThread { if(!isFinishing && !isDestroyed)
                    Toast.makeText(this,"导出未完成，请停止记牌并等待记录保存后重试",Toast.LENGTH_LONG).show() } }
        },"jpq-diagnostic-export").start()
    }
    private fun dp(v: Int)=(v*resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store=ConfigStore(this); page=savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        if(page=="update") page="settings" // Existing notification/saved-state compatibility.
        if(page in listOf("size","order")) draft=store.load()
        savedInstanceState?.getString("draft")?.let { draft=store.readDraft(it) }
        restorePage=savedInstanceState?.getString("restorePage") ?: "settings"
        permissionsReturnPage=savedInstanceState?.getString("permissionsReturnPage") ?: "settings"
        capturePending=savedInstanceState?.getBoolean("capturePending") ?: false
        onBackPressedDispatcher.addCallback(this,object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(page=="home") finish() else leave() }
        })
        render()
        com.cyotun.jpq.vision.VisionRuntime.warm(applicationContext)
        UpdateManager.schedule(this)
        Thread { runCatching { UpdateManager(applicationContext).check() } }.start()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page",page); outState.putString("draft",store.draftJson(draft)); outState.putString("restorePage",restorePage)
        outState.putString("permissionsReturnPage",permissionsReturnPage); outState.putBoolean("capturePending",capturePending)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume(); updateUiResumed=true; AppState.subscribe(listener)
        if(page in listOf("permissions","home")) render()
        updateState(); scheduleUpdateRefresh()
    }
    override fun onPause() {
        updateUiResumed=false; updateHandler.removeCallbacks(updateTick)
        AppState.unsubscribe(listener); super.onPause()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if(intent.getStringExtra("page") in setOf("update","settings")) { page="settings"; render() }
    }
    override fun onDestroy() {
        updateHandler.removeCallbacks(updateTick); updateConsent?.dismiss(); updateConsent=null
        super.onDestroy()
    }
    private fun bg(fill: Int,radius: Int=16,stroke: Boolean=false)=GradientDrawable().apply {
        setColor(fill); cornerRadius=dp(radius).toFloat(); if(stroke) setStroke(dp(1),gold)
    }
    private fun text(value: String,size: Float=17f,color: Int=Color.WHITE)=TextView(this).apply {
        text=value; textSize=size; typeface=appFont; setTextColor(color); setPadding(dp(4),dp(10),dp(4),dp(10))
    }
    private fun row(label: String,value: String="",action: (() -> Unit)?=null): LinearLayout {
        val r=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            background=bg(Color.rgb(38,38,38)); setPadding(dp(14),dp(4),dp(14),dp(4)) }
        r.addView(text(label),LinearLayout.LayoutParams(0,dp(56),1f))
        r.addView(text(value,15f,gold)); if(action!=null) r.setOnClickListener { action() }
        return r
    }
    private fun button(label: String,action: () -> Unit)=Button(this).apply {
        text=label; textSize=18f; typeface=appFont; setTextColor(Color.BLACK); background=bg(gold,26)
        isAllCaps=false; maxLines=1
        setAutoSizeTextTypeUniformWithConfiguration(10,18,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        setOnClickListener { action() }
    }
    private fun add(parent: LinearLayout,view: View,height: Int=ViewGroup.LayoutParams.WRAP_CONTENT) {
        parent.addView(view,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,if(height<0) height else dp(height)).apply { bottomMargin=dp(10) })
    }
    private fun render() {
        val layoutOrientation=if(page in listOf("size","order")) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if(requestedOrientation!=layoutOrientation) requestedOrientation=layoutOrientation
        stateText=null
        captureButton=null
        preview=null
        updateActionButton=null; updateStatusText=null
        homeState=null; homeControls.clear()
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(19,19,19)); setPadding(dp(18),dp(8),dp(18),dp(12)) }
        root.setOnApplyWindowInsetsListener { v,insets ->
            @Suppress("DEPRECATION") v.setPadding(dp(18),insets.systemWindowInsetTop+dp(8),dp(18),insets.systemWindowInsetBottom+dp(12)); insets
        }
        setContentView(root)
        val title=when(page) { "settings" -> "设置"; "permissions" -> "权限设置"; "diagnostics" -> "牌局与诊断"; "size" -> "尺寸调整"; "order" -> "牌序调整"; "backup" -> "配置备份"; else -> applicationInfo.loadLabel(packageManager).toString() }
        val head=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        if(page !in listOf("home","settings")) head.addView(text("‹",32f,gold).apply { setOnClickListener { leave() } })
        head.addView(text(title,26f,gold).apply {
            maxLines=1; setAutoSizeTextTypeUniformWithConfiguration(10,26,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        },LinearLayout.LayoutParams(0,dp(if(page=="home") 52 else 62),1f))
        if(page=="home") {
            homeState=homeText("本地记牌",12f,muted)
            head.addView(homeState,LinearLayout.LayoutParams(-2,dp(40)))
        }
        root.addView(head)
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        if(page=="home" && resources.configuration.screenHeightDp>=800 && resources.configuration.fontScale<=1.3f)
            root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        else root.addView(ScrollView(this).apply { isFillViewport=true; addView(content) },LinearLayout.LayoutParams(-1,0,1f))
        when(page) {
            "home" -> home(content)
            "settings" -> settings(content)
            "permissions" -> permissions(content)
            "diagnostics" -> diagnostics(content)
            "size" -> size(content)
            "order" -> order(content)
            "backup" -> backup(content)
        }
        if(page=="home") {
            stateText=homeText("先开启记牌，再进入游戏房间",11f,muted).apply { gravity=Gravity.CENTER; maxLines=2 }
            root.addView(stateText,LinearLayout.LayoutParams(-1,dp(26)))
            captureButton=button("") { if(AppState.running) stopCapture() else startCapture() }
            captureButton!!.textSize=24f
            captureButton!!.setAutoSizeTextTypeUniformWithConfiguration(16,24,1,android.util.TypedValue.COMPLEX_UNIT_SP)
            val ring=FrameLayout(this).apply { background=bg(Color.rgb(48,45,32),36); setPadding(dp(6),dp(6),dp(6),dp(6)) }
            ring.addView(captureButton,FrameLayout.LayoutParams(-1,-1))
            root.addView(ring,LinearLayout.LayoutParams(-1,dp(66)).apply { bottomMargin=dp(14) })
            updateState()
        }
        if(page in listOf("home","settings")) {
            val nav=LinearLayout(this).apply { background=bg(Color.rgb(25,25,25),30); setPadding(dp(2),dp(2),dp(2),dp(2)) }
            listOf("home" to "记牌","settings" to "设置").forEach { (key,label) ->
                val item=LinearLayout(this).apply {
                    gravity=Gravity.CENTER; isSelected=page==key
                    if(isSelected) background=bg(Color.rgb(37,35,29),28)
                    setOnClickListener { navigate(key) }
                }
                item.addView(ImageView(this).apply {
                    setImageResource(if(key=="home") android.R.drawable.ic_menu_edit else android.R.drawable.ic_menu_preferences)
                    setColorFilter(if(page==key) gold else muted)
                },LinearLayout.LayoutParams(dp(18),dp(18)).apply { marginEnd=dp(7) })
                item.addView(homeText(label,15f,if(page==key) gold else muted))
                nav.addView(item,LinearLayout.LayoutParams(0,dp(46),1f))
            }; root.addView(nav)
        }
    }
    private fun navigate(next: String) {
        if(next in listOf("size","order") && page !in listOf("size","order")) { draft=store.load(); restorePage=page }
        if(next=="permissions" && page!="permissions") permissionsReturnPage=page
        page=next; render()
    }
    private fun home(c: LinearLayout) {
        fun section(): LinearLayout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
            .also { c.addView(it,LinearLayout.LayoutParams(-1,-2)) }
        fun spacer() { c.addView(View(this),LinearLayout.LayoutParams(1,dp(8),1f)) }
        fun heading(target: LinearLayout,label: String,note: String="") {
            val line=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
            line.addView(homeText(label,17f,Color.rgb(190,190,190)),LinearLayout.LayoutParams(0,dp(27),1f))
            line.addView(homeText(note,10f,Color.rgb(112,112,112)))
            target.addView(line)
        }
        val platform=selectedPlatform()
        val game=section(); heading(game,"1、选择游戏平台","1 款已适配")
        val gameRow=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        gameRow.addView(homeChip("＋",false,true,22f) { chooseGame() }.also { homeControls.add(it) },LinearLayout.LayoutParams(dp(44),dp(44)).apply { marginEnd=dp(9) })
        if(platform!=null) {
            val selected=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; background=chipBackground(true) }
            selected.addView(homeText(platform.label,19f,gold).apply {
                setPadding(dp(12),0,dp(7),0); setOnClickListener { chooseGame() }; homeControls.add(this)
            },LinearLayout.LayoutParams(-2,dp(44)))
            selected.addView(homeText("×",23f,Color.rgb(144,131,102)).apply {
                gravity=Gravity.CENTER; contentDescription="移除游戏平台"; homeControls.add(this)
                setOnClickListener { if(!AppState.running && !capturePending) { homePreferences.edit().putString("platform","").apply(); render() } }
            },LinearLayout.LayoutParams(dp(34),dp(44)))
            gameRow.addView(selected)
        } else gameRow.addView(homeText("请添加游戏平台",16f,muted))
        game.addView(gameRow); spacer()
        val play=section(); heading(play,"2、选择玩法","更多玩法待适配")
        HomeGameCatalog.plays.chunked(5).forEachIndexed { i,items ->
            play.addView(chipRow(items,"掼蛋"),LinearLayout.LayoutParams(-1,dp(38)).apply { if(i>0) topMargin=dp(7) })
        }
        val variant=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        variant.addView(homeText("细分玩法",14f,muted),LinearLayout.LayoutParams(dp(72),dp(32)))
        HomeGameCatalog.variants.forEach { label -> variant.addView(homeChip(label,label=="默认",label=="默认",16f),
            LinearLayout.LayoutParams(dp(66),dp(32)).apply { marginEnd=dp(8) }) }
        play.addView(variant,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) }); spacer()
        val people=section(); heading(people,"3、选择玩家人数")
        people.addView(chipRow(HomeGameCatalog.players.map { "${it}人" },"4人"),LinearLayout.LayoutParams(-1,dp(38))); spacer()
        val decks=section(); heading(decks,"4、几副牌","当前适配 4 人 · 2 副")
        decks.addView(chipRow(HomeGameCatalog.decks.map { "${it}副" },"2副"),LinearLayout.LayoutParams(-1,dp(38)))
        val cards=LinearLayout(this).apply { contentDescription="初始总牌数 108 张" }
        store.load().order.forEachIndexed { index,rank ->
            val cell=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
            cell.addView(homeText(rank.label,14f,gold).apply {
                gravity=Gravity.CENTER; background=bg(Color.rgb(87,79,55),4,true)
            },LinearLayout.LayoutParams(-1,dp(22)))
            cell.addView(homeText(rank.capacity.toString(),14f,Color.WHITE).apply {
                gravity=Gravity.CENTER; background=bg(Color.rgb(39,39,39),3)
            },LinearLayout.LayoutParams(-1,dp(23)))
            cards.addView(cell,LinearLayout.LayoutParams(0,-1,if(rank==Rank.TEN) 1.55f else 1f).apply { if(index<14) marginEnd=dp(2) })
        }
        decks.addView(cards,LinearLayout.LayoutParams(-1,dp(45)).apply { topMargin=dp(10) })
        decks.addView(homeText("初始总牌数 108 张",10f,Color.rgb(112,112,112)).apply { gravity=Gravity.END },LinearLayout.LayoutParams(-1,dp(17)))
        spacer()
        val sample=section()
        val sampleHeader=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        sampleHeader.addView(homeText("悬浮记牌条示例",12f,muted),LinearLayout.LayoutParams(0,dp(22),1f))
        sampleHeader.addView(homeText("预览 ›",12f,gold).apply { setOnClickListener { navigate("size") } })
        sample.addView(sampleHeader)
        val sampleController=OverlayController(this,preview=true)
        val sampleView=sampleController.previewView()
        val sampleBox=FrameLayout(this).apply { clipChildren=true; contentDescription="悬浮记牌条样式示例" }
        sampleBox.addView(sampleView)
        sampleBox.addOnLayoutChangeListener { _,l,_,r,_,_,_,_,_ ->
            val original=sampleView.layoutParams.width
            if(original>0) {
                val scale=minOf(1f,(r-l).toFloat()/original)
                sampleView.pivotX=0f; sampleView.pivotY=0f; sampleView.scaleX=scale; sampleView.scaleY=scale
            }
        }
        sample.addView(sampleBox,LinearLayout.LayoutParams(-1,dp(44)))
        updateState()
    }
    private fun homeText(value: String,size: Float,color: Int)=text(value,size,color).apply {
        setPadding(0,0,0,0); gravity=Gravity.CENTER_VERTICAL; maxLines=1
        ellipsize=TextUtils.TruncateAt.END; includeFontPadding=false
    }
    private fun chipBackground(selected: Boolean)=GradientDrawable().apply {
        setColor(if(selected) Color.rgb(37,34,27) else Color.TRANSPARENT); cornerRadius=dp(12).toFloat()
        setStroke(dp(1).coerceAtLeast(1),if(selected) Color.rgb(231,203,129) else Color.rgb(62,62,62))
    }
    private fun homeChip(label: String,selected: Boolean,available: Boolean,size: Float=18f,action: (() -> Unit)?=null)=
        homeText(label,size,if(selected) gold else if(available) Color.rgb(208,208,208) else Color.rgb(116,116,116)).apply {
            gravity=Gravity.CENTER; background=chipBackground(selected); isSelected=selected; isEnabled=available
            setAutoSizeTextTypeUniformWithConfiguration(12,size.toInt(),1,android.util.TypedValue.COMPLEX_UNIT_SP)
            if(action!=null) { isClickable=true; setOnClickListener { action() } }
        }
    private fun chipRow(labels: List<String>,selected: String)=LinearLayout(this).apply {
        labels.forEachIndexed { index,label -> addView(homeChip(label,label==selected,label==selected),
            LinearLayout.LayoutParams(0,-1,1f).apply { if(index<labels.lastIndex) marginEnd=dp(7) }) }
    }
    private fun chooseGame() {
        if(AppState.running || capturePending) return
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(12),dp(20),0) }
        val search=EditText(this).apply { hint="搜索游戏平台"; textSize=16f; setTextColor(Color.WHITE); typeface=appFont; setSingleLine() }
        val choices=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        body.addView(search); body.addView(choices)
        val dialog=AlertDialog.Builder(this).setTitle("选择游戏平台").setView(body).setNegativeButton("完成",null).create()
        fun list() {
            choices.removeAllViews()
            HomeGameCatalog.search(search.text.toString()).forEach { platform ->
                choices.addView(homeText(platform.label+if(platform.profile==null) " · 待适配" else "",17f,if(platform.profile==null) muted else gold).apply {
                    setPadding(dp(4),dp(14),dp(4),dp(14)); isEnabled=platform.profile!=null
                    if(isEnabled) setOnClickListener {
                        homePreferences.edit().putString("platform",platform.id).apply(); dialog.dismiss(); render()
                    }
                })
            }
            if(choices.childCount==0) choices.addView(homeText("没有找到匹配的游戏",15f,muted),LinearLayout.LayoutParams(-1,dp(64)))
        }
        search.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int)=Unit
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { list() }
            override fun afterTextChanged(s: Editable?)=Unit
        })
        list(); dialog.show(); dialog.window?.setBackgroundDrawable(bg(Color.rgb(32,32,32),20))
    }
    private fun settings(c: LinearLayout) {
        val d=store.load()
        add(c,text("显示设置",15f,muted))
        add(c,row("三家出牌显示",historyLabel(d)) { selectHistoryMode() })
        switch(c,"显示同花顺标识",d.showStraightFlush) { store.save(store.load().copy(showStraightFlush=it)); changed() }
        add(c,text("亮色：尚未排除；暗色：当前计算范围已排除。未识别级牌时只判断天然同花顺，确认逢人配后计入变牌；花色不清楚时保留可能。",13f,muted))
        switch(c,"显示级牌遮挡提示",d.showGradeOcclusionTip) { store.save(store.load().copy(showGradeOcclusionTip=it)); changed() }
        add(c,text("记牌条挡住级牌区域时提醒。一键挪开或手动拖动；忽略后本次记牌不再提醒。",13f,muted))
        switch(c,"显示重置按钮",d.showReset) { store.save(store.load().copy(showReset=it)); changed() }
        switch(c,"双击记牌条隐藏",d.doubleTapHide) { store.save(store.load().copy(doubleTapHide=it)); changed() }
        add(c,text("双击开启后移除隐藏按钮；隐藏后只显示恢复按钮。",13f,muted))
        add(c,row("尺寸调整","›") { navigate("size") }); add(c,row("牌序调整","›") { navigate("order") })
        add(c,text("应用设置",15f,muted))
        add(c,row("权限设置","›") { navigate("permissions") })
        add(c,updateRow())
        add(c,row("配置备份","›") { navigate("backup") })
        add(c,row("牌局与诊断","›") { navigate("diagnostics") })
        add(c,row("关于记牌器",BuildConfig.VERSION_NAME) {
            AlertDialog.Builder(this).setTitle("记牌器").setMessage("个人本地使用，无账号、会员或收费。牌面识别在本机完成；当前适配版尚未完成连续真机牌局验收。\n\n版本：${BuildConfig.VERSION_NAME}") .setPositiveButton("知道了",null).show()
        })
    }
    private fun historyLabel(config: DisplayConfig)=if(!config.showHistory) "隐藏" else if(config.showHistoryDetails) "数量和详情" else "仅数量"
    private fun selectHistoryMode() {
        val config=store.load()
        val selected=if(!config.showHistory) 0 else if(!config.showHistoryDetails) 1 else 2
        AlertDialog.Builder(this).setTitle("三家出牌显示")
            .setSingleChoiceItems(arrayOf("隐藏","仅数量","数量和详情"),selected) { dialog,which ->
                store.save(store.load().copy(showHistory=which!=0,showHistoryDetails=which==2))
                changed(); dialog.dismiss(); render()
            }.setNegativeButton("取消",null).show()
    }
    private fun diagnostics(c: LinearLayout) {
        add(c,text("用于本机回看与排查记牌问题。",14f,muted))
        add(c,row("最近牌局","查看") { RoundRepository(this).use { db -> AlertDialog.Builder(this).setTitle("最近牌局").setMessage(db.summary()).setPositiveButton("关闭",null).show() } })
        add(c,row("导出牌局诊断","仅保存到本机") {
            if(!AppState.running) diagnosticResult.launch("记牌器牌局诊断.json") else Toast.makeText(this,"请先正常完成本局，再停止记牌导出",Toast.LENGTH_LONG).show()
        })
        add(c,row("导出完整测试日志","日志和诊断画面") {
            if(!AppState.running) archiveResult.launch("记牌器测试日志.zip") else Toast.makeText(this,"请先正常完成本局，再停止记牌导出",Toast.LENGTH_LONG).show()
        })
        switch(c,"保存本机识别诊断画面",getSharedPreferences("jpq-diagnostics",MODE_PRIVATE).getBoolean("enabled",false)) {
            getSharedPreferences("jpq-diagnostics",MODE_PRIVATE).edit().putBoolean("enabled",it).apply()
        }
        add(c,text("默认关闭。画面最多100 MiB，会话日志停止后保留64 MiB，采集探针保留256 MiB；仅存本机。",13f,muted))
        add(c,row("清理牌局和诊断","清理") {
            AlertDialog.Builder(this).setMessage("清理已保存的本机记录和诊断画面？正在记牌时不可清理。").setPositiveButton("清理") { _,_ ->
                Thread {
                    val cleared=runCatching { DiagnosticStorage.clear(applicationContext) }.isSuccess
                    runOnUiThread { if(!isDestroyed) Toast.makeText(this,if(cleared) "本机牌局和诊断已清理" else "请先停止记牌，等待记录保存后重试",Toast.LENGTH_LONG).show() }
                }.start()
            }.setNegativeButton("取消",null).show()
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
        val details=draft.showHistory && draft.showHistoryDetails
        slider(groups[0],"总宽度",if(details) draft.widthScale else draft.countWidthScale,.8f,1.35f) {
            draft=if(details) draft.copy(widthScale=it) else draft.copy(countWidthScale=it)
        }
        slider(groups[0],"左右比例",draft.gridRatio,.3f,.6f,details) { draft=draft.copy(gridRatio=it) }
        slider(groups[1],"牌格高度",draft.gridHeight,.85f,1.3f) { draft=draft.copy(gridHeight=it) }
        slider(groups[1],"记录行高",draft.recordHeight,.85f,1.3f,draft.showHistory) { draft=draft.copy(recordHeight=it) }
        slider(groups[2],"标题字号",draft.headerText,.8f,1.35f) { draft=draft.copy(headerText=it) }
        slider(groups[2],"数值字号",draft.valueText,.8f,1.35f) { draft=draft.copy(valueText=it) }
        slider(groups[3],"计数字号",draft.counterText,.8f,1.35f,draft.showHistory) { draft=draft.copy(counterText=it) }
        slider(groups[3],"详情字号",draft.detailText,.8f,1.35f,details) { draft=draft.copy(detailText=it) }
        saveBar(c)
    }
    private fun slider(c: LinearLayout,label: String,value: Float,min: Float,max: Float,enabled: Boolean=true,change: (Float)->Unit) {
        val title=text("$label  ${(value*100).toInt()}%",17f,if(enabled) Color.WHITE else muted); add(c,title)
        val seek=GoldSlider(this,min,max,value) { next -> change(next); title.text="$label  ${(next*100).toInt()}%"; preview?.refresh() }.apply { isEnabled=enabled }
        add(c,seek)
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
            draft=if(page=="order") draft.copy(order=guandanOrder) else defaults.copy(showHistory=draft.showHistory,showHistoryDetails=draft.showHistoryDetails,showStraightFlush=draft.showStraightFlush,showGradeOcclusionTip=draft.showGradeOcclusionTip,showReset=draft.showReset,doubleTapHide=draft.doubleTapHide,order=draft.order,x=draft.x,y=draft.y,positionIsScreenRelative=draft.positionIsScreenRelative)
            render()
        })
        add(c,button("保存并退出") { store.save(draft.validate()); changed(); page=restorePage; render() },56)
        add(c,row("退出","不保存") { leave() })
    }
    private fun updateRow(): View {
        val row=LinearLayout(this).apply {
            tag="application-update-row"; orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            background=bg(Color.rgb(38,38,38)); setPadding(dp(14),dp(10),dp(14),dp(10))
        }
        val labels=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        labels.addView(text("应用更新",17f).apply { setPadding(0,0,0,dp(4)) })
        updateStatusText=text("",12f,muted).apply {
            setPadding(0,0,0,0); maxLines=2; ellipsize=TextUtils.TruncateAt.END
        }
        labels.addView(updateStatusText)
        row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        updateActionButton=button("") { performUpdateAction() }.apply {
            tag="application-update-action"
            textSize=14f; setPadding(dp(10),0,dp(10),0)
            setAutoSizeTextTypeUniformWithConfiguration(10,14,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        row.addView(updateActionButton,LinearLayout.LayoutParams(dp(108),dp(48)).apply { marginStart=dp(12) })
        refreshUpdateRow(); scheduleUpdateRefresh()
        Thread({
            runCatching { updates.refreshCachedState() }
            runOnUiThread { if(!isFinishing && !isDestroyed) refreshUpdateRow() }
        },"jpq-update-cache-check").start()
        return row
    }
    private fun scheduleUpdateRefresh() {
        updateHandler.removeCallbacks(updateTick)
        if(updateUiResumed && page=="settings") updateHandler.post(updateTick)
    }
    private fun refreshUpdateRow() {
        if(page!="settings" || updateActionButton==null) return
        val model=updates.rowState(updateRequested)
        updateActionButton!!.apply {
            val label=if(updateConsent?.isShowing==true) "等待确认" else model.button
            if(text.toString()!=label) text=label
            isEnabled=model.enabled && updateConsent?.isShowing!=true
            alpha=if(isEnabled) 1f else .5f
            val description="应用更新：$label"
            if(contentDescription?.toString()!=description) contentDescription=description
        }
        val status=when {
            AppState.running && !BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE -> "停止记牌后可更新"
            model.action==UpdateRowAction.INSTALL -> "更新已下载，安装需系统确认"
            else -> updates.status()
        }
        updateStatusText?.let { if(it.text.toString()!=status) it.text=status }
    }
    private fun performUpdateAction() {
        val model=updates.rowState(updateRequested)
        if(!model.enabled || updateConsent!=null || isFinishing || isDestroyed) return
        when(model.action) {
            UpdateRowAction.CHECK -> runUpdateAction(UpdateRowAction.CHECK)
            UpdateRowAction.DOWNLOAD -> if(updates.mobileDownloadConfirmationRequired()) {
                updateConsent=AlertDialog.Builder(this).setMessage("当前不是 Wi-Fi，下载更新可能使用移动流量。")
                    .setPositiveButton("下载") { _,_ -> runUpdateAction(UpdateRowAction.DOWNLOAD) }
                    .setNegativeButton("取消",null).create().also { dialog ->
                        dialog.setOnDismissListener { updateConsent=null; refreshUpdateRow() }; dialog.show()
                    }
                refreshUpdateRow()
            } else runUpdateAction(UpdateRowAction.DOWNLOAD)
            UpdateRowAction.INSTALL -> {
                updates.install(this) { if(!isFinishing && !isDestroyed) refreshUpdateRow() }
                refreshUpdateRow()
            }
            UpdateRowAction.NONE -> Unit
        }
    }
    private fun runUpdateAction(action: UpdateRowAction) {
        if(updateRequested!=null) return
        updateRequested=if(action==UpdateRowAction.DOWNLOAD) UpdateOperation.DOWNLOADING else UpdateOperation.CHECKING
        refreshUpdateRow()
        Thread({
            try { if(action==UpdateRowAction.DOWNLOAD) updates.download(true) else updates.check(true) }
            finally { runOnUiThread {
                updateRequested=null
                if(!isFinishing && !isDestroyed) refreshUpdateRow()
            } }
        },"jpq-update-action").start()
    }
    private fun backup(c: LinearLayout) {
        add(c,text("只备份显示配置，不含权限、录屏、牌局或密钥。",15f,muted))
        add(c,button("导出配置") { exportResult.launch("记牌器配置.json") },56)
        add(c,button("导入配置") { importResult.launch(arrayOf("application/json")) },56)
    }
    private fun changed() { if(AppState.running) startService(Intent(this,CaptureService::class.java).setAction("CONFIG")) }
    private fun startCapture() {
        if(AppState.running) { stopCapture(); return }
        if(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE && Build.VERSION.SDK_INT<37) {
            Toast.makeText(this,"本轮记牌器BETA需要 Android 17，请继续使用正式版",Toast.LENGTH_LONG).show(); return
        }
        if(selectedPlatform()?.profile==null) { chooseGame(); return }
        if(capturePending) return
        if(!Settings.canDrawOverlays(this)) { navigate("permissions"); Toast.makeText(this,"请先开启悬浮窗权限",Toast.LENGTH_LONG).show(); return }
        capturePending=true
        updateState()
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            notificationResult.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else requestProjection()
    }
    private fun requestProjection() {
        val manager=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try { captureResult.launch(ProjectionConsentRequest.create(manager)) }
        catch(error: Exception) {
            capturePending=false; updateState()
            android.util.Log.w("JPQCapture","consent_unavailable",error)
            Toast.makeText(this,if(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE)
                "系统暂不支持本轮单应用授权，请继续使用正式版" else "暂时无法打开屏幕共享授权",Toast.LENGTH_LONG).show()
        }
    }
    private fun stopCapture() {
        if(stopPending) return
        stopPending=true; updateState()
        startService(Intent(this,CaptureService::class.java).setAction("STOP"))
    }
    private fun updateState() {
        refreshUpdateRow()
        if(!AppState.running) stopPending=false
        stateText?.text=when {
            page!="home" -> AppState.message
            !AppState.running -> "先开启记牌，再进入游戏房间"
            !AppState.recognitionReady -> "正在准备识别，稍后自动开始"
            else -> AppState.message
        }
        homeState?.text=if(AppState.running) "记牌中" else "本地记牌"
        homeState?.setTextColor(if(AppState.running) gold else muted)
        homeControls.forEach { it.isEnabled=!AppState.running && !capturePending && !stopPending }
        captureButton?.apply {
            text=when { stopPending -> "正在停止…"; AppState.running -> "停止记牌"; capturePending -> "等待屏幕共享授权"; else -> "开始记牌" }
            isEnabled=!capturePending && !stopPending && (AppState.running || selectedPlatform()?.profile!=null)
        }
    }
    private fun leave() {
        page=when(page) { "size","order" -> restorePage; "permissions" -> permissionsReturnPage; "settings" -> "home"; else -> "settings" }
        render()
    }
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
