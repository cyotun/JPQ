package com.cyotun.jpq.core

/** A bounded proof window, never a second ledger or a guessed play history. */
internal class RoundRecovery(
    private val startedAt: Long,
    private val ownHand: List<Card>,
    private val selfFinished: Boolean,
    private val anchors: Map<Seat,Anchor>,
    private val opening: Boolean,
    private val expectedHandCounts: Map<Seat,Int>,
    private val knownTeammateHand: List<Card>? = null,
) {
    data class Anchor(val cards: List<Card>,val glyphs: List<PlayGlyph>,val eventId: String?,val observedAt: Long)
    private val emptySeen=anchors.filterValues { it.cards.isEmpty() }.mapValues { it.value.observedAt }.toMutableMap()
    private val brokenEmpty=mutableSetOf<Seat>()
    private var candidate=""
    private var votes=0
    private var firstVote=0L
    private var lastVote=-1L
    private var previousGlyphs=emptyMap<Seat,List<PlayGlyph>>()
    private var countVotes=0
    private var firstCountSource=-1L
    private var lastCountSource=-1L
    private var countSourceCutoff=startedAt
    private var openingSourceCutoff=startedAt
    private val clearOpeningFrames=java.util.ArrayDeque<Long>()
    private var lastOpeningFrame=-1L
    private var openingVotes=0
    private var firstOpeningSource=-1L
    private var lastOpeningSource=-1L
    var recoveredTeammatePlay: ObservedPlay? = null
        private set
    private var teammateHandKey=""
    private var teammateDesk: ObservedPlay?=null
    private var teammateCountVotes=0
    private var firstTeammateSource=-1L
    private var lastTeammateSource=-1L
    private fun key(cards: List<Card>)=cards.map { it.colorKey() }.sorted().joinToString(",")
    fun observationInterrupted(timeMs: Long) {
        countSourceCutoff=maxOf(countSourceCutoff,timeMs)
        countVotes=0; firstCountSource=-1; lastCountSource=-1
        resetTeammateProof()
        resetOpeningProof(timeMs)
    }

    /** Null also means the old checkpoint cannot prove that no play was missed. */
    fun reconcile(frame: TableFrame): Map<String,List<Card>>? {
        // Visible pre-play choice controls can prove an opening after a longer
        // mask interruption. They cannot repair a round which already has plays.
        if(openingChoicesProven(frame)) return emptyMap()
        // Fresh independent remaining-card badges can establish that no outside
        // player lost a card, even after the physical-presentation window expired.
        // A cached OCR result is one observation, regardless of frame rate.
        if(unchangedCountsProven(frame)) return emptyMap()
        if(teammateDifferenceProven(frame)) return emptyMap()
        if(frame.timeMs-startedAt !in 0L..4000L) { votes=0; return null }
        val expansions=mutableMapOf<String,List<Card>>()
        var complete=frame.scene in setOf(TableScene.TABLE,TableScene.TEAMMATE) && frame.incompleteReason==null &&
            !frame.captureGap && !frame.ambiguousPresentation &&
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
    private fun unchangedCountsProven(frame: TableFrame): Boolean {
        val sceneValid=frame.scene in setOf(TableScene.TABLE,TableScene.TEAMMATE) &&
            !frame.captureGap && !frame.ambiguousPresentation &&
            (frame.incompleteReason==null || frame.onlyLocalizedOverlay())
        if(!sceneValid) {
            countSourceCutoff=maxOf(countSourceCutoff,frame.timeMs)
            countVotes=0; firstCountSource=-1; lastCountSource=-1
            resetTeammateProof()
        }
        val ownValid=if(selfFinished) frame.scene==TableScene.TEAMMATE || frame.selfFinished
            else frame.hand?.let { key(it)==key(ownHand) }==true
        val readings=expectedHandCounts.keys.mapNotNull { seat -> frame.remainingHandCounts[seat]?.let { seat to it } }.toMap()
        val countsValid=expectedHandCounts.isNotEmpty() && readings.keys==expectedHandCounts.keys && readings.all { (seat,reading) ->
            reading.count==expectedHandCounts[seat] && reading.sourceTimeMs>countSourceCutoff &&
                frame.timeMs-reading.sourceTimeMs in 0L..1000L
        }
        val source=readings.values.map { it.sourceTimeMs }.distinct().singleOrNull()
        val tableConsistent=anchors.all { (seat,anchor) ->
            val play=frame.plays[seat]
            // Every seat still needs its own independent unchanged quantity.
            // An obscured presentation cannot commit or expand a play; its
            // quantity can prove that no new card was lost behind the mask.
            (frame.onlyLocalizedOverlay() && seat in frame.localizedOverlaySeats) ||
            (play!=null && play.regionObserved && play.confidence>=.65f &&
                (play.cards.isEmpty() || (anchor.cards.isNotEmpty() && play.glyphs.size==play.cards.size &&
                    key(play.cards)==key(play.glyphs.map { it.card }) &&
                    containsTracked(anchor.glyphs,play.glyphs))))
        }
        if(!sceneValid || !ownValid || !countsValid || source==null || !tableConsistent) {
            countVotes=0; firstCountSource=-1; lastCountSource=-1; return false
        }
        fun ready()=countVotes>=3 && source-firstCountSource>=300L && frame.incompleteReason==null
        // A cached source never adds a vote. It may deliver an already proved
        // result when the local mask clears, provided the source is still fresh
        // and every current visible presentation passed the checks above.
        if(source==lastCountSource) return ready()
        if(lastCountSource<0 || source-lastCountSource !in 1L..1500L) {
            countVotes=1; firstCountSource=source
        } else countVotes++
        lastCountSource=source
        return ready()
    }
    private fun resetOpeningProof(timeMs: Long) {
        openingSourceCutoff=maxOf(openingSourceCutoff,timeMs)
        clearOpeningFrames.clear(); lastOpeningFrame=-1L
        openingVotes=0; firstOpeningSource=-1L; lastOpeningSource=-1L
    }
    private fun openingChoicesProven(frame: TableFrame): Boolean {
        val outside=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT)
        val clear=opening && !selfFinished && ownHand.size==27 &&
            expectedHandCounts.keys==outside.toSet() && expectedHandCounts.values.all { it==27 } &&
            anchors.values.all { it.cards.isEmpty() && it.eventId==null } &&
            frame.scene in setOf(TableScene.TABLE,TableScene.DEALING) && !frame.selfFinished &&
            !frame.captureGap && !frame.ambiguousPresentation && frame.incompleteReason==null &&
            frame.hand?.let { key(it)==key(ownHand) }==true &&
            frame.plays[Seat.SELF]?.cards.orEmpty().isEmpty() &&
            outside.all { seat -> frame.plays[seat]?.let { it.regionObserved && it.confidence>=.65f && it.cards.isEmpty() }==true }
        if(!clear) { resetOpeningProof(frame.timeMs); return false }
        if(lastOpeningFrame>=0 && frame.timeMs-lastOpeningFrame !in 1L..500L)
            resetOpeningProof(lastOpeningFrame)
        lastOpeningFrame=frame.timeMs
        clearOpeningFrames.addLast(frame.timeMs)
        while(clearOpeningFrames.isNotEmpty() && frame.timeMs-clearOpeningFrames.first>1000L) clearOpeningFrames.removeFirst()
        val source=frame.openingChoiceSourceTimeMs ?: return false
        // The asynchronous OCR source must itself have had a complete, clear
        // opening, and every intervening card frame must still prove that state.
        if(!frame.dealingChoicesObserved || frame.scene!=TableScene.DEALING || source<=openingSourceCutoff ||
            frame.timeMs-source !in 0L..1000L || !clearOpeningFrames.contains(source)) return false
        if(source==lastOpeningSource) return false
        if(lastOpeningSource>=0 && source<lastOpeningSource) {
            openingVotes=0; firstOpeningSource=-1L; return false
        }
        if(lastOpeningSource<0 || source-lastOpeningSource>1500L || openingVotes==0) {
            openingVotes=1; firstOpeningSource=source
        } else openingVotes++
        lastOpeningSource=source
        return openingVotes>=2 && source-firstOpeningSource>=300L
    }
    /** A complete known teammate hand accounts for every new card; other seats
     * still need independent unchanged quantities, never an inferred pass. */
    private fun teammateDifferenceProven(frame: TableFrame): Boolean {
        fun reject(): Boolean { resetTeammateProof(); return false }
        val known=knownTeammateHand ?: return reject()
        val expected=expectedHandCounts[Seat.OPPOSITE] ?: return reject()
        val hand=frame.teammateHand ?: return reject()
        if(!selfFinished || frame.scene!=TableScene.TEAMMATE || frame.captureGap ||
            frame.ambiguousPresentation || frame.incompleteReason!=null ||
            !(frame.teammateHandConfidence>=.80f) || known.size!=expected ||
            known.isEmpty() || hand.size>=known.size || !validCards(known) || !validCards(hand)) return reject()
        val removed=known.toMutableList()
        if(!hand.all { removed.remove(it) } || removed.isEmpty()) return reject()
        val play=frame.plays[Seat.OPPOSITE] ?: return reject()
        if(!play.regionObserved || !(play.confidence>=.80f) || play.cards.size!=play.glyphs.size ||
            key(play.cards)!=key(removed) || key(play.glyphs.map { it.card })!=key(removed) ||
            play.glyphs.any { !(it.confidence>=.80f) || !validBox(it.box) }) return reject()

        val otherSeats=expectedHandCounts.keys-Seat.OPPOSITE
        if(!anchors.keys.containsAll(otherSeats)) return reject()
        if(otherSeats.any { seat ->
            val anchor=anchors.getValue(seat)
            val observed=frame.plays[seat]
            observed==null || !observed.regionObserved || !(observed.confidence>=.65f) ||
                (observed.cards.isNotEmpty() && (anchor.cards.isEmpty() ||
                    observed.glyphs.size!=observed.cards.size ||
                    key(observed.cards)!=key(observed.glyphs.map { it.card }) ||
                    !containsTracked(anchor.glyphs,observed.glyphs)))
        }) return reject()
        val readings=otherSeats.mapNotNull { seat -> frame.remainingHandCounts[seat]?.let { seat to it } }.toMap()
        if(readings.keys!=otherSeats || readings.any { (seat,reading) -> reading.count!=expectedHandCounts[seat] }) return reject()
        val opposite=frame.remainingHandCounts[Seat.OPPOSITE]
        if(opposite!=null && opposite.count!=hand.size) return reject()
        val sources=readings.values.toList()+listOfNotNull(opposite)
        if(sources.isEmpty() || sources.any { it.sourceTimeMs<=countSourceCutoff ||
                frame.timeMs-it.sourceTimeMs !in 0L..1000L }) return reject()
        val source=sources.map { it.sourceTimeMs }.distinct().singleOrNull() ?: return reject()
        if(lastTeammateSource>=0 && source<lastTeammateSource) return reject()
        val previous=teammateDesk
        val sameCandidate=teammateHandKey==key(hand) && previous!=null &&
            previous.cards.size==play.cards.size && containsTracked(play.glyphs,previous.glyphs)
        if(!sameCandidate || lastTeammateSource<0 || source-lastTeammateSource>1500L) {
            resetTeammateProof()
            teammateHandKey=key(hand)
            teammateDesk=play.copy(cards=play.cards.toList(),glyphs=play.glyphs.toList())
            teammateCountVotes=1; firstTeammateSource=source; lastTeammateSource=source
            return false
        }
        if(source==lastTeammateSource) return false
        teammateCountVotes++; lastTeammateSource=source
        if(teammateCountVotes<3 || source-firstTeammateSource<300L) return false
        recoveredTeammatePlay=teammateDesk
        return true
    }
    private fun resetTeammateProof() {
        teammateHandKey=""; teammateDesk=null; teammateCountVotes=0
        firstTeammateSource=-1L; lastTeammateSource=-1L; recoveredTeammatePlay=null
    }
    private fun validCards(cards: List<Card>)=cards.groupingBy { it.rank }.eachCount().all { (rank,count) -> count<=rank.capacity } &&
        cards.groupingBy { it.colorKey() }.eachCount().all { (color,count) -> count<=if(':' in color) 4 else 2 }
    private fun validBox(box: GlyphBox)=listOf(box.left,box.top,box.right,box.bottom).all { it.isFinite() } &&
        box.left>=0 && box.top>=0 && box.right<=1 && box.bottom<=1 && box.width>0 && box.height>0
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
