package com.cyotun.jpq.core

/** A bounded proof window, never a second ledger or a guessed play history. */
internal class RoundRecovery(
    private val startedAt: Long,
    private val ownHand: List<Card>,
    private val selfFinished: Boolean,
    private val anchors: Map<Seat,Anchor>,
    private val opening: Boolean,
) {
    data class Anchor(val cards: List<Card>,val glyphs: List<PlayGlyph>,val eventId: String?,val observedAt: Long)
    private val emptySeen=anchors.filterValues { it.cards.isEmpty() }.mapValues { it.value.observedAt }.toMutableMap()
    private val brokenEmpty=mutableSetOf<Seat>()
    private var candidate=""
    private var votes=0
    private var firstVote=0L
    private var lastVote=-1L
    private var previousGlyphs=emptyMap<Seat,List<PlayGlyph>>()
    private fun key(cards: List<Card>)=cards.map { it.colorKey() }.sorted().joinToString(",")

    /** Null also means the old checkpoint cannot prove that no play was missed. */
    fun reconcile(frame: TableFrame): Map<String,List<Card>>? {
        if(frame.timeMs-startedAt !in 0L..4000L) { votes=0; return null }
        val expansions=mutableMapOf<String,List<Card>>()
        var complete=frame.scene in setOf(TableScene.TABLE,TableScene.TEAMMATE) && frame.incompleteReason==null &&
            anchors.values.all { startedAt-it.observedAt in 0L..500L }
        val ownProven=if(selfFinished) frame.scene==TableScene.TEAMMATE || frame.selfFinished
            else frame.hand?.let { key(it)==key(ownHand) }==true || frame.selfFinished || frame.scene==TableScene.TEAMMATE
        complete=complete && ownProven
        for((seat,anchor) in anchors) {
            val play=frame.plays[seat]
            val observed=play!=null && play.regionObserved && play.confidence>=.65f
            if(anchor.cards.isEmpty()) {
                if(observed && play!!.cards.isEmpty()) {
                    if(frame.timeMs-(emptySeen[seat] ?: -10000L)>500) brokenEmpty.add(seat)
                    emptySeen[seat]=frame.timeMs
                } else if(observed && play!!.cards.isNotEmpty()) brokenEmpty.add(seat)
                if(!observed || play!!.cards.isNotEmpty() || seat in brokenEmpty) complete=false
                continue
            }
            if(!observed || play!!.glyphs.size!=play.cards.size || key(play.glyphs.map { it.card })!=key(play.cards) ||
                anchor.glyphs.size!=anchor.cards.size ||
                !containsTracked(play.glyphs,anchor.glyphs)) { complete=false; continue }
            if(key(play.cards)!=key(anchor.cards)) {
                val event=anchor.eventId
                if(event==null) complete=false else expansions[event]=play.cards
            }
        }
        // At least one physical presentation must reconnect a played round.
        // A blank table and an unchanged hand alone cannot recover missed events.
        if(anchors.isEmpty() || (!opening && anchors.values.none { it.cards.isNotEmpty() })) complete=false
        if(!complete) { votes=0; return null }
        val signature=anchors.keys.joinToString(";") { seat -> "$seat:${key(frame.plays.getValue(seat)!!.cards)}" }
        val stableGeometry=anchors.keys.all { seat ->
            val current=frame.plays.getValue(seat)!!.glyphs
            val previous=previousGlyphs[seat].orEmpty()
            current.isEmpty() && previous.isEmpty() || (current.size==previous.size && containsTracked(current,previous))
        }
        if(signature!=candidate || lastVote<0 || frame.timeMs-lastVote>500 || !stableGeometry) {
            candidate=signature; votes=1; firstVote=frame.timeMs
        } else votes++
        lastVote=frame.timeMs
        previousGlyphs=anchors.keys.associateWith { frame.plays.getValue(it)!!.glyphs.toList() }
        return if(votes>=4 && frame.timeMs-firstVote>=180) expansions else null
    }
    private fun containsTracked(current: List<PlayGlyph>,old: List<PlayGlyph>): Boolean {
        if(old.isEmpty()) return false
        val rest=current.toMutableList()
        return old.all { tracked ->
            val match=rest.indexOfFirst { p -> p.card==tracked.card && p.box.width>0 && p.box.height>0 &&
                kotlin.math.abs(p.box.cx-tracked.box.cx)<=minOf(p.box.width,tracked.box.width)*.35f &&
                kotlin.math.abs(p.box.cy-tracked.box.cy)<=minOf(p.box.height,tracked.box.height)*.35f &&
                maxOf(p.box.width,tracked.box.width)<=minOf(p.box.width,tracked.box.width)*1.4f &&
                maxOf(p.box.height,tracked.box.height)<=minOf(p.box.height,tracked.box.height)*1.4f }
            if(match<0) false else { rest.removeAt(match); true }
        }
    }
}
