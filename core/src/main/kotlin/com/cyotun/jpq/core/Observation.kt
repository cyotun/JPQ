package com.cyotun.jpq.core

enum class TableScene { TABLE, DEALING, LOBBY, TRIBUTE, TEAMMATE, RESULT, UNKNOWN }
data class ObservedPlay(val cards: List<Card>,val confidence: Float,val glyphs: List<PlayGlyph> = emptyList(),val regionObserved: Boolean = true)
data class TableFrame(val timeMs: Long,val scene: TableScene,val hand: List<Card>?,val plays: Map<Seat,ObservedPlay?> = emptyMap(),val selfFinished: Boolean = false)
data class ObserverStatus(val armed: Boolean,val locked: Boolean,val baselineWindow: Boolean,val tribute: Boolean,
    val handVotes: Int,val playVotes: Map<Seat,Int>,val pendingPlays: Int,val sequence: Long)

/** Short animations are candidates; only lost continuity permanently invalidates a round. */
class RoundObserver(private val ledger: RoundLedger,private val changed: (RoundSnapshot)->Unit = {}) {
    private class Vote(private val required: Int) {
        var key=""; var count=0; var first=0L
        private var lastVoteTime=-1L
        fun accept(value: String,time: Long): Boolean {
            if(time==lastVoteTime) return count>=required && time-first>=100
            val previousTime=lastVoteTime
            lastVoteTime=time
            if(key!=value || previousTime<0 || time-previousTime>500) { key=value; count=1; first=time } else count++
            return count>=required && time-first>=100
        }
        fun clear() { key=""; count=0; lastVoteTime=-1 }
        fun duration(time: Long)=if(count==0) 0 else time-first
    }
    private class Presentation(var cards: List<Card>,var glyphs: List<PlayGlyph>,var lastSeen: Long,var eventId: String?=null,
                               var unobservedSince: Long?=null)
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
    fun diagnosticStatus()=ObserverStatus(armed,locked,baselineWindow,tribute,handVote.count,playVotes.mapValues { it.value.count },pending.size,sequence)
    private fun key(cards: List<Card>)=cards.map { "${it.rank.name}:${it.suit.name}" }.sorted().joinToString(",")
    private fun emit() { runCatching { changed(ledger.snapshot()) } }
    private fun clearCandidates() {
        handVote.clear(); tributeVote.clear(); playVotes.values.forEach { it.clear() }; emptyVotes.values.forEach { it.clear() }; pending.clear()
        visible.keys.forEach { visible[it]=""; presentations[it]=null; previousGlyphs[it]=emptyList(); empty[it]=0 }; unknownSince=null
    }
    fun interrupted(reason: String) { locked=true; ledger.invalidate(reason); clearCandidates(); emit() }
    fun observe(frame: TableFrame) {
        if(frame.timeMs<=lastTime) return
        var snap=ledger.snapshot()
        if(snap.roundId!=lastRound) {
            if(lastRound!=null && snap.roundId==null) { locked=true; armed=false; baselineWindow=false }
            clearCandidates(); lastRound=snap.roundId
        }
        if(lastTime>=0 && frame.timeMs-lastTime>2500 && snap.remaining!=null) interrupted("画面连续性中断，等待下一局")
        lastTime=frame.timeMs
        if(frame.scene in setOf(TableScene.LOBBY,TableScene.RESULT)) {
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
        if(frame.scene==TableScene.UNKNOWN) {
            if(unknownSince==null) unknownSince=frame.timeMs
            if(frame.timeMs-unknownSince!!>2000 && snap.remaining!=null) interrupted("画面无法完整观察，等待下一局")
            handVote.clear(); tributeVote.clear(); playVotes.values.forEach { it.clear() }; emptyVotes.values.forEach { it.clear() }; return
        }
        unknownSince=null
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
        if((frame.scene==TableScene.TEAMMATE || frame.selfFinished) && ledger.snapshot().phase==RoundPhase.ACTIVE) { ledger.selfFinished(); emit() }
        if(ledger.snapshot().phase==RoundPhase.SELF_FINISHED &&
            (ledger.snapshot().played(Seat.OPPOSITE)==27 ||
            listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).count { ledger.snapshot().played(it)==27 }>=2)) {
            // Both teammates have finished, or three players have finished. The
            // remaining players' revealed cards are not new table presentations.
            ledger.finish(); emit(); ledger.waitForNext(); lastRound=null; emit()
            armed=false; baselineWindow=false; tribute=false; freshDeal=false; clearCandidates(); return
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
            if(p.cards.isEmpty()) {
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
            if(k==visible[seat]) {
                if(presentation!=null && geometric && containsTracked(p.glyphs,presentation.glyphs)) {
                    presentation.glyphs=p.glyphs.toList(); presentation.lastSeen=frame.timeMs
                }
                playVotes[seat]!!.clear(); continue
            }
            if(presentation!=null && isSubset(p.cards,presentation.cards)) {
                // Losing a glyph from an unchanged displayed group never creates a second play.
                playVotes[seat]!!.clear(); continue
            }
            if(playVotes[seat]!!.accept(k,frame.timeMs)) {
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
                        visible[seat]=k; playVotes[seat]!!.clear()
                    } else if(playVotes[seat]!!.duration(frame.timeMs)>=900) {
                        interrupted("出牌变化无法完整确认，等待下一局"); return
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
    private fun containsTracked(current: List<PlayGlyph>,tracked: List<PlayGlyph>): Boolean {
        if(tracked.isEmpty()) return false
        val remaining=current.toMutableList()
        for(old in tracked.sortedBy { it.box.cx }) {
            val match=remaining.filter { p -> p.card==old.card &&
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
