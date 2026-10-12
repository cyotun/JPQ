package com.cyotun.jpq.vision

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import com.cyotun.jpq.*
import com.cyotun.jpq.core.*
import org.json.JSONObject
import org.json.JSONArray

/** Bundled CPU detector and bundled Chinese OCR. No screenshot upload. */
class TencentRecognizer(private val context: Context,private val sessionAlive: ()->Boolean = { true },
                        private val visibilityEpoch: ()->Long = { 0L },private val sessionGuard: Any=Any()): FrameRecognizer {
    private val diagnostics=context.getSharedPreferences("jpq-diagnostics",Context.MODE_PRIVATE)
    private val diagnosticFolder=java.io.File(context.filesDir,"diagnostic")
    private var lastDiagnostic=0L
    private val detector=VisionRuntime.getDetector(context)
    private val ocr=SceneOcr()
    private val trace=SessionTrace(context)
    private val evidenceIo=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val evidenceBusy=java.util.concurrent.atomic.AtomicBoolean(false)
    private val emptyGuard=EmptyRegionGuard()
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
            .put("phase",snapshot.phase.name).put("reason",snapshot.reason).put("remaining",snapshot.remaining?.let { JSONObject(it.mapKeys { entry -> entry.key.name }) } ?: JSONObject.NULL)
            .put("played",JSONObject(snapshot.plays.mapKeys { it.key.name }.mapValues { it.value.sumOf { p -> p.cards.size } }))
            .put("changes",JSONArray(changes.map { p -> JSONObject().put("id",p.eventId).put("sequence",p.sequence).put("seat",p.seat.name).put("cards",JSONArray(p.cards.map { it.colorKey() })) })))
        if(frameCurrent()) AppState.message=when(snapshot.phase) {
            RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED -> "正在记牌"
            RoundPhase.UNAVAILABLE -> snapshot.reason ?: "等待下一局"
            else -> "进入游戏房间后，会自动开始记牌"
        }
        if(frameCurrent()) AppState.notifyChanged()
    }
    override val ready=true
    private var ambiguousSince: Long?=null
    private var overlayOcclusionSince: Long?=null
    private var closed=false
    private var diagnosticFrameAllowed=false
    private var activeVisibilityEpoch=0L
    private fun frameCurrent()=sessionAlive() && activeVisibilityEpoch==visibilityEpoch()
    private fun interrupted(reason: String) = synchronized(sessionGuard) {
        if(frameCurrent()) observer.interrupted(reason)
    }
    private fun observe(frame: TableFrame) {
      synchronized(sessionGuard) {
        if(!frameCurrent()) return
        observer.observe(frame)
        val state=observer.diagnosticStatus()
        trace.write(JSONObject().put("type","observer").put("elapsedMs",frame.timeMs).put("frame",frameNumber)
            .put("armed",state.armed).put("locked",state.locked).put("baselineWindow",state.baselineWindow).put("tribute",state.tribute)
            .put("handVotes",state.handVotes).put("playVotes",JSONObject(state.playVotes.mapKeys { it.key.name }))
            .put("pendingPlays",state.pendingPlays).put("sequence",state.sequence))
      }
    }
    override fun observe(captured: CapturedFrame) {
        activeVisibilityEpoch=captured.visibilityEpoch
        if(closed || !frameCurrent()) return
        if(captured.bitmap==null || !captured.completeDisplay) {
            val now=captured.capturedElapsedMs
            trace.write(JSONObject().put("type","frame").put("elapsedMs",now).put("width",captured.width).put("height",captured.height)
                .put("scene","UNKNOWN").put("reason",when {
                    captured.adjusting -> "adjustment_occlusion"
                    captured.bitmap==null -> "portrait_non_game"
                    else -> "partial_capture_crop"
                }))
            observe(TableFrame(now,TableScene.UNKNOWN,null)); return
        }
        val start=captured.capturedElapsedMs; frameNumber++
        diagnosticFrameAllowed=false
        val bitmap=captured.bitmap
        val normalizedMasks=captured.masks
        try {
            val converted=SystemClock.elapsedRealtime()
            val rawDetections=detector.detect(bitmap)
            val detections=rawDetections.filter { it.box.centerY()>.13f && it.box.centerX() in .10f.. .88f && it.box.width()<.12f && it.box.height()>.02f }
            captured.probeFolder?.let { saveProbeInput(it,bitmap,captured.imageTimestampNs,rawDetections,detections.size) }
            ocr.request(bitmap,start,activeVisibilityEpoch)
            val text=ocr.snapshot(captured.width,captured.height,start,activeVisibilityEpoch)
            val tableLines=text?.lines.orEmpty().filter { (it.box?.centerY() ?: 0)>captured.height*.12f }
            val hints=text?.lines.orEmpty().map { line -> SceneHint(line.text,line.box?.let { b -> GlyphBox(b.left.toFloat()/captured.width,b.top.toFloat()/captured.height,b.right.toFloat()/captured.width,b.bottom.toFloat()/captured.height) }) }
            val scene=tencentScene(hints,detections.isNotEmpty(),detections.any { it.box.centerY()>.55f })
            val glyphs=detections.map { p -> PlayGlyph(p.card,p.confidence,GlyphBox(p.box.left,p.box.top,p.box.right,p.box.bottom)) }
            val partition=partitionGuandanHand(glyphs)
            val hand=partition.hand.map { it.card }
            diagnosticFrameAllowed=scene==TableScene.LOBBY || (scene!=TableScene.UNKNOWN && hand.isNotEmpty())
            val passing=tableLines.filter { it.text.contains("不出") || it.text.contains("不要") }.mapNotNull { l ->
                val b=l.box ?: return@mapNotNull null
                when { b.centerY()<captured.height*.3 -> Seat.OPPOSITE; b.centerX()<captured.width*.4 -> Seat.PREVIOUS; b.centerX()>captured.width*.65 -> Seat.NEXT; else -> null }
            }.toSet()
            val removed=AppState.ledger.currentHand().toMutableList()
            val subset=hand.all { removed.remove(it) }
            val ownership=assignPlayGroups(partition.table.filter { it.box.cy<.55f },passing,if(subset && scene!=TableScene.TEAMMATE && !captured.adjusting) removed else emptyList(),scene==TableScene.TEAMMATE)
            val evidenceStart=SystemClock.elapsedRealtime()
            val emptyEvidence=emptyGuard.evaluate(bitmap,scene,hand.size,ownership.seats,AppState.ledger.snapshot().roundId,normalizedMasks)
            fun rounded(value: Float)=kotlin.math.round(value*10000f)/10000f
            trace.write(JSONObject().put("type","frame").put("frame",frameNumber).put("imageTimestampNs",captured.imageTimestampNs).put("elapsedMs",start)
                .put("width",captured.width).put("height",captured.height).put("scene",scene.name).put("backend",detector.backend)
                .put("sourceWidth",captured.sourceWidth).put("sourceHeight",captured.sourceHeight)
                .put("convertMs",captured.preparedElapsedMs-start).put("queueMs",converted-captured.preparedElapsedMs)
                .put("preprocessMs",detector.preprocessMs).put("inferenceMs",detector.inferenceMs).put("decodeMs",detector.decodeMs)
                .put("rawDetectionCount",rawDetections.size).put("filteredDetectionCount",detections.size).put("maxClassScore",detector.maxClassScore)
                .put("modelInputUnchanged",detector.cacheHit).put("inferenceCount",detector.inferenceCount)
                .put("ocrAgeMs",if(text==null) JSONObject.NULL else SystemClock.elapsedRealtime()-text.timeMs)
                .put("emptyEvidenceMs",SystemClock.elapsedRealtime()-evidenceStart)
                .put("overlayMasks",JSONArray(normalizedMasks.map { b -> JSONArray(listOf(rounded(b.left),rounded(b.top),rounded(b.right),rounded(b.bottom))) }))
                .put("emptyEvidence",JSONObject(emptyEvidence.mapKeys { it.key.name }.mapValues { (_,e) -> JSONObject().put("observed",e.observed).put("reason",e.reason).put("tableRatio",e.tableRatio ?: JSONObject.NULL) }))
                .put("hand",JSONArray(hand.map { it.colorKey() })).put("groups",JSONObject(ownership.seats.mapKeys { it.key.name }.mapValues { (_,p) -> p.map { it.card.colorKey() } }))
                .put("confidences",JSONObject(ownership.seats.mapKeys { it.key.name }.mapValues { (_,p) -> p.minOfOrNull { it.confidence } ?: 1f }))
                .put("glyphs",JSONArray(glyphs.map { p -> JSONArray(listOf(p.card.colorKey(),rounded(p.confidence),rounded(p.box.left),rounded(p.box.top),rounded(p.box.right),rounded(p.box.bottom))) }))
                .put("ambiguous",ownership.ambiguous.size).put("showHistory",ConfigStore(context).load().showHistory)
                .put("sceneHints",JSONArray(hints.filter { it.text in setOf("末游","四游","头游","二游","三游","进贡","还贡","队友手牌","对家手牌","加倍","不加倍","超级加倍") }.map { it.text })))
            if(diagnostics.getBoolean("enabled",false) && SystemClock.elapsedRealtime()-lastDiagnostic>=2000) {
                android.util.Log.i("JPQVision","scene=$scene hand=${hand.map { it.colorKey() }} passing=$passing groups=${ownership.seats.mapValues { (_,p) -> p.map { it.card.colorKey() } }} ambiguous=${ownership.ambiguous.size}")
            }
            val overlayOccluded=emptyEvidence.values.any { it.reason=="overlay_occlusion" } &&
                scene !in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.TRIBUTE)
            if(overlayOccluded) {
                val now=start
                if(overlayOcclusionSince==null) overlayOcclusionSince=now
                if(now-overlayOcclusionSince!!>1500 && AppState.ledger.snapshot().remaining!=null)
                    interrupted("悬浮条遮住出牌区，请移回顶部并等待下一局")
                // No partial presentation or empty-region vote may survive a masked table.
                observe(TableFrame(now,TableScene.UNKNOWN,null)); return
            }
            overlayOcclusionSince=null
            if(ownership.ambiguous.isNotEmpty() && scene !in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.TRIBUTE)) {
                val now=start; if(ambiguousSince==null) ambiguousSince=now
                if(now-ambiguousSince!!>1500 && AppState.ledger.snapshot().remaining!=null) interrupted("出牌归属无法完整确认，等待下一局")
                observe(TableFrame(start,scene,if(scene==TableScene.TEAMMATE || captured.adjusting) null else hand))
                return
            }
            ambiguousSince=null
            val observed=ownership.seats.mapValues { (seat,cards) ->
                val sorted=cards.sortedBy { it.box.cx }
                val effect=tableLines.any { line ->
                    val b=line.box
                    b!=null && b.centerY()<captured.height*.55f && listOf("炸弹","同花顺","钢板","飞机").any { line.text.contains(it) }
                }
                // Animation text is positive occlusion evidence, never proof of an empty seat.
                ObservedPlay(sorted.map { it.card },sorted.minOfOrNull { it.confidence } ?: 1f,sorted,
                    regionObserved=emptyEvidence.getValue(seat).observed && (sorted.isNotEmpty() || !effect))
            }
            observe(TableFrame(start,scene,if(scene==TableScene.TEAMMATE || captured.adjusting) null else hand,observed,tencentSelfFinished(hints)))
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
    private fun saveProbeInput(folder: java.io.File,bitmap: Bitmap,imageTimestampNs: Long,
                               detections: List<CardDetection>,filteredCount: Int) {
        var masked: Bitmap?=null
        var modelInput: Bitmap?=null
        try {
            val ownedMasked=checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888,false))
            masked=ownedMasked
            val ownedInput=detector.copyInputForProbe()
            modelInput=ownedInput
            val data=JSONObject().put("frame",frameNumber).put("imageTimestampNs",imageTimestampNs)
                .put("rawDetectionCount",detections.size).put("filteredDetectionCount",filteredCount)
                .put("maxClassScore",detector.maxClassScore)
                .put("detections",JSONArray(detections.map { p -> JSONObject().put("card",p.card.colorKey()).put("score",p.confidence)
                    .put("box",JSONArray(listOf(p.box.left,p.box.top,p.box.right,p.box.bottom))) }))
            evidenceIo.execute {
                try {
                    check(folder.isDirectory || folder.mkdirs() || folder.isDirectory)
                    java.io.File(folder,"masked.png").outputStream().use { check(ownedMasked.compress(Bitmap.CompressFormat.PNG,100,it)) }
                    java.io.File(folder,"model-input.png").outputStream().use { check(ownedInput.compress(Bitmap.CompressFormat.PNG,100,it)) }
                    java.io.File(folder,"model-detections.json").writeText(data.toString(2),Charsets.UTF_8)
                } catch(error: Exception) {
                    android.util.Log.w("JPQCaptureProbe","model_input_save_failed",error)
                } finally { ownedMasked.recycle(); ownedInput.recycle() }
            }
        } catch(error: Exception) {
            masked?.recycle(); modelInput?.recycle()
            android.util.Log.w("JPQCaptureProbe","model_input_snapshot_failed",error)
        }
    }
    override fun close() {
        if(closed) return
        closed=true
        runCatching { repository.save(AppState.ledger) }; runCatching { repository.close() }
        runCatching { ocr.close() }
        runCatching { trace.close() }; evidenceIo.shutdown()
    }
}
