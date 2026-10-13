package com.cyotun.jpq.core

import java.io.File

/** Recorded model/scene/empty-evidence replay, not independent image or card truth. */
fun main(args: Array<String>) {
    val ledger=RoundLedger(); var time=0L; var failures=0; var frames=0; var baselineCount=0
    val history=StringBuilder(); val ownExit=OwnExitPresentationTracker()
    val observer=RoundObserver(ledger) { snap ->
        if(snap.phase==RoundPhase.UNAVAILABLE) failures++
        if(snap.phase==RoundPhase.ACTIVE && snap.plays.values.all { it.isEmpty() }) baselineCount++
        if(history.isNotEmpty()) history.append(',')
        history.append("{\"timeMs\":$time,\"phase\":\"${snap.phase}\",\"reason\":${snap.reason?.let { "\"$it\"" } ?: "null"},\"previous\":${snap.played(Seat.PREVIOUS)},\"opposite\":${snap.played(Seat.OPPOSITE)},\"next\":${snap.played(Seat.NEXT)},\"remaining\":${snap.remaining?.values?.sum()}}")
    }
    var ambiguousSince: Long?=null
    File(args[0]).forEachLine { line ->
        val fields=line.split(';',limit=7); time=fields[0].toLong(); val detectedScene=TableScene.valueOf(fields[1])
        val selfFinished=fields[2].toBoolean(); val observed=fields[3].split(',').map { it.toBoolean() }
        val selfClean=fields[4].toBoolean(); val adjusting=fields[5].toBoolean()
        val glyphs=if(fields[6].isBlank()) emptyList() else fields[6].split('|').map { value ->
            val g=value.split(','); val rank=Rank.valueOf(g[0]); val suit=Suit.valueOf(g[1])
            PlayGlyph(Card(rank,suit),g[2].toFloat(),GlyphBox(g[3].toFloat(),g[4].toFloat(),g[5].toFloat(),g[6].toFloat()))
        }
        val partition=partitionGuandanHand(glyphs); val hand=partition.hand.map { it.card }
        val snapshot=ledger.snapshot(); val previous=ledger.currentHand()
        val scene=continueTeammateScene(detectedScene,snapshot.phase)
        val removed=previous.toMutableList(); val subset=hand.all { removed.remove(it) }
        val ownProof=ownExit.prepare(time,scene,snapshot.roundId)
        val ownership=assignPlayGroups(partition.table.filter { it.box.cy<.55f },emptySet(),
            if(subset && scene!=TableScene.TEAMMATE && !adjusting) removed else emptyList(),scene==TableScene.TEAMMATE,ownProof)
        val self=ownership.seats.getValue(Seat.SELF)
        // The original trace may have classified a now-owned exit as ambiguous.
        // As with non-empty plays below, its fresh positive glyphs re-establish
        // region observation; recorded empty-surface evidence applies to empty groups.
        if(!adjusting && self.isNotEmpty()) ownExit.observe(time,snapshot.phase,previous,hand,self)
        ownExit.emptySurface(selfClean,self.isNotEmpty())
        if(ownership.ambiguous.isNotEmpty() && scene !in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.TRIBUTE)) {
            if(ambiguousSince==null) ambiguousSince=time
            if(time-ambiguousSince!!>1500 && ledger.snapshot().remaining!=null) observer.interrupted("出牌归属无法完整确认，等待下一局")
            observer.observe(TableFrame(time,scene,if(scene==TableScene.TEAMMATE || adjusting) null else hand))
        } else {
            ambiguousSince=null
            val plays=ownership.seats.mapValues { (seat,p) -> ObservedPlay(p.map { it.card },p.minOfOrNull { it.confidence } ?: 1f,p,
                if(p.isNotEmpty()) !adjusting else observed[Seat.entries.indexOf(seat)]) }
            observer.observe(TableFrame(time,scene,if(scene==TableScene.TEAMMATE || adjusting) null else hand,plays,selfFinished))
        }
        frames++
    }
    File(args[1]).writeText("{\"scope\":\"recorded_model_and_scene_evidence_not_independent_card_truth\",\"frames\":$frames,\"failures\":$failures,\"baselineCount\":$baselineCount,\"history\":[$history]}")
    println("Logged frame replay frames=$frames failures=$failures baselines=$baselineCount")
}
