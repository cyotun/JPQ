package com.cyotun.jpq.core

import java.io.File
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.InflaterInputStream

/** Offline replay of model predictions + video pixels. Scene labels are explicitly supplied by the tape. */
fun main(args: Array<String>) {
    require(args.size in 2..3) { "Replay tape, output JSON and optional frame trace paths required" }
    val ledger=RoundLedger(); val evidence=EmptyRegionEvidence(); val ownExit=OwnExitPresentationTracker()
    var time=0L; var frames=0; var ambiguous=0; var initial=emptyList<Card>()
    var latestKnown: RoundSnapshot?=null
    val failures=mutableListOf<String>(); val history=mutableListOf<String>()
    fun quote(s: String)="\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\""
    fun cards(c: List<Card>)=c.joinToString(",","[","]") { quote(it.colorKey()) }
    val observer=RoundObserver(ledger) { snap ->
        if(snap.phase==RoundPhase.ACTIVE && initial.isEmpty()) initial=ledger.initialHand()
        if(snap.remaining!=null) latestKnown=snap
        if(snap.phase==RoundPhase.UNAVAILABLE) failures.add("$time:${snap.reason}")
        history.add("{\"timeMs\":$time,\"phase\":${quote(snap.phase.name)},\"remaining\":${snap.remaining?.values?.sum()},\"previous\":${snap.played(Seat.PREVIOUS)},\"opposite\":${snap.played(Seat.OPPOSITE)},\"next\":${snap.played(Seat.NEXT)}}")
    }
    val trace=if(args.size==3) File(args[2]).bufferedWriter(Charsets.UTF_8) else null
    var pixels=IntArray(0)
    File(args[0]).bufferedReader(Charsets.UTF_8).useLines { rows -> rows.forEach { row ->
        val f=row.split(';',limit=7); require(f.size==7)
        time=f[0].toLong(); val trackingPhase=ledger.phaseBeforeRecovery()
        val scene=continueTeammateScene(TableScene.valueOf(f[1]),trackingPhase); val width=f[2].toInt(); val height=f[3].toInt()
        val rgb=InflaterInputStream(ByteArrayInputStream(Base64.getDecoder().decode(f[4]))).use { it.readBytes() }
        require(rgb.size==width*height*3)
        if(pixels.size!=width*height) pixels=IntArray(width*height)
        for(i in pixels.indices) { val p=i*3; pixels[i]=(255 shl 24) or ((rgb[p].toInt() and 255) shl 16) or ((rgb[p+1].toInt() and 255) shl 8) or (rgb[p+2].toInt() and 255) }
        val glyphs=if(f[5].isBlank()) emptyList() else f[5].split('|').map { item ->
            val a=item.split(','); val rank=Rank.valueOf(a[0]); val suit=Suit.valueOf(a[1])
            PlayGlyph(Card(rank,suit),a[2].toFloat(),GlyphBox(a[3].toFloat(),a[4].toFloat(),a[5].toFloat(),a[6].toFloat()))
        }.filter { it.box.cy>.13f && it.box.cx in .10f.. .88f && it.box.width<.12f && it.box.height>.02f }
        val partition=partitionGuandanHand(glyphs); val hand=partition.hand.map { it.card }
        val removed=ledger.currentHand().toMutableList(); val subset=hand.all { removed.remove(it) }
        val round=ledger.snapshot(); val previous=ledger.currentHand()
        ownExit.prepare(time,scene,round.roundId)
        if(scene!=TableScene.TEAMMATE && partition.hand.all { it.confidence>=.65f })
            ownExit.observeOwnHand(time,trackingPhase,previous,hand)
        if(scene==TableScene.TEAMMATE)
            ownExit.teammateTransition(time,trackingPhase,previous,partition.table.filter { it.box.cy<.55f })
        val proof=ownExit.prepare(time,scene,round.roundId)
        val assignedOwnership=assignPlayGroups(partition.table.filter { it.box.cy<.55f },emptySet(),if(subset && scene!=TableScene.TEAMMATE) removed else emptyList(),
            scene==TableScene.TEAMMATE,proof,confirmedFinishedSeats(round.copy(phase=trackingPhase)),ownExit.blocksCentralFallback())
        val owned=observer.reassociatePresentationFragments(assignedOwnership,time)
        val seen=evidence.evaluate(pixels,width,height,scene,hand.size,owned.seats,ledger.snapshot().roundId)
        val self=owned.seats.getValue(Seat.SELF)
        if(seen.getValue(Seat.SELF).observed) ownExit.observe(time,trackingPhase,previous,hand,self)
        ownExit.emptySurface(seen.getValue(Seat.SELF).observed && seen.getValue(Seat.SELF).reason=="table_surface_visible",self.isNotEmpty(),
            owned.ambiguous.any { it.box.cy in .285f.. .48f && it.box.cx in .32f.. .70f })
        trace?.appendLine("{\"timeMs\":$time,\"scene\":${quote(scene.name)},\"hand\":${cards(hand)},\"phase\":${quote(ledger.snapshot().phase.name)},\"groups\":{${owned.seats.entries.joinToString(",") { (seat,g) -> "${quote(seat.name)}:${cards(g.map { it.card })}" }}},\"empty\":{${seen.entries.joinToString(",") { (seat,e) -> "${quote(seat.name)}:{\"observed\":${e.observed},\"reason\":${quote(e.reason)}}" }}}" + "}")
        if(confirmedRoundFinished(round) && scene !in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.DEALING,TableScene.TRIBUTE)) {
            observer.observe(TableFrame(time,scene,null))
        } else if(owned.ambiguous.isNotEmpty() && scene !in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.TRIBUTE)) {
            ambiguous++; observer.observe(TableFrame(time,scene,if(scene==TableScene.TEAMMATE) null else hand,
                selfFinished=f[6].toBoolean(),incompleteReason="出牌归属待核对，正在重新识别",ambiguousPresentation=true))
        } else {
            val plays=owned.seats.mapValues { (seat,g) -> ObservedPlay(g.map { it.card },g.minOfOrNull { it.confidence } ?: 1f,g,seen.getValue(seat).observed) }
            observer.observe(TableFrame(time,scene,if(scene==TableScene.TEAMMATE) null else hand,plays,f[6].toBoolean(),
                teammateHand=hand.takeIf { scene==TableScene.TEAMMATE && partition.hand.all { it.confidence>=.65f } }))
        }
        frames++
    } }
    trace?.close()
    val final=latestKnown
    val events=final?.plays?.values?.flatten().orEmpty().sortedBy { it.sequence }
    val encoded="{\"frames\":$frames,\"ambiguousFrames\":$ambiguous,\"initial\":${cards(initial)},\"finalPhase\":${quote(ledger.snapshot().phase.name)},\"lastKnownPhase\":${quote(final?.phase?.name ?: "NONE")},\"remaining\":${final?.remaining?.values?.sum()},\"failures\":[${failures.joinToString(",") { quote(it) }}],\"history\":[${history.joinToString(",")}],\"events\":[${events.joinToString(",") { p -> "{\"id\":${quote(p.eventId)},\"sequence\":${p.sequence},\"seat\":${quote(p.seat.name)},\"cards\":${cards(p.cards)}}" }}]}"
    File(args[1]).writeText(encoded,Charsets.UTF_8)
    println("Replay frames=$frames ambiguous=$ambiguous remaining=${final?.remaining?.values?.sum()} failures=${failures.size}")
}
