package com.cyotun.jpq.core

/** Records a directly seen last own play before teammate view clears the own hand.
 * A completion label alone never supplies cards or changes an outside count. */
class OwnExitPlayProof {
    data class Certificate internal constructor(
        val roundId: String,
        val originalHand: List<Card>,
        val ownEvents: List<PlayEvent>,
        val cards: List<Card>
    )
    private data class Version(val round: String,val handKey: String,val hand: List<Card>,val ownEvents: List<PlayEvent>)
    private var version: Version?=null
    private var anchor: ObservedPlay?=null
    private var previous: ObservedPlay?=null
    private var firstTime=-1L
    private var lastTime=-1L
    private var votes=0
    private var ownSeenAt=-1L
    private var lastObservationTime=-1L
    // A short, rank-only departure hint bridges a known own hand to the next
    // complete whole-hand play. It never stores a play or contributes a vote.
    private var departureStart=-1L
    // Once the lower hand has visibly become foreign, retain an ACTIVE anchor
    // for at most 2000ms across the empty/scene handoff. Votes restart after it.
    private var foreignHandSeenAt=-1L
    private var continuationAt=-1L
    private val ambiguousOwnershipReason="出牌归属待核对，正在重新识别"

    fun reset() {
        version=null; ownSeenAt=-1L; lastObservationTime=-1L
        clearVotes(); clearTransition()
    }
    private fun clearVotes() { anchor=null; previous=null; firstTime=-1L; lastTime=-1L; votes=0 }
    private fun clearFreshVotes() { previous=null; firstTime=-1L; lastTime=-1L; votes=0 }
    private fun clearTransition() { departureStart=-1L; foreignHandSeenAt=-1L; continuationAt=-1L }

    fun observe(frame: TableFrame,snapshot: RoundSnapshot,knownOwnHand: List<Card>): List<Card>? =
        observeWithCertificate(frame,snapshot,knownOwnHand)?.cards

    /**
     * A certificate can continue only when a direct, exact-own-hand candidate
     * was already anchored while ACTIVE. SELF_FINISHED cannot seed a candidate.
     */
    fun observeWithCertificate(frame: TableFrame,snapshot: RoundSnapshot,knownOwnHand: List<Card>): Certificate? {
        val id=snapshot.roundId
        if(id==null || snapshot.remaining==null) { reset(); return null }
        val active=snapshot.phase==RoundPhase.ACTIVE
        if(departureStart>=0 && frame.timeMs-departureStart !in 0L..500L) departureStart=-1L
        val continuationLive=continuationAt>=0 && frame.timeMs-continuationAt in 0L..2000L
        if(continuationAt>=0 && !continuationLive) { reset(); return null }
        val latched=snapshot.phase==RoundPhase.SELF_FINISHED && version!=null && anchor!=null && (votes>0 || continuationLive) &&
            version?.round==id && knownOwnHand.isEmpty() &&
            version?.ownEvents==snapshot.plays[Seat.SELF].orEmpty()
        if(!active && !latched) { reset(); return null }
        val originalHand=if(active) knownOwnHand else version!!.hand
        if(originalHand.isEmpty() || snapshot.played(Seat.SELF)+originalHand.size!=27) { reset(); return null }
        if(active) {
            val next=Version(id,key(originalHand),originalHand.toList(),snapshot.plays[Seat.SELF].orEmpty().map { it.copy(cards=it.cards.toList()) })
            if(version!=next) { reset(); version=next }
        }
        if(frame.timeMs<=lastObservationTime) return null
        lastObservationTime=frame.timeMs
        val frameHand=if(frame.scene==TableScene.TEAMMATE)
            frame.teammateHand.takeIf { frame.teammateHandConfidence>=.65f } else frame.hand
        val foreignHand=frameHand!=null && frameHand.isNotEmpty() && !isSubset(frameHand,originalHand)
        val handoffHasNoSelfPlay=frame.plays[Seat.SELF]?.cards.isNullOrEmpty()
        val freshAnchoredHandoff=anchor!=null && votes>0 && frame.timeMs-lastTime in 0L..500L
        val ongoingHandoff=anchor!=null && continuationAt>=0L && continuationLive
        val ambiguousForeignHandoff = frame.scene==TableScene.TEAMMATE && frame.ambiguousPresentation &&
            frame.incompleteReason==ambiguousOwnershipReason && !frame.captureGap &&
            frame.localizedOverlaySeats.isEmpty() && !frame.dealingChoicesObserved &&
            (freshAnchoredHandoff || ongoingHandoff) &&
            foreignHand && handoffHasNoSelfPlay && (active || latched)
        if(ambiguousForeignHandoff) {
            // Keep only the already established exact-own-hand authority. The
            // ambiguous frame supplies no card vote, and repeated ambiguous
            // frames cannot restart or extend this handoff deadline.
            if(continuationAt<0L) continuationAt=frame.timeMs
            foreignHandSeenAt=frame.timeMs
            clearFreshVotes()
            return null
        }
        if(frame.captureGap || frame.incompleteReason!=null || frame.ambiguousPresentation ||
            frame.localizedOverlaySeats.isNotEmpty() ||
            frame.scene !in setOf(TableScene.TABLE,TableScene.TEAMMATE)) {
            reset()
            return null
        }
        if(frameHand!=null && key(frameHand)==key(originalHand)) {
            clearVotes(); clearTransition()
            if(active && frame.scene==TableScene.TABLE) ownSeenAt=frame.timeMs else reset()
            return null
        }
        val play=frame.plays[Seat.SELF]
        val wholeOwnPlay=play!=null && strong(play) && key(play.cards)==key(originalHand)
        val absentSelfPlay=play==null || play.cards.isEmpty()
        // A partial remnant of the old hand is not proof that the lower area
        // has been replaced by another player's complete hand.
        val departed=frameHand!=null && (frameHand.isEmpty() || foreignHand)

        // A terminal cue can close an already established full-hand proof,
        // but cannot supply a missing card or start a candidate. This branch
        // runs before absent-play handling clears the three direct votes.
        val terminalWholeHandFromPriorReads = frame.selfFinished && frameHand?.isEmpty()==true &&
            !frame.dealingChoicesObserved && absentSelfPlay && snapshot.played(Seat.OPPOSITE)==27 && anchor!=null && votes>=3 &&
            previous!=null && (active || latched) && frame.timeMs-lastTime in 0L..500L &&
            frame.timeMs-firstTime in 100L..1500L && key(anchor!!.cards)==key(originalHand) &&
            sameSlots(previous!!,anchor!!)
        if(terminalWholeHandFromPriorReads) return completeCertificate()

        // First stage: an empty lower hand plus a directly observed strict
        // subset can only mark the start of the departure animation. It cannot
        // establish card identities, votes, or a certificate.
        val partialOwnPlay=play!=null && strong(play) && strictSubset(play.cards,originalHand)
        if(active && frame.scene==TableScene.TABLE && frameHand?.isEmpty()==true && partialOwnPlay &&
            ownSeenAt>=0 && frame.timeMs-ownSeenAt in 0L..1000L) {
            if(departureStart<0) departureStart=frame.timeMs
            return null
        }

        val noSelfPlay=play==null || play.cards.isEmpty()
        if(anchor!=null && (active || latched) && noSelfPlay) {
            if(continuationAt<0 && foreignHand && votes>0 && frame.timeMs-lastTime in 0L..500L) {
                // A complete non-own lower hand is positive replacement
                // evidence; start the bounded authority window at the actual
                // disappearance, not at the earlier own-hand witness.
                continuationAt=frame.timeMs
                foreignHandSeenAt=frame.timeMs
                clearFreshVotes()
                return null
            }
            val emptyOrUnobserved=frameHand==null || frameHand.isEmpty()
            if(continuationLive && (emptyOrUnobserved || foreignHand)) {
                clearFreshVotes()
                return null
            }
            if((active || latched) && continuationAt<0 && emptyOrUnobserved && votes>0 && foreignHandSeenAt>=0 &&
                frame.timeMs-foreignHandSeenAt in 0L..500L) {
                continuationAt=frame.timeMs
                clearFreshVotes()
                return null
            }
        }

        if(!departed || !wholeOwnPlay) {
            if(latched || continuationLive) reset()
            else {
                clearVotes()
                // The departure bridge is deliberately narrow: only the
                // explicit empty+partial stage can keep it alive.
                if(frameHand==null || frameHand.isNotEmpty() || !partialOwnPlay) departureStart=-1L
                foreignHandSeenAt=-1L
            }
            return null
        }
        val start=anchor
        if(start==null) {
            clearVotes()
            val witnessedOwn=ownSeenAt>=0 && frame.timeMs-ownSeenAt in 0L..500L
            val bridgedDeparture=departureStart>=0 && frame.timeMs-departureStart in 0L..500L
            if((!witnessedOwn && !bridgedDeparture) || frame.scene!=TableScene.TABLE) {
                departureStart=-1L
                return null
            }
            anchor=play.copy(cards=play.cards.toList(),glyphs=play.glyphs.toList())
            firstTime=frame.timeMs
            departureStart=-1L
        } else if(continuationLive && votes==0 && firstTime<0) {
            // The old ACTIVE geometry remains an identity/slot authority only;
            // every post-handoff vote must be fresh.
            if(!sameSlots(play,start)) { reset(); return null }
            firstTime=frame.timeMs
        } else if(frame.timeMs-lastTime>500L || frame.timeMs-firstTime>1500L ||
            !sameSlots(play,start) || previous==null || !sameSlots(play,previous!!)) {
            if(latched || continuationLive) { reset(); return null }
            clearVotes()
            val witnessedOwn=ownSeenAt>=0 && frame.timeMs-ownSeenAt in 0L..500L
            val bridgedDeparture=departureStart>=0 && frame.timeMs-departureStart in 0L..500L
            if((!witnessedOwn && !bridgedDeparture) || frame.scene!=TableScene.TABLE) {
                departureStart=-1L
                return null
            }
            anchor=play.copy(cards=play.cards.toList(),glyphs=play.glyphs.toList())
            firstTime=frame.timeMs
            departureStart=-1L
        }
        previous=play.copy(cards=play.cards.toList(),glyphs=play.glyphs.toList())
        lastTime=frame.timeMs
        if(foreignHand) foreignHandSeenAt=frame.timeMs
        // Each eligible frame is direct evidence, including a complete
        // terminal-frame display. The terminal cue still cannot supply cards.
        votes++
        if(!frame.selfFinished && frame.scene!=TableScene.TEAMMATE) return null
        // With our teammate already independently out, a positive own-finish
        // cue permits three direct reads of any whole original hand, but only
        // when the current lower hand is explicitly empty.
        val terminalWholeHand = votes>=3 && snapshot.played(Seat.OPPOSITE)==27 &&
            frame.selfFinished && !frame.dealingChoicesObserved && frameHand?.isEmpty()==true && wholeOwnPlay
        if((votes<4 && !terminalWholeHand) || frame.timeMs-firstTime<100L ||
            (frame.scene!=TableScene.TEAMMATE && !frame.selfFinished)) return null
        return completeCertificate()
    }

    private fun completeCertificate(): Certificate {
        // Return ledger-owned identities after matching every directly visible card.
        // The immutable initial hand already excludes these from outside remaining.
        val cards=version!!.hand.toList()
        val result=Certificate(version!!.round,cards,version!!.ownEvents.map { it.copy(cards=it.cards.toList()) },cards.toList())
        clearVotes(); clearTransition()
        return result
    }

    private fun key(cards: List<Card>)=cards.map { it.colorKey() }.sorted().joinToString(",")
    private fun isSubset(cards: List<Card>,of: List<Card>): Boolean {
        val remaining=of.toMutableList()
        return cards.all { card ->
            val at=remaining.indexOf(card)
            if(at<0) false else { remaining.removeAt(at); true }
        }
    }
    private fun strictSubset(cards: List<Card>,of: List<Card>)=cards.isNotEmpty() && cards.size<of.size && isSubset(cards,of)
    private fun valid(box: GlyphBox)=listOf(box.left,box.top,box.right,box.bottom).all { it.isFinite() && it in 0f..1f } &&
        box.width>0f && box.height>0f
    private fun strong(play: ObservedPlay)=play.regionObserved && play.confidence in .80f..1f && play.cards.isNotEmpty() &&
        play.cards.size==play.glyphs.size && key(play.cards)==key(play.glyphs.map { it.card }) &&
        play.glyphs.all { it.confidence in .80f..1f && valid(it.box) } &&
        play.glyphs.indices.all { i -> (i+1 until play.glyphs.size).none { j ->
            val a=play.glyphs[i].box; val b=play.glyphs[j].box
            // Reject two detections of the same print, while preserving real
            // partly overlapping/stacked card ranks as separate physical slots.
            kotlin.math.abs(a.cx-b.cx)<=minOf(a.width,b.width)*.1f &&
                kotlin.math.abs(a.cy-b.cy)<=minOf(a.height,b.height)*.1f
        } }
    private fun sameSlots(current: ObservedPlay,old: ObservedPlay): Boolean {
        val remaining=old.glyphs.toMutableList()
        return current.glyphs.all { glyph ->
            val index=remaining.indexOfFirst { tracked -> glyph.card.colorKey()==tracked.card.colorKey() &&
                kotlin.math.abs(glyph.box.cx-tracked.box.cx)<=minOf(glyph.box.width,tracked.box.width)*.35f &&
                kotlin.math.abs(glyph.box.cy-tracked.box.cy)<=minOf(glyph.box.height,tracked.box.height)*.35f &&
                maxOf(glyph.box.width,tracked.box.width)<=minOf(glyph.box.width,tracked.box.width)*1.4f &&
                maxOf(glyph.box.height,tracked.box.height)<=minOf(glyph.box.height,tracked.box.height)*1.4f }
            if(index<0) false else { remaining.removeAt(index); true }
        } && remaining.isEmpty()
    }
}
