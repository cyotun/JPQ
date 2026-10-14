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
    private val report: (JSONObject)->Unit): AutoCloseable {
    private data class Input(val glyph: PlayGlyph,val pixels: SuitSymbolPixels)
    private val store=ConfigStore(context)
    private val reader=SuitPixelReader()
    private val executor=Executors.newSingleThreadExecutor()
    private val busy=AtomicBoolean(false)
    private val generation=AtomicLong(0L)
    private val evidence=StraightFlushEvidenceTracker()
    private val ruleTracker=WildcardRuleTracker()
    private val gradeReader=GradeSourceReader()
    private val gradeTracker=RoundGradeTracker()
    @Volatile private var cachedGrade=WildcardRuleEvidence(null,false)
    @Volatile private var cornerGrades=emptySet<Rank>()
    private var pendingGrade=false
    private var gradeEpoch: Long?=null
    private var lastGradeTrace=""
    private val repository=RoundRepository(context)
    private var lastCheckpoint: String?=null
    private var workerGeneration=-1L
    private var workerRound: String?=null
    private var lastRound: String?=null
    private var lastOffer=-10000L
    private var lastEpoch: Long?=null
    private var enabled=true
    @Volatile private var closed=false

    fun onLedgerChanged(snapshot: RoundSnapshot) {
        if(snapshot.roundId!=lastRound || snapshot.phase !in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED)) {
            val begins=snapshot.roundId!=lastRound && snapshot.phase in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED)
            if(begins) {
                // A newly established baseline adopts only evidence collected in
                // this deal's preceding dealing/tribute window, never a prior round.
                if(!pendingGrade) resetGrade()
                pendingGrade=false; cornerGrades=emptySet()
            } else if(!pendingGrade) resetGrade()
            lastRound=snapshot.roundId; generation.incrementAndGet()
            publishUnknown()
        }
    }
    private fun resetGrade() {
        gradeTracker.reset(); gradeReader.reset(); cachedGrade=WildcardRuleEvidence(null,false)
        cornerGrades=emptySet(); lastGradeTrace=""
    }
    /** Called on the vision owner thread even before the 27-card baseline exists. */
    fun observeGrade(bitmap: Bitmap,time: Long,epoch: Long,scene: TableScene,
        text: SceneText?,masks: List<GlyphBox>) = synchronized(sessionGuard) {
        if(closed || !alive() || visibilityEpoch()!=epoch) return@synchronized
        if(gradeEpoch!=epoch) { gradeTracker.clearCandidateVotes(); gradeEpoch=epoch }
        try {
        val phase=AppState.ledger.snapshot().phase
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
        if(cachedGrade!=rule) { cachedGrade=rule; generation.incrementAndGet(); publishUnknown() }
        if(AppState.gradeRegion!=source.hudRegion) {
            AppState.gradeRegion=source.hudRegion; AppState.notifyChanged()
        }
        val fingerprint="${source.reading.source}:${rule.ranks}:${rule.confirmed}:${source.obscured}"
        if(fingerprint!=lastGradeTrace) {
            lastGradeTrace=fingerprint
            report(JSONObject().put("type","grade").put("elapsedMs",time).put("roundId",lastRound ?: JSONObject.NULL)
                .put("source",source.reading.source).put("confirmed",rule.confirmed)
                .put("ranks",JSONArray(rule.ranks.orEmpty().map { it.name })).put("obscured",source.obscured))
        }
        } catch(error: Exception) {
            resetGrade(); generation.incrementAndGet(); publishUnknown()
            if(AppState.gradeRegion!=null) { AppState.gradeRegion=null; AppState.notifyChanged() }
            android.util.Log.w("JPQVision","grade_metadata_unavailable:${error.javaClass.simpleName}")
        }
    }
    private fun publishUnknown() {
        if(AppState.straightFlushState!=StraightFlushState.UNKNOWN || AppState.straightFlushIncludesWildcards) {
            AppState.straightFlushState=StraightFlushState.UNKNOWN
            AppState.straightFlushIncludesWildcards=false; AppState.notifyChanged()
        }
    }
    fun offer(bitmap: Bitmap,time: Long,epoch: Long,snapshot: RoundSnapshot,initial: List<Card>,
        hand: List<PlayGlyph>,groups: Map<Seat,List<PlayGlyph>>,masks: List<GlyphBox>,text: SceneText?) {
        if(closed || !alive() || time-lastOffer<250) return
        if(lastEpoch!=epoch) {
            lastEpoch=epoch; generation.incrementAndGet(); publishUnknown()
        }
        val nowEnabled=store.load().showStraightFlush
        if(nowEnabled!=enabled) { enabled=nowEnabled; generation.incrementAndGet(); publishUnknown() }
        if(!enabled || snapshot.phase !in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED) ||
            snapshot.roundId==null || initial.size!=27) return
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
                        if(workerRound!=snapshot.roundId) { evidence.reset(); ruleTracker.reset(); workerRound=snapshot.roundId }
                        else { evidence.clearCandidateVotes(); ruleTracker.clearCandidateVotes() }
                        workerGeneration=requestGeneration; lastCheckpoint=null
                    }
                    val started=SystemClock.elapsedRealtimeNanos()
                    val observations=mutableListOf<Pair<Input,SuitSymbolObservation>>()
                    fun classify(inputs: List<Input>): SuitedGroup {
                        val cards=inputs.map { input ->
                            if(input.glyph.card.rank.isJoker) input.glyph.card else {
                                val result=reader.classify(input.pixels); observations.add(input to result)
                                Card(input.glyph.card.rank,result.suit ?: input.glyph.card.suit)
                            }
                        }
                        return SuitedGroup(cards,inputs.map { it.glyph.box })
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
                    // Without a complete qualified rule, allow every ordinary
                    // presentation to have substituted for a physical card.
                    // Restorations retain natural-card possibilities; they do not
                    // construct a wildcard run in this natural-only policy.
                    val replacementBound=playedOrdinary
                    val calculationRule=if(rule.confirmed) rule else WildcardRuleEvidence(emptySet(),true,replacementBound)
                    val result=evidence.update(time,snapshot,initialCopy,
                        ownRead.takeIf { snapshot.phase==RoundPhase.ACTIVE },others,calculationRule)
                    val analysisMs=(SystemClock.elapsedRealtimeNanos()-started)/1000000.0
                    var accepted=false
                    val metadata=JSONObject().put("schemaVersion",1).put("roundId",snapshot.roundId)
                        .put("state",result.state.name).put("reason",result.reason ?: JSONObject.NULL)
                        .put("ruleConfirmed",rule.confirmed).put("wildcardRanks",JSONArray(rule.ranks.orEmpty().map { it.name }))
                        .put("calculationScope",if(rule.confirmed) "WITH_WILDCARDS" else "NATURAL_ONLY")
                        .put("unresolvedReplacementCards",calculationRule.unresolvedReplacements)
                        .put("poolMeaning","initial physical cards plus three seats rendered cards; substitutions preserve a conservative ability bound")
                        .put("initial",cards(evidence.initialCards))
                        .put("events",JSONArray(snapshot.plays.values.flatten().filter { it.seat!=Seat.SELF }.sortedBy { it.sequence }.map { event ->
                            JSONObject().put("id",event.eventId).put("seat",event.seat.name).put("sequence",event.sequence)
                                .put("renderedCards",cards(evidence.eventCards[event.eventId] ?: event.cards))
                        }))
                    synchronized(sessionGuard) {
                        val current=AppState.ledger.snapshot()
                        if(closed || requestGeneration!=generation.get() || !alive() || visibilityEpoch()!=epoch ||
                            current.roundId!=snapshot.roundId || current.phase!=snapshot.phase ||
                            current.plays!=snapshot.plays) return@synchronized
                        accepted=true
                        cornerGrades=ruleTracker.observedRanks
                        if(AppState.straightFlushState!=result.state || AppState.straightFlushIncludesWildcards!=rule.confirmed) {
                            AppState.straightFlushState=result.state
                            AppState.straightFlushIncludesWildcards=rule.confirmed; AppState.notifyChanged()
                        }
                        report(JSONObject().put("type","straight_flush").put("elapsedMs",time)
                            .put("roundId",snapshot.roundId).put("state",result.state.name)
                            .put("reason",result.reason ?: JSONObject.NULL).put("ruleConfirmed",rule.confirmed)
                            .put("wildcardRanks",JSONArray(rule.ranks.orEmpty().map { it.name }))
                            .put("calculationScope",if(rule.confirmed) "WITH_WILDCARDS" else "NATURAL_ONLY")
                            .put("gradeCandidates",JSONArray(gradeRanks.map { it.name })).put("observedMode",mode.name)
                            .put("handSource",if(snapshot.phase==RoundPhase.SELF_FINISHED) "teammate" else "self")
                            .put("knownSuitCards",evidence.consumed.count { it.suit !in setOf(Suit.RED,Suit.BLACK) && !it.rank.isJoker })
                            .put("readings",JSONArray(observations.map { (input,obs) -> JSONObject()
                                .put("card",input.glyph.card.colorKey()).put("suit",obs.suit?.name ?: JSONObject.NULL)
                                .put("placement",if(input in ownSet) "HAND" else "TABLE")
                                .put("similarity",obs.confidence).put("margin",obs.margin).put("reason",obs.reason)
                                .put("goldHeart",obs.goldHeart) }))
                            .put("roiPixels",copiedPixels).put("extractMs",extractMs).put("analysisMs",analysisMs))
                    }
                    // Database I/O stays outside the capture/ledger lock and runs only when
                    // accepted annotations change. It cannot alter the main round state.
                    val checkpoint=metadata.toString()
                    if(accepted && checkpoint!=lastCheckpoint) {
                        repository.saveSuitEvidence(snapshot.roundId!!,metadata)
                        lastCheckpoint=checkpoint
                    }
                } catch(error: Exception) {
                    synchronized(sessionGuard) {
                        if(!closed && requestGeneration==generation.get() && alive() && visibilityEpoch()==epoch) publishUnknown()
                    }
                    android.util.Log.w("JPQVision","suit_side_channel_unavailable:${error.javaClass.simpleName}")
                } finally { busy.set(false) }
            }
        } catch(error: Exception) {
            busy.set(false); publishUnknown()
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
    override fun close() {
        if(closed) return
        closed=true; generation.incrementAndGet()
        executor.execute { runCatching { repository.close() } }; executor.shutdown()
    }
}
