package com.cyotun.jpq.core

import java.io.File

/** Recorded model/scene/empty-evidence replay, not independent image or card truth. */
fun main(args: Array<String>) {
    if(File(args[0]).isDirectory) {
        val output=File(args[1]).apply { mkdirs() }
        File(args[0]).listFiles().orEmpty().filter { it.isFile && it.extension=="tape" }.sortedBy { it.name }.forEach {
            replayLoggedTape(arrayOf(it.absolutePath,File(output,"${it.nameWithoutExtension}.json").absolutePath))
        }
        return
    }
    replayLoggedTape(args)
}

internal fun replayLoggedTape(args: Array<String>) {
    data class Coverage(val roundId: String,val startedAt: Long,var lastPhase: RoundPhase,
        var endedAt: Long?=null,var observedFrames: Int=0,var recoveringFrames: Int=0,
        var recoveryStartedAt: Long?=null,var longestRecoveryMs: Long=0,
        var confirmedEvents: Int=0,var self: Int=0,var previous: Int=0,var opposite: Int=0,var next: Int=0,var countsVerified: Boolean=false,
        var selfFinished: Boolean=false,var completionObserved: Boolean=false,
        var teammateExcludedFrames: Int=0,var firstTeammateExcludedAt: Long?=null,var maximumTeammateExcludedCards: Int=0,
        var resultSceneObserved: Boolean=false,var endingScene: TableScene?=null,var unresolvedReason: String?=null)
    val ledger=RoundLedger()
    val seeded=args.getOrNull(2)?.let { loadLoggedCheckpoint(File(it),ledger); true } ?: false
    var time=0L; var failures=0; var frames=0; var baselineCount=0
    val history=StringBuilder(); val ownExit=OwnExitPresentationTracker(); val visibility=EmptyRegionEvidence()
    val ownRegions=PrebaselineOwnRegionReassignment()
    val teammatePool=RemainingPoolTracker()
    val poolHistory=StringBuilder()
    var lastPool: RemainingPoolExclusion?=null
    val coverage=linkedMapOf<String,Coverage>(); var currentRound: String?=null
    val eventsByRound=linkedMapOf<String,List<PlayEvent>>()
    val observer=RoundObserver(ledger) { snap ->
        val id=snap.roundId
        if(id!=null) {
            eventsByRound[id]=snap.plays.values.flatten().sortedBy { it.sequence }.map { it.copy(cards=it.cards.toList()) }
            currentRound=id
            val round=coverage.getOrPut(id) { Coverage(id,time,snap.phase) }
            round.lastPhase=snap.phase; round.confirmedEvents=snap.plays.values.sumOf { it.size }
            round.countsVerified=snap.remaining!=null
            if(snap.phase==RoundPhase.SELF_FINISHED || snap.played(Seat.SELF)==27) round.selfFinished=true
            if(confirmedRoundFinished(snap.copy(phase=if(round.selfFinished) RoundPhase.SELF_FINISHED else RoundPhase.ACTIVE)))
                round.completionObserved=true
            round.self=snap.played(Seat.SELF); round.previous=snap.played(Seat.PREVIOUS); round.opposite=snap.played(Seat.OPPOSITE); round.next=snap.played(Seat.NEXT)
        } else {
            currentRound?.let { coverage[it]?.endedAt=time }
            currentRound=null
        }
        if(snap.phase==RoundPhase.UNAVAILABLE) failures++
        if(snap.phase==RoundPhase.ACTIVE && snap.plays.values.all { it.isEmpty() }) baselineCount++
        if(history.isNotEmpty()) history.append(',')
        history.append("{\"timeMs\":$time,\"phase\":\"${snap.phase}\",\"reason\":${snap.reason?.let { "\"$it\"" } ?: "null"},\"self\":${snap.played(Seat.SELF)},\"previous\":${snap.played(Seat.PREVIOUS)},\"opposite\":${snap.played(Seat.OPPOSITE)},\"next\":${snap.played(Seat.NEXT)},\"remaining\":${snap.remaining?.values?.sum()}}")
    }
    File(args[0]).forEachLine { line ->
        val fields=line.split(';',limit=17); time=fields[0].toLong(); var detectedScene=TableScene.valueOf(fields[1])
        currentRound?.let { id -> coverage[id]?.let { round ->
            round.endingScene=detectedScene
            if(detectedScene==TableScene.RESULT) round.resultSceneObserved=true
        } }
        var selfFinished=fields[2].toBoolean(); val observed=fields[3].split(',').map { it.toBoolean() }
        val selfClean=fields[4].toBoolean(); val adjusting=fields[5].toBoolean()
        val captureGap=fields.getOrNull(8)?.toBoolean() ?: false
        // Legacy scene enums alone did not distinguish persistent avatar badges.
        val dealingChoicesObserved=fields.getOrNull(9)?.toBoolean() ?: false
        // An optional, source-verified raw mask set permits the same nonempty
        // geometry check as production. Missing metadata never means no masks.
        val masks=fields.getOrNull(10)?.takeIf { it!="?" }?.let { encoded ->
            if(encoded.isEmpty()) emptyList() else encoded.split('|').map { value ->
                val box=value.split(',').map(String::toFloat)
                require(box.size==4 && box.all { it.isFinite() && it in 0f..1f } && box[0]<box[2] && box[1]<box[3])
                GlyphBox(box[0],box[1],box[2],box[3])
            }
        }
        // Legacy booleans and missing metadata never acquire a synthetic OCR source.
        val openingSource=if(masks==null) null else fields.getOrNull(11)?.takeIf { it!="?" && it.isNotEmpty() }?.toLong()
        val remainingCounts=fields.getOrNull(12)?.takeIf { it!="?" }?.let { encoded ->
            if(encoded.isEmpty()) emptyMap() else encoded.split('|').associate { value ->
                val c=value.split(','); require(c.size==3)
                Seat.valueOf(c[0]) to RemainingHandCount(c[1].toInt(),c[2].toLong())
            }
        }.orEmpty()
        val observedPasses=fields.getOrNull(15)?.takeIf { it!="?" && it.isNotEmpty() }?.split('|')?.associate { value ->
            val c=value.split(','); require(c.size==2)
            Seat.valueOf(c[0]) to c[1].toLong()
        }.orEmpty()
        val earlyFinishHints=fields.getOrNull(16)?.takeIf { it!="?" && it.isNotEmpty() }?.split('|')?.map { value ->
            val c=value.split(','); require(c.size==6)
            val box=GlyphBox(c[2].toFloat(),c[3].toFloat(),c[4].toFloat(),c[5].toFloat())
            c[1].toLong() to SceneHint(c[0],box)
        }.orEmpty()
        val qualifiedFinishHints=earlyFinishHints.filter { (source,hint) -> source>=0 && time-source in 0L..1000L &&
            masks!=null && masks.none { mask -> hint.box!!.let { b -> mask.left<b.right && b.left<mask.right && mask.top<b.bottom && b.top<mask.bottom } } }
        var observedEarlyFinishes=qualifiedFinishHints.groupBy { it.first }
            .flatMap { (source,hints) -> tencentEarlyFinishSeats(hints.map { it.second }).keys.map { it to source } }.toMap()
        val observedLastPlaceSeat=tencentLastPlaceSeat(qualifiedFinishHints.map { it.second })
        val observedLastPlaceSourceTimeMs=qualifiedFinishHints.filter { (_,hint) ->
            observedLastPlaceSeat!=null && tencentLastPlaceSeat(listOf(hint))==observedLastPlaceSeat
        }.maxOfOrNull { it.first }
        if(observedLastPlaceSeat!=null) {
            detectedScene=TableScene.RESULT
            if(observedLastPlaceSeat==Seat.SELF) selfFinished=false
        }
        // Production's early-rank adapter does not emit observations in RESULT;
        // only the separately qualified last-place adapter remains available.
        if(detectedScene !in setOf(TableScene.TABLE,TableScene.TEAMMATE)) observedEarlyFinishes=emptyMap()
        val physicalSingles=fields.getOrNull(14)?.takeIf { it!="?" && it.isNotEmpty() }?.split('|')?.associate { value ->
            val c=value.split(','); require(c.size==6)
            Seat.valueOf(c[0]) to SingleCardIdentity(Card(Rank.valueOf(c[1]),Suit.valueOf(c[2])),
                c[3].toFloat(),c[4].toFloat(),c[5].toLong())
        }.orEmpty()
        val glyphs=if(fields[6].isBlank()) emptyList() else fields[6].split('|').map { value ->
            val g=value.split(','); val rank=Rank.valueOf(g[0]); val suit=Suit.valueOf(g[1])
            PlayGlyph(Card(rank,suit),g[2].toFloat(),GlyphBox(g[3].toFloat(),g[4].toFloat(),g[5].toFloat(),g[6].toFloat()))
        }
        val partition=partitionGuandanHand(glyphs); val hand=partition.hand.map { it.card }
        val passing=fields.getOrNull(7)?.split(',')?.filter { it.isNotEmpty() }?.map { Seat.valueOf(it) }?.toSet().orEmpty()
        val snapshot=ledger.snapshot(); val previous=ledger.currentHand(); val trackingPhase=ledger.phaseBeforeRecovery()
        val scene=continueTeammateScene(detectedScene,trackingPhase)
        val handMasked=masks?.let { !guandanHandRegionObserved(partition.hand,it) }==true
        val removed=previous.toMutableList(); val subset=hand.all { removed.remove(it) }
        ownExit.prepare(time,scene,snapshot.roundId)
        if(!adjusting && !handMasked && scene!=TableScene.TEAMMATE && partition.hand.all { it.confidence>=.65f })
            ownExit.observeOwnHand(time,trackingPhase,previous,hand)
        if(scene==TableScene.TEAMMATE && !adjusting)
            ownExit.teammateTransition(time,trackingPhase,previous,partition.table.filter { it.box.cy<.55f },
                observedHand=hand.takeIf { !handMasked && partition.hand.all { it.confidence>=.65f } })
        val ownProof=ownExit.prepare(time,scene,snapshot.roundId)
        val assignedOwnership=assignPlayGroups(partition.table.filter { it.box.cy<.55f },passing,
            if(subset && !handMasked && scene!=TableScene.TEAMMATE && !adjusting) removed else emptyList(),scene==TableScene.TEAMMATE,ownProof,
            confirmedFinishedSeats(snapshot.copy(phase=trackingPhase)),ownExit.blocksCentralFallback())
        val ownership=if(adjusting) assignedOwnership else observer.reassociatePresentationFragments(assignedOwnership,time)
        val self=ownership.seats.getValue(Seat.SELF)
        val emptyReasons=fields.getOrNull(13)?.split(',').orEmpty()
        val retiredOwnRegions=ownRegions.step(if(masks==null) TableScene.UNKNOWN else scene,hand.size,partition.hand,
            ownership.seats,snapshot.roundId,masks.orEmpty(),time,adjusting || ownership.ambiguous.isNotEmpty())
        fun regionObserved(seat: Seat,cards: List<PlayGlyph>) = !adjusting &&
            if(cards.isNotEmpty() && masks!=null) visibility.directlyObserved(scene,seat,cards,masks) else
                (observed[seat.ordinal] || (masks!=null && seat in retiredOwnRegions &&
                    emptyReasons.getOrNull(seat.ordinal)=="foreground_or_animation")) &&
                    (masks==null || !visibility.overlayOccluded(scene,seat,cards,masks))
        // Reassigning a visible fragment cannot undo an explicit recorded
        // occlusion: a hidden tail may still be missing behind the overlay.
        if(regionObserved(Seat.SELF,self) && self.isNotEmpty()) ownExit.observe(time,trackingPhase,previous,hand,self)
        ownExit.emptySurface(selfClean,self.isNotEmpty(),
            ownership.ambiguous.any { it.box.cy in .285f.. .48f && it.box.cx in .32f.. .70f })
        val plays=ownership.seats.mapValues { (seat,p) -> ObservedPlay(p.map { it.card },p.minOfOrNull { it.confidence } ?: 1f,p,
            regionObserved(seat,p),singleCardIdentity=physicalSingles[seat]) }
        val overlayOccluded=masks?.let { boxes -> Seat.entries.any { seat ->
            visibility.overlayOccluded(scene,seat,ownership.seats.getValue(seat),boxes)
        } }==true && scene !in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.TRIBUTE)
        val qualifiedHand=hand.takeUnless { scene==TableScene.TEAMMATE || adjusting || handMasked }
        val reason=when {
            adjusting -> "正在调整悬浮条，等待完整牌面"
            overlayOccluded -> "悬浮条遮住出牌区，请移回顶部，正在重新核对"
            handMasked -> "悬浮条遮住手牌区，请移回顶部，正在重新核对"
            else -> null
        }
        val localizedOverlaySeats=if(overlayOccluded && !adjusting && !handMasked && !captureGap && ownership.ambiguous.isEmpty())
            Seat.entries.filter { masks!=null && visibility.overlayOccluded(scene,it,ownership.seats.getValue(it),masks) }.toSet()
            else emptySet()
        if(confirmedRoundFinished(snapshot) && scene !in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.DEALING,TableScene.TRIBUTE)) {
            observer.observe(TableFrame(time,scene,null))
        } else if(ownership.ambiguous.isNotEmpty() && scene !in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.TRIBUTE)) {
            observer.observe(TableFrame(time,scene,qualifiedHand,
                plays=plays,selfFinished=selfFinished,incompleteReason="出牌归属待核对，正在重新识别",ambiguousPresentation=true,captureGap=captureGap,
                teammateHand=hand.takeIf { scene==TableScene.TEAMMATE && !adjusting && !handMasked && partition.hand.all { it.confidence>=.65f } },
                teammateHandConfidence=partition.hand.minOfOrNull { it.confidence } ?: 1f,dealingChoicesObserved=dealingChoicesObserved,
                remainingHandCounts=remainingCounts,openingChoiceSourceTimeMs=openingSource,
                observedPasses=observedPasses,observedEarlyFinishes=observedEarlyFinishes,
                observedLastPlaceSeat=observedLastPlaceSeat,observedLastPlaceSourceTimeMs=observedLastPlaceSourceTimeMs))
        } else {
            observer.observe(TableFrame(time,scene,qualifiedHand,plays,selfFinished,
                reason,captureGap=captureGap,
                teammateHand=hand.takeIf { scene==TableScene.TEAMMATE && !adjusting && !handMasked && partition.hand.all { it.confidence>=.65f } },
                teammateHandConfidence=partition.hand.minOfOrNull { it.confidence } ?: 1f,dealingChoicesObserved=dealingChoicesObserved,
                remainingHandCounts=remainingCounts,openingChoiceSourceTimeMs=openingSource,
                localizedOverlaySeats=localizedOverlaySeats,observedPasses=observedPasses,observedEarlyFinishes=observedEarlyFinishes,
                observedLastPlaceSeat=observedLastPlaceSeat,observedLastPlaceSourceTimeMs=observedLastPlaceSourceTimeMs))
        }
        // Mirror the production rank/color reveal gate after the observer has
        // updated the ledger. This is a recorded projection check, not fresh
        // pixel inference or a test of the Android display cache.
        val poolSnapshot=ledger.snapshot()
        val qualifiedPoolHand=hand.takeIf { scene==TableScene.TEAMMATE && !adjusting && !handMasked &&
            ownership.ambiguous.isEmpty() && partition.hand.isNotEmpty() && partition.hand.all { it.confidence>=.65f } &&
            ledger.phaseBeforeRecovery()==RoundPhase.SELF_FINISHED && !observer.diagnosticStatus().recovering }
        val pool=teammatePool.observe(time,0L,poolSnapshot,qualifiedPoolHand)
        if(pool!=null && pool.matches(poolSnapshot)) {
            coverage[poolSnapshot.roundId]?.let { round ->
                round.teammateExcludedFrames++
                if(round.firstTeammateExcludedAt==null) round.firstTeammateExcludedAt=time
                round.maximumTeammateExcludedCards=maxOf(round.maximumTeammateExcludedCards,pool.cards.size)
            }
            if(pool!=lastPool) {
                if(poolHistory.isNotEmpty()) poolHistory.append(',')
                val ranks=pool.cards.groupingBy { it.rank }.eachCount().entries.joinToString(",") { (rank,n) -> "\"${rank.name}\":$n" }
                poolHistory.append("{\"timeMs\":$time,\"roundId\":\"${pool.roundId}\",\"excludedCards\":${pool.cards.size},\"ranks\":{$ranks},\"rawPool\":${poolSnapshot.remaining?.values?.sum()},\"opponentsPool\":${remainingOutsidePool(poolSnapshot,pool)?.values?.sum()}}")
            }
        }
        lastPool=pool
        ledger.snapshot().roundId?.let { id -> coverage[id]?.let { round ->
            round.observedFrames++
            round.endingScene=detectedScene
            round.unresolvedReason=ledger.snapshot().reason
            if(ledger.snapshot().phase==RoundPhase.RECOVERING) {
                round.recoveringFrames++
                if(round.recoveryStartedAt==null) round.recoveryStartedAt=time
                round.longestRecoveryMs=maxOf(round.longestRecoveryMs,time-round.recoveryStartedAt!!)
            } else round.recoveryStartedAt=null
        } }
        frames++
    }
    val finishedRounds=coverage.values.count { it.lastPhase==RoundPhase.FINISHED }
    val verifiedFinishedRounds=coverage.values.count { it.lastPhase==RoundPhase.FINISHED && it.countsVerified && it.completionObserved }
    val unresolvedRounds=coverage.values.count { it.lastPhase in setOf(RoundPhase.RECOVERING,RoundPhase.UNAVAILABLE) ||
        (it.lastPhase==RoundPhase.FINISHED && (!it.countsVerified || !it.completionObserved)) }
    val rounds=coverage.values.joinToString(",") { round ->
        "{\"roundId\":\"${round.roundId}\",\"startedAt\":${round.startedAt},\"endedAt\":${round.endedAt},\"lastPhase\":\"${round.lastPhase}\",\"countsVerified\":${round.countsVerified},\"selfFinished\":${round.selfFinished},\"selfHistoryComplete\":${if(round.selfFinished) round.self==27 else "null"},\"teammateExcludedFrames\":${round.teammateExcludedFrames},\"firstTeammateExcludedAt\":${round.firstTeammateExcludedAt},\"maximumTeammateExcludedCards\":${round.maximumTeammateExcludedCards},\"completionObserved\":${round.completionObserved},\"resultSceneObserved\":${round.resultSceneObserved},\"endingScene\":${round.endingScene?.let { "\"$it\"" } ?: "null"},\"unresolvedReason\":${round.unresolvedReason?.let { "\"$it\"" } ?: "null"},\"observedFrames\":${round.observedFrames},\"recoveringFrames\":${round.recoveringFrames},\"longestRecoveryMs\":${round.longestRecoveryMs},\"confirmedEvents\":${round.confirmedEvents},\"self\":${round.self},\"previous\":${round.previous},\"opposite\":${round.opposite},\"next\":${round.next}}"
    }
    val finalEvents=eventsByRound.entries.joinToString(",") { (id,events) ->
        val encoded=events.joinToString(",") { event ->
            val cards=event.cards.joinToString(",") { "\"${it.colorKey()}\"" }
            "{\"eventId\":\"${event.eventId}\",\"sequence\":${event.sequence},\"seat\":\"${event.seat}\",\"cards\":[$cards]}"
        }
        "\"$id\":[$encoded]"
    }
    File(args[1]).writeText("{\"scope\":\"recorded_model_and_scene_evidence_not_independent_card_truth\",\"checkpointSeeded\":$seeded,\"frames\":$frames,\"failures\":$failures,\"finishedRounds\":$finishedRounds,\"verifiedFinishedRounds\":$verifiedFinishedRounds,\"unresolvedRounds\":$unresolvedRounds,\"baselineCount\":$baselineCount,\"finalPhase\":\"${ledger.snapshot().phase}\",\"rounds\":[$rounds],\"history\":[$history],\"teammatePoolHistory\":[$poolHistory],\"eventsByRound\":{$finalEvents}}")
    println("Logged frame replay frames=$frames failures=$failures baselines=$baselineCount finishedRounds=$finishedRounds verifiedFinishedRounds=$verifiedFinishedRounds unresolvedRounds=$unresolvedRounds")
}

/** Explicit recorded ledger prefix, for transition regression when the opening frames rotated out.
 * This is never an independently observed opening and never used by production. */
internal fun loadLoggedCheckpoint(file: File,ledger: RoundLedger) {
    val lines=file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
    fun cards(encoded: String)=encoded.split('|').map { value ->
        val fields=value.split(','); require(fields.size==2)
        Card(Rank.valueOf(fields[0]),Suit.valueOf(fields[1]))
    }
    val initial=lines.first().split(';')
    require(initial.size==3 && initial[0]=="INITIAL")
    val id=initial[1]
    ledger.begin(id,cards(initial[2]))
    for(line in lines.drop(1)) {
        val fields=line.split(';'); require(fields.size==5 && fields[0]=="EVENT")
        ledger.commit(PlayEvent(id,fields[1],fields[2].toLong(),Seat.valueOf(fields[3]),cards(fields[4])))
    }
    require(ledger.snapshot().phase==RoundPhase.ACTIVE) { "Checkpoint must precede the proved handoff" }
}
