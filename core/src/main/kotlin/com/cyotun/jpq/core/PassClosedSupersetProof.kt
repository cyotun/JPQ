package com.cyotun.jpq.core

/**
 * Proves that a directly visible strict superset is a new play after a pass
 * closed the prior presentation's turn. It never edits that prior event.
 *
 * Pass observations only establish a bounded boundary. The returned cards
 * always come from a new, complete glyph group which does not retain an old
 * card identity in its old physical slot.
 */
class PassClosedSupersetProof {
    private data class Scope(
        val roundId: String,
        val seat: Seat,
        val priorEventId: String,
        val priorSequence: Long,
        val priorCards: List<String>,
        val priorGlyphs: List<String>,
        val ownHand: List<String>,
    )

    private data class ClosingEvent(
        val sequence: Long,
        val seat: Seat,
        val eventId: String,
        val cards: List<String>,
    )

    private data class ClosingVersion(val events: List<ClosingEvent>)

    private data class Candidate(
        val identity: List<String>,
        val anchor: ObservedPlay,
        var latest: ObservedPlay,
        val firstTime: Long,
        var lastTime: Long,
        var votes: Int,
    )

    private var scope: Scope? = null
    // closingVersion is the immutable closure prefix which authorized the
    // witness. observedClosingVersion advances only across verified appends.
    private var closingVersion: ClosingVersion? = null
    private var observedClosingVersion: ClosingVersion? = null
    private var closingFenceAt = -1L
    private var lastFrameTime = -1L
    private var lastPriorObservedAt = -1L
    private var firstPassSource = -1L
    private var lastPassSource = -1L
    private var passVotes = 0
    private var candidate: Candidate? = null
    private var ownershipAmbiguityUntil: Long? = null
    // Keep successful versions fenced across transient observation faults. A
    // later recovery of the same presentation must not emit it a second time.
    private val delivered = linkedSetOf<String>()

    /** Drop pending proof state after a caller-observed global fault/round reset. */
    fun clear() = reset()

    /**
     * True only while this exact frame extends a live, strong candidate which
     * has at least one strong vote. Callers may use this to defer a conflicting
     * fallback for this frame; the pass witness alone never suppresses it.
     */
    fun pendingStrongCandidate(timeMs: Long): Boolean {
        val current=candidate ?: return false
        return current.votes>=1 && current.lastTime==timeMs &&
            timeMs-current.firstTime in 0L..MAX_CANDIDATE_LIFETIME_MS &&
            timeMs-current.lastTime in 0L..MAX_CANDIDATE_FRAME_GAP_MS &&
            passVotes>=REQUIRED_PASS_VOTES && lastPassSource>=0 &&
            timeMs-lastPassSource in 0L..MAX_PASS_AGE_MS &&
            scope!=null && observedClosingVersion!=null
    }

    /**
     * Preserve an already established pass fence across one short ownership-only
     * ambiguity. This frame contributes no pass or candidate vote. The caller
     * must still run its ordinary recovery path when this returns false.
     *
     * Repeated ambiguity frames can advance the observation clock only until
     * the fixed deadline set by the first accepted frame; they never refresh
     * the pass-source timestamps or candidate's first/last visible times.
     */
    fun retainAcrossOwnershipAmbiguity(
        frame: TableFrame,
        snapshot: RoundSnapshot,
        seat: Seat,
        prior: QuantityAnchoredPlayProof.PriorPresentation?,
        expectedOwnHand: List<Card>,
        priorObservedAt: Long,
    ): Boolean {
        val currentClosing = prior?.let { currentClosingVersion(snapshot, seat, it) }
        val sameClosing = currentClosing != null && currentClosing == observedClosingVersion
        val appendedClosing = currentClosing != null && observedClosingVersion != null &&
            isPrefix(observedClosingVersion!!.events, currentClosing.events) &&
            passVotes >= REQUIRED_PASS_VOTES &&
            lastPassSource - firstPassSource >= PASS_MIN_SPAN_MS &&
            lastPassSource >= 0 && frame.timeMs - lastPassSource in 0L..MAX_PASS_AGE_MS
        val valid = frame.ambiguousPresentation &&
            frame.scene == TableScene.TABLE &&
            !frame.captureGap &&
            !frame.selfFinished &&
            !frame.dealingChoicesObserved &&
            frame.localizedOverlaySeats.isEmpty() &&
            (frame.incompleteReason == null || frame.incompleteReason == OWNERSHIP_REVIEW_REASON) &&
            frame.hand != null && sameCards(frame.hand, expectedOwnHand) && expectedOwnHand.isNotEmpty() &&
            frame.timeMs > lastFrameTime && lastFrameTime >= 0 && frame.timeMs - lastFrameTime <= MAX_FRAME_GAP_MS &&
            snapshot.phase == RoundPhase.ACTIVE && seat != Seat.SELF &&
            prior != null && matchesCurrentScope(snapshot, seat, prior, expectedOwnHand, priorObservedAt) &&
            hasEstablishedPass(frame.timeMs) && (sameClosing || appendedClosing)

        if (!valid) {
            clearProof()
            ownershipAmbiguityUntil = null
            return false
        }

        val deadline = ownershipAmbiguityUntil ?: (frame.timeMs + MAX_OWNERSHIP_AMBIGUITY_MS)
        if (frame.timeMs > deadline) {
            clearProof()
            ownershipAmbiguityUntil = null
            return false
        }
        ownershipAmbiguityUntil = deadline
        // Match normal observation behavior: once the whole retention gate has
        // passed, an established pass may advance across verified new events.
        // Keep closingVersion as the original pass fence.
        if(appendedClosing) observedClosingVersion = currentClosing
        // Advance only the inter-frame clock. Pass and candidate evidence keeps
        // its original source times, so this cannot create votes or extend TTL.
        lastFrameTime = frame.timeMs
        return true
    }

    /**
     * expectedOwnHand is the immutable active-round hand checkpoint. The
     * caller supplies priorObservedAt from the current presentation's lastSeen
     * time so old/cached pass text cannot predate its latest glyph observation.
     */
    fun observe(
        frame: TableFrame,
        snapshot: RoundSnapshot,
        seat: Seat,
        prior: QuantityAnchoredPlayProof.PriorPresentation?,
        expectedOwnHand: List<Card>,
        priorObservedAt: Long,
    ): ObservedPlay? {
        if(frame.timeMs<=lastFrameTime) {
            if(frame.timeMs<lastFrameTime) reset()
            return null
        }
        if(ownershipAmbiguityUntil?.let { frame.timeMs > it } == true) clearProof()
        if(lastFrameTime>=0 && frame.timeMs-lastFrameTime>MAX_FRAME_GAP_MS) clearProof()
        ownershipAmbiguityUntil = null
        lastFrameTime=frame.timeMs

        val previous=prior ?: run { clearProof(); return null }
        val roundId=snapshot.roundId ?: run { clearProof(); return null }
        val ownHand=frame.hand ?: run { clearProof(); return null }
        if(snapshot.phase!=RoundPhase.ACTIVE || frame.scene!=TableScene.TABLE || seat==Seat.SELF ||
            frame.selfFinished || frame.captureGap || frame.ambiguousPresentation || frame.incompleteReason!=null ||
            !sameCards(ownHand,expectedOwnHand) || expectedOwnHand.isEmpty() ||
            priorObservedAt<0 || previous.eventId.isBlank() || previous.cards.isEmpty() ||
            previous.glyphs.size!=previous.cards.size || !validGlyphs(previous.glyphs,previous.cards)) {
            clearProof(); return null
        }

        val latestForSeat=snapshot.plays[seat].orEmpty().lastOrNull()
        if(latestForSeat==null || latestForSeat.eventId!=previous.eventId ||
            !sameCards(latestForSeat.cards,previous.cards)) {
            clearProof(); return null
        }

        val nextScope=Scope(roundId,seat,previous.eventId,latestForSeat.sequence,
            cardKeys(previous.cards),glyphKeys(previous.glyphs),cardKeys(expectedOwnHand))
        if(scope!=nextScope) {
            clearProof()
            scope=nextScope
        }
        if(priorObservedAt>lastPriorObservedAt) {
            if(lastPassSource>=0 && lastPassSource<=priorObservedAt) clearPassAndCandidate()
            lastPriorObservedAt=priorObservedAt
        } else if(priorObservedAt<lastPriorObservedAt) {
            clearPassAndCandidate()
            lastPriorObservedAt=priorObservedAt
        } else if(lastPriorObservedAt<0) {
            lastPriorObservedAt=priorObservedAt
        }

        val newerOtherEvents=snapshot.plays.values.flatten()
            .filter { it.seat!=seat && it.sequence>latestForSeat.sequence }
            .sortedBy { it.sequence }
        if(newerOtherEvents.isEmpty()) {
            clearPassAndCandidate()
            closingVersion=null
            observedClosingVersion=null
            closingFenceAt=-1L
            return null
        }
        val nextClosing=ClosingVersion(newerOtherEvents.map { event ->
            ClosingEvent(event.sequence,event.seat,event.eventId,cardKeys(event.cards))
        })
        if(!validClosingChain(nextClosing.events)) {
            clearPassAndCandidate()
            closingVersion=nextClosing
            observedClosingVersion=nextClosing
            closingFenceAt=frame.timeMs
            return null
        }
        if(closingVersion==null) {
            closingVersion=nextClosing
            observedClosingVersion=nextClosing
            closingFenceAt=frame.timeMs
            clearPassAndCandidate()
        } else if(observedClosingVersion!=nextClosing) {
            val previous=observedClosingVersion
            val appendOnly=previous!=null && isPrefix(previous.events,nextClosing.events)
            val passAlreadyClosed=passVotes>=REQUIRED_PASS_VOTES &&
                lastPassSource-firstPassSource>=PASS_MIN_SPAN_MS &&
                lastPassSource>=0 && frame.timeMs-lastPassSource in 0L..MAX_PASS_AGE_MS
            if(appendOnly && passAlreadyClosed) {
                // Later other-seat events cannot reopen this seat's passed
                // presentation. Keep the first closure fence and witness;
                // candidate clocks also remain anchored to their first vote.
                observedClosingVersion=nextClosing
            } else {
                // If a pass was not already proved, restart its source fence at
                // the new version so pre-append OCR cannot complete it later.
                closingVersion=nextClosing
                observedClosingVersion=nextClosing
                closingFenceAt=frame.timeMs
                clearPassAndCandidate()
            }
        }

        val current=frame.plays[seat]
        if(current==null) {
            clearPassAndCandidate()
            return null
        }
        val passSource=frame.observedPasses[seat]
        if(current.cards.isEmpty() && current.glyphs.isEmpty()) {
            if(passSource!=null) observePassSource(passSource,frame.timeMs,priorObservedAt)
            expirePassWitness(frame.timeMs)
            return null
        }

        // Any structurally invalid visible group during the pass interval
        // invalidates the boundary. A structurally complete but weak reading
        // is an abstention: it cannot vote, yet need not erase a fresh pass
        // fence established independently before this arrival effect.
        if(passVotes<REQUIRED_PASS_VOTES || lastPassSource-firstPassSource<PASS_MIN_SPAN_MS ||
            frame.timeMs-lastPassSource>MAX_PASS_AGE_MS ||
            !structurallyValidNewSuperset(current,previous)) {
            clearPassAndCandidate()
            return null
        }
        if(!strongNewSuperset(current)) {
            candidate=null
            return null
        }
        return observeCandidate(current,frame.timeMs,previous)
    }

    private fun observePassSource(sourceTime: Long,frameTime: Long,priorObservedAt: Long) {
        if(sourceTime<=priorObservedAt || sourceTime<closingFenceAt || sourceTime>frameTime ||
            frameTime-sourceTime !in 0L..MAX_PASS_SOURCE_AGE_MS) {
            clearPassAndCandidate()
            return
        }
        if(sourceTime==lastPassSource) return // repeated/cached OCR is one vote
        if(lastPassSource>=0 && sourceTime<lastPassSource) {
            clearPassAndCandidate()
            return
        }
        if(lastPassSource>=0 && sourceTime-lastPassSource>MAX_PASS_SOURCE_GAP_MS) {
            firstPassSource=sourceTime
            lastPassSource=sourceTime
            passVotes=1
            candidate=null
            return
        }
        if(passVotes==0) firstPassSource=sourceTime
        lastPassSource=sourceTime
        passVotes++
        if(passVotes<REQUIRED_PASS_VOTES || lastPassSource-firstPassSource<PASS_MIN_SPAN_MS)
            candidate=null
    }

    private fun expirePassWitness(frameTime: Long) {
        if(lastPassSource>=0 && frameTime-lastPassSource>MAX_PASS_AGE_MS) clearPassAndCandidate()
    }

    private fun observeCandidate(current: ObservedPlay,frameTime: Long,
        prior: QuantityAnchoredPlayProof.PriorPresentation): ObservedPlay? {
        val identity=cardKeys(current.cards)
        val deliveredKey="${scope?.roundId}:${scope?.seat}:${scope?.priorEventId}:${closingVersion?.events}:$identity"
        if(deliveredKey in delivered) return null
        val existing=candidate
        if(existing==null) {
            candidate=Candidate(identity,copy(current),copy(current),frameTime,frameTime,1)
            return null
        }
        if(frameTime-existing.firstTime>MAX_CANDIDATE_LIFETIME_MS || frameTime-existing.lastTime>MAX_CANDIDATE_FRAME_GAP_MS ||
            identity!=existing.identity || !sameSlots(current,existing.anchor) || !sameSlots(current,existing.latest) ||
            matchesAnyOldSlot(current.glyphs,prior.glyphs)) {
            clearPassAndCandidate()
            return null
        }
        existing.latest=copy(current)
        existing.lastTime=frameTime
        existing.votes++
        if(existing.votes<REQUIRED_CANDIDATE_VOTES || frameTime-existing.firstTime<CANDIDATE_MIN_SPAN_MS)
            return null
        delivered += deliveredKey
        if(delivered.size>128) delivered.remove(delivered.first())
        val result=copy(existing.latest)
        candidate=null
        return result
    }

    private fun structurallyValidNewSuperset(play: ObservedPlay,
        prior: QuantityAnchoredPlayProof.PriorPresentation): Boolean =
        play.regionObserved && play.confidence in MIN_GLYPH_CONFIDENCE..1f && validGlyphs(play.glyphs,play.cards) &&
            play.cards.size>prior.cards.size && isSubset(prior.cards,play.cards) &&
            !matchesAnyOldSlot(play.glyphs,prior.glyphs)

    private fun strongNewSuperset(play: ObservedPlay): Boolean =
        play.confidence>=MIN_STRONG_CONFIDENCE && play.glyphs.all { it.confidence>=MIN_STRONG_CONFIDENCE }

    private fun clearPassAndCandidate() {
        firstPassSource=-1L
        lastPassSource=-1L
        passVotes=0
        candidate=null
    }

    private fun clearProof() {
        scope=null
        closingVersion=null
        observedClosingVersion=null
        closingFenceAt=-1L
        lastPriorObservedAt=-1L
        clearPassAndCandidate()
        ownershipAmbiguityUntil=null
    }

    private fun matchesCurrentScope(snapshot: RoundSnapshot, seat: Seat,
        prior: QuantityAnchoredPlayProof.PriorPresentation, expectedOwnHand: List<Card>, priorObservedAt: Long): Boolean {
        val latest=snapshot.plays[seat].orEmpty().lastOrNull() ?: return false
        val roundId=snapshot.roundId ?: return false
        val expected=Scope(roundId,seat,latest.eventId,latest.sequence,cardKeys(latest.cards),
            glyphKeys(prior.glyphs),cardKeys(expectedOwnHand))
        return scope==expected && latest.eventId==prior.eventId && sameCards(latest.cards,prior.cards) &&
            latest.eventId.isNotBlank() && prior.cards.isNotEmpty() &&
            prior.glyphs.size==prior.cards.size && validGlyphs(prior.glyphs,prior.cards) &&
            priorObservedAt==lastPriorObservedAt
    }

    private fun hasEstablishedPass(timeMs: Long): Boolean = passVotes>=REQUIRED_PASS_VOTES &&
        lastPassSource-firstPassSource>=PASS_MIN_SPAN_MS && lastPassSource>=0 &&
        timeMs-lastPassSource in 0L..MAX_PASS_AGE_MS && closingVersion!=null && observedClosingVersion!=null

    private fun currentClosingVersion(snapshot: RoundSnapshot, seat: Seat,
        prior: QuantityAnchoredPlayProof.PriorPresentation): ClosingVersion? {
        val latest=snapshot.plays[seat].orEmpty().lastOrNull()?.takeIf { it.eventId==prior.eventId } ?: return null
        val events=snapshot.plays.values.flatten().filter { it.seat!=seat && it.sequence>latest.sequence }
            .sortedBy { it.sequence }.map { ClosingEvent(it.sequence,it.seat,it.eventId,cardKeys(it.cards)) }
        if(!validClosingChain(events)) return null
        return ClosingVersion(events)
    }

    private fun reset() {
        clearProof()
        lastFrameTime=-1L
    }

    private fun sameCards(left: List<Card>,right: List<Card>): Boolean = cardKeys(left)==cardKeys(right)
    private fun cardKeys(cards: List<Card>)=cards.map { it.colorKey() }.sorted()
    private fun validClosingChain(events: List<ClosingEvent>): Boolean = events.isNotEmpty() &&
        events.all { it.sequence>=0 && it.eventId.isNotBlank() && it.cards.isNotEmpty() } &&
        events.map { it.eventId }.distinct().size==events.size &&
        events.zipWithNext().all { (a,b) -> a.sequence<b.sequence }
    private fun isPrefix(prefix: List<ClosingEvent>,all: List<ClosingEvent>): Boolean =
        prefix.size < all.size && all.take(prefix.size)==prefix &&
            all.drop(prefix.size).zipWithNext().all { (a,b) -> a.sequence<b.sequence } &&
            all[prefix.size].sequence>prefix.last().sequence
    private fun glyphKeys(glyphs: List<PlayGlyph>)=glyphs.map { glyph ->
        "${glyph.card.colorKey()}:${glyph.box.left}:${glyph.box.top}:${glyph.box.right}:${glyph.box.bottom}"
    }.sorted()

    private fun isSubset(subset: List<Card>,all: List<Card>): Boolean {
        val rest=cardKeys(all).toMutableList()
        return cardKeys(subset).all { rest.remove(it) }
    }

    private fun validGlyphs(glyphs: List<PlayGlyph>,cards: List<Card>): Boolean =
        cards.isNotEmpty() && cards.size==glyphs.size && cardKeys(cards)==cardKeys(glyphs.map { it.card }) &&
            glyphs.all { glyph ->
                val box=glyph.box
                glyph.confidence in MIN_GLYPH_CONFIDENCE..1f && validBox(box)
            } && glyphs.indices.all { i ->
                (i+1 until glyphs.size).none { j ->
                    val a=glyphs[i].box; val b=glyphs[j].box
                    kotlin.math.abs(a.cx-b.cx)<=minOf(a.width,b.width)*.1f &&
                        kotlin.math.abs(a.cy-b.cy)<=minOf(a.height,b.height)*.1f
                }
            }

    private fun validBox(box: GlyphBox)=listOf(box.left,box.top,box.right,box.bottom).all { it.isFinite() } &&
        box.left>=0f && box.top>=0f && box.right<=1f && box.bottom<=1f && box.width>0f && box.height>0f

    private fun matchesAnyOldSlot(current: List<PlayGlyph>,old: List<PlayGlyph>): Boolean =
        current.any { glyph -> old.any { previous ->
            // A red/black classifier flicker is not proof that the old card left.
            glyph.card.rank==previous.card.rank && samePhysicalSlot(glyph.box,previous.box)
        } }

    private fun samePhysicalSlot(left: GlyphBox,right: GlyphBox): Boolean =
        kotlin.math.abs(left.cx-right.cx)<=minOf(left.width,right.width)*SLOT_CENTER_FRACTION &&
            kotlin.math.abs(left.cy-right.cy)<=minOf(left.height,right.height)*SLOT_CENTER_FRACTION &&
            maxOf(left.width,right.width)<=minOf(left.width,right.width)*MAX_SLOT_SIZE_RATIO &&
            maxOf(left.height,right.height)<=minOf(left.height,right.height)*MAX_SLOT_SIZE_RATIO

    private fun sameSlots(current: ObservedPlay,anchor: ObservedPlay): Boolean {
        if(cardKeys(current.cards)!=cardKeys(anchor.cards) || current.glyphs.size!=anchor.glyphs.size) return false
        val remaining=anchor.glyphs.toMutableList()
        return current.glyphs.all { glyph ->
            val match=remaining.indexOfFirst { old -> glyph.card.colorKey()==old.card.colorKey() &&
                samePhysicalSlot(glyph.box,old.box) }
            if(match<0) false else { remaining.removeAt(match); true }
        } && remaining.isEmpty()
    }

    private fun copy(play: ObservedPlay)=play.copy(cards=play.cards.toList(),glyphs=play.glyphs.toList())

    private companion object {
        const val OWNERSHIP_REVIEW_REASON="出牌归属待核对，正在重新识别"
        const val MIN_STRONG_CONFIDENCE=.80f
        const val MIN_GLYPH_CONFIDENCE=.70f
        const val REQUIRED_PASS_VOTES=2
        const val PASS_MIN_SPAN_MS=100L
        const val MAX_PASS_SOURCE_AGE_MS=1000L
        const val MAX_PASS_SOURCE_GAP_MS=1500L
        const val MAX_PASS_AGE_MS=15_000L
        const val MAX_FRAME_GAP_MS=500L
        const val MAX_OWNERSHIP_AMBIGUITY_MS=500L
        const val REQUIRED_CANDIDATE_VOTES=4
        const val CANDIDATE_MIN_SPAN_MS=180L
        const val MAX_CANDIDATE_FRAME_GAP_MS=500L
        const val MAX_CANDIDATE_LIFETIME_MS=2000L
        const val SLOT_CENTER_FRACTION=.35f
        const val MAX_SLOT_SIZE_RATIO=1.4f
    }
}
