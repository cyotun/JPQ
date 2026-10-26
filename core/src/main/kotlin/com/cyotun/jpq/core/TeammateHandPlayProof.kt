package com.cyotun.jpq.core

/**
 * Produces one proof of a new OPPOSITE play from a complete teammate hand
 * shrinking by exactly the cards directly visible in OPPOSITE's play region.
 * The caller still owns ledger mutation and must recheck the event version.
 */
class TeammateHandPlayProofTracker {
    private data class Version(val roundId: String, val events: List<String>)
    private data class DeskEvidence(val play: ObservedPlay, val time: Long)
    private data class Hand(val cards: List<Card>, val key: String)
    private data class IssuedHandoff(val version: Version,val cards: List<Card>,val hand: Hand,
        val votes: Int,val firstSeen: Long,val lastSeen: Long,val issuedAt: Long)
    private data class PartialHandoff(val hand: Hand,val removed: List<Card>,val glyphs: List<PlayGlyph>,
        val startedAt: Long,var fullEvidence: DeskEvidence? = null,var fullWitness: DeskEvidence? = null,
        var handVotes: Int = 1,var firstHandVote: Long? = startedAt,
        var lastHandVote: Long? = startedAt,
        var firstBadgeSource: Long? = null,var lastBadgeSource: Long = -1L,var badgeVotes: Int = 0)

    private var version: Version? = null
    private var lastTime: Long? = null
    private var seed: Hand? = null
    private var seedVotes = 0
    private var seedFirstSeen: Long? = null
    private var seedLastSeen: Long? = null
    private var shrink: Hand? = null
    private var shrinkVotes = 0
    private var shrinkFirstSeen: Long? = null
    private var shrinkLastSeen: Long? = null
    private var shrinkDeskEvidence: DeskEvidence? = null
    private var deskEvidence: DeskEvidence? = null
    private var partialHandoff: PartialHandoff? = null
    private var delivered = false
    private var issuedHandoff: IssuedHandoff? = null

    fun reset() {
        version = null
        lastTime = null
        clearProofState()
        issuedHandoff=null
    }

    /** Read-only checkpoint of the last independently confirmed complete hand.
     * Partial subset readings do not replace this seed or certify a play. */
    fun checkpointHand(snapshot: RoundSnapshot): List<Card>? {
        val roundId=snapshot.roundId ?: return null
        val remaining=snapshot.remaining ?: return null
        val currentVersion=Version(roundId,snapshot.plays[Seat.OPPOSITE].orEmpty().map {
            it.eventId+":"+it.sequence+":"+cardsKey(it.cards)
        })
        val known=seed ?: return null
        val first=seedFirstSeen ?: return null
        val last=seedLastSeen ?: return null
        if(snapshot.phase!=RoundPhase.SELF_FINISHED || version!=currentVersion || delivered ||
            snapshot.played(Seat.OPPOSITE)>=27 || known.cards.size!=27-snapshot.played(Seat.OPPOSITE) ||
            seedVotes<REQUIRED_VOTES || last-first<MIN_VOTE_SPAN_MS || !validHand(known.cards,remaining)) return null
        return known.cards.toList()
    }

    fun observe(frame: TableFrame, snapshot: RoundSnapshot): ObservedPlay? {
        val roundId = snapshot.roundId ?: run { reset(); return null }
        val nextVersion = Version(roundId, snapshot.plays[Seat.OPPOSITE].orEmpty().map {
            it.eventId + ":" + it.sequence + ":" + cardsKey(it.cards)
        })
        var rebasedSeed=false
        if (version != nextVersion) {
            val inherited=issuedHandoff?.takeIf { canRebase(it,version,nextVersion,frame,snapshot) }
            clearProofState()
            lastTime = null
            version = nextVersion
            issuedHandoff=null
            if(inherited!=null) {
                seed=inherited.hand.copy(cards=inherited.hand.cards.toList())
                seedVotes=inherited.votes
                seedFirstSeen=inherited.firstSeen
                seedLastSeen=inherited.lastSeen
                rebasedSeed=true
            }
        } else if(issuedHandoff!=null) {
            // The observer commits synchronously after consuming a proof. If
            // the next observation still has the old history, that write did
            // not happen and its one-shot seed candidate is no longer valid.
            issuedHandoff=null
        }

        val priorTime = lastTime
        if (priorTime != null && frame.timeMs == priorTime) return null
        if (priorTime != null && frame.timeMs < priorTime) {
            clearProofState()
            lastTime = frame.timeMs
            return null
        }
        lastTime = frame.timeMs
        if (priorTime != null && frame.timeMs - priorTime > MAX_GAP_MS) {
            clearProofState()
            return null
        }

        val handCards = frame.teammateHand
        val eligible = snapshot.phase == RoundPhase.SELF_FINISHED &&
            snapshot.remaining != null &&
            snapshot.played(Seat.OPPOSITE) < 27 &&
            frame.scene == TableScene.TEAMMATE &&
            !frame.captureGap &&
            (frame.incompleteReason == null || (frame.ambiguousPresentation &&
                frame.incompleteReason in setOf("归属待核对","出牌归属待核对，正在重新识别"))) &&
            handCards != null &&
            frame.teammateHandConfidence >= MIN_CONFIDENCE
        if (!eligible) {
            clearProofState()
            return null
        }

        val hand = handCards!!.toList()
        val expectedSize = 27 - snapshot.played(Seat.OPPOSITE)
        if (!validHand(hand, snapshot.remaining!!)) {
            clearProofState()
            return null
        }

        val visiblePlay = frame.plays[Seat.OPPOSITE]
        val strongDesk = visiblePlay?.takeIf { isStrongCompletePlay(it) }
        if (visiblePlay == null || !visiblePlay.regionObserved || visiblePlay.cards.isEmpty() ||
            visiblePlay.glyphs.size != visiblePlay.cards.size ||
            !sameCards(visiblePlay.cards, visiblePlay.glyphs.map { it.card })) {
            // An empty, missing, partial, or unassigned region breaks the
            // correspondence; it cannot borrow an earlier table observation.
            deskEvidence = null
            resetShrink()
        } else if (strongDesk != null) {
            deskEvidence = DeskEvidence(strongDesk, frame.timeMs)
        }

        val current = Hand(hand, cardsKey(hand))
        val baseline = seed
        if (baseline == null) {
            if (hand.size != expectedSize) return null
            seed = current
            seedVotes = 1
            seedFirstSeen = frame.timeMs
            seedLastSeen = frame.timeMs
            return null
        }

        if (current.key == baseline.key) {
            if (hand.size != expectedSize) {
                clearProofState()
                return null
            }
            if(rebasedSeed) return null
            resetShrink()
            partialHandoff = null
            seedVotes++
            seedLastSeen = frame.timeMs
            return null
        }

        val removed = multisetDifference(baseline.cards, current.cards)
        if (removed == null || removed.isEmpty() || baseline.cards.size != expectedSize ||
            hand.size != expectedSize - removed.size) {
            // A changed complete hand that is not a strict subset may become a
            // new baseline, but it needs its own two independent votes.
            if (hand.size != expectedSize) {
                clearProofState()
                return null
            }
            resetProofForNewSeed(current, frame.timeMs)
            return null
        }

        val seedTime = seedLastSeen
        val seedFirst = seedFirstSeen
        if (seedVotes < REQUIRED_VOTES || seedFirst == null ||
            seedLastSeen!! - seedFirst < MIN_VOTE_SPAN_MS || seedTime == null) {
            resetShrink()
            partialHandoff = null
            return null
        }

        partialHandoff?.let { pending ->
            if (pending.hand.key != current.key || !sameCards(pending.removed,removed) ||
                frame.timeMs-pending.startedAt !in 0L..PARTIAL_HANDOFF_MS ||
                !validHand(baseline.cards,snapshot.remaining!!)) {
                clearProofState()
                return null
            }
            return observePartialHandoff(pending,frame,visiblePlay)
        }
        // A directly visible prefix can reserve a fresh hand difference while
        // its other cards arrive. The reservation cannot supply missing cards.
        if (!delivered && shrink==null && frame.timeMs-seedTime in 0L..MAX_GAP_MS &&
            validHand(baseline.cards,snapshot.remaining!!) && visiblePlay!=null &&
            directlyRead(visiblePlay,PARTIAL_MIN_CONFIDENCE) && visiblePlay.cards.size<removed.size &&
            multisetDifference(removed,visiblePlay.cards)!=null) {
            val clean=frame.incompleteReason==null && !frame.ambiguousPresentation && frame.localizedOverlaySeats.isEmpty()
            partialHandoff=PartialHandoff(current,removed.toList(),visiblePlay.glyphs.toList(),frame.timeMs,
                handVotes=if(clean) 1 else 0,firstHandVote=frame.timeMs.takeIf { clean },
                lastHandVote=frame.timeMs.takeIf { clean })
            resetShrink()
            deskEvidence=null
            return null
        }
        if (shrink == null && frame.timeMs - seedTime !in 0L..MAX_GAP_MS) {
            resetShrink()
            return null
        }

        val currentPlay = visiblePlay ?: run { resetShrink(); return null }
        if (!sameCards(currentPlay.cards, removed)) {
            resetShrink()
            return null
        }

        val evidence = deskEvidence
        val matchingEvidence = when {
            strongDesk != null && sameCards(strongDesk.cards, removed) ->
                DeskEvidence(strongDesk, frame.timeMs)
            evidence != null &&
                frame.timeMs - evidence.time in 0L..MAX_GAP_MS &&
                sameCards(evidence.play.cards, removed) &&
                sameGeometry(currentPlay.glyphs, evidence.play.glyphs) ->
                evidence
            else -> null
        }
        if (matchingEvidence == null) {
            resetShrink()
            return null
        }

        if (shrink?.key != current.key) {
            beginShrink(current, frame.timeMs, matchingEvidence)
            return null
        }

        val anchoredDesk = shrinkDeskEvidence
        if (anchoredDesk == null || frame.timeMs - anchoredDesk.time !in 0L..MAX_GAP_MS ||
            !sameCards(anchoredDesk.play.cards, removed) ||
            !sameGeometry(currentPlay.glyphs, anchoredDesk.play.glyphs)) {
            // A moved or replaced table group starts a new independent proof;
            // it cannot be treated as the earlier direct observation.
            if (strongDesk != null && sameCards(strongDesk.cards, removed))
                beginShrink(current, frame.timeMs, DeskEvidence(strongDesk, frame.timeMs))
            else resetShrink()
            return null
        }

        val previousShrinkTime = shrinkLastSeen ?: frame.timeMs
        if (frame.timeMs - previousShrinkTime !in 0L..MAX_GAP_MS ||
            frame.timeMs - (shrinkFirstSeen ?: frame.timeMs) > MAX_GAP_MS) {
            resetShrink()
            return null
        }
        shrinkVotes++
        shrinkLastSeen = frame.timeMs
        if (shrinkVotes < REQUIRED_VOTES || delivered ||
            frame.timeMs - (shrinkFirstSeen ?: frame.timeMs) < MIN_VOTE_SPAN_MS) return null

        delivered = true
        val proof=anchoredDesk.play.copy(
            cards = anchoredDesk.play.cards.toList(),
            glyphs = anchoredDesk.play.glyphs.toList(),
        )
        issuePostCommitHandoff(proof,current,shrinkVotes,shrinkFirstSeen,shrinkLastSeen,frame.timeMs)
        return proof
    }

    private fun observePartialHandoff(pending: PartialHandoff,frame: TableFrame,play: ObservedPlay?): ObservedPlay? {
        val timeMs=frame.timeMs
        val clean=frame.incompleteReason==null && !frame.ambiguousPresentation && frame.localizedOverlaySeats.isEmpty()
        if(!clean) resetShortProof(pending)

        if(play==null || !play.regionObserved) {
            pending.fullEvidence=null
            if(clean) resetShortProof(pending)
            return null
        }
        if(play.cards.isEmpty()) {
            if(play.glyphs.isNotEmpty()) { clearProofState(); return null }
            pending.fullEvidence=null
        } else {
            if(play.glyphs.size!=play.cards.size || !sameCards(play.cards,play.glyphs.map { it.card }) ||
                play.glyphs.any { !validBox(it.box) } || multisetDifference(pending.removed,play.cards)==null) {
                // An observed conflicting group is not the reserved hand transfer.
                clearProofState()
                return null
            }
            if(play.cards.size==pending.removed.size && !containsGeometry(play.glyphs,pending.glyphs)) {
                clearProofState()
                return null
            }
        }

        if(play.cards.size<pending.removed.size) pending.fullEvidence=null

        // Preserve the established path: two strong complete reads of the same
        // slots prove the handoff without requiring count badges.
        if(play.cards.size==pending.removed.size && isStrongCompletePlay(play)) {
            val previous=pending.fullEvidence
            if(previous==null || timeMs-previous.time !in 1L..MAX_GAP_MS ||
                !sameGeometry(play.glyphs,previous.play.glyphs)) {
                pending.fullEvidence=DeskEvidence(play,timeMs)
            } else if(!delivered && timeMs-previous.time>=MIN_VOTE_SPAN_MS) {
                delivered=true
                partialHandoff=null
                val proof=previous.play.copy(cards=previous.play.cards.toList(),glyphs=previous.play.glyphs.toList())
                issuePostCommitHandoff(proof,pending.hand,pending.handVotes,pending.firstHandVote,
                    pending.lastHandVote,timeMs)
                return proof
            }
        } else if(play.cards.isNotEmpty()) {
            pending.fullEvidence=null
        }

        if(!clean) return null

        val witness=pending.fullWitness
        if(witness!=null) {
            // Once frozen, the witness can survive a positively observed empty
            // region or the exact same complete group only. Never refresh it.
            if(timeMs-witness.time !in 0L..MAX_PARTIAL_WITNESS_AGE_MS ||
                (play.cards.isNotEmpty() && (play.cards.size!=pending.removed.size ||
                    !sameCards(play.cards,pending.removed) || !sameGeometry(play.glyphs,witness.play.glyphs)))) {
                resetShortProof(pending)
                return null
            }
        } else if(play.cards.size==pending.removed.size && isStrongCompletePlay(play)) {
            // One independently strong complete read freezes the direct card
            // identities and slots. Later blank frames cannot erase it.
            pending.fullWitness=DeskEvidence(play.copy(cards=play.cards.toList(),glyphs=play.glyphs.toList()),timeMs)
        }

        if(pending.handVotes==0) pending.firstHandVote=timeMs
        pending.handVotes++
        pending.lastHandVote=timeMs

        val badge=frame.remainingHandCounts[Seat.OPPOSITE]
        if(badge!=null) {
            val source=badge.sourceTimeMs
            if(source<pending.startedAt || source>timeMs || timeMs-source>MAX_BADGE_AGE_MS) {
                // A stale/out-of-order badge cannot contribute to this handoff.
            } else if(badge.count!=pending.hand.cards.size) {
                resetShortProof(pending)
                return null
            } else if(source==pending.lastBadgeSource) {
                // Re-reading one OCR result is one source, never another vote.
            } else if(source<pending.lastBadgeSource) {
                resetShortProof(pending)
                return null
            } else {
                if(pending.firstBadgeSource==null || source-pending.firstBadgeSource!!>MAX_BADGE_AGE_MS) {
                    pending.firstBadgeSource=source
                    pending.badgeVotes=1
                } else {
                    pending.badgeVotes++
                }
                pending.lastBadgeSource=source
            }
        }

        val confirmedWitness=pending.fullWitness ?: return null
        val firstBadge=pending.firstBadgeSource ?: return null
        if(delivered || pending.handVotes<REQUIRED_VOTES || timeMs-pending.startedAt<MIN_VOTE_SPAN_MS ||
            pending.badgeVotes<REQUIRED_VOTES || pending.lastBadgeSource-firstBadge<MIN_VOTE_SPAN_MS ||
            timeMs-(pending.firstHandVote ?: timeMs)<MIN_VOTE_SPAN_MS ||
            timeMs-firstBadge>MAX_BADGE_PAIR_AGE_MS ||
            timeMs-pending.lastBadgeSource>MAX_BADGE_AGE_MS ||
            timeMs-confirmedWitness.time>MAX_PARTIAL_WITNESS_AGE_MS) return null

        delivered=true
        partialHandoff=null
        val proof=confirmedWitness.play.copy(cards=confirmedWitness.play.cards.toList(),glyphs=confirmedWitness.play.glyphs.toList())
        issuePostCommitHandoff(proof,pending.hand,pending.handVotes,pending.firstHandVote,pending.lastHandVote,timeMs)
        return proof
    }

    private fun resetShortProof(pending: PartialHandoff) {
        pending.fullWitness=null
        pending.handVotes=0
        pending.firstHandVote=null
        pending.lastHandVote=null
        pending.firstBadgeSource=null
        pending.lastBadgeSource=-1L
        pending.badgeVotes=0
    }

    private fun beginShrink(hand: Hand, timeMs: Long, evidence: DeskEvidence) {
        shrink = hand
        shrinkVotes = 1
        shrinkFirstSeen = timeMs
        shrinkLastSeen = timeMs
        shrinkDeskEvidence = evidence
    }

    private fun resetProofForNewSeed(hand: Hand, timeMs: Long) {
        seed = hand
        seedVotes = 1
        seedFirstSeen = timeMs
        seedLastSeen = timeMs
        resetShrink()
        partialHandoff = null
        deskEvidence = null
        delivered = false
    }

    private fun issuePostCommitHandoff(proof: ObservedPlay,hand: Hand,votes: Int,firstSeen: Long?,
        lastSeen: Long?,issuedAt: Long) {
        val sourceVersion=version ?: return
        val first=firstSeen ?: return
        val last=lastSeen ?: return
        if(hand.cards.isEmpty() || votes<REQUIRED_VOTES || last-first<MIN_VOTE_SPAN_MS ||
            last>issuedAt || issuedAt-last>MAX_GAP_MS) return
        issuedHandoff=IssuedHandoff(sourceVersion,proof.cards.toList(),hand.copy(cards=hand.cards.toList()),
            votes,first,last,issuedAt)
    }

    private fun canRebase(issued: IssuedHandoff,oldVersion: Version?,nextVersion: Version,
        frame: TableFrame,snapshot: RoundSnapshot): Boolean {
        val old=oldVersion ?: return false
        if(old!=issued.version || nextVersion.roundId!=old.roundId ||
            nextVersion.events.size!=old.events.size+1 ||
            nextVersion.events.take(old.events.size)!=old.events) return false
        val appended=snapshot.plays[Seat.OPPOSITE].orEmpty().lastOrNull() ?: return false
        if(appended.roundId!=old.roundId || appended.seat!=Seat.OPPOSITE ||
            !sameCards(appended.cards,issued.cards)) return false
        if(frame.timeMs-issued.issuedAt !in 1L..MAX_GAP_MS ||
            frame.timeMs-issued.lastSeen !in 0L..MAX_GAP_MS ||
            issued.votes<REQUIRED_VOTES || issued.lastSeen-issued.firstSeen<MIN_VOTE_SPAN_MS) return false
        val remaining=snapshot.remaining ?: return false
        val currentHand=frame.teammateHand ?: return false
        if(snapshot.phase!=RoundPhase.SELF_FINISHED ||
            snapshot.played(Seat.OPPOSITE)!=27-issued.hand.cards.size ||
            frame.scene!=TableScene.TEAMMATE || frame.captureGap || frame.incompleteReason!=null ||
            frame.ambiguousPresentation || frame.localizedOverlaySeats.isNotEmpty() ||
            frame.teammateHandConfidence<MIN_CONFIDENCE || !validHand(issued.hand.cards,remaining) ||
            !validHand(currentHand,remaining)) return false
        return true
    }

    private fun resetShrink() {
        shrink = null
        shrinkVotes = 0
        shrinkFirstSeen = null
        shrinkLastSeen = null
        shrinkDeskEvidence = null
    }

    private fun clearProofState() {
        seed = null
        seedVotes = 0
        seedFirstSeen = null
        seedLastSeen = null
        resetShrink()
        partialHandoff = null
        deskEvidence = null
        delivered = false
        issuedHandoff = null
    }

    private fun isStrongCompletePlay(play: ObservedPlay): Boolean = directlyRead(play,MIN_CONFIDENCE)

    private fun directlyRead(play: ObservedPlay,minConfidence: Float): Boolean =
        play.regionObserved && play.cards.isNotEmpty() && play.confidence >= minConfidence &&
            play.cards.size == play.glyphs.size &&
            sameCards(play.cards, play.glyphs.map { it.card }) &&
            play.glyphs.all { it.confidence >= minConfidence && validBox(it.box) }

    private fun validBox(box: GlyphBox): Boolean =
        box.left.isFinite() && box.top.isFinite() && box.right.isFinite() && box.bottom.isFinite() &&
            box.width > 0f && box.height > 0f

    private fun validHand(cards: List<Card>, remaining: Map<Rank, Int>): Boolean {
        if (cards.groupingBy { it.rank }.eachCount().any { (rank, count) ->
                count > minOf(rank.capacity, remaining[rank] ?: 0)
            }) return false
        return cards.groupingBy { it.colorKey() }.eachCount().all { (key, count) ->
            count <= if (':' in key) 4 else 2
        }
    }

    private fun sameCards(first: List<Card>, second: List<Card>): Boolean =
        first.size == second.size && cardsKey(first) == cardsKey(second)

    private fun cardsKey(cards: List<Card>): String =
        cards.map { it.colorKey() }.sorted().joinToString(",")

    private fun multisetDifference(full: List<Card>, subset: List<Card>): List<Card>? {
        val rest = full.toMutableList()
        for (card in subset) {
            val index = rest.indexOfFirst { it.colorKey() == card.colorKey() }
            if (index < 0) return null
            rest.removeAt(index)
        }
        return rest
    }

    private fun sameGeometry(current: List<PlayGlyph>, tracked: List<PlayGlyph>): Boolean {
        if (current.size != tracked.size || current.isEmpty()) return false
        return containsGeometry(current,tracked)
    }

    private fun containsGeometry(current: List<PlayGlyph>,tracked: List<PlayGlyph>): Boolean {
        if (tracked.isEmpty() || current.size<tracked.size) return false
        val available = current.toMutableList()
        return tracked.all { old ->
            val match = available.indexOfFirst { fresh ->
                fresh.card.colorKey() == old.card.colorKey() &&
                    validBox(fresh.box) && validBox(old.box) &&
                    kotlin.math.abs(fresh.box.cx - old.box.cx) <= minOf(fresh.box.width, old.box.width) * .35f &&
                    kotlin.math.abs(fresh.box.cy - old.box.cy) <= minOf(fresh.box.height, old.box.height) * .35f &&
                    maxOf(fresh.box.width, old.box.width) <= minOf(fresh.box.width, old.box.width) * 1.4f &&
                    maxOf(fresh.box.height, old.box.height) <= minOf(fresh.box.height, old.box.height) * 1.4f
            }
            if (match < 0) false else { available.removeAt(match); true }
        }
    }

    private companion object {
        const val REQUIRED_VOTES = 2
        const val MIN_CONFIDENCE = .80f
        const val MIN_VOTE_SPAN_MS = 100L
        const val MAX_GAP_MS = 500L
        const val PARTIAL_MIN_CONFIDENCE = .65f
        const val PARTIAL_HANDOFF_MS = 4000L
        const val MAX_PARTIAL_WITNESS_AGE_MS = 1500L
        const val MAX_BADGE_AGE_MS = 1000L
        const val MAX_BADGE_PAIR_AGE_MS = 1500L
    }
}
