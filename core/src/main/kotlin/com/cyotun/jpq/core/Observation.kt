package com.cyotun.jpq.core

enum class TableScene { TABLE, LOBBY, TRIBUTE, TEAMMATE, RESULT, UNKNOWN }
data class ObservedPlay(val cards: List<Card>, val confidence: Float)
data class TableFrame(
    val timeMs: Long, val scene: TableScene, val hand: List<Card>?,
    /** Null is unobserved/occluded; an empty list is an observed empty region. */
    val plays: Map<Seat, ObservedPlay?> = emptyMap(),
)

/** Votes operate on observed card multisets. A missing region cannot clear a play. */
class RoundObserver(private val ledger: RoundLedger, private val changed: (RoundSnapshot)->Unit = {}) {
    private class Vote { var key=""; var count=0; var first=0L
        fun accept(value: String,time: Long): Boolean {
            if(key!=value) { key=value; count=1; first=time } else count++
            return count>=4 && time-first>=240
        }
        fun clear() { key=""; count=0; first=0 }
    }
    private val handVote=Vote()
    private val playVotes=Seat.entries.associateWith { Vote() }
    private val visible=Seat.entries.associateWith { "" }.toMutableMap()
    private val empty=Seat.entries.associateWith { 0 }.toMutableMap()
    private var sequence=0L
    private var lastTime=-1L
    private var roundSerial=0L
    private var lastRound: String?=null
    private var locked=false
    private var tribute=false
    private var armed=false
    private var unknownSince: Long?=null
    private fun key(cards: List<Card>)=cards.map { "${it.rank.name}:${it.suit.name}" }.sorted().joinToString(",")
    private fun emitChanged() { changed(ledger.snapshot()) }
    private fun clearVotes() { handVote.clear(); playVotes.values.forEach { it.clear() }; visible.keys.forEach { visible[it]=""; empty[it]=0 } }
    fun interrupted(reason: String) { locked=true; ledger.invalidate(reason); clearVotes(); emitChanged() }
    fun observe(frame: TableFrame) {
        if(frame.timeMs<=lastTime) return
        val snap=ledger.snapshot()
        if(snap.roundId!=lastRound) {
            if(lastRound!=null && snap.roundId==null) { locked=true; armed=false }
            clearVotes(); lastRound=snap.roundId
        }
        if(lastTime>=0 && frame.timeMs-lastTime>2500 && snap.phase in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED)) {
            interrupted("画面连续性中断，等待下一局")
        }
        lastTime=frame.timeMs
        if(frame.scene in setOf(TableScene.LOBBY,TableScene.RESULT)) {
            if(snap.roundId!=null) { ledger.finish(); emitChanged(); ledger.waitForNext(); lastRound=null; emitChanged() }
            locked=false; armed=true; tribute=false; clearVotes(); return
        }
        if(frame.scene==TableScene.UNKNOWN) {
            if(unknownSince==null) unknownSince=frame.timeMs
            if(frame.timeMs-unknownSince!!>1500 && snap.remaining!=null) interrupted("画面无法完整观察，等待下一局")
            handVote.clear(); playVotes.values.forEach { it.clear() }; return
        }
        unknownSince=null
        if(frame.scene==TableScene.TRIBUTE) {
            if(snap.plays.values.any { it.isNotEmpty() }) { interrupted("检测到未跟踪的牌面转移，等待下一局"); return }
            tribute=true; clearVotes(); return
        }
        if(tribute) {
            val allClear=Seat.entries.all { frame.plays[it]?.cards?.isEmpty()==true }
            if(!allClear) return
            empty[Seat.SELF]=(empty[Seat.SELF] ?: 0)+1
            if(empty[Seat.SELF]!!<2) return
            tribute=false; ledger.waitForNext(); lastRound=null; armed=true; clearVotes()
        }
        if(locked || ledger.snapshot().phase==RoundPhase.UNAVAILABLE) return
        if(frame.scene==TableScene.TEAMMATE && ledger.snapshot().phase==RoundPhase.ACTIVE) { ledger.selfFinished(); emitChanged() }
        val hand=frame.hand
        if(frame.scene!=TableScene.TEAMMATE && hand!=null && handVote.accept(key(hand),frame.timeMs)) {
            val current=ledger.snapshot()
            if(current.phase in setOf(RoundPhase.WAITING,RoundPhase.FINISHED)) {
                if(!armed || hand.size!=27 || frame.plays.values.any { it?.cards?.isNotEmpty()==true }) return
                runCatching {
                    ledger.begin("${frame.timeMs}-${++roundSerial}",hand); sequence=0; clearVotes(); lastRound=ledger.snapshot().roundId; emitChanged()
                }.onFailure { handVote.clear() }
            } else if(current.phase==RoundPhase.ACTIVE) {
                val previous=ledger.currentHand()
                if(key(previous)!=key(hand)) {
                    val removed=previous.toMutableList()
                    if(hand.any { !removed.remove(it) }) {
                        if(hand.size==27 && current.plays.values.all { it.isEmpty() }) {
                            ledger.waitForNext(); lastRound=null; armed=true; clearVotes(); emitChanged(); return
                        }
                        interrupted("手牌变化无法完整确认，等待下一局"); return
                    }
                    if(removed.isNotEmpty()) {
                        val proof=frame.plays[Seat.SELF]
                        if(proof!=null && proof.confidence>=.65f && key(proof.cards)==key(removed) && playVotes[Seat.SELF]!!.accept(key(removed),frame.timeMs)) commit(Seat.SELF,removed)
                    }
                }
            }
        }
        if(ledger.snapshot().remaining==null) return
        for(seat in listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT)) {
            val observation=frame.plays[seat] ?: continue
            val cards=observation.cards
            if(cards.isEmpty()) {
                playVotes[seat]!!.clear(); empty[seat]=empty[seat]!!+1
                if(empty[seat]!!>=2) visible[seat]=""
                continue
            }
            empty[seat]=0
            if(observation.confidence<.65f) { playVotes[seat]!!.clear(); continue }
            val k=key(cards)
            if(k==visible[seat]) continue
            if(playVotes[seat]!!.accept(k,frame.timeMs)) {
                if(commit(seat,cards)) visible[seat]=k
                playVotes[seat]!!.clear()
            }
        }
    }
    private fun commit(seat: Seat,cards: List<Card>): Boolean {
        val id=ledger.snapshot().roundId ?: return false
        return runCatching { ledger.commit(PlayEvent(id,"$id:${++sequence}",sequence,seat,cards)); emitChanged(); true }
            .getOrElse { interrupted("识别结果存在冲突，等待下一局"); false }
    }
}
