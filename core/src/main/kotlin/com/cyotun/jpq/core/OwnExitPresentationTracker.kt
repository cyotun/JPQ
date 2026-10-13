package com.cyotun.jpq.core

/** Associates an observed own final group across the switch to teammate hand view.
 * It never commits a play or changes the immutable initial hand. */
class OwnExitPresentationTracker {
    private var glyphs=emptyList<PlayGlyph>()
    private var seenAt=-1L
    private var emptyVotes=0
    private var roundId: String?=null
    fun prepare(time: Long,scene: TableScene,currentRoundId: String?): List<PlayGlyph> {
        if(currentRoundId!=roundId || scene in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.DEALING,TableScene.TRIBUTE) ||
            (seenAt>=0 && time-seenAt>1500)) reset()
        roundId=currentRoundId
        return if(scene==TableScene.TEAMMATE) glyphs else emptyList()
    }
    fun observe(time: Long,phase: RoundPhase,currentHand: List<Card>,observedHand: List<Card>,self: List<PlayGlyph>) {
        if(self.isEmpty() || self.minOf { it.confidence }<.65f) return
        val rest=currentHand.toMutableList()
        val stillOwnHand=observedHand.all { rest.remove(it) }
        // The revealed teammate hand can arrive before its OCR label. The entire
        // old own hand positively displayed in the centre is still exit evidence.
        // This records ownership only; it never guesses a final play count.
        val exiting=phase==RoundPhase.ACTIVE && (observedHand.isEmpty() || !stillOwnHand) && currentHand.isNotEmpty() &&
            same(self.map { it.card },currentHand)
        val associated=associate(self,glyphs)
        if(exiting && glyphs.isEmpty()) { glyphs=self.toList(); seenAt=time; emptyVotes=0 }
        else if(associated!=null) {
            // Rank/color equality alone cannot move an ownership proof. Follow
            // the same physical display only after one-to-one geometry matches.
            glyphs=associated; seenAt=time; emptyVotes=0
        }
    }
    fun emptySurface(observedCleanEmpty: Boolean,selfDetected: Boolean) {
        if(glyphs.isEmpty() || selfDetected) return
        if(observedCleanEmpty) { if(++emptyVotes>=2) reset() } else emptyVotes=0
    }
    fun reset() { glyphs=emptyList(); emptyVotes=0; seenAt=-1 }
    private fun same(a: List<Card>,b: List<Card>)=a.groupingBy { it.colorKey() }.eachCount()==b.groupingBy { it.colorKey() }.eachCount()
    private fun associate(current: List<PlayGlyph>,old: List<PlayGlyph>): List<PlayGlyph>? {
        if(old.isEmpty() || current.size!=old.size) return null
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
