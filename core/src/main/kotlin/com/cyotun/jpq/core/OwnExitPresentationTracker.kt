package com.cyotun.jpq.core

/** Associates an observed own final group across the switch to teammate hand view.
 * It never commits a play or changes the immutable initial hand. */
class OwnExitPresentationTracker {
    private val pendingLifetimeMs=4000L
    private var glyphs=emptyList<PlayGlyph>()
    private var seenAt=-1L
    private var emptyVotes=0
    private var roundId: String?=null
    private var lastOwnHand=emptyList<Card>()
    private var lastOwnHandSeen=-1L
    private data class PendingExit(val cards: List<Card>,var glyphs: List<PlayGlyph>,val since: Long)
    private var pending: PendingExit?=null
    private var expiredReservation=false
    fun blocksCentralFallback()=pending!=null || expiredReservation
    fun prepare(time: Long,scene: TableScene,currentRoundId: String?): List<PlayGlyph> {
        if(currentRoundId!=roundId || scene in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.DEALING,TableScene.TRIBUTE)) reset()
        else if(seenAt>=0 && time-seenAt>1500) {
            reset(); expiredReservation=true
        }
        if(pending?.let { time-it.since>pendingLifetimeMs }==true) { pending=null; expiredReservation=true }
        roundId=currentRoundId
        // A partial exit is an ownership anchor, never a complete play count.
        return if(scene==TableScene.TEAMMATE) glyphs.ifEmpty { pending?.glyphs.orEmpty() } else emptyList()
    }
    /** The own-hand region can remain visible while the central surface has an effect. */
    fun observeOwnHand(time: Long,phase: RoundPhase,currentHand: List<Card>,observedHand: List<Card>) {
        if(phase==RoundPhase.ACTIVE && currentHand.isNotEmpty() && same(currentHand,observedHand)) {
            lastOwnHand=currentHand.toList(); lastOwnHandSeen=time
            // Seeing the still-owned hand again disproves an incomplete exit.
            pending=null; expiredReservation=false
        }
    }
    fun observe(time: Long,phase: RoundPhase,currentHand: List<Card>,observedHand: List<Card>,self: List<PlayGlyph>) {
        observeOwnHand(time,phase,currentHand,observedHand)
        if(self.isEmpty() || self.minOf { it.confidence }<.65f) return
        val rest=currentHand.toMutableList()
        val stillOwnHand=observedHand.all { rest.remove(it) }
        // The revealed teammate hand can arrive before its OCR label. The entire
        // old own hand positively displayed in the centre is still exit evidence.
        // This records ownership only; it never guesses a final play count.
        val exiting=phase==RoundPhase.ACTIVE && (observedHand.isEmpty() || !stillOwnHand) && currentHand.isNotEmpty() &&
            same(self.map { it.card },currentHand)
        val associated=associate(self,glyphs)
        if(exiting && glyphs.isEmpty()) { glyphs=self.toList(); pending=null; seenAt=time; emptyVotes=0 }
        else if(associated!=null) {
            // Rank/color equality alone cannot move an ownership proof. Follow
            // the same physical display only after one-to-one geometry matches.
            glyphs=associated; seenAt=time; emptyVotes=0
        } else if(glyphs.isEmpty()) {
            if(phase==RoundPhase.ACTIVE && (observedHand.isEmpty() || !stillOwnHand))
                seedPending(time,currentHand,self)
            refreshPending(time,self)
        }
    }
    /**
     * OCR can expose the teammate label on the first own-completion frame. At
     * that instant the ordinary SELF assignment is already disabled. Seed only
     * from a freshly seen complete own hand and a matching final group in the
     * ordinary self row. A briefly partial group may recover missing visible
     * glyphs after completion, but only through its already established geometry.
     */
    fun teammateTransition(time: Long,phase: RoundPhase,currentHand: List<Card>,table: List<PlayGlyph>) {
        if(phase !in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED) || glyphs.isNotEmpty()) return
        val candidate=assignPlayGroups(table,emptySet(),emptyList()).seats.getValue(Seat.SELF)
        if(candidate.isEmpty() || candidate.minOf { it.confidence }<.65f) return
        if(phase==RoundPhase.ACTIVE) seedPending(time,currentHand,candidate)
        refreshPending(time,candidate)
    }
    fun emptySurface(observedCleanEmpty: Boolean,selfDetected: Boolean,unownedCentralDetected: Boolean=false) {
        if((glyphs.isEmpty() && pending==null && !expiredReservation) || selfDetected || unownedCentralDetected) {
            emptyVotes=0; return
        }
        if(observedCleanEmpty) { if(++emptyVotes>=2) reset() } else emptyVotes=0
    }
    fun reset() { glyphs=emptyList(); pending=null; expiredReservation=false; emptyVotes=0; seenAt=-1; lastOwnHand=emptyList(); lastOwnHandSeen=-1 }
    private fun same(a: List<Card>,b: List<Card>)=a.groupingBy { it.colorKey() }.eachCount()==b.groupingBy { it.colorKey() }.eachCount()
    private fun seedPending(time: Long,currentHand: List<Card>,candidate: List<PlayGlyph>) {
        if(pending!=null || lastOwnHandSeen<0 || time-lastOwnHandSeen !in 0L..500L ||
            !same(lastOwnHand,currentHand) || currentHand.isEmpty()) return
        val rest=currentHand.toMutableList()
        if(candidate.all { rest.remove(it.card) }) pending=PendingExit(currentHand.toList(),candidate.toList(),time)
    }
    private fun refreshPending(time: Long,candidate: List<PlayGlyph>) {
        val previous=pending ?: return
        if(time-previous.since !in 0L..pendingLifetimeMs || associate(candidate,previous.glyphs,true)==null) return
        val rest=previous.cards.toMutableList()
        if(!candidate.all { rest.remove(it.card) }) return
        if(rest.isEmpty()) {
            // Every missing card is now directly visible; no absent card is invented.
            glyphs=candidate.toList(); pending=null; seenAt=time; emptyVotes=0
        } else previous.glyphs=candidate.toList()
    }
    private fun associate(current: List<PlayGlyph>,old: List<PlayGlyph>,allowExpansion: Boolean=false): List<PlayGlyph>? {
        if(old.isEmpty() || current.size<old.size || (!allowExpansion && current.size!=old.size)) return null
        val remaining=current.toMutableList()
        return old.map { tracked ->
            val match=remaining.filter { p ->
                p.card==tracked.card &&
                kotlin.math.abs(p.box.cx-tracked.box.cx)<=minOf(p.box.width,tracked.box.width)*.35f &&
                kotlin.math.abs(p.box.cy-tracked.box.cy)<=minOf(p.box.height,tracked.box.height)*.35f &&
                maxOf(p.box.width,tracked.box.width)<=minOf(p.box.width,tracked.box.width)*1.4f &&
                maxOf(p.box.height,tracked.box.height)<=minOf(p.box.height,tracked.box.height)*1.4f
            }.minByOrNull { kotlin.math.abs(it.box.cx-tracked.box.cx)+kotlin.math.abs(it.box.cy-tracked.box.cy) }
                ?: return null
            remaining.remove(match)
            match.copy(card=tracked.card)
        }
    }
}
