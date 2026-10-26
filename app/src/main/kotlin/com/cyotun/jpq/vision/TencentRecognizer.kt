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
                        private val visibilityEpoch: ()->Long = { 0L },private val sessionGuard: Any=Any(),
                        private val onPipelineStage: (String)->Unit = {},
                        private val frameAvailable: ()->Boolean = { true },
                        private val onStartupInputRecovery: ()->Unit = {},
                        private val onResumeInputRecovery: (TaskResumeRecoveryPolicy.Request)->Unit = {}): FrameRecognizer {
    private val diagnostics=context.getSharedPreferences("jpq-diagnostics",Context.MODE_PRIVATE)
    private val sessionCaptureScope=AppState.captureScope
    private val startupInputRecovery=TaskStartupRecoveryPolicy()
    private val resumeInputRecovery=TaskResumeRecoveryPolicy()
    private var resumeRoundId: String?=null
    private var resumeGapPending=false
    private val diagnosticFolder=java.io.File(context.filesDir,"diagnostic")
    private var lastDiagnostic=0L
    private val detector=VisionRuntime.getDetector(context)
    private val detectionPipeline=context.getSharedPreferences("jpq-runtime",Context.MODE_PRIVATE).let { prefs ->
        DetectionPipelinePolicy.select(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE,
            prefs.getString("referencePipelineFingerprint",null),
            prefs.getString("referencePipelineVersion",null),
            android.os.Build.FINGERPRINT,ReferenceCardDetector.PIPELINE_VERSION)
    }
    private val referenceDetector=if(detectionPipeline==DetectionPipelinePolicy.REFERENCE)
        ReferenceCardDetector(context,detector) else null
    private var referenceEpoch: Long?=null
    private val ownExitTracker=OwnExitPresentationTracker()
    private var referenceRoundId: String?=null
    private val ocr=SceneOcr()
    private val trace=SessionTrace(context,detectionPipeline)
    private val straightFlush=StraightFlushAnalyzer(context,sessionGuard,
        { sessionAlive() && frameAvailable() && !closed },visibilityEpoch,report={},
        remainingPool={ snapshot,epoch -> AppState.remainingPoolFor(snapshot,epoch) },
        lazyReport={ trace.writeIfEnabled(it) })
    private val evidenceIo=EvidenceExecutor("jpq-evidence-image")
    private val evidenceBusy=java.util.concurrent.atomic.AtomicBoolean(false)
    private val emptyGuard=EmptyRegionGuard()
    private val singleCardReader=SingleCardIdentityReader()
    private var frameNumber=0L
    private val repository=AsyncRoundStore(context)
    private var tracedEvents=emptyMap<String,PlayEvent>()
    private val observer=RoundObserver(AppState.ledger) { snapshot ->
        straightFlush.onLedgerChanged(snapshot)
        if(snapshot.roundId!=referenceRoundId) {
            referenceDetector?.reset("round_boundary")
            referenceRoundId=snapshot.roundId
        }
        runCatching { repository.save(AppState.ledger) }.onFailure { android.util.Log.w("JPQTrace","round_checkpoint_unavailable") }
        val events=snapshot.plays.values.flatten()
        val changes=events.filter { tracedEvents[it.eventId]!=it }
        tracedEvents=events.associateBy { it.eventId }
        android.util.Log.i("JPQRound","phase=${snapshot.phase} remaining=${snapshot.remaining?.values?.sum()} plays=${listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).map { snapshot.played(it) }}")
        trace.writeIfEnabled { JSONObject().put("type","ledger").put("elapsedMs",SystemClock.elapsedRealtime()).put("roundId",snapshot.roundId)
            .put("phase",snapshot.phase.name).put("reason",snapshot.reason).put("remaining",snapshot.remaining?.let { JSONObject(it.mapKeys { entry -> entry.key.name }) } ?: JSONObject.NULL)
            .put("played",JSONObject(snapshot.plays.mapKeys { it.key.name }.mapValues { it.value.sumOf { p -> p.cards.size } }))
            .put("changes",JSONArray(changes.map { p -> JSONObject().put("id",p.eventId).put("sequence",p.sequence).put("seat",p.seat.name).put("cards",JSONArray(p.cards.map { it.colorKey() })) })) }
        if(frameCurrent()) AppState.message=when(snapshot.phase) {
            RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED -> "正在记牌"
            RoundPhase.RECOVERING -> snapshot.reason ?: "正在重新核对牌面"
            RoundPhase.UNAVAILABLE -> snapshot.reason ?: "等待下一局"
            else -> "进入游戏房间后，会自动开始记牌"
        }
        if(frameCurrent()) AppState.notifyChanged()
    }
    override val ready=true
    private var closed=false
    private var diagnosticFrameAllowed=false
    private var activeVisibilityEpoch=0L
    private fun frameCurrent()=sessionAlive() && frameAvailable() && activeVisibilityEpoch==visibilityEpoch()
    override fun startupCaptureSurface(stage: String,generation: Long,recoveryId: Int,epoch: Long,imageTimestampNs: Long?) {
        if(!BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE || sessionCaptureScope!=CaptureScope.APP_TASK) return
        trace.writeIfEnabled { JSONObject().put("type","task_startup_surface").put("stage",stage)
            .put("elapsedMs",SystemClock.elapsedRealtime()).put("generation",generation)
            .put("recoveryId",recoveryId).put("visibilityEpoch",epoch)
            .put("imageTimestampNs",imageTimestampNs ?: JSONObject.NULL).put("captureScope",sessionCaptureScope.name) }
    }
    override fun observationGap(timeMs: Long,reason: String,inputDiscontinuity: Boolean) = synchronized(sessionGuard) {
        if(!closed && sessionAlive()) {
            // The gap announces an epoch change; the previous frame's epoch is
            // intentionally not used as permission to retain its exact counts.
            observer.incomplete(reason,timeMs,immediate=true)
            if(inputDiscontinuity && BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE && sessionCaptureScope==CaptureScope.APP_TASK) {
                val snapshot=AppState.ledger.snapshot()
                resumeRoundId=snapshot.roundId.takeIf { snapshot.phase==RoundPhase.RECOVERING }
                // Start the bounded repair window at the first visible source,
                // not while the user may keep the game in the background.
                resumeGapPending=resumeRoundId!=null
            }
            AppState.notifyChanged()
        }
    }
    private fun observe(frame: TableFrame) {
      synchronized(sessionGuard) {
        if(!frameCurrent()) return
        observer.observe(frame)
        val state=observer.diagnosticStatus()
        trace.writeIfEnabled { JSONObject().put("type","observer").put("elapsedMs",frame.timeMs).put("frame",frameNumber)
            .put("armed",state.armed).put("locked",state.locked).put("baselineWindow",state.baselineWindow).put("tribute",state.tribute)
            .put("recovering",state.recovering)
            .put("pendingReviewSeats",org.json.JSONArray(state.pendingReviewSeats.map { it.name }))
            .put("rejectedPlayCandidates",state.rejectedPlayCandidates)
            .put("dealingChoicesObserved",frame.dealingChoicesObserved)
            .put("handVotes",state.handVotes).put("playVotes",JSONObject(state.playVotes.mapKeys { it.key.name }))
            .put("pendingPlays",state.pendingPlays).put("sequence",state.sequence) }
      }
    }
    override fun observe(captured: CapturedFrame) {
        val epochChanged=captured.visibilityEpoch!=activeVisibilityEpoch
        if(captured.visibilityEpoch!=activeVisibilityEpoch && BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE &&
            sessionCaptureScope==CaptureScope.APP_TASK)
            startupInputRecovery.observe(captured.capturedElapsedMs,eligible=false,suspicious=false)
        activeVisibilityEpoch=captured.visibilityEpoch
        if(closed || !frameCurrent()) return
        synchronized(sessionGuard) {
            if(!frameCurrent()) return
            if((resumeGapPending || epochChanged) && resumeRoundId!=null && BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE &&
                sessionCaptureScope==CaptureScope.APP_TASK) {
                val snapshot=AppState.ledger.snapshot()
                if(snapshot.roundId==resumeRoundId) resumeInputRecovery.onDiscontinuity(SystemClock.elapsedRealtime(),
                    activeVisibilityEpoch,snapshot.phase,snapshot.roundId,sourceFenceMs=captured.capturedElapsedMs)
                resumeGapPending=false
            }
        }
        if(captured.bitmap==null || !captured.completeDisplay) {
            if(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE && sessionCaptureScope==CaptureScope.APP_TASK)
                startupInputRecovery.observe(captured.capturedElapsedMs,eligible=false,suspicious=false)
            onPipelineStage(if(captured.bitmap==null) "null_bitmap" else "partial_capture")
            observeResumeInput(captured,if(captured.adjusting || captured.bitmap!=null) TaskResumeRecoveryPolicy.Content.OTHER
                else TaskResumeRecoveryPolicy.Content.PORTRAIT_OR_NULL)
            val now=captured.capturedElapsedMs
            trace.writeIfEnabled { JSONObject().put("type","frame").put("elapsedMs",now).put("width",captured.width).put("height",captured.height)
                .put("scene","UNKNOWN").put("reason",when {
                    captured.adjusting -> "adjustment_occlusion"
                    captured.bitmap==null -> "portrait_non_game"
                    else -> "partial_capture_crop"
                }) }
            observe(TableFrame(now,TableScene.UNKNOWN,null,captureGap=true)); return
        }
        val start=captured.capturedElapsedMs; frameNumber++
        diagnosticFrameAllowed=false
        val bitmap=captured.bitmap
        val normalizedMasks=captured.masks
        var diagnosticScene=TableScene.UNKNOWN
        var diagnosticIntervalMs=2000L
        var diagnosticRawCount=0
        var diagnosticHandCount=0
        try {
            val converted=SystemClock.elapsedRealtime()
            referenceDetector?.let { prepared ->
                if(referenceEpoch!=activeVisibilityEpoch) { prepared.reset("visibility_epoch"); referenceEpoch=activeVisibilityEpoch }
            }
            onPipelineStage("detect_start")
            val rawDetections=referenceDetector?.detect(bitmap,start) ?: detector.detect(bitmap)
            diagnosticRawCount=rawDetections.size
            onPipelineStage("detect_end")
            val detections=rawDetections.filter { it.box.centerY()>.13f && it.box.centerX() in .10f.. .88f && it.box.width()<.12f && it.box.height()>.02f }
            captured.probeFolder?.let { saveProbeInput(it,bitmap,captured.imageTimestampNs,rawDetections,detections.size) }
            val round=AppState.ledger.snapshot()
            val trackingPhase=AppState.ledger.phaseBeforeRecovery()
            ocr.request(bitmap,start,activeVisibilityEpoch,captureRemainingCounts=trackingPhase in
                setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED,RoundPhase.RECOVERING),
                masks=normalizedMasks)
            val text=ocr.snapshot(captured.width,captured.height,start,activeVisibilityEpoch)
            val tableLines=text?.lines.orEmpty().filter { (it.box?.centerY() ?: 0)>captured.height*.12f }
            val hints=text?.lines.orEmpty().map { line -> SceneHint(line.text,line.box?.let { b -> GlyphBox(b.left.toFloat()/captured.width,b.top.toFloat()/captured.height,b.right.toFloat()/captured.width,b.bottom.toFloat()/captured.height) }) }
            val detectedScene=tencentScene(hints,detections.isNotEmpty(),detections.any { it.box.centerY()>.55f })
            val remainingHandCounts=TencentRemainingCountSource.read(bitmap,start,text,normalizedMasks)
            // Own completion is a round state, not a label that must be re-read
            // on every frame. Teammate hand pixels never become our hand again.
            val scene=continueTeammateScene(detectedScene,trackingPhase)
            diagnosticScene=scene
            if(!captured.adjusting && frameCurrent())
                straightFlush.observeGrade(bitmap,start,activeVisibilityEpoch,scene,text,normalizedMasks)
            ownExitTracker.prepare(start,scene,round.roundId)
            val glyphs=detections.map { p -> PlayGlyph(p.card,p.confidence,GlyphBox(p.box.left,p.box.top,p.box.right,p.box.bottom)) }
            val partition=partitionGuandanHand(glyphs)
            val hand=partition.hand.map { it.card }
            observeResumeInput(captured,if(captured.adjusting) TaskResumeRecoveryPolicy.Content.OTHER
                else TaskResumeRecoveryPolicy.contentFor(detectedScene,glyphs.any { it.confidence>=.65f }))
            val startupRecoveryEligible=BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE &&
                sessionCaptureScope==CaptureScope.APP_TASK && round.phase==RoundPhase.WAITING &&
                round.roundId==null && scene==TableScene.UNKNOWN && hand.isEmpty() && frameCurrent()
            if(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE && sessionCaptureScope==CaptureScope.APP_TASK &&
                !startupInputRecovery.exhausted) {
                val blackFraction=if(startupRecoveryEligible) startupBlackFraction(bitmap) else 0f
                if(startupInputRecovery.observe(start,startupRecoveryEligible,
                        TaskStartupRecoveryPolicy.isSuspiciousBlackFraction(blackFraction))) {
                    trace.writeIfEnabled { JSONObject().put("type","task_startup_input_recovery")
                        .put("elapsedMs",start).put("frame",frameNumber).put("imageTimestampNs",captured.imageTimestampNs)
                        .put("blackFraction",blackFraction).put("captureScope",sessionCaptureScope.name)
                        .put("phase",round.phase.name).put("handCount",hand.size)
                        .put("reason",startupInputRecovery.lastRequestReason?.name) }
                    onStartupInputRecovery()
                }
            }
            val handObserved=TencentFrameVisibility.handObserved(partition.hand,normalizedMasks)
            if(!captured.adjusting && handObserved && scene!=TableScene.TEAMMATE && partition.hand.all { it.confidence>=.65f })
                ownExitTracker.observeOwnHand(start,trackingPhase,AppState.ledger.currentHand(),hand)
            if(scene==TableScene.TEAMMATE && !captured.adjusting)
                ownExitTracker.teammateTransition(start,trackingPhase,AppState.ledger.currentHand(),partition.table.filter { it.box.cy<.55f },
                    observedHand=hand.takeIf { handObserved && partition.hand.all { it.confidence>=.65f } })
            val ownExitPresentation=ownExitTracker.prepare(start,scene,round.roundId)
            // A zero-detection/effect frame inside a tracked round is exactly
            // where recovery needs source pixels. Do not discard that evidence
            // merely because neither hand nor scene could be recognized.
            val diagnosticPlan=FrameDiagnosticPolicy.decide(
                scene==TableScene.LOBBY || (scene!=TableScene.UNKNOWN && hand.isNotEmpty()) ||
                    (round.roundId!=null && trackingPhase in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED)),
                BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE,sessionCaptureScope,scene)
            diagnosticFrameAllowed=diagnosticPlan.eligible
            diagnosticIntervalMs=diagnosticPlan.minimumIntervalMs
            diagnosticHandCount=hand.size
            val passing=tableLines.filter { it.text.contains("不出") || it.text.contains("不要") }.mapNotNull { l ->
                val b=l.box ?: return@mapNotNull null
                when { b.centerY()<captured.height*.3 -> Seat.OPPOSITE; b.centerX()<captured.width*.4 -> Seat.PREVIOUS; b.centerX()>captured.width*.65 -> Seat.NEXT; else -> null }
            }.toSet()
            val selfFinished=tencentSelfFinished(hints)
            val finishedSeats=confirmedFinishedSeats(round.copy(phase=trackingPhase))
            val removed=AppState.ledger.currentHand().toMutableList()
            val subset=hand.all { removed.remove(it) }
            val assignedOwnership=assignPlayGroups(partition.table.filter { it.box.cy<.55f },passing,if(subset && handObserved && scene!=TableScene.TEAMMATE && !captured.adjusting) removed else emptyList(),scene==TableScene.TEAMMATE,
                if(scene==TableScene.TEAMMATE) ownExitPresentation else emptyList(),finishedSeats,ownExitTracker.blocksCentralFallback())
            val ownership=synchronized(sessionGuard) {
                if(frameCurrent() && !captured.adjusting) observer.reassociatePresentationFragments(assignedOwnership,start) else assignedOwnership
            }
            val selfGlyphs=ownership.seats.getValue(Seat.SELF)
            val evidenceStart=SystemClock.elapsedRealtime()
            val emptyEvidence=emptyGuard.evaluate(bitmap,scene,hand.size,ownership.seats,AppState.ledger.snapshot().roundId,normalizedMasks,
                partition.hand,start,captured.adjusting || ownership.ambiguous.isNotEmpty())
            if(!captured.adjusting && handObserved && emptyEvidence.getValue(Seat.SELF).observed)
                ownExitTracker.observe(start,trackingPhase,AppState.ledger.currentHand(),hand,selfGlyphs)
            ownExitTracker.emptySurface(emptyEvidence.getValue(Seat.SELF).reason=="table_surface_visible" &&
                emptyEvidence.getValue(Seat.SELF).observed,selfGlyphs.isNotEmpty(),
                ownership.ambiguous.any { it.box.cy in .285f.. .48f && it.box.cx in .32f.. .70f })
            val qualifiedTeammateHand=hand.takeIf { scene==TableScene.TEAMMATE && !captured.adjusting && handObserved &&
                partition.hand.all { it.confidence>=.65f } }
            val effect=tableLines.any { line ->
                val b=line.box
                b!=null && b.centerY()<captured.height*.55f && listOf("炸弹","同花顺","钢板","飞机").any { line.text.contains(it) }
            }
            val singleIdentityStart=SystemClock.elapsedRealtime()
            val observed=ownership.seats.mapValues { (seat,cards) ->
                val sorted=cards.sortedBy { it.box.cx }
                val play=ObservedPlay(sorted.map { it.card },sorted.minOfOrNull { it.confidence } ?: 1f,sorted,
                    regionObserved=emptyEvidence.getValue(seat).observed && (sorted.isNotEmpty() || !effect))
                if(seat==Seat.SELF || captured.adjusting || ownership.ambiguous.isNotEmpty()) play
                else play.copy(singleCardIdentity=singleCardReader.read(bitmap,play,start,normalizedMasks))
            }
            val singleIdentityMs=SystemClock.elapsedRealtime()-singleIdentityStart
            val ambiguous=ownership.ambiguous.isNotEmpty() && scene !in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.TRIBUTE)
            val openingChoiceSourceTimeMs=if(detectedScene==TableScene.DEALING && !captured.adjusting)
                TencentFrameVisibility.openingChoiceSourceTimeMs(text,start,captured.width,captured.height,activeVisibilityEpoch,normalizedMasks)
                else null
            val lastPlaceSeat=if(captured.adjusting) null else TencentFrameVisibility.observedLastPlaceSeat(
                text,start,captured.width,captured.height,activeVisibilityEpoch,scene,normalizedMasks)
            val observation=TencentFrameVisibility.qualify(TableFrame(start,scene,
                if(scene==TableScene.TEAMMATE || captured.adjusting) null else hand,observed,selfFinished,
                incompleteReason=when {
                    captured.adjusting -> "正在调整悬浮条，等待完整牌面"
                    ambiguous -> "出牌归属待核对，正在重新识别"
                    else -> null
                },ambiguousPresentation=ambiguous,
                teammateHand=qualifiedTeammateHand,teammateHandConfidence=partition.hand.minOfOrNull { it.confidence } ?: 1f,
                dealingChoicesObserved=detectedScene==TableScene.DEALING,remainingHandCounts=remainingHandCounts,
                openingChoiceSourceTimeMs=openingChoiceSourceTimeMs,
                observedPasses=if(captured.adjusting) emptyMap() else TencentFrameVisibility.observedPasses(
                    text,start,captured.width,captured.height,activeVisibilityEpoch,scene,ownership.seats,normalizedMasks),
                observedEarlyFinishes=if(captured.adjusting) emptyMap() else TencentFrameVisibility.observedEarlyFinishes(
                    text,start,captured.width,captured.height,activeVisibilityEpoch,scene,normalizedMasks),
                observedLastPlaceSeat=lastPlaceSeat,
                observedLastPlaceSourceTimeMs=text?.timeMs.takeIf { lastPlaceSeat!=null }),
                partition.hand,emptyEvidence,normalizedMasks)
            fun rounded(value: Float)=kotlin.math.round(value*10000f)/10000f
            trace.writeIfEnabled { JSONObject().put("type","frame").put("frame",frameNumber).put("imageTimestampNs",captured.imageTimestampNs).put("elapsedMs",start)
                .put("visibilityEpoch",activeVisibilityEpoch)
                .put("width",captured.width).put("height",captured.height).put("scene",scene.name).put("backend",referenceDetector?.backend ?: detector.backend)
                .put("detectedScene",detectedScene.name)
                .put("dealingChoicesObserved",detectedScene==TableScene.DEALING).put("adjusting",captured.adjusting)
                .put("openingChoiceSourceTimeMs",openingChoiceSourceTimeMs ?: JSONObject.NULL)
                .put("observedPasses",JSONObject(observation.observedPasses.mapKeys { it.key.name }))
                .put("observedEarlyFinishes",JSONObject(observation.observedEarlyFinishes.mapKeys { it.key.name }))
                .put("sceneSourceAgeMs",text?.let { start-it.timeMs } ?: JSONObject.NULL)
                .put("sceneSourceHints",JSONArray(hints.map { hint ->
                    JSONObject().put("text",hint.text).put("box",hint.box?.let { box ->
                        JSONArray(listOf(rounded(box.left),rounded(box.top),rounded(box.right),rounded(box.bottom)))
                    } ?: JSONObject.NULL)
                }))
                .put("sceneSourceMasks",JSONArray(text?.sourceMasks.orEmpty().map { b ->
                    JSONArray(listOf(rounded(b.left),rounded(b.top),rounded(b.right),rounded(b.bottom)))
                }))
                .put("handObserved",handObserved && !captured.adjusting)
                .put("observationReason",observation.incompleteReason ?: JSONObject.NULL)
                .put("localizedOverlaySeats",JSONArray(observation.localizedOverlaySeats.map { it.name }))
                .put("regionObserved",JSONObject(observation.plays.mapKeys { it.key.name }.mapValues { it.value?.regionObserved==true }))
                .put("remainingHandCounts",JSONObject(remainingHandCounts.mapKeys { it.key.name }.mapValues { (_,reading) ->
                    JSONObject().put("count",reading.count).put("sourceTimeMs",reading.sourceTimeMs)
                }))
                .put("singleCardIdentities",JSONObject(observation.plays.mapNotNull { (seat,play) ->
                    play?.singleCardIdentity?.let { reading -> seat.name to JSONObject()
                        .put("card",reading.card.colorKey()).put("suit",reading.card.suit.name)
                        .put("similarity",reading.confidence).put("margin",reading.margin)
                        .put("sourceTimeMs",reading.sourceTimeMs) }
                }.toMap()))
                .put("singleIdentityMs",singleIdentityMs)
                .put("sourceWidth",captured.sourceWidth).put("sourceHeight",captured.sourceHeight)
                .put("convertMs",captured.preparedElapsedMs-start).put("queueMs",converted-captured.preparedElapsedMs)
                .put("preprocessMs",referenceDetector?.preprocessMs ?: detector.preprocessMs).put("inferenceMs",referenceDetector?.inferenceMs ?: detector.inferenceMs).put("decodeMs",referenceDetector?.decodeMs ?: detector.decodeMs)
                .put("detectorProfile",referenceDetector?.selectedProfile?.name ?: DetectorProfile.FULL.name)
                .put("nativeMs",referenceDetector?.nativeMs ?: 0.0).put("packMs",referenceDetector?.packMs ?: 0.0)
                .put("coverageGuardMs",referenceDetector?.coverageGuardMs ?: 0L)
                .put("detectorFallback",referenceDetector?.fallbackReason ?: JSONObject.NULL)
                .put("nativeRegionCount",referenceDetector?.nativeRegionCount ?: 0).put("packedTileCount",referenceDetector?.packedTileCount ?: 0)
                .put("rawDetectionCount",rawDetections.size).put("filteredDetectionCount",detections.size).put("maxClassScore",referenceDetector?.maxClassScore ?: detector.maxClassScore)
                .put("modelInputUnchanged",referenceDetector?.cacheHit ?: detector.cacheHit).put("inferenceCount",referenceDetector?.inferenceCount ?: detector.inferenceCount)
                .put("ocrAgeMs",if(text==null) JSONObject.NULL else SystemClock.elapsedRealtime()-text.timeMs)
                .put("emptyEvidenceMs",SystemClock.elapsedRealtime()-evidenceStart)
                .put("overlayMasks",JSONArray(normalizedMasks.map { b -> JSONArray(listOf(rounded(b.left),rounded(b.top),rounded(b.right),rounded(b.bottom))) }))
                .put("emptyEvidence",JSONObject(emptyEvidence.mapKeys { it.key.name }.mapValues { (_,e) -> JSONObject().put("observed",e.observed).put("reason",e.reason).put("tableRatio",e.tableRatio ?: JSONObject.NULL) }))
                .put("hand",JSONArray(hand.map { it.colorKey() })).put("groups",JSONObject(ownership.seats.mapKeys { it.key.name }.mapValues { (_,p) -> p.map { it.card.colorKey() } }))
                .put("confidences",JSONObject(ownership.seats.mapKeys { it.key.name }.mapValues { (_,p) -> p.minOfOrNull { it.confidence } ?: 1f }))
                .put("glyphs",JSONArray(glyphs.map { p -> JSONArray(listOf(p.card.colorKey(),rounded(p.confidence),rounded(p.box.left),rounded(p.box.top),rounded(p.box.right),rounded(p.box.bottom))) }))
                .put("ambiguous",ownership.ambiguous.size).put("showHistory",ConfigStore(context).load().showHistory)
                .put("selfFinished",selfFinished).put("passing",JSONArray(passing.map { it.name }))
                .put("finishedSeats",JSONArray(finishedSeats.map { it.name }))
                .put("sceneHints",JSONArray(hints.filter { it.text in setOf("末游","四游","头游","二游","三游","进贡","还贡","队友手牌","对家手牌","加倍","不加倍","超级加倍") }.map { it.text }))
                .put("sceneHintBoxes",JSONArray(hints.filter { it.text in setOf("末游","四游","头游","二游","三游","进贡","还贡","队友手牌","对家手牌","加倍","不加倍","超级加倍","不出","不要") }.map { hint ->
                    JSONObject().put("text",hint.text).put("box",hint.box?.let { box ->
                        JSONArray(listOf(rounded(box.left),rounded(box.top),rounded(box.right),rounded(box.bottom)))
                    } ?: JSONObject.NULL)
                })) }
            if(diagnostics.getBoolean("enabled",false) && SystemClock.elapsedRealtime()-lastDiagnostic>=2000) {
                android.util.Log.i("JPQVision","scene=$scene hand=${hand.map { it.colorKey() }} passing=$passing groups=${ownership.seats.mapValues { (_,p) -> p.map { it.card.colorKey() } }} ambiguous=${ownership.ambiguous.size}")
            }
            if(confirmedRoundFinished(round) && scene !in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.DEALING,TableScene.TRIBUTE)) {
                // The opponents' double-finish ends the match before both losing
                // hands are revealed in central table rows. Such reveals may have
                // no play owner and must not reach the ambiguity timeout.
                observe(TableFrame(start,scene,null)); return
            }
            // Each obscured seat abstains through regionObserved. Other independently
            // visible regions, the complete hand and remaining-count badges survive.
            observe(observation)
            if(ambiguous) return
            synchronized(sessionGuard) {
                if(!captured.adjusting && frameCurrent()) {
                    val snapshot=AppState.ledger.snapshot()
                    val handConfident=partition.hand.isNotEmpty() && partition.hand.all { glyph -> glyph.confidence>=.65f }
                    val handQualified=scene==TableScene.TEAMMATE && handConfident && handObserved &&
                        AppState.ledger.phaseBeforeRecovery()==RoundPhase.SELF_FINISHED && !observer.diagnosticStatus().recovering
                    val qualifiedHand=if(handQualified) partition.hand else null
                    val previousPool=AppState.remainingPoolFor(snapshot,activeVisibilityEpoch)
                    AppState.observeTeammatePool(start,activeVisibilityEpoch,snapshot,qualifiedHand)
                    val nextPool=AppState.remainingPoolFor(snapshot,activeVisibilityEpoch)
                    val poolChanged=if(previousPool==null) nextPool!=null else nextPool==null || !previousPool.sameHandVersion(nextPool)
                    if(poolChanged) straightFlush.onLedgerChanged(snapshot)
                }
            }
            if(!captured.adjusting && frameCurrent() && !observer.diagnosticStatus().recovering) straightFlush.offer(bitmap,start,activeVisibilityEpoch,
                AppState.ledger.snapshot(),AppState.ledger.initialHand(),
                partition.hand,ownership.seats,normalizedMasks,text)
        } finally {
            val now=SystemClock.elapsedRealtime()
            if(diagnosticFrameAllowed && diagnostics.getBoolean("enabled",false) && now-lastDiagnostic>=diagnosticIntervalMs && evidenceBusy.compareAndSet(false,true)) {
                lastDiagnostic=now
                var copy: Bitmap?=null
                try {
                    copy=bitmap.copy(Bitmap.Config.ARGB_8888,false)
                    val owned=copy
                    // Bind the saved pixels to their actual capture input. File
                    // write time alone cannot identify a fast animation frame.
                    val metadata=JSONObject().put("sessionId",trace.id).put("frame",frameNumber)
                        .put("sourceElapsedMs",start).put("imageTimestampNs",captured.imageTimestampNs)
                        .put("visibilityEpoch",activeVisibilityEpoch)
                        .put("width",bitmap.width).put("height",bitmap.height)
                        .put("captureScope",sessionCaptureScope.name).put("scene",diagnosticScene.name)
                        .put("rawDetectionCount",diagnosticRawCount).put("handCount",diagnosticHandCount)
                        .put("minimumIntervalMs",diagnosticIntervalMs)
                        .put("overlayMasks",JSONArray(normalizedMasks.map { b ->
                            JSONArray(listOf(b.left,b.top,b.right,b.bottom)) }))
                        .put("scope","masked_local_capture_input")
                    evidenceIo.execute {
                        try { runCatching {
                            diagnosticFolder.mkdirs()
                            val output=java.io.File(diagnosticFolder,"${System.currentTimeMillis()}.jpg")
                            output.outputStream().use { check(owned.compress(Bitmap.CompressFormat.JPEG,75,it)) }
                            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(output.readBytes())
                                .joinToString("") { "%02x".format(it.toInt() and 255) }
                            metadata.put("imageFile",output.name).put("imageSha256",digest)
                            java.io.File(diagnosticFolder,"${output.nameWithoutExtension}.json").writeText(metadata.toString(2))
                            val files=diagnosticFolder.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
                            var size=files.sumOf { it.length() }
                            for(file in files.filter { it.extension=="jpg" }) {
                                if(size<=100L*1024*1024) break
                                val bytes=file.length()
                                if(file.delete()) {
                                    size-=bytes
                                    val sidecar=java.io.File(diagnosticFolder,"${file.nameWithoutExtension}.json")
                                    val sidecarBytes=sidecar.length(); if(sidecar.delete()) size-=sidecarBytes
                                }
                            }
                        } } finally { owned.recycle(); evidenceBusy.set(false) }
                    }
                } catch(error: Exception) { copy?.recycle(); evidenceBusy.set(false); android.util.Log.w("JPQTrace","diagnostic_frame_unavailable") }
            }
        }
    }
    /** Sparse sampling only before a round binds, never in the active recognition loop. */
    private fun startupBlackFraction(bitmap: Bitmap): Float {
        var black=0
        for(y in 0 until 16) for(x in 0 until 32) {
            val pixel=bitmap.getPixel(((x+.5f)*bitmap.width/32).toInt().coerceAtMost(bitmap.width-1),
                ((y+.5f)*bitmap.height/16).toInt().coerceAtMost(bitmap.height-1))
            if((pixel shr 16 and 255)<=8 && (pixel shr 8 and 255)<=8 && (pixel and 255)<=8) black++
        }
        return black/512f
    }
    private fun observeResumeInput(captured: CapturedFrame,content: TaskResumeRecoveryPolicy.Content) = synchronized(sessionGuard) {
        if(!BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE || sessionCaptureScope!=CaptureScope.APP_TASK || !frameCurrent()) return@synchronized
        val snapshot=AppState.ledger.snapshot()
        val request=resumeInputRecovery.observe(SystemClock.elapsedRealtime(),captured.capturedElapsedMs,
            activeVisibilityEpoch,snapshot.phase,snapshot.roundId,content)
        if(content==TaskResumeRecoveryPolicy.Content.GAME_CONTENT || snapshot.roundId!=resumeRoundId ||
            snapshot.phase!=RoundPhase.RECOVERING) { resumeRoundId=null; resumeGapPending=false }
        if(request!=null) {
            trace.writeIfEnabled { JSONObject().put("type","task_resume_input_recovery")
                .put("elapsedMs",SystemClock.elapsedRealtime()).put("sourceTimeMs",captured.capturedElapsedMs)
                .put("roundId",request.roundId).put("visibilityEpoch",request.visibilityEpoch)
                .put("sequence",request.sequence).put("content",content.name) }
            onResumeInputRecovery(request)
        }
    }
    /** Queued surface repair must still belong to this recognizer and old checkpoint. */
    fun takeResumeInputRecovery(request: TaskResumeRecoveryPolicy.Request): Boolean = synchronized(sessionGuard) {
        if(closed || !sessionAlive() || !frameAvailable() || sessionCaptureScope!=CaptureScope.APP_TASK ||
            !BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE) return@synchronized false
        val snapshot=AppState.ledger.snapshot()
        resumeInputRecovery.takeRequest(request,SystemClock.elapsedRealtime(),visibilityEpoch(),snapshot.phase,snapshot.roundId)
    }
    private fun saveProbeInput(folder: java.io.File,bitmap: Bitmap,imageTimestampNs: Long,
                               detections: List<CardDetection>,filteredCount: Int) {
        var masked: Bitmap?=null
        var modelInput: Bitmap?=null
        try {
            val ownedMasked=checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888,false))
            masked=ownedMasked
            val ownedInput=referenceDetector?.copyInputForProbe() ?: detector.copyInputForProbe()
            modelInput=ownedInput
            val data=JSONObject().put("frame",frameNumber).put("imageTimestampNs",imageTimestampNs)
                .put("rawDetectionCount",detections.size).put("filteredDetectionCount",filteredCount)
                .put("maxClassScore",referenceDetector?.maxClassScore ?: detector.maxClassScore)
                .put("detectorProfile",referenceDetector?.selectedProfile?.name ?: DetectorProfile.FULL.name)
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
        straightFlush.close()
        runCatching { repository.save(AppState.ledger) }; runCatching { repository.close() }
        runCatching { ocr.close() }
        runCatching { referenceDetector?.close() }
        runCatching { trace.close() }; evidenceIo.shutdown()
    }
}
