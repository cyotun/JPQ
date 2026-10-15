package com.cyotun.jpq.core

enum class TableScene { TABLE, DEALING, LOBBY, TRIBUTE, TEAMMATE, RESULT, UNKNOWN }
data class ObservedPlay(val cards: List<Card>,val confidence: Float,val glyphs: List<PlayGlyph> = emptyList(),val regionObserved: Boolean = true)
data class TableFrame(val timeMs: Long,val scene: TableScene,val hand: List<Card>?,val plays: Map<Seat,ObservedPlay?> = emptyMap(),val selfFinished: Boolean = false,
                      val incompleteReason: String? = null,val ambiguousPresentation: Boolean = false,val captureGap: Boolean = false,
                      val teammateHand: List<Card>? = null)
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
        fun accept(value: String,time: Long,fastProof: Boolean=false): Boolean {
            if(time==lastVoteTime) return accepted
            val previousTime=lastVoteTime
            lastVoteTime=time
            if(key!=value || previousTime<0 || time-previousTime>500) {
                key=value; count=1; first=time; strongCount=0
            } else count++
            if(fastProof) { if(strongCount==0) strongFirst=time; strongCount++ } else strongCount=0
            accepted=(count>=required && time-first>=100) || (strongCount>=3 && time-strongFirst>=100)
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
    private val observedEmptyAt=mutableMapOf<Seat,Long>()
    fun diagnosticStatus()=ObserverStatus(armed,locked,baselineWindow,tribute,handVote.count,playVotes.mapValues { it.value.count },pending.size,sequence,recovery!=null)
    private fun key(cards: List<Card>)=cards.map { "${it.rank.name}:${it.suit.name}" }.sorted().joinToString(",")
    private fun emit() { runCatching { changed(ledger.snapshot()) } }
    private fun clearCandidates() {
        handVote.clear(); tributeVote.clear(); playVotes.values.forEach { it.clear() }; emptyVotes.values.forEach { it.clear() }; pending.clear()
        visible.keys.forEach { visible[it]=""; presentations[it]=null; previousGlyphs[it]=emptyList(); empty[it]=0 }; unknownSince=null
        recovery=null; recoveryStartedAt=-1L; recoveryReason=""; ambiguitySince=null
        shortJokerAnimation=false; jokerTableReturnedAt=null; jokerTeammateVote.clear(); observedEmptyAt.clear()
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
        val snapshot=ledger.snapshot()
        if(recovery!=null) { publishRecoveryIfDue(timeMs,immediate); return }
        if(snapshot.phase !in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED)) return
        if(snapshot.roundId!=lastRound) { clearCandidates(); lastRound=snapshot.roundId }
        val anchors=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).filter { snapshot.played(it)<27 }.associateWith { seat ->
            val p=presentations[seat]
            if(p?.eventId!=null) RoundRecovery.Anchor(p.cards.toList(),p.glyphs.toList(),p.eventId,p.lastSeen)
            else RoundRecovery.Anchor(emptyList(),emptyList(),null,observedEmptyAt[seat] ?: -10000L)
        }
        recovery=RoundRecovery(timeMs,ledger.currentHand(),snapshot.phase==RoundPhase.SELF_FINISHED,anchors,
            snapshot.plays.values.all { it.isEmpty() })
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
        }
        if(lastTime>=0 && frame.timeMs-lastTime>2500 && snap.remaining!=null)
            incomplete("画面连续性中断，正在重新核对",lastTime,immediate=true)
        lastTime=frame.timeMs
        if(frame.scene in setOf(TableScene.LOBBY,TableScene.RESULT)) {
            // A result cannot certify a checkpoint still awaiting reconciliation.
            publishRecoveryIfDue(frame.timeMs,immediate=true)
            if(snap.roundId!=null) { ledger.finish(); emit(); ledger.waitForNext(); lastRound=null; emit() }
            locked=false; armed=true; tribute=false; freshDeal=false; baselineWindow=false; sceneBeforeDeal=frame.scene
            clearCandidates(); return
        }
        if(frame.scene==TableScene.DEALING) {
            // A visible new dealing/doubling stage is a boundary even without visiting the lobby.
            if(sceneBeforeDeal!=TableScene.DEALING && frame.timeMs-lastDealAt>3000) {
                val current=ledger.snapshot()
                val sameOpening=current.phase==RoundPhase.ACTIVE && current.plays.values.all { it.isEmpty() } &&
                    frame.hand?.size==27 && key(frame.hand)==key(ledger.initialHand())
                // Doubling labels appear and disappear while each player decides. That is still
                // the same opening when the complete hand and empty event history are unchanged.
                if(!sameOpening) {
                    if(current.roundId!=null) { ledger.finish(); emit(); ledger.waitForNext(); lastRound=null; emit() }
                    clearCandidates(); locked=false; tribute=false; armed=true; freshDeal=true; baselineWindow=true
                }
                lastDealAt=frame.timeMs
            }
            sceneBeforeDeal=TableScene.DEALING
        } else if(frame.scene!=TableScene.UNKNOWN) sceneBeforeDeal=frame.scene
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
        if(frame.ambiguousPresentation) {
            if(ambiguitySince==null) ambiguitySince=frame.timeMs
            // Retain the original 1500ms ownership-animation tolerance. These
            // unconfirmed candidates receive no play/empty/hand votes; they are
            // distinct from a genuinely UNKNOWN capture's stricter checkpoint.
            if(recovery==null && frame.timeMs-ambiguitySince!!<=1500) {
                handVote.clear(); playVotes.values.forEach { it.clear() }; emptyVotes.values.forEach { it.clear() }
                return
            }
            incomplete(frame.incompleteReason ?: "出牌归属待核对，正在重新识别",ambiguitySince!!)
        } else ambiguitySince=null
        frame.incompleteReason?.let { incomplete(it,frame.timeMs) }
        recovery?.let { checkpoint ->
            val expansions=checkpoint.reconcile(frame) ?: run { publishRecoveryIfDue(frame.timeMs); return }
            if(!runCatching { ledger.resumeAfterRecovery(expansions) }.isSuccess) {
                interrupted("识别结果存在冲突，等待下一局"); return
            }
            for((seat,presentation) in presentations) {
                if(presentation?.eventId in expansions.keys) {
                    val observed=frame.plays[seat]!!
                    presentation!!.cards=observed.cards.toList(); presentation.glyphs=observed.glyphs.toList()
                    presentation.lastSeen=frame.timeMs; presentation.colorReturnAnchor=observed.glyphs.toList()
                    presentation.colorReturnSeen=frame.timeMs; visible[seat]=key(observed.cards)
                }
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
    private fun observedEmpty(play: ObservedPlay?)=play!=null && play.regionObserved && play.confidence>=.65f && play.cards.isEmpty()
    private fun collectOthers(frame: TableFrame,commitNow: Boolean) {
        val confirmed=if(commitNow) ledger.snapshot() else null
        for(seat in listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT)) {
            if(confirmed?.played(seat)==27) {
                // A finished player's last presentation can temporarily move away during
                // an effect and return over real table pixels. It cannot be another play.
                playVotes[seat]!!.clear(); emptyVotes[seat]!!.clear(); visible[seat]=""
                presentations[seat]=null; previousGlyphs[seat]=emptyList(); empty[seat]=0
                continue
            }
            val p=frame.plays[seat]
            if(p==null || !p.regionObserved || p.confidence<.65f) {
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
                // Wildcards cannot replace jokers. An impossible animated class
                // reading is an abstention, never a capacity-breaking ledger event.
                playVotes[seat]!!.clear(); previousGlyphs[seat]=emptyList(); continue
            }
            if(p.cards.isEmpty()) {
                observedEmptyAt[seat]=frame.timeMs
                playVotes[seat]!!.clear(); empty[seat]=empty[seat]!!+1
                if(emptyVotes[seat]!!.accept("empty",frame.timeMs)) { visible[seat]=""; presentations[seat]=null }
                previousGlyphs[seat]=emptyList()
                continue
            }
            empty[seat]=0; emptyVotes[seat]!!.clear()
            val k=key(p.cards)
            val presentation=presentations[seat]
            val geometric=hasGeometry(p)
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
            if(playVotes[seat]!!.accept(k,frame.timeMs,fastProof=fourJokerProof)) {
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
                                interrupted("识别结果存在冲突，等待下一局"); return
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
        return runCatching { ledger.commit(PlayEvent(id,"$id:${++sequence}",sequence,seat,cards)); emit(); true }
            .getOrElse { interrupted("识别结果存在冲突，等待下一局"); false }
    }
}
