package com.cyotun.jpq.core

enum class TableScene { TABLE, DEALING, LOBBY, TRIBUTE, TEAMMATE, RESULT, UNKNOWN }
data class ObservedPlay(val cards: List<Card>,val confidence: Float,val glyphs: List<PlayGlyph> = emptyList(),val regionObserved: Boolean = true,
                        val singleCardIdentity: SingleCardIdentity? = null)
data class TableFrame(val timeMs: Long,val scene: TableScene,val hand: List<Card>?,val plays: Map<Seat,ObservedPlay?> = emptyMap(),val selfFinished: Boolean = false,
                      val incompleteReason: String? = null,val ambiguousPresentation: Boolean = false,val captureGap: Boolean = false,
                      val teammateHand: List<Card>? = null,val teammateHandConfidence: Float = 0f,
                      val dealingChoicesObserved: Boolean = false,
                      val remainingHandCounts: Map<Seat,RemainingHandCount> = emptyMap(),
                      // Timestamp of independently validated central choice controls, never the OCR callback time.
                      val openingChoiceSourceTimeMs: Long? = null,
                      // Qualified by the source adapter only when the overlay is
                      // the sole incompleteness and the own hand is unobstructed.
                      val localizedOverlaySeats: Set<Seat> = emptySet(),
                      // Qualified positioned pass bubbles, timestamped at OCR capture.
                      // They close an action; they never prove that its old pixels are empty.
                      val observedPasses: Map<Seat,Long> = emptyMap())

internal fun TableFrame.onlyLocalizedOverlay(): Boolean = incompleteReason!=null &&
    localizedOverlaySeats.isNotEmpty() && !captureGap && !ambiguousPresentation &&
    scene in setOf(TableScene.TABLE,TableScene.TEAMMATE) &&
    localizedOverlaySeats.all { plays[it]?.regionObserved==false }
data class ObserverStatus(val armed: Boolean,val locked: Boolean,val baselineWindow: Boolean,val tribute: Boolean,
    val handVotes: Int,val playVotes: Map<Seat,Int>,val pendingPlays: Int,val sequence: Long,val recovering: Boolean = false)

/** Positive completion evidence only; an unfinished player's pass is a different state. */
fun confirmedFinishedSeats(snapshot: RoundSnapshot): Set<Seat> {
    if(snapshot.phase !in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED,RoundPhase.RECOVERING)) return emptySet()
    return Seat.entries.filter { seat ->
        (seat==Seat.SELF && snapshot.phase==RoundPhase.SELF_FINISHED) || snapshot.played(seat)==27
    }.toSet()
}

/** Only confirmed plays/completion state can establish an early double-finish. */
fun confirmedRoundFinished(snapshot: RoundSnapshot): Boolean {
    if(snapshot.phase !in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED)) return false
    val previous=snapshot.played(Seat.PREVIOUS)==27
    val opposite=snapshot.played(Seat.OPPOSITE)==27
    val next=snapshot.played(Seat.NEXT)==27
    return (previous && next) || (snapshot.phase==RoundPhase.SELF_FINISHED &&
        (opposite || listOf(previous,opposite,next).count { it }>=2)) || (previous && opposite && next)
}

/** Interrupted observations hide counts while a bounded checkpoint is reconciled. */
class RoundObserver(private val ledger: RoundLedger,private val changed: (RoundSnapshot)->Unit = {}) {
    private class Vote(private val required: Int) {
        var key=""; var count=0; var first=0L
        private var lastVoteTime=-1L
        private var strongCount=0; private var strongFirst=0L; private var accepted=false
        fun accept(value: String,time: Long,fastProof: Boolean=false,fastMinimumSpanMs: Long=100L): Boolean {
            if(time==lastVoteTime) return accepted
            val previousTime=lastVoteTime
            lastVoteTime=time
            if(key!=value || previousTime<0 || time-previousTime>500) {
                key=value; count=1; first=time; strongCount=0
            } else count++
            if(fastProof) { if(strongCount==0) strongFirst=time; strongCount++ } else strongCount=0
            accepted=(count>=required && time-first>=100) || (strongCount>=3 && time-strongFirst>=fastMinimumSpanMs)
            return accepted
        }
        fun clear() { key=""; count=0; lastVoteTime=-1; strongCount=0; accepted=false }
        fun duration(time: Long)=if(count==0) 0 else time-first
    }
    private class Presentation(var cards: List<Card>,var glyphs: List<PlayGlyph>,var lastSeen: Long,var eventId: String?=null,
                               var unobservedSince: Long?=null) {
        // An immutable-card presentation can reappear after a short effect. This
        // position anchor is separate from the stricter expansion association.
        var colorReturnAnchor=glyphs.toList()
        var colorReturnSeen=lastSeen
    }
    private val handVote=Vote(3)
    private val tributeVote=Vote(2)
    private val newDealVote=Vote(3)
    private val playVotes=Seat.entries.associateWith { Vote(4) }
    private val emptyVotes=Seat.entries.associateWith { Vote(2) }
    private val visible=Seat.entries.associateWith { "" }.toMutableMap()
    private val presentations=Seat.entries.associateWith { null as Presentation? }.toMutableMap()
    private val previousGlyphs=Seat.entries.associateWith { emptyList<PlayGlyph>() }.toMutableMap()
    private val empty=Seat.entries.associateWith { 0 }.toMutableMap()
    private val pending=mutableListOf<Pair<Seat,Presentation>>()
    private var sequence=0L; private var lastTime=-1L; private var roundSerial=0L
    private var lastRound: String?=null; private var locked=false; private var tribute=false; private var armed=false
    private var baselineWindow=false; private var freshDeal=false; private var unknownSince: Long?=null
    private var sceneBeforeDeal=TableScene.UNKNOWN
    private var lastDealAt=-10000L
    private var recovery: RoundRecovery?=null
    private var recoveryStartedAt=-1L
    private var recoveryReason=""
    private var ambiguitySince: Long?=null
    private var shortJokerAnimation=false
    private var jokerTableReturnedAt: Long?=null
    private val jokerTeammateVote=Vote(3)
    private val teammateHandProof=TeammateHandPlayProofTracker()
    private val terminalOwnHandProof=TerminalOwnHandProof()
    private val ownExitPlayProof=OwnExitPlayProof()
    private val localizedPendingRecovery=LocalizedPendingPlayRecovery()
    private val quantityProofs=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).associateWith { QuantityAnchoredPlayProof() }
    private val repeatedPlayProofs=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).associateWith { RepeatedPlayCountProof() }
    private val repeatedSingleProofs=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).associateWith { RepeatedSingleCardProof() }
    private val countAnchoredRepeatedSingles=CountAnchoredRepeatedSingleProof()
    private val passedSupersetProofs=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).associateWith { PassClosedSupersetProof() }
    private val observedEmptyAt=mutableMapOf<Seat,Long>()
    private val countPlayBoundaryAt=mutableMapOf<Seat,Long>()
    fun diagnosticStatus()=ObserverStatus(armed,locked,baselineWindow,tribute,handVote.count,playVotes.mapValues { it.value.count },pending.size,sequence,recovery!=null)
    private fun key(cards: List<Card>)=cards.map { "${it.rank.name}:${it.suit.name}" }.sorted().joinToString(",")
    private fun emit() { runCatching { changed(ledger.snapshot()) } }
    private fun clearCandidates() {
        handVote.clear(); tributeVote.clear(); newDealVote.clear(); playVotes.values.forEach { it.clear() }; emptyVotes.values.forEach { it.clear() }; pending.clear()
        visible.keys.forEach { visible[it]=""; presentations[it]=null; previousGlyphs[it]=emptyList(); empty[it]=0 }; unknownSince=null
        recovery=null; recoveryStartedAt=-1L; recoveryReason=""; ambiguitySince=null
        shortJokerAnimation=false; jokerTableReturnedAt=null; jokerTeammateVote.clear(); observedEmptyAt.clear(); countPlayBoundaryAt.clear()
        teammateHandProof.reset()
        terminalOwnHandProof.reset()
        ownExitPlayProof.reset()
        localizedPendingRecovery.reset()
        quantityProofs.values.forEach { it.clear() }
        repeatedPlayProofs.values.forEach { it.clear() }
        repeatedSingleProofs.values.forEach { it.clear() }
        countAnchoredRepeatedSingles.clear()
        passedSupersetProofs.values.forEach { it.clear() }
    }
    fun interrupted(reason: String) { locked=true; ledger.invalidate(reason); clearCandidates(); emit() }
    /** A fragmented confirmed display stays that event; no missing glyph is invented. */
    fun reassociatePresentationFragments(ownership: OwnedPlays,timeMs: Long): OwnedPlays {
        if(recovery!=null || ledger.snapshot().phase !in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED) ||
            ownership.ambiguous.isEmpty()) return ownership
        val remaining=ownership.ambiguous.toMutableList()
        val seats=ownership.seats.toMutableMap()
        for(seat in listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT)) {
            val presentation=presentations[seat] ?: continue
            if(presentation.eventId==null || timeMs-presentation.lastSeen !in 0L..1500L || seats[seat].orEmpty().isNotEmpty()) continue
            val available=presentation.glyphs.toMutableList()
            val matched=remaining.filter { glyph ->
                if(glyph.confidence<.65f) false else {
                    val old=available.indexOfFirst { tracked -> containsTracked(listOf(glyph),listOf(tracked)) }
                    if(old<0) false else { available.removeAt(old); true }
                }
            }
            if(matched.isEmpty()) continue
            // Only visible cards are returned. collectOthers preserves the full
            // already-confirmed presentation when these form its strict subset.
            seats[seat]=matched; remaining.removeAll(matched.toSet())
            presentation.lastSeen=timeMs
        }
        return ownership.copy(seats=seats,ambiguous=remaining)
    }
    /** A capture/ownership gap is not a ledger conflict and must remain recoverable. */
    fun incomplete(reason: String,timeMs: Long=lastTime,immediate: Boolean=false) {
        ownExitPlayProof.reset()
        val preserveStrictSupersetQuantity=reason=="出牌变化待核对，正在重新识别"
        if(!preserveStrictSupersetQuantity) quantityProofs.values.forEach { it.clear() }
        repeatedPlayProofs.values.forEach { it.clear() }
        repeatedSingleProofs.values.forEach { it.clear() }
        countAnchoredRepeatedSingles.clear()
        passedSupersetProofs.values.forEach { it.clear() }
        val snapshot=ledger.snapshot()
        if(recovery!=null) { recovery!!.observationInterrupted(timeMs); publishRecoveryIfDue(timeMs,immediate); return }
        if(snapshot.phase !in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED)) return
        if(snapshot.roundId!=lastRound) { clearCandidates(); lastRound=snapshot.roundId }
        val anchors=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).filter { snapshot.played(it)<27 }.associateWith { seat ->
            val p=presentations[seat]
            if(p?.eventId!=null) RoundRecovery.Anchor(p.cards.toList(),p.glyphs.toList(),p.eventId,p.lastSeen)
            else RoundRecovery.Anchor(emptyList(),emptyList(),null,observedEmptyAt[seat] ?: -10000L)
        }
        val knownTeammate=teammateHandProof.checkpointHand(snapshot)
        recovery=RoundRecovery(timeMs,ledger.currentHand(),snapshot.phase==RoundPhase.SELF_FINISHED,anchors,
            snapshot.plays.values.all { it.isEmpty() },anchors.keys.associateWith { 27-snapshot.played(it) },knownTeammate,
            quantityProofs,if(preserveStrictSupersetQuantity) anchors.keys.mapNotNull { seat ->
                quantityPrior(seat)?.let { seat to it }
            }.toMap() else emptyMap())
        teammateHandProof.reset()
        shortJokerAnimation=false; jokerTableReturnedAt=null; jokerTeammateVote.clear()
        recoveryStartedAt=timeMs; recoveryReason=reason
        handVote.clear(); playVotes.values.forEach { it.clear() }; emptyVotes.values.forEach { it.clear() }; pending.clear()
        publishRecoveryIfDue(timeMs,immediate)
    }
    /** Up to 500ms is ordinary confirmation latency; no new events may cross it. */
    private fun publishRecoveryIfDue(timeMs: Long,immediate: Boolean=false) {
        // A proved short four-joker animation may need a few clear frames to
        // expose the unchanged hand; that wait is not extra UNKNOWN duration.
        val settleMs=if(ledger.phaseBeforeRecovery()==RoundPhase.SELF_FINISHED) 1000L else 500L
        if(!immediate && shortJokerAnimation && jokerTableReturnedAt?.let { timeMs-it in 0L..settleMs }==true) return
        if(recovery!=null && (immediate || timeMs-recoveryStartedAt>=500) &&
            ledger.snapshot().phase in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED)) {
            shortJokerAnimation=false; jokerTableReturnedAt=null
            ledger.suspendForRecovery(recoveryReason); emit()
        }
    }
    private fun lastConfirmedPlayIsFourJokers(): Boolean {
        val last=ledger.snapshot().plays.values.flatten().maxByOrNull { it.sequence } ?: return false
        return last.cards.size==4 && last.cards.count { it.rank==Rank.BIG }==2 && last.cards.count { it.rank==Rank.SMALL }==2
    }
    private fun recoverShortJokerAnimation(frame: TableFrame) {
        if(!shortJokerAnimation || recovery==null) return
        val hand=frame.hand
        val own=ledger.currentHand()
        val selfDone=ledger.phaseBeforeRecovery()==RoundPhase.SELF_FINISHED
        val handVisible=if(selfDone) frame.scene==TableScene.TEAMMATE else
            frame.scene==TableScene.TABLE && hand!=null && hand.isNotEmpty() && isSubset(hand,own)
        val clearedTable=handVisible && !frame.captureGap && !frame.ambiguousPresentation && frame.incompleteReason==null &&
            frame.plays.isNotEmpty() && frame.plays.values.all { it!=null && it.cards.isEmpty() }
        if(!clearedTable || (jokerTableReturnedAt==null && frame.timeMs-recoveryStartedAt>=500)) {
            shortJokerAnimation=false; jokerTableReturnedAt=null; return
        }
        if(jokerTableReturnedAt==null) jokerTableReturnedAt=frame.timeMs
        val settleMs=if(selfDone) 1000L else 500L
        if(frame.timeMs-jokerTableReturnedAt!!>settleMs) { shortJokerAnimation=false; jokerTableReturnedAt=null; return }
        val teammate=frame.teammateHand
        val snapshot=ledger.snapshot()
        val completeTeammate=selfDone && teammate!=null && teammate.isNotEmpty() &&
            teammate.size==27-snapshot.played(Seat.OPPOSITE) &&
            teammate.groupingBy { it.rank }.eachCount().all { (rank,count) -> count<=(snapshot.remaining?.get(rank) ?: 0) } &&
            teammate.groupingBy { it.colorKey() }.eachCount().all { (color,count) -> count<=if(':' in color) 4 else 2 }
        val teammateReady=if(completeTeammate) jokerTeammateVote.accept(key(teammate!!),frame.timeMs)
            else { jokerTeammateVote.clear(); false }
        if((!selfDone && hand!=null && key(hand)==key(own)) || teammateReady) {
            // Four jokers cannot be covered by another play. The directly seen
            // original hand and short clear-table transition reconnect that
            // known animation; no absent play/card is inferred or committed.
            recovery=null; recoveryStartedAt=-1L; recoveryReason=""
            shortJokerAnimation=false; jokerTableReturnedAt=null; jokerTeammateVote.clear()
        }
    }
    private fun finishConfirmedRound() {
        ledger.finish(); emit(); ledger.waitForNext(); lastRound=null; emit()
        armed=false; baselineWindow=false; tribute=false; freshDeal=false; clearCandidates()
    }
    fun observe(frame: TableFrame) {
        if(frame.timeMs<=lastTime) return
        var snap=ledger.snapshot()
        if(snap.roundId!=lastRound) {
            if(lastRound!=null && snap.roundId==null) { locked=true; armed=false; baselineWindow=false }
            clearCandidates(); lastRound=snap.roundId
            // A preconfirmed checkpoint already owns its event sequences.
            sequence=snap.plays.values.flatten().maxOfOrNull { it.sequence } ?: 0L
        }
        if(lastTime>=0 && frame.timeMs-lastTime>2500 && snap.remaining!=null)
            incomplete("画面连续性中断，正在重新核对",lastTime,immediate=true)
        lastTime=frame.timeMs
        if(frame.captureGap || frame.ambiguousPresentation || frame.incompleteReason!=null ||
            frame.scene !in setOf(TableScene.TABLE,TableScene.TEAMMATE)) {
            quantityProofs.values.forEach { it.clear() }
            countAnchoredRepeatedSingles.clear()
            passedSupersetProofs.values.forEach { it.clear() }
        }
        val localizedPlay=localizedPendingRecovery.observe(frame,ledger.snapshot(),ledger.currentHand(),ledger.phaseBeforeRecovery())
        if(localizedPlay!=null && recovery!=null) {
            // A complete new group was already seen twice before another seat's
            // short mask. That old seat and every other anchor have now directly
            // reconnected. Append only this still-visible group atomically; an
            // arbitrary later group cannot replace a stale empty checkpoint.
            val id=ledger.snapshot().roundId ?: return
            val nextSequence=sequence+1
            val event=PlayEvent(id,"$id:$nextSequence",nextSequence,localizedPlay.seat,localizedPlay.play.cards)
            if(!runCatching { ledger.resumeAfterRecovery(emptyMap(),listOf(event)) }.isSuccess) {
                recovery!!.observationInterrupted(frame.timeMs)
                publishRecoveryIfDue(frame.timeMs,immediate=true); return
            }
            sequence=nextSequence
            val seat=localizedPlay.seat
            val play=localizedPlay.play
            presentations[seat]=Presentation(play.cards.toList(),play.glyphs.toList(),frame.timeMs,event.eventId)
            visible[seat]=key(play.cards); previousGlyphs[seat]=play.glyphs.toList()
            playVotes.getValue(seat).clear(); emptyVotes.getValue(seat).clear()
            recovery=null; recoveryStartedAt=-1L; recoveryReason=""
            shortJokerAnimation=false; jokerTableReturnedAt=null; emit()
            if(confirmedRoundFinished(ledger.snapshot())) { finishConfirmedRound(); return }
        }
        // Observe before the scene/hand-off branches clear currentHand. A full
        // last own display may precede the teammate label while its lower hand
        // has already replaced ours. Recovery is reconciled independently.
        val ownExit=if(recovery==null) ownExitPlayProof.observeWithCertificate(frame,ledger.snapshot(),ledger.currentHand())
            else { ownExitPlayProof.reset(); null }
        if(ownExit!=null) {
            val current=ledger.snapshot()
            if(current.phase==RoundPhase.SELF_FINISHED) {
                val id=current.roundId
                if(id!=null) {
                    val nextSequence=sequence+1
                    val event=PlayEvent(id,"$id:$nextSequence",nextSequence,Seat.SELF,ownExit.cards)
                    if(runCatching { ledger.commitLateOwnExit(ownExit,event) }.isSuccess) {
                        sequence=nextSequence; emit()
                    }
                }
            } else if(!commit(Seat.SELF,ownExit.cards)) return
        }
        val terminalOwn=terminalOwnHandProof.observe(frame,ledger.snapshot(),ledger.currentHand(),ledger.phaseBeforeRecovery())
        if(terminalOwn!=null && recovery!=null) {
            // Every unfinished outside region stayed directly empty across this
            // short own animation. The final whole own hand is visible twice;
            // a completion label or a result screen alone never reaches here.
            if(!runCatching { ledger.resumeAfterRecovery(emptyMap()) }.isSuccess) return
            if(!commit(Seat.SELF,terminalOwn.cards)) return
            recovery=null; recoveryStartedAt=-1L; recoveryReason=""
            if(confirmedRoundFinished(ledger.snapshot())) { finishConfirmedRound(); return }
        }
        if(frame.scene in setOf(TableScene.LOBBY,TableScene.RESULT)) {
            // A result cannot certify a checkpoint still awaiting reconciliation.
            publishRecoveryIfDue(frame.timeMs,immediate=true)
            if(snap.roundId!=null) { ledger.finish(); emit(); ledger.waitForNext(); lastRound=null; emit() }
            locked=false; armed=true; tribute=false; freshDeal=false; baselineWindow=false; sceneBeforeDeal=frame.scene
            clearCandidates(); return
        }
        if(frame.scene==TableScene.DEALING) {
            val current=ledger.snapshot()
            val tracked=current.roundId!=null && current.phase in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED,RoundPhase.RECOVERING)
            val sameOpening=current.phase==RoundPhase.ACTIVE && current.plays.values.all { it.isEmpty() } &&
                frame.hand?.size==27 && key(frame.hand)==key(ledger.initialHand())
            if(sameOpening) {
                // Latch a positively matched opening stage. A later short
                // detector variation inside that same stage is not a new deal.
                newDealVote.clear(); sceneBeforeDeal=TableScene.DEALING; lastDealAt=frame.timeMs
            }
            // Persistent avatar doubling badges can be misclassified as DEALING.
            // An ongoing round needs an independently stable, different complete
            // opening hand and an empty outside table before its ledger is replaced.
            val newHand=frame.hand?.takeIf { frame.dealingChoicesObserved && it.size==27 && key(it)!=key(ledger.initialHand()) &&
                !frame.captureGap && !frame.ambiguousPresentation && frame.incompleteReason==null &&
                listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).all { seat -> observedEmpty(frame.plays[seat]) } }
            val boundaryReady=if(sameOpening) false else if(!tracked) true else if(newHand!=null) newDealVote.accept(key(newHand),frame.timeMs)
                else { newDealVote.clear(); false }
            if(boundaryReady && sceneBeforeDeal!=TableScene.DEALING && frame.timeMs-lastDealAt>3000) {
                    if(current.roundId!=null) { ledger.finish(); emit(); ledger.waitForNext(); lastRound=null; emit() }
                    clearCandidates(); locked=false; tribute=false; armed=true; freshDeal=true; baselineWindow=true
                lastDealAt=frame.timeMs
                sceneBeforeDeal=TableScene.DEALING
            }
        } else {
            newDealVote.clear()
            if(frame.scene!=TableScene.UNKNOWN) sceneBeforeDeal=frame.scene
        }
        if(confirmedRoundFinished(ledger.snapshot()) && frame.scene !in setOf(TableScene.DEALING,TableScene.TRIBUTE)) {
            finishConfirmedRound(); return
        }
        if(frame.scene==TableScene.UNKNOWN) {
            if(unknownSince==null) unknownSince=frame.timeMs
            val startShortJokerAnimation=recovery==null && !frame.captureGap &&
                ledger.snapshot().phase in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED) && lastConfirmedPlayIsFourJokers()
            if(frame.captureGap || jokerTableReturnedAt!=null) { shortJokerAnimation=false; jokerTableReturnedAt=null }
            incomplete(frame.incompleteReason ?: "画面暂不完整，正在重新核对",frame.timeMs)
            if(startShortJokerAnimation && ledger.snapshot().phase in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED)) shortJokerAnimation=true
            recovery?.reconcile(frame)
            handVote.clear(); tributeVote.clear(); playVotes.values.forEach { it.clear() }; emptyVotes.values.forEach { it.clear() }; return
        }
        unknownSince=null
        recoverShortJokerAnimation(frame)
        if(frame.scene==TableScene.TRIBUTE) {
            if(snap.plays.values.any { it.isNotEmpty() }) { interrupted("检测到未跟踪的牌面转移，等待下一局"); return }
            if(snap.roundId!=null) { ledger.waitForNext(); lastRound=null; emit() }
            tribute=true; clearCandidates(); return
        }
        if(tribute) {
            if(!Seat.entries.all { observedEmpty(frame.plays[it]) }) { tributeVote.clear(); return }
            if(!tributeVote.accept("empty",frame.timeMs)) return
            tribute=false; ledger.waitForNext(); lastRound=null; armed=true; baselineWindow=true; freshDeal=true; clearCandidates()
        }
        if(locked || ledger.snapshot().phase==RoundPhase.UNAVAILABLE) return
        if(recovery==null) {
            val proof=teammateHandProof.observe(frame,ledger.snapshot())
            if(proof!=null) {
                // This is an independently observed hand difference, including
                // a same-value next play that has no clean empty-table boundary.
                // It does not infer any other player's missing cards.
                if(!commit(Seat.OPPOSITE,proof.cards)) return
                val event=ledger.snapshot().plays.getValue(Seat.OPPOSITE).last()
                presentations[Seat.OPPOSITE]=Presentation(proof.cards.toList(),proof.glyphs.toList(),frame.timeMs,event.eventId)
                visible[Seat.OPPOSITE]=key(proof.cards)
                previousGlyphs[Seat.OPPOSITE]=proof.glyphs.toList()
                playVotes.getValue(Seat.OPPOSITE).clear()
                emptyVotes.getValue(Seat.OPPOSITE).clear()
            }
        }
        if(confirmedRoundFinished(ledger.snapshot())) { finishConfirmedRound(); return }
        if(frame.ambiguousPresentation) {
            // Independent own-hand proof survives another seat's ambiguity.
            // Unowned outside cards are still never committed by this path.
            val ownQualified=if(recovery==null) observeIndependentSelf(frame) else false
            if(confirmedRoundFinished(ledger.snapshot())) { finishConfirmedRound(); return }
            if(ambiguitySince==null) ambiguitySince=frame.timeMs
            // Retain the original 1500ms ownership-animation tolerance. These
            // unconfirmed candidates receive no play/empty/hand votes; they are
            // distinct from a genuinely UNKNOWN capture's stricter checkpoint.
            if(recovery==null && frame.timeMs-ambiguitySince!!<=1500) {
                if(!ownQualified) { handVote.clear(); playVotes.getValue(Seat.SELF).clear() }
                playVotes.filterKeys { it!=Seat.SELF }.values.forEach { it.clear() }
                emptyVotes.values.forEach { it.clear() }
                return
            }
            incomplete(frame.incompleteReason ?: "出牌归属待核对，正在重新识别",ambiguitySince!!)
        } else ambiguitySince=null
        frame.incompleteReason?.let {
            // A continuing local mask cannot erase new, independently visible
            // quantity sources on every frame. Global/unknown faults still do.
            if(recovery!=null && frame.onlyLocalizedOverlay()) publishRecoveryIfDue(frame.timeMs)
            else incomplete(it,frame.timeMs)
        }
        recovery?.let { checkpoint ->
            val expansions=checkpoint.reconcile(frame,ledger.snapshot()) ?: run { publishRecoveryIfDue(frame.timeMs); return }
            val recovered=checkpoint.recoveredQuantityPlay ?: checkpoint.recoveredTeammatePlay?.let { Seat.OPPOSITE to it }
            val recoveredEvent=if(recovered!=null) {
                val id=ledger.snapshot().roundId ?: return
                val nextSequence=sequence+1
                PlayEvent(id,"$id:$nextSequence",nextSequence,recovered.first,recovered.second.cards)
            } else null
            if(!runCatching { ledger.resumeAfterRecovery(expansions,listOfNotNull(recoveredEvent)) }.isSuccess) {
                // A contradictory recovery candidate cannot replace a valid
                // checkpoint or permanently stop subsequent reconciliation.
                checkpoint.observationInterrupted(frame.timeMs)
                publishRecoveryIfDue(frame.timeMs,immediate=true); return
            }
            for((seat,presentation) in presentations) {
                if(presentation?.eventId in expansions.keys) {
                    val observed=frame.plays[seat]!!
                    presentation!!.cards=observed.cards.toList(); presentation.glyphs=observed.glyphs.toList()
                    presentation.lastSeen=frame.timeMs; presentation.colorReturnAnchor=observed.glyphs.toList()
                    presentation.colorReturnSeen=frame.timeMs; visible[seat]=key(observed.cards)
                }
            }
            if(recovered!=null && recoveredEvent!=null) {
                sequence=recoveredEvent.sequence
                val seat=recovered.first; val play=recovered.second
                presentations[seat]=Presentation(play.cards.toList(),play.glyphs.toList(),frame.timeMs,recoveredEvent.eventId)
                visible[seat]=key(play.cards)
                previousGlyphs[seat]=play.glyphs.toList()
                playVotes.getValue(seat).clear(); emptyVotes.getValue(seat).clear(); empty[seat]=0
            }
            recovery=null; recoveryStartedAt=-1L; recoveryReason=""; shortJokerAnimation=false; jokerTableReturnedAt=null; emit()
        }
        if((frame.scene==TableScene.TEAMMATE || frame.selfFinished) && ledger.snapshot().phase==RoundPhase.ACTIVE) { ledger.selfFinished(); emit() }
        if(confirmedRoundFinished(ledger.snapshot())) {
            // Either pair of teammates has finished, or three players have finished. The
            // remaining players' revealed cards are not new table presentations.
            finishConfirmedRound(); return
        }
        snap=ledger.snapshot()
        val hand=frame.hand
        if(armed && hand?.size==27 && listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).all { observedEmpty(frame.plays[it]) }) baselineWindow=true

        // Vote self display in parallel with hand confirmation, not after it.
        val selfProof=frame.plays[Seat.SELF]
        val selfReady=if(selfProof!=null && selfProof.regionObserved && selfProof.cards.isNotEmpty() && selfProof.confidence>=.65f)
            playVotes[Seat.SELF]!!.accept(key(selfProof.cards),frame.timeMs) else { playVotes[Seat.SELF]!!.clear(); false }

        // Keep first visible plays while the initial hand is being confirmed.
        val bufferedOthers=snap.remaining==null && armed && baselineWindow
        if(bufferedOthers) collectOthers(frame,false)
        if(hand==null) handVote.clear()
        if(frame.scene!=TableScene.TEAMMATE && hand!=null && handVote.accept(key(hand),frame.timeMs)) {
            val current=ledger.snapshot()
            if(current.phase in setOf(RoundPhase.WAITING,RoundPhase.FINISHED)) {
                if(!armed || !baselineWindow || hand.size!=27) return
                runCatching {
                    ledger.begin("${frame.timeMs}-${++roundSerial}",hand); sequence=0; lastRound=ledger.snapshot().roundId; handVote.clear(); emit()
                    val buffered=pending.toList(); pending.clear(); buffered.forEach { (seat,presentation) ->
                        if(commit(seat,presentation.cards)) presentation.eventId=ledger.snapshot().plays[seat]!!.last().eventId
                    }
                }.onFailure { handVote.clear() }
            } else if(current.phase==RoundPhase.ACTIVE) {
                val previous=ledger.currentHand(); val removed=previous.toMutableList()
                val isSubset=hand.all { removed.remove(it) }
                if(isSubset && removed.isNotEmpty() && selfReady && selfProof!=null && key(selfProof.cards)==key(removed)) commit(Seat.SELF,removed)
                // A short missing/raised glyph is not a card transfer. Do not invalidate the whole round here.
            }
        }
        if(confirmedRoundFinished(ledger.snapshot())) { finishConfirmedRound(); return }
        if(!bufferedOthers && ledger.snapshot().remaining!=null) collectOthers(frame,true)
    }
    private fun observeIndependentSelf(frame: TableFrame): Boolean {
        val snapshot=ledger.snapshot()
        val hand=frame.hand
        val play=frame.plays[Seat.SELF]
        if(snapshot.phase!=RoundPhase.ACTIVE || frame.captureGap || frame.scene!=TableScene.TABLE ||
            hand==null || play==null || !play.regionObserved || play.confidence<.65f ||
            play.cards.isEmpty() || !hasGeometry(play)) return false
        val removed=ledger.currentHand().toMutableList()
        if(!hand.all { removed.remove(it) } || removed.isEmpty() || key(removed)!=key(play.cards)) return false
        val ready=playVotes.getValue(Seat.SELF).accept(key(play.cards),frame.timeMs)
        if(handVote.accept(key(hand),frame.timeMs) && ready) commit(Seat.SELF,removed)
        return true
    }
    private fun observedEmpty(play: ObservedPlay?)=play!=null && play.regionObserved && play.confidence>=.65f && play.cards.isEmpty()
    private fun quantityPrior(seat: Seat): QuantityAnchoredPlayProof.PriorPresentation? = presentations[seat]
        ?.takeIf { it.eventId!=null }?.let {
            // An old blind row cannot justify expanding its event. Retain its
            // last physical reference only for the independent full-count proof.
            QuantityAnchoredPlayProof.PriorPresentation(it.eventId!!,it.cards.toList(),
                it.glyphs.ifEmpty { it.colorReturnAnchor }.toList())
        }
    private fun collectOthers(frame: TableFrame,commitNow: Boolean) {
        val confirmed=if(commitNow) ledger.snapshot() else null
        if(confirmed==null) countAnchoredRepeatedSingles.clear()
        for(seat in listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT)) {
            val repeatedProof=repeatedPlayProofs.getValue(seat)
            val singleProof=repeatedSingleProofs.getValue(seat)
            val quantityProof=quantityProofs.getValue(seat)
            val passedSupersetProof=passedSupersetProofs.getValue(seat)
            if(confirmed?.played(seat)==27) {
                repeatedProof.clear()
                singleProof.clear()
                quantityProof.clear()
                passedSupersetProof.clear()
                // A finished player's last presentation can temporarily move away during
                // an effect and return over real table pixels. It cannot be another play.
                playVotes[seat]!!.clear(); emptyVotes[seat]!!.clear(); visible[seat]=""
                presentations[seat]=null; previousGlyphs[seat]=emptyList(); empty[seat]=0
                continue
            }
            val p=frame.plays[seat]
            // A fresh per-seat count baseline remains observable while the old
            // table row is covered by its ordinary clear animation. Only a new,
            // directly seen single plus two later decrement sources can commit.
            val reappeared=confirmed?.let {
                countAnchoredRepeatedSingles.observe(frame,it,seat,presentations[seat]?.eventId)
            }
            if(reappeared!=null) {
                if(!commit(seat,reappeared.cards)) return
                val event=ledger.snapshot().plays.getValue(seat).last()
                presentations[seat]=Presentation(reappeared.cards.toList(),reappeared.glyphs.toList(),frame.timeMs,event.eventId)
                visible[seat]=key(reappeared.cards); previousGlyphs[seat]=reappeared.glyphs.toList()
                playVotes.getValue(seat).clear(); emptyVotes.getValue(seat).clear()
                repeatedProof.clear(); singleProof.clear(); quantityProof.clear()
                if(confirmedRoundFinished(ledger.snapshot())) { finishConfirmedRound(); return }
                continue
            }
            val priorPresentation=quantityPrior(seat)
            // A pass bubble can cover the old glyph sampling area for seconds.
            // Keep that old event intact: only a separately located, repeatedly
            // observed complete group after a proved closed turn earns a new event.
            val passedSuperset=if(confirmed!=null) passedSupersetProof.observe(frame,confirmed,seat,
                priorPresentation,ledger.currentHand(),presentations[seat]?.lastSeen ?: -1L)
                else { passedSupersetProof.clear(); null }
            val shortPlay=passedSuperset ?: if(confirmed!=null) quantityProof.observe(frame,confirmed,seat,
                countPlayBoundaryAt[seat].takeIf { presentations[seat]==null },priorPresentation)
                else { quantityProof.clear(); null }
            if(shortPlay!=null) {
                if(!commit(seat,shortPlay.cards)) return
                val event=ledger.snapshot().plays.getValue(seat).last()
                presentations[seat]=Presentation(shortPlay.cards.toList(),shortPlay.glyphs.toList(),frame.timeMs,event.eventId)
                visible[seat]=key(shortPlay.cards); previousGlyphs[seat]=shortPlay.glyphs.toList()
                playVotes.getValue(seat).clear(); emptyVotes.getValue(seat).clear()
                repeatedProof.clear(); singleProof.clear(); quantityProof.clear(); passedSupersetProof.clear()
                if(p?.cards?.isEmpty()==true) observedEmptyAt[seat]=frame.timeMs
                if(confirmedRoundFinished(ledger.snapshot())) { finishConfirmedRound(); return }
                continue
            }
            if(p==null || !p.regionObserved || p.confidence<.65f) {
                repeatedProof.clear()
                singleProof.unobserved(frame.timeMs)
                // A low score with a visible region may recover its known geometry.
                // A blind region cannot establish that an expansion is the old play.
                presentations[seat]?.let { current ->
                    if(p==null || !p.regionObserved) {
                        if(current.unobservedSince==null) current.unobservedSince=frame.timeMs
                        if(frame.timeMs-current.unobservedSince!!>500) current.glyphs=emptyList()
                    } else current.unobservedSince=null
                }
                playVotes[seat]!!.clear(); emptyVotes[seat]!!.clear(); previousGlyphs[seat]=emptyList(); empty[seat]=0; continue
            }
            presentations[seat]?.let { current ->
                if(current.unobservedSince!=null && frame.timeMs-current.unobservedSince!!>500) current.glyphs=emptyList()
                current.unobservedSince=null
            }
            if(p.cards.filter { it.rank.isJoker }.groupingBy { it.rank }.eachCount().values.any { it>2 }) {
                repeatedProof.clear()
                singleProof.clear()
                // Wildcards cannot replace jokers. An impossible animated class
                // reading is an abstention, never a capacity-breaking ledger event.
                playVotes[seat]!!.clear(); previousGlyphs[seat]=emptyList(); continue
            }
            if(p.cards.isEmpty()) {
                repeatedProof.clear()
                singleProof.clear()
                observedEmptyAt[seat]=frame.timeMs
                playVotes[seat]!!.clear(); empty[seat]=empty[seat]!!+1
                if(emptyVotes[seat]!!.accept("empty",frame.timeMs)) {
                    visible[seat]=""; presentations[seat]=null; countPlayBoundaryAt[seat]=frame.timeMs
                }
                previousGlyphs[seat]=emptyList()
                continue
            }
            empty[seat]=0; emptyVotes[seat]!!.clear()
            val k=key(p.cards)
            val presentation=presentations[seat]
            val geometric=hasGeometry(p)
            val duplicateSource=if(confirmed!=null && presentation?.eventId!=null && k==visible[seat] &&
                geometric && containsTracked(p.glyphs,presentation.glyphs) && !frame.captureGap &&
                !frame.ambiguousPresentation && frame.incompleteReason==null) frame.remainingHandCounts[seat] else null
            val countBoundary=repeatedProof.observe(frame.timeMs,confirmed?.roundId,seat,presentation?.eventId,
                    confirmed?.let { 27-it.played(seat) } ?: 0,p,duplicateSource)
            val physicalBoundary=if(!countBoundary && confirmed!=null && presentation?.eventId!=null &&
                k==visible[seat] && !frame.captureGap && !frame.ambiguousPresentation && frame.incompleteReason==null)
                singleProof.observe(frame.timeMs,confirmed.roundId,presentation.eventId,27-confirmed.played(seat),p,
                    frame.remainingHandCounts[seat])
                else { singleProof.clear(); false }
            if(countBoundary || physicalBoundary) {
                // The cards are directly observed; the independent hand-count
                // decrement proves that this is a second play at the same row.
                if(!commit(seat,p.cards)) return
                val event=ledger.snapshot().plays.getValue(seat).last()
                presentations[seat]=Presentation(p.cards.toList(),p.glyphs.toList(),frame.timeMs,event.eventId)
                visible[seat]=k; previousGlyphs[seat]=p.glyphs.toList()
                singleProof.clear()
                playVotes.getValue(seat).clear()
                if(confirmedRoundFinished(ledger.snapshot())) { finishConfirmedRound(); return }
                continue
            }
            val trackedPresentation=geometric && presentation!=null && presentation.glyphs.isNotEmpty() &&
                frame.timeMs-presentation.lastSeen<=1500 && containsTracked(p.glyphs,presentation.glyphs)
            if(trackedPresentation) presentation!!.lastSeen=frame.timeMs
            val previous=previousGlyphs[seat]!!
            if(geometric && key(previous.map { it.card })==k && !containsTracked(p.glyphs,previous)) playVotes[seat]!!.clear()
            previousGlyphs[seat]=if(geometric) p.glyphs else emptyList()
            val sameConfirmedRanks=presentation?.eventId!=null && p.cards.size>1 &&
                p.cards.map { it.rank }.sorted()==presentation.cards.map { it.rank }.sorted() &&
                frame.timeMs-presentation.colorReturnSeen<=1500 && hasGeometry(p) &&
                p.glyphs.size==presentation.colorReturnAnchor.size &&
                containsTracked(p.glyphs,presentation.colorReturnAnchor,compareColor=false)
            val sameFourJokerTrack=presentation?.eventId!=null && presentation.cards.size==4 &&
                presentation.cards.count { it.rank==Rank.BIG }==2 && presentation.cards.count { it.rank==Rank.SMALL }==2 &&
                p.cards.size==4 && p.cards.all { it.rank.isJoker } && hasGeometry(p) &&
                frame.timeMs-presentation.colorReturnSeen<=1500 &&
                containsTracked(p.glyphs.map { it.copy(card=Card(Rank.BIG,Suit.NONE)) },
                    presentation.colorReturnAnchor.map { it.copy(card=Card(Rank.BIG,Suit.NONE)) })
            // A confirmed displayed group does not become another play when the
            // classifier's auxiliary red/black class flickers during an effect.
            // Keep the reference's single-card exception: different colours can
            // represent distinct single plays and are not suppressed here.
            // A positive empty boundary clears the old presentation first; then
            // another same-rank group still needs its normal four colour votes.
            if(k==visible[seat] || sameConfirmedRanks || sameFourJokerTrack) {
                if(presentation!=null && geometric && containsTracked(p.glyphs,presentation.glyphs)) {
                    presentation.glyphs=p.glyphs.toList(); presentation.lastSeen=frame.timeMs
                    presentation.colorReturnAnchor=p.glyphs.toList(); presentation.colorReturnSeen=frame.timeMs
                }
                if(sameConfirmedRanks || sameFourJokerTrack) {
                    presentation!!.colorReturnAnchor=p.glyphs.toList(); presentation.colorReturnSeen=frame.timeMs
                }
                playVotes[seat]!!.clear(); continue
            }
            if(presentation!=null && isSubset(p.cards,presentation.cards)) {
                // Losing a glyph from an unchanged displayed group never creates a second play.
                playVotes[seat]!!.clear(); continue
            }
            // The unique four-joker bomb has four independent card faces. Three
            // consecutive high-confidence, position-stable complete readings
            // suffice before its effect clears; all other plays retain four votes.
            val fourJokerProof=p.confidence>=.80f && hasGeometry(p) && p.cards.size==4 &&
                p.cards.count { it.rank==Rank.BIG }==2 && p.cards.count { it.rank==Rank.SMALL }==2
            // An existing physical prefix can gain directly seen missing cards
            // through three stable full readings; a new play still needs four.
            val trackedExpansion=presentation?.eventId!=null && p.confidence>=.70f && trackedPresentation &&
                p.cards.size>presentation.cards.size && isSubset(presentation.cards,p.cards)
            // A directly seen complete final hand has an independent size from
            // the accepted seat ledger. Three stable high-confidence frames
            // spanning at least 500ms may precede the result reveal; ordinary
            // presentations retain their four-frame confirmation.
            val finalHandProof=confirmed?.phase==RoundPhase.ACTIVE && frame.scene==TableScene.TABLE &&
                !frame.hand.isNullOrEmpty() && key(frame.hand)==key(ledger.currentHand()) &&
                !frame.captureGap && !frame.ambiguousPresentation && frame.incompleteReason==null &&
                p.confidence>=.85f && hasGeometry(p) && p.cards.size==27-confirmed.played(seat)
            val fastSpan=if(finalHandProof && !fourJokerProof && !trackedExpansion) 500L else 100L
            if(playVotes[seat]!!.accept(k,frame.timeMs,fastProof=fourJokerProof || trackedExpansion || finalHandProof,
                fastMinimumSpanMs=fastSpan)) {
                // A same-box BIG/SMALL flip during an arrival effect is a new
                // identity candidate, not automatically another play. A real
                // lasting switch still commits; a proved empty clears the anchor.
                val jokerSwitch=p.cards.singleOrNull()?.rank?.isJoker==true &&
                    presentation?.eventId!=null && presentation.cards.singleOrNull()?.rank?.isJoker==true &&
                    p.cards.single().rank!=presentation.cards.single().rank &&
                    frame.timeMs-presentation.colorReturnSeen<=1500 && hasGeometry(p) &&
                    containsTracked(p.glyphs.map { it.copy(card=Card(Rank.BIG,Suit.NONE)) },
                        presentation.colorReturnAnchor.map { it.copy(card=Card(Rank.BIG,Suit.NONE)) })
                if(jokerSwitch && playVotes[seat]!!.duration(frame.timeMs)<900) continue
                if(presentation!=null && isSubset(presentation.cards,p.cards)) {
                    if(trackedPresentation) {
                        if(commitNow) {
                            val id=presentation.eventId
                            if(id==null || !runCatching { ledger.replaceLatestObservedPresentation(id,p.cards); true }.getOrDefault(false)) {
                                incomplete("牌面变化待复核，正在重新识别",frame.timeMs,immediate=true); return
                            }
                            emit()
                        }
                        presentation.cards=p.cards.toList(); presentation.glyphs=p.glyphs.toList(); presentation.lastSeen=frame.timeMs
                        presentation.colorReturnAnchor=p.glyphs.toList(); presentation.colorReturnSeen=frame.timeMs
                        visible[seat]=k; playVotes[seat]!!.clear()
                        if(commitNow && confirmedRoundFinished(ledger.snapshot())) { finishConfirmedRound(); return }
                    } else if(playVotes[seat]!!.duration(frame.timeMs)>=900) {
                        // This conflict has already persisted through 900ms of
                        // confirmation, so it has used the short animation grace.
                        incomplete("出牌变化待核对，正在重新识别",frame.timeMs,immediate=true); return
                    }
                    // A stable strict superset alone cannot prove another play or the same animation.
                    continue
                }
                val next=Presentation(p.cards.toList(),if(geometric) p.glyphs.toList() else emptyList(),frame.timeMs)
                if(commitNow) {
                    if(!commit(seat,p.cards)) return
                    next.eventId=ledger.snapshot().plays[seat]!!.last().eventId
                } else pending.add(seat to next)
                visible[seat]=k; presentations[seat]=next
                playVotes[seat]!!.clear()
                if(commitNow && confirmedRoundFinished(ledger.snapshot())) { finishConfirmedRound(); return }
            }
        }
    }
    private fun isSubset(sub: List<Card>,all: List<Card>): Boolean {
        val rest=all.toMutableList()
        return sub.all { rest.remove(it) }
    }
    private fun hasGeometry(play: ObservedPlay)=play.glyphs.size==play.cards.size && key(play.glyphs.map { it.card })==key(play.cards) &&
        play.glyphs.all { it.box.width>0 && it.box.height>0 }
    /** One-to-one adjacent-frame association; rank/color equality alone is never a track. */
    private fun containsTracked(current: List<PlayGlyph>,tracked: List<PlayGlyph>,compareColor: Boolean=true): Boolean {
        if(tracked.isEmpty()) return false
        val remaining=current.toMutableList()
        for(old in tracked.sortedBy { it.box.cx }) {
            val match=remaining.filter { p -> (if(compareColor) p.card==old.card else p.card.rank==old.card.rank) &&
                kotlin.math.abs(p.box.cx-old.box.cx)<=minOf(p.box.width,old.box.width)*.35f &&
                kotlin.math.abs(p.box.cy-old.box.cy)<=minOf(p.box.height,old.box.height)*.35f &&
                maxOf(p.box.width,old.box.width)<=minOf(p.box.width,old.box.width)*1.4f &&
                maxOf(p.box.height,old.box.height)<=minOf(p.box.height,old.box.height)*1.4f
            }.minByOrNull { kotlin.math.abs(it.box.cx-old.box.cx)+kotlin.math.abs(it.box.cy-old.box.cy) } ?: return false
            remaining.remove(match)
        }
        return true
    }
    private fun commit(seat: Seat,cards: List<Card>): Boolean {
        val id=ledger.snapshot().roundId ?: return false
        val nextSequence=sequence+1
        return runCatching {
            ledger.commit(PlayEvent(id,"$id:$nextSequence",nextSequence,seat,cards))
            sequence=nextSequence; emit(); true
        }.getOrElse { incomplete("牌面变化待复核，正在重新识别",lastTime,immediate=true); false }
    }
}
