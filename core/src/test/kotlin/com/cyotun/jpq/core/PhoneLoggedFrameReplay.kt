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
        var confirmedEvents: Int=0,var previous: Int=0,var opposite: Int=0,var next: Int=0,var countsVerified: Boolean=false,
        var selfFinished: Boolean=false,var completionObserved: Boolean=false,
        var resultSceneObserved: Boolean=false,var endingScene: TableScene?=null,var unresolvedReason: String?=null)
    val ledger=RoundLedger(); var time=0L; var failures=0; var frames=0; var baselineCount=0
    val history=StringBuilder(); val ownExit=OwnExitPresentationTracker(); val visibility=EmptyRegionEvidence()
    val coverage=linkedMapOf<String,Coverage>(); var currentRound: String?=null
    val observer=RoundObserver(ledger) { snap ->
        val id=snap.roundId
        if(id!=null) {
            currentRound=id
            val round=coverage.getOrPut(id) { Coverage(id,time,snap.phase) }
            round.lastPhase=snap.phase; round.confirmedEvents=snap.plays.values.sumOf { it.size }
            round.countsVerified=snap.remaining!=null
            if(snap.phase==RoundPhase.SELF_FINISHED || snap.played(Seat.SELF)==27) round.selfFinished=true
            if(confirmedRoundFinished(snap.copy(phase=if(round.selfFinished) RoundPhase.SELF_FINISHED else RoundPhase.ACTIVE)))
                round.completionObserved=true
            round.previous=snap.played(Seat.PREVIOUS); round.opposite=snap.played(Seat.OPPOSITE); round.next=snap.played(Seat.NEXT)
        } else {
            currentRound?.let { coverage[it]?.endedAt=time }
            currentRound=null
        }
        if(snap.phase==RoundPhase.UNAVAILABLE) failures++
        if(snap.phase==RoundPhase.ACTIVE && snap.plays.values.all { it.isEmpty() }) baselineCount++
        if(history.isNotEmpty()) history.append(',')
        history.append("{\"timeMs\":$time,\"phase\":\"${snap.phase}\",\"reason\":${snap.reason?.let { "\"$it\"" } ?: "null"},\"previous\":${snap.played(Seat.PREVIOUS)},\"opposite\":${snap.played(Seat.OPPOSITE)},\"next\":${snap.played(Seat.NEXT)},\"remaining\":${snap.remaining?.values?.sum()}}")
    }
    File(args[0]).forEachLine { line ->
        val fields=line.split(';',limit=11); time=fields[0].toLong(); val detectedScene=TableScene.valueOf(fields[1])
        currentRound?.let { id -> coverage[id]?.let { round ->
            round.endingScene=detectedScene
            if(detectedScene==TableScene.RESULT) round.resultSceneObserved=true
        } }
        val selfFinished=fields[2].toBoolean(); val observed=fields[3].split(',').map { it.toBoolean() }
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
        val glyphs=if(fields[6].isBlank()) emptyList() else fields[6].split('|').map { value ->
            val g=value.split(','); val rank=Rank.valueOf(g[0]); val suit=Suit.valueOf(g[1])
            PlayGlyph(Card(rank,suit),g[2].toFloat(),GlyphBox(g[3].toFloat(),g[4].toFloat(),g[5].toFloat(),g[6].toFloat()))
        }
        val partition=partitionGuandanHand(glyphs); val hand=partition.hand.map { it.card }
        val passing=fields.getOrNull(7)?.split(',')?.filter { it.isNotEmpty() }?.map { Seat.valueOf(it) }?.toSet().orEmpty()
        val snapshot=ledger.snapshot(); val previous=ledger.currentHand(); val trackingPhase=ledger.phaseBeforeRecovery()
        val scene=continueTeammateScene(detectedScene,trackingPhase)
        val removed=previous.toMutableList(); val subset=hand.all { removed.remove(it) }
        ownExit.prepare(time,scene,snapshot.roundId)
        if(!adjusting && scene!=TableScene.TEAMMATE && partition.hand.all { it.confidence>=.65f })
            ownExit.observeOwnHand(time,trackingPhase,previous,hand)
        if(scene==TableScene.TEAMMATE && !adjusting)
            ownExit.teammateTransition(time,trackingPhase,previous,partition.table.filter { it.box.cy<.55f })
        val ownProof=ownExit.prepare(time,scene,snapshot.roundId)
        val assignedOwnership=assignPlayGroups(partition.table.filter { it.box.cy<.55f },passing,
            if(subset && scene!=TableScene.TEAMMATE && !adjusting) removed else emptyList(),scene==TableScene.TEAMMATE,ownProof,
            confirmedFinishedSeats(snapshot.copy(phase=trackingPhase)),ownExit.blocksCentralFallback())
        val ownership=if(adjusting) assignedOwnership else observer.reassociatePresentationFragments(assignedOwnership,time)
        val self=ownership.seats.getValue(Seat.SELF)
        fun regionObserved(seat: Seat,cards: List<PlayGlyph>) = !adjusting &&
            if(cards.isNotEmpty() && masks!=null) visibility.directlyObserved(scene,seat,cards,masks) else observed[seat.ordinal]
        // Reassigning a visible fragment cannot undo an explicit recorded
        // occlusion: a hidden tail may still be missing behind the overlay.
        if(regionObserved(Seat.SELF,self) && self.isNotEmpty()) ownExit.observe(time,trackingPhase,previous,hand,self)
        ownExit.emptySurface(selfClean,self.isNotEmpty(),
            ownership.ambiguous.any { it.box.cy in .285f.. .48f && it.box.cx in .32f.. .70f })
        val plays=ownership.seats.mapValues { (seat,p) -> ObservedPlay(p.map { it.card },p.minOfOrNull { it.confidence } ?: 1f,p,
            regionObserved(seat,p)) }
        if(confirmedRoundFinished(snapshot) && scene !in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.DEALING,TableScene.TRIBUTE)) {
            observer.observe(TableFrame(time,scene,null))
        } else if(ownership.ambiguous.isNotEmpty() && scene !in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.TRIBUTE)) {
            observer.observe(TableFrame(time,scene,if(scene==TableScene.TEAMMATE || adjusting) null else hand,
                plays=plays,selfFinished=selfFinished,incompleteReason="出牌归属待核对，正在重新识别",ambiguousPresentation=true,captureGap=captureGap,
                teammateHand=hand.takeIf { scene==TableScene.TEAMMATE && !adjusting && partition.hand.all { it.confidence>=.65f } },
                teammateHandConfidence=partition.hand.minOfOrNull { it.confidence } ?: 1f,dealingChoicesObserved=dealingChoicesObserved))
        } else {
            observer.observe(TableFrame(time,scene,if(scene==TableScene.TEAMMATE || adjusting) null else hand,plays,selfFinished,
                if(adjusting) "正在调整悬浮条，等待完整牌面" else null,captureGap=captureGap,
                teammateHand=hand.takeIf { scene==TableScene.TEAMMATE && !adjusting && partition.hand.all { it.confidence>=.65f } },
                teammateHandConfidence=partition.hand.minOfOrNull { it.confidence } ?: 1f,dealingChoicesObserved=dealingChoicesObserved))
        }
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
        (it.lastPhase==RoundPhase.FINISHED && it.confirmedEvents>0 && (!it.countsVerified || !it.completionObserved)) }
    val rounds=coverage.values.joinToString(",") { round ->
        "{\"roundId\":\"${round.roundId}\",\"startedAt\":${round.startedAt},\"endedAt\":${round.endedAt},\"lastPhase\":\"${round.lastPhase}\",\"countsVerified\":${round.countsVerified},\"selfFinished\":${round.selfFinished},\"completionObserved\":${round.completionObserved},\"resultSceneObserved\":${round.resultSceneObserved},\"endingScene\":${round.endingScene?.let { "\"$it\"" } ?: "null"},\"unresolvedReason\":${round.unresolvedReason?.let { "\"$it\"" } ?: "null"},\"observedFrames\":${round.observedFrames},\"recoveringFrames\":${round.recoveringFrames},\"longestRecoveryMs\":${round.longestRecoveryMs},\"confirmedEvents\":${round.confirmedEvents},\"previous\":${round.previous},\"opposite\":${round.opposite},\"next\":${round.next}}"
    }
    File(args[1]).writeText("{\"scope\":\"recorded_model_and_scene_evidence_not_independent_card_truth\",\"frames\":$frames,\"failures\":$failures,\"finishedRounds\":$finishedRounds,\"verifiedFinishedRounds\":$verifiedFinishedRounds,\"unresolvedRounds\":$unresolvedRounds,\"baselineCount\":$baselineCount,\"finalPhase\":\"${ledger.snapshot().phase}\",\"rounds\":[$rounds],\"history\":[$history]}")
    println("Logged frame replay frames=$frames failures=$failures baselines=$baselineCount finishedRounds=$finishedRounds verifiedFinishedRounds=$verifiedFinishedRounds unresolvedRounds=$unresolvedRounds")
}
