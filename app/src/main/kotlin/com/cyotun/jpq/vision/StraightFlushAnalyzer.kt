package com.cyotun.jpq.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.cyotun.jpq.AppState
import com.cyotun.jpq.ConfigStore
import com.cyotun.jpq.RoundRepository
import com.cyotun.jpq.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Low-rate, bounded side channel. Only ROI copies cross this thread boundary. */
class StraightFlushAnalyzer(context: Context,private val sessionGuard: Any,
    private val alive: ()->Boolean,private val visibilityEpoch: ()->Long,
    private val report: (JSONObject)->Unit,
    private val remainingPool: (RoundSnapshot,Long)->RemainingPoolExclusion?={_,_->null},
    private val lazyReport: ((() -> JSONObject)->Unit)?=null): AutoCloseable {
    private data class Input(val glyph: PlayGlyph,val pixels: SuitSymbolPixels)
    private val store=ConfigStore(context)
    private val reader=SuitPixelReader()
    private val executor=com.cyotun.jpq.EvidenceExecutor("jpq-suit-evidence")
    private val busy=AtomicBoolean(false)
    private val generation=AtomicLong(0L)
    private val evidence=StraightFlushEvidenceTracker()
    private val ruleTracker=WildcardRuleTracker()
    private val gradeReader=GradeSourceReader()
    private val gradeTracker=RoundGradeTracker()
    private val presentation=StraightFlushPresentation()
    @Volatile private var cachedGrade=WildcardRuleEvidence(null,false)
    @Volatile private var cornerGrades=emptySet<Rank>()
    private var pendingGrade=false
    private var gradeEpoch: Long?=null
    private var lastGradeTrace=""
    private val repository=RoundRepository(context)
    private var lastCheckpoint: String?=null
    private var lastCapacityTrace: String?=null
    private var workerGeneration=-1L
    private var workerRound: String?=null
    private var lastRound: String?=null
    private var lastOffer=-10000L
    private var lastEpoch: Long?=null
    private var enabled=true
    private var counterKey=""
    private var cachedCounterProjection: GradeCounterProjection?=null
    @Volatile private var publishedCounterProjection: GradeCounterProjection?=null
    @Volatile private var closed=false

    private fun emitReport(record: () -> JSONObject) {
        val sink=lazyReport
        if(sink!=null) sink(record) else report(record())
    }

    fun onLedgerChanged(snapshot: RoundSnapshot) = synchronized(sessionGuard) {
        if(closed || !alive()) return@synchronized
        if(snapshot.phase==RoundPhase.FINISHED && snapshot.roundId==lastRound) {
            // The observer emits FINISHED before WAITING. Its final card grid
            // can still be drawn in that interval: keep its accepted side-channel
            // presentation and retire queued work, then clear at WAITING.
            generation.incrementAndGet()
            return@synchronized
        }
        if(snapshot.phase==RoundPhase.RECOVERING && snapshot.roundId==lastRound) {
            generation.incrementAndGet(); publishUnknown(snapshot)
            AppState.publishCounterState(GradeCounterState(snapshot.roundId,
                cachedGrade.ranks.orEmpty().takeIf { cachedGrade.confirmed }.orEmpty(),reason="round_recovering"))
            return@synchronized
        }
        if(snapshot.roundId!=lastRound || snapshot.phase !in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED)) {
            val begins=snapshot.roundId!=lastRound && snapshot.phase in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED)
            if(begins) {
                // A newly established baseline adopts only evidence collected in
                // this deal's preceding dealing/tribute window, never a prior round.
                if(!pendingGrade) resetGrade()
                pendingGrade=false; cornerGrades=emptySet()
            } else if(!pendingGrade) resetGrade()
            lastRound=snapshot.roundId; generation.incrementAndGet()
            publishUnknown(snapshot)
        }
        // Own plays do not consume the outside pool a second time. Reuse the
        // accepted evidence version; new opponent events are re-evaluated now,
        // without first publishing an empty '?' counter or displaying stale counts.
        val hand=remainingPool(snapshot,visibilityEpoch())
        val rebased=publishedCounterProjection?.rebase(snapshot,cachedGrade,hand)
        publishedCounterProjection=rebased
        AppState.publishCounterState(rebased?.state ?: GradeCounterState(snapshot.roundId,
            cachedGrade.ranks.orEmpty().takeIf { cachedGrade.confirmed }.orEmpty(),reason="awaiting_physical_evidence"))
        val countProof=straightFlushHandCapacityExclusion(snapshot,hand)
        if(countProof!=null) {
            // Publish immediately with the committed counts, even when the suit
            // worker is busy or the final table presentation is clearing.
            publishUnknown(snapshot)
            val hands=straightFlushRemainingHandSizes(snapshot,hand).orEmpty()
            val fingerprint="${snapshot.roundId}:$hands:${snapshot.phase}"
            if(fingerprint!=lastCapacityTrace) {
                lastCapacityTrace=fingerprint
                emitReport { JSONObject().put("type","straight_flush").put("elapsedMs",SystemClock.elapsedRealtime())
                    .put("roundId",snapshot.roundId).put("state",countProof.state.name)
                    .put("reason",countProof.reason).put("calculationScope","COUNT_BOUND")
                    .put("remainingHands",JSONArray(hands)).put("analysisSource","seat_capacity") }
            }
        } else lastCapacityTrace=null
    }
    private fun resetGrade() {
        gradeTracker.reset(); gradeReader.reset(); cachedGrade=WildcardRuleEvidence(null,false)
        publishedCounterProjection=null
        cornerGrades=emptySet(); lastGradeTrace=""
        // The next deal must establish its own visible HUD before it can warn.
        if(AppState.gradeRegion!=null) { AppState.gradeRegion=null; AppState.notifyChanged() }
        AppState.publishCounterState(GradeCounterState())
    }
    /** Called on the vision owner thread even before the 27-card baseline exists. */
    fun observeGrade(bitmap: Bitmap,time: Long,epoch: Long,scene: TableScene,
        text: SceneText?,masks: List<GlyphBox>) = synchronized(sessionGuard) {
        if(closed || !alive() || visibilityEpoch()!=epoch) return@synchronized
        if(gradeEpoch!=epoch) { gradeTracker.clearCandidateVotes(); gradeEpoch=epoch }
        try {
        val phase=AppState.ledger.snapshot().phase
        if(phase==RoundPhase.RECOVERING) { gradeTracker.clearCandidateVotes(); return@synchronized }
        if(scene in setOf(TableScene.LOBBY,TableScene.RESULT)) {
            pendingGrade=false; resetGrade()
        } else if(phase !in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED) &&
            scene in setOf(TableScene.DEALING,TableScene.TRIBUTE) && !pendingGrade) {
            resetGrade(); pendingGrade=true
        }
        val lines=text?.lines.orEmpty().map { line -> SceneHint(line.text,line.box?.let { b ->
            GlyphBox(b.left.toFloat()/bitmap.width,b.top.toFloat()/bitmap.height,b.right.toFloat()/bitmap.width,b.bottom.toFloat()/bitmap.height)
        }) }
        val source=gradeReader.read(bitmap,time,text,masks,scene,cornerGrades,tencentWildcardMode(lines))
        val rule=gradeTracker.observe(time,source.sourceTime,source.reading)
        if(cachedGrade!=rule) {
            cachedGrade=rule; generation.incrementAndGet(); publishUnknown()
            publishedCounterProjection=null
            AppState.publishCounterState(GradeCounterState(lastRound,rule.ranks.orEmpty().takeIf { rule.confirmed }.orEmpty(),reason="awaiting_physical_evidence"))
        }
        if(AppState.gradeRegion!=source.hudRegion) {
            AppState.gradeRegion=source.hudRegion; AppState.notifyChanged()
        }
        val fingerprint="${source.reading.source}:${rule.ranks}:${rule.confirmed}:${source.obscured}:${source.activeHudTeams}"
        if(fingerprint!=lastGradeTrace) {
            lastGradeTrace=fingerprint
            emitReport { JSONObject().put("type","grade").put("elapsedMs",time).put("roundId",lastRound ?: JSONObject.NULL)
                .put("source",source.reading.source).put("confirmed",rule.confirmed)
                .put("activeHudTeams",JSONArray(source.activeHudTeams.map { it.name }))
                .put("hudRegion",source.hudRegion?.let { JSONArray(listOf(it.left,it.top,it.right,it.bottom)) } ?: JSONObject.NULL)
                .put("ranks",JSONArray(rule.ranks.orEmpty().map { it.name })).put("obscured",source.obscured) }
        }
        } catch(error: Exception) {
            // A failed/obscured read is an abstention. Only a qualified
            // contradiction or round transition can withdraw accepted grade proof.
            gradeTracker.clearCandidateVotes()
            android.util.Log.w("JPQVision","grade_metadata_unavailable:${error.javaClass.simpleName}")
        }
    }
    internal fun publishResult(snapshot: RoundSnapshot,result: StraightFlushResult,includesWildcards: Boolean,
                               teammateExcluded: Boolean=false): StraightFlushDisplay =
        synchronized(sessionGuard) {
            val display=presentation.accept(snapshot,result,includesWildcards,teammateExcluded,cachedGrade)
            AppState.publishStraightFlush(display.result,display.includesWildcards)
            display
        }
    private fun publishUnknown(snapshot: RoundSnapshot=AppState.ledger.snapshot()) {
        val pool=remainingPool(snapshot,visibilityEpoch())
        val fallback=straightFlushHandCapacityExclusion(snapshot,pool)
            ?: StraightFlushResult(StraightFlushState.UNKNOWN)
        publishResult(snapshot,fallback,false,pool!=null)
    }
    fun offer(bitmap: Bitmap,time: Long,epoch: Long,snapshot: RoundSnapshot,initial: List<Card>,
        hand: List<PlayGlyph>,groups: Map<Seat,List<PlayGlyph>>,masks: List<GlyphBox>,text: SceneText?) {
        if(closed || !alive() || time-lastOffer<250) return
        if(lastEpoch!=epoch) {
            lastEpoch=epoch; generation.incrementAndGet(); publishUnknown()
        }
        val nowEnabled=store.load().showStraightFlush
        if(nowEnabled!=enabled) { enabled=nowEnabled; generation.incrementAndGet(); publishUnknown() }
        if((!enabled && !cachedGrade.confirmed) || snapshot.phase !in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED) ||
            snapshot.roundId==null || initial.size!=27) return
        val poolForFrame=remainingPool(snapshot,epoch)
        if(straightFlushHandCapacityExclusion(snapshot,poolForFrame)!=null && !cachedGrade.confirmed) { publishUnknown(); return }
        if(!busy.compareAndSet(false,true)) return
        lastOffer=time
        val requestGeneration=generation.get()
        val extractStart=SystemClock.elapsedRealtimeNanos()
        try {
            var copiedPixels=0
            fun copy(glyphs: List<PlayGlyph>,placement: SuitPixelReader.Placement): List<Input> =
                glyphs.sortedWith(compareBy({it.box.cx},{it.box.cy})).take(40).map { glyph ->
                    val pixels=if(copiedPixels+128*128<=400000) reader.extract(bitmap,glyph,placement,masks)
                    else SuitSymbolPixels(0,0,IntArray(0),glyph.card.suit,false,"roi_budget")
                    copiedPixels+=pixels.argb.size; Input(glyph,pixels)
                }
            val own=copy(hand,SuitPixelReader.Placement.HAND)
            val other=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).associateWith {
                copy(groups[it].orEmpty(),SuitPixelReader.Placement.TABLE)
            }
            fun hints(lines: List<OcrLine>)=lines.map { line -> SceneHint(line.text,line.box?.let { b ->
                GlyphBox(b.left.toFloat()/bitmap.width,b.top.toFloat()/bitmap.height,b.right.toFloat()/bitmap.width,b.bottom.toFloat()/bitmap.height)
            }) }
            val mode=tencentWildcardMode(hints(text?.lines.orEmpty()))
            val markedRanks=tencentGradeMarkerRanks(hand,hints(text?.gradeElements.orEmpty()),bitmap.width.toFloat()/bitmap.height)
            val initialCopy=initial.toList()
            val gradeForFrame=cachedGrade
            val extractMs=(SystemClock.elapsedRealtimeNanos()-extractStart)/1000000.0
            executor.execute {
                try {
                    if(closed || !alive() || requestGeneration!=generation.get() || visibilityEpoch()!=epoch) return@execute
                    if(workerGeneration!=requestGeneration) {
                        counterKey=""; cachedCounterProjection=null
                        if(workerRound!=snapshot.roundId) { evidence.reset(); ruleTracker.reset(); workerRound=snapshot.roundId }
                        else { evidence.clearCandidateVotes(); ruleTracker.clearCandidateVotes() }
                        workerGeneration=requestGeneration; lastCheckpoint=null
                    }
                    val started=SystemClock.elapsedRealtimeNanos()
                    val observations=mutableListOf<Pair<Input,SuitSymbolObservation>>()
                    fun classify(inputs: List<Input>): SuitedGroup {
                        val physical=mutableListOf<Boolean?>()
                        val cards=inputs.map { input ->
                            if(input.glyph.card.rank.isJoker) { physical.add(false); input.glyph.card } else {
                                val result=reader.classify(input.pixels); observations.add(input to result)
                                physical.add(tencentPhysicalWildcardIdentity(input.glyph.card,input.pixels,result,gradeForFrame))
                                Card(input.glyph.card.rank,result.suit ?: input.glyph.card.suit)
                            }
                        }
                        return SuitedGroup(cards,inputs.map { it.glyph.box },physical)
                    }
                    val ownRead=classify(own)
                    val others=other.mapValues { classify(it.value) }
                    // A table's paired/wild display can show a logical substitution.
                    // Only actual hand corners supply effective wildcard rank evidence.
                    val ownSet=own.toSet()
                    val gradeRanks=observations.filter { (input,obs) -> input in ownSet &&
                        (obs.goldHeart || input.glyph.card.rank in markedRanks) &&
                        greenGradeSupport(input.pixels) }.map { it.first.glyph.card.rank }.toSet()
                    ruleTracker.observe(time,text?.timeMs,mode,gradeRanks)
                    // All sources, including stabilized corners, pass the one
                    // priority-aware grade cache. A withdrawn announcement must
                    // not be silently replaced by an older corner-only rule.
                    val rule=gradeForFrame
                    // The selected Tencent policy deliberately falls back to natural
                    // straight flushes when the round's effective grade is unavailable.
                    // Persist that scope so natural-only absence cannot be presented as
                    // proof that wildcard combinations are also absent.
                    val playedOrdinary=snapshot.plays.values.flatten().filter { it.seat!=Seat.SELF }.sumOf { event -> event.cards.count { !it.rank.isJoker } }
                    // Without a complete qualified rule, a displayed substitute
                    // can only come from at most two/four physical heart wildcards.
                    // Restorations retain natural-card possibilities; they do not
                    // construct a wildcard run in this natural-only policy.
                    val replacementBound=tencentUnresolvedReplacementBound(playedOrdinary,mode)
                    val calculationRule=if(rule.confirmed) rule else WildcardRuleEvidence(emptySet(),true,replacementBound)
                    val result=evidence.update(time,snapshot,initialCopy,
                        ownRead,others,calculationRule,poolForFrame)
                    val physicalPool=poolForFrame?.copy(cards=evidence.teammateCards)
                    val identity="${snapshot.roundId}:${rule}:${evidence.initialCards}:${evidence.physicalEventCards}:$physicalPool"
                    if(identity!=counterKey) {
                        cachedCounterProjection=GradeCounterProjection.create(snapshot,evidence.initialCards,evidence.physicalEventCards,rule,physicalPool,
                            TableCardFaces.ORIGINAL)
                        counterKey=identity
                    }
                    // A committed event may arrive before its stable suit annotation.
                    // The physical-evidence key alone therefore cannot version the ledger.
                    val projection=checkNotNull(cachedCounterProjection?.rebase(snapshot,rule,physicalPool))
                    cachedCounterProjection=projection
                    val counter=projection.state
                    fun bounds(value: GradeCountBounds?): Any=value?.let {
                        JSONObject().put("minimum",it.minimum).put("maximum",it.maximum).put("exact",it.exact ?: JSONObject.NULL)
                    } ?: JSONObject.NULL
                    val counterMetadata=JSONObject().put("reason",counter.reason ?: JSONObject.NULL)
                        .put("hearts",bounds(counter.heartCount))
                        .put("ordinary",JSONObject().apply { counter.ordinaryCounts.forEach { (rank,value) -> put(rank.name,bounds(value)) } })
                    val analysisMs=(SystemClock.elapsedRealtimeNanos()-started)/1000000.0
                    var accepted=false
                    val metadata=JSONObject().put("schemaVersion",1).put("roundId",snapshot.roundId)
                        .put("state",result.state.name).put("reason",result.reason ?: JSONObject.NULL)
                        .put("ruleConfirmed",rule.confirmed).put("wildcardRanks",JSONArray(rule.ranks.orEmpty().map { it.name }))
                        .put("calculationScope",if(rule.confirmed) "WITH_WILDCARDS" else "NATURAL_ONLY")
                        .put("unresolvedReplacementCards",calculationRule.unresolvedReplacements)
                        .put("remainingHands",JSONArray(straightFlushRemainingHandSizes(snapshot,poolForFrame).orEmpty()))
                        .put("physicalCounter",counterMetadata)
                        .put("poolMeaning",if(poolForFrame!=null) "opponents only; confirmed current teammate hand excluded" else "three outside seats; teammate hand unconfirmed")
                        .put("teammateHand",cards(evidence.teammateCards))
                        .put("initial",cards(evidence.initialCards))
                        .put("events",JSONArray(snapshot.plays.values.flatten().filter { it.seat!=Seat.SELF }.sortedBy { it.sequence }.map { event ->
                            JSONObject().put("id",event.eventId).put("seat",event.seat.name).put("sequence",event.sequence)
                                .put("renderedCards",cards(evidence.eventCards[event.eventId] ?: event.cards))
                                .put("physicalWildcards",JSONArray(evidence.physicalEventCards[event.eventId].orEmpty().map { it.wildcard ?: JSONObject.NULL }))
                        }))
                    synchronized(sessionGuard) {
                        // Validate the session before querying shared projections. A
                        // retired task must not resurrect its epoch after stop/reset.
                        if(closed || requestGeneration!=generation.get() || !alive() || visibilityEpoch()!=epoch)
                            return@synchronized
                        val current=AppState.ledger.snapshot()
                        val currentPool=remainingPool(current,epoch)
                        val samePool=if(poolForFrame==null) currentPool==null else currentPool?.sameHandVersion(poolForFrame)==true
                        if(closed || requestGeneration!=generation.get() || !alive() || visibilityEpoch()!=epoch ||
                            current.roundId!=snapshot.roundId || current.phase!=snapshot.phase ||
                            current.plays!=snapshot.plays || !samePool) return@synchronized
                        accepted=true
                        publishedCounterProjection=projection
                        AppState.publishCounterState(counter)
                        cornerGrades=ruleTracker.observedRanks
                        val displayed=publishResult(snapshot,result,rule.confirmed,poolForFrame!=null)
                        emitReport { JSONObject().put("type","straight_flush").put("elapsedMs",time)
                            .put("roundId",snapshot.roundId).put("state",result.state.name)
                            .put("reason",result.reason ?: JSONObject.NULL).put("ruleConfirmed",rule.confirmed)
                            .put("displayState",displayed.result.state.name)
                            .put("displayCandidateRanks",JSONArray(displayed.result.candidateRanks.map { it.name }))
                            .put("displayHeld",displayed.result!=result || displayed.includesWildcards!=rule.confirmed)
                            .put("wildcardRanks",JSONArray(rule.ranks.orEmpty().map { it.name }))
                            .put("calculationScope",if(rule.confirmed) "WITH_WILDCARDS" else "NATURAL_ONLY")
                            .put("remainingHands",JSONArray(straightFlushRemainingHandSizes(snapshot,poolForFrame).orEmpty()))
                            .put("physicalCounter",counterMetadata)
                            .put("unresolvedReplacementCards",calculationRule.unresolvedReplacements)
                            .put("gradeCandidates",JSONArray(gradeRanks.map { it.name })).put("observedMode",mode.name)
                            .put("handSource",if(snapshot.phase==RoundPhase.SELF_FINISHED) "teammate" else "self")
                            .put("knownSuitCards",evidence.consumed.count { it.suit !in setOf(Suit.RED,Suit.BLACK) && !it.rank.isJoker })
                            .put("readings",JSONArray(observations.map { (input,obs) -> JSONObject()
                                .put("card",input.glyph.card.colorKey()).put("suit",obs.suit?.name ?: JSONObject.NULL)
                                .put("placement",if(input in ownSet) "HAND" else "TABLE")
                                .put("similarity",obs.confidence).put("margin",obs.margin).put("reason",obs.reason)
                                .put("goldHeart",obs.goldHeart) }))
                            .put("roiPixels",copiedPixels).put("extractMs",extractMs).put("analysisMs",analysisMs) }
                    }
                    // Database I/O stays outside the capture/ledger lock and runs only when
                    // accepted annotations change. It cannot alter the main round state.
                    if(accepted) persistCheckpoint(snapshot.roundId!!,metadata)
                } catch(error: Exception) {
                    synchronized(sessionGuard) {
                        if(!closed && requestGeneration==generation.get() && alive() && visibilityEpoch()==epoch) publishUnknown()
                    }
                    android.util.Log.w("JPQVision","suit_side_channel_unavailable:${error.javaClass.simpleName}")
                } finally { busy.set(false) }
            }
        } catch(error: Exception) {
            busy.set(false)
            synchronized(sessionGuard) {
                if(!closed && requestGeneration==generation.get() && alive() && visibilityEpoch()==epoch) publishUnknown()
            }
            android.util.Log.w("JPQVision","suit_snapshot_unavailable:${error.javaClass.simpleName}")
        }
    }
    private fun greenGradeSupport(pixels: SuitSymbolPixels): Boolean {
        if(!pixels.complete || pixels.argb.isEmpty()) return false
        val green=pixels.argb.count { p ->
            val r=p ushr 16 and 255; val g=p ushr 8 and 255; val b=p and 255
            g>=115 && g>r*1.1f && g>b*1.1f
        }
        return green>=12 && green.toFloat()/pixels.argb.size>=.025f
    }
    private fun cards(values: List<Card>)=JSONArray(values.map { JSONObject().put("rank",it.rank.name).put("colorOrSuit",it.suit.name) })
    /** Persistence is advisory; a disk failure cannot invalidate accepted card analysis. */
    internal fun persistCheckpoint(roundId: String,metadata: JSONObject): Boolean {
        val checkpoint=metadata.toString()
        if(checkpoint==lastCheckpoint) return true
        return try {
            repository.saveSuitEvidence(roundId,metadata)
            lastCheckpoint=checkpoint
            true
        } catch(error: Exception) {
            android.util.Log.w("JPQVision","suit_checkpoint_unavailable",error)
            false
        }
    }
    override fun close() {
        if(closed) return
        closed=true; generation.incrementAndGet()
        executor.execute { runCatching { repository.close() } }; executor.shutdown()
    }
}
