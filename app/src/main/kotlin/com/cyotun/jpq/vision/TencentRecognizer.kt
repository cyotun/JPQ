package com.cyotun.jpq.vision

import android.content.Context
import android.graphics.*
import android.media.Image
import android.os.SystemClock
import com.cyotun.jpq.*
import com.cyotun.jpq.core.*
import org.json.JSONObject
import org.json.JSONArray

/** Bundled CPU detector and bundled Chinese OCR. No screenshot upload. */
class TencentRecognizer(private val context: Context): FrameRecognizer {
    private val diagnostics=context.getSharedPreferences("jpq-diagnostics",Context.MODE_PRIVATE)
    private val diagnosticFolder=java.io.File(context.filesDir,"diagnostic")
    private var lastDiagnostic=0L
    private val detector=VisionRuntime.getDetector(context)
    private val ocr=SceneOcr()
    private val trace=SessionTrace(context)
    private val evidenceIo=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val evidenceBusy=java.util.concurrent.atomic.AtomicBoolean(false)
    private var reusableBitmap: Bitmap?=null
    private var pixelBuffer=IntArray(0)
    private var rowBuffer=ByteArray(0)
    private var frameNumber=0L
    private val repository=RoundRepository(context)
    private var tracedEvents=emptyMap<String,PlayEvent>()
    private val observer=RoundObserver(AppState.ledger) { snapshot ->
        runCatching { repository.save(AppState.ledger) }.onFailure { android.util.Log.w("JPQTrace","round_checkpoint_unavailable") }
        val events=snapshot.plays.values.flatten()
        val changes=events.filter { tracedEvents[it.eventId]!=it }
        tracedEvents=events.associateBy { it.eventId }
        android.util.Log.i("JPQRound","phase=${snapshot.phase} remaining=${snapshot.remaining?.values?.sum()} plays=${listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).map { snapshot.played(it) }}")
        trace.write(JSONObject().put("type","ledger").put("elapsedMs",SystemClock.elapsedRealtime()).put("roundId",snapshot.roundId)
            .put("phase",snapshot.phase.name).put("reason",snapshot.reason).put("remaining",snapshot.remaining?.mapKeys { it.key.name })
            .put("played",JSONObject(snapshot.plays.mapKeys { it.key.name }.mapValues { it.value.sumOf { p -> p.cards.size } }))
            .put("changes",JSONArray(changes.map { p -> JSONObject().put("id",p.eventId).put("sequence",p.sequence).put("seat",p.seat.name).put("cards",JSONArray(p.cards.map { it.colorKey() })) })))
        AppState.message=when(snapshot.phase) {
            RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED -> "正在记牌"
            RoundPhase.UNAVAILABLE -> snapshot.reason ?: "等待下一局"
            else -> "进入游戏房间后，会自动开始记牌"
        }; AppState.notifyChanged()
    }
    override val ready=true
    private var ambiguousSince: Long?=null
    private var closed=false
    private var diagnosticFrameAllowed=false
    private fun observe(frame: TableFrame) {
        observer.observe(frame)
        val state=observer.diagnosticStatus()
        trace.write(JSONObject().put("type","observer").put("elapsedMs",frame.timeMs).put("frame",frameNumber)
            .put("armed",state.armed).put("locked",state.locked).put("baselineWindow",state.baselineWindow).put("tribute",state.tribute)
            .put("handVotes",state.handVotes).put("playVotes",JSONObject(state.playVotes.mapKeys { it.key.name }))
            .put("pendingPlays",state.pendingPlays).put("sequence",state.sequence))
    }
    override fun observe(image: Image) {
        if(closed) return
        if(image.width<=image.height || AppState.adjusting) {
            val now=SystemClock.elapsedRealtime()
            trace.write(JSONObject().put("type","frame").put("elapsedMs",now).put("width",image.width).put("height",image.height)
                .put("scene","UNKNOWN").put("reason",if(AppState.adjusting) "adjustment_occlusion" else "portrait_non_game"))
            observe(TableFrame(now,TableScene.UNKNOWN,null)); return
        }
        val start=SystemClock.elapsedRealtime(); frameNumber++
        diagnosticFrameAllowed=false
        val plane=image.planes[0]
        if(rowBuffer.size!=plane.rowStride) rowBuffer=ByteArray(plane.rowStride)
        if(pixelBuffer.size!=image.width*image.height) pixelBuffer=IntArray(image.width*image.height)
        val row=rowBuffer; val pixels=pixelBuffer
        val data=plane.buffer.duplicate()
        for(y in 0 until image.height) {
            data.position(y*plane.rowStride); val length=minOf(row.size,data.remaining()); data.get(row,0,length)
            for(x in 0 until image.width) {
                val p=x*plane.pixelStride
                pixels[y*image.width+x]=Color.argb(255,row[p].toInt() and 255,row[p+1].toInt() and 255,row[p+2].toInt() and 255)
            }
        }
        if(reusableBitmap?.width!=image.width || reusableBitmap?.height!=image.height) { reusableBitmap?.recycle(); reusableBitmap=Bitmap.createBitmap(image.width,image.height,Bitmap.Config.ARGB_8888) }
        val bitmap=reusableBitmap!!.apply { setPixels(pixels,0,image.width,0,0,image.width,image.height) }
        val maskingCanvas=Canvas(bitmap); val maskingPaint=Paint().apply { color=Color.rgb(38,38,38) }
        listOfNotNull(AppState.barMask,AppState.adjustmentMask).forEach { mask ->
            if(mask.screenWidth<=0 || mask.screenHeight<=0 || (mask.screenWidth>mask.screenHeight)!=(image.width>image.height)) return@forEach
            val sx=image.width.toFloat()/mask.screenWidth; val sy=image.height.toFloat()/mask.screenHeight
            maskingCanvas.drawRect(RectF(mask.rectangle.left*sx,mask.rectangle.top*sy,mask.rectangle.right*sx,mask.rectangle.bottom*sy),maskingPaint)
        }
        try {
            val converted=SystemClock.elapsedRealtime()
            val detections=detector.detect(bitmap).filter { it.box.centerY()>.13f && it.box.centerX() in .10f.. .88f && it.box.width()<.12f && it.box.height()>.02f }
            ocr.request(bitmap,converted)
            val text=ocr.snapshot(image.width,image.height,SystemClock.elapsedRealtime())
            val tableLines=text?.lines.orEmpty().filter { (it.box?.centerY() ?: 0)>image.height*.12f }
            val all=tableLines.joinToString(" ") { it.text.replace(" ","") }
            val scene=when {
                listOf("进贡","还贡","还牌","贡牌").any { all.contains(it) } -> TableScene.TRIBUTE
                tableLines.count { it.text in setOf("加倍","不加倍","超级加倍") }>=2 -> TableScene.DEALING
                all.contains("队友手牌") || all.contains("对家手牌") -> TableScene.TEAMMATE
                listOf("再来一局","继续游戏","本局结束","本局结算").any { all.contains(it) } -> TableScene.RESULT
                listOf("快速开始","私人房","经典","组队").count { all.contains(it) }>=3 && detections.none { it.box.centerY()>.55f } -> TableScene.LOBBY
                detections.isNotEmpty() || all.contains("一键理") || (all.contains("理牌") && all.contains("聊天")) -> TableScene.TABLE
                else -> TableScene.UNKNOWN
            }
            val glyphs=detections.map { p -> PlayGlyph(p.card,p.confidence,GlyphBox(p.box.left,p.box.top,p.box.right,p.box.bottom)) }
            val partition=partitionGuandanHand(glyphs)
            val hand=partition.hand.map { it.card }
            diagnosticFrameAllowed=scene==TableScene.LOBBY || (scene!=TableScene.UNKNOWN && hand.isNotEmpty())
            val passing=tableLines.filter { it.text.contains("不出") || it.text.contains("不要") }.mapNotNull { l ->
                val b=l.box ?: return@mapNotNull null
                when { b.centerY()<image.height*.3 -> Seat.OPPOSITE; b.centerX()<image.width*.4 -> Seat.PREVIOUS; b.centerX()>image.width*.65 -> Seat.NEXT; else -> null }
            }.toSet()
            val removed=AppState.ledger.currentHand().toMutableList()
            val subset=hand.all { removed.remove(it) }
            val ownership=assignPlayGroups(partition.table.filter { it.box.cy<.55f },passing,if(subset && scene!=TableScene.TEAMMATE && !AppState.adjusting) removed else emptyList(),scene==TableScene.TEAMMATE)
            trace.write(JSONObject().put("type","frame").put("frame",frameNumber).put("imageTimestampNs",image.timestamp).put("elapsedMs",start)
                .put("width",image.width).put("height",image.height).put("scene",scene.name).put("backend",detector.backend)
                .put("sourceWidth",AppState.sourceWidth).put("sourceHeight",AppState.sourceHeight)
                .put("convertMs",converted-start).put("preprocessMs",detector.preprocessMs).put("inferenceMs",detector.inferenceMs).put("decodeMs",detector.decodeMs)
                .put("ocrAgeMs",if(text==null) JSONObject.NULL else SystemClock.elapsedRealtime()-text.timeMs)
                .put("hand",JSONArray(hand.map { it.colorKey() })).put("groups",JSONObject(ownership.seats.mapKeys { it.key.name }.mapValues { (_,p) -> p.map { it.card.colorKey() } }))
                .put("ambiguous",ownership.ambiguous.size).put("showHistory",ConfigStore(context).load().showHistory))
            if(diagnostics.getBoolean("enabled",false) && SystemClock.elapsedRealtime()-lastDiagnostic>=2000) {
                android.util.Log.i("JPQVision","scene=$scene hand=${hand.map { it.colorKey() }} passing=$passing groups=${ownership.seats.mapValues { (_,p) -> p.map { it.card.colorKey() } }} ambiguous=${ownership.ambiguous.size}")
            }
            if(ownership.ambiguous.isNotEmpty() && scene !in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.TRIBUTE)) {
                val now=SystemClock.elapsedRealtime(); if(ambiguousSince==null) ambiguousSince=now
                if(now-ambiguousSince!!>1500 && AppState.ledger.snapshot().remaining!=null) observer.interrupted("出牌归属无法完整确认，等待下一局")
                observe(TableFrame(SystemClock.elapsedRealtime(),scene,if(scene==TableScene.TEAMMATE || AppState.adjusting) null else hand))
                return
            }
            ambiguousSince=null
            val observed=ownership.seats.mapValues { (_,cards) ->
                val sorted=cards.sortedBy { it.box.cx }
                val effect=tableLines.any { line ->
                    val b=line.box
                    b!=null && b.centerY()<image.height*.55f && listOf("炸弹","同花顺","钢板","飞机").any { line.text.contains(it) }
                }
                // Animation text is positive occlusion evidence, never proof of an empty seat.
                ObservedPlay(sorted.map { it.card },sorted.minOfOrNull { it.confidence } ?: 1f,sorted,
                    regionObserved=sorted.isNotEmpty() || !effect)
            }
            observe(TableFrame(SystemClock.elapsedRealtime(),scene,if(scene==TableScene.TEAMMATE || AppState.adjusting) null else hand,observed))
        } finally {
            val now=SystemClock.elapsedRealtime()
            if(diagnosticFrameAllowed && diagnostics.getBoolean("enabled",false) && now-lastDiagnostic>=2000 && evidenceBusy.compareAndSet(false,true)) {
                lastDiagnostic=now
                var copy: Bitmap?=null
                try {
                    copy=bitmap.copy(Bitmap.Config.ARGB_8888,false)
                    val owned=copy
                    evidenceIo.execute {
                        try { runCatching {
                            diagnosticFolder.mkdirs()
                            val output=java.io.File(diagnosticFolder,"${System.currentTimeMillis()}.jpg")
                            output.outputStream().use { owned.compress(Bitmap.CompressFormat.JPEG,75,it) }
                            val files=diagnosticFolder.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
                            var size=files.sumOf { it.length() }
                            for(file in files) { if(size<=100L*1024*1024) break; val bytes=file.length(); if(file.delete()) size-=bytes }
                        } } finally { owned.recycle(); evidenceBusy.set(false) }
                    }
                } catch(error: Exception) { copy?.recycle(); evidenceBusy.set(false); android.util.Log.w("JPQTrace","diagnostic_frame_unavailable") }
            }
        }
    }
    override fun close() {
        if(closed) return
        closed=true
        runCatching { repository.save(AppState.ledger) }; runCatching { repository.close() }
        runCatching { ocr.close() }; runCatching { reusableBitmap?.recycle() }; reusableBitmap=null
        runCatching { trace.close() }; evidenceIo.shutdown()
    }
}
