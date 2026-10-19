package com.cyotun.jpq.core

/**
 * Proves one additional play only when its still-visible, already confirmed
 * presentation is accompanied by a fresh, seat-specific hand-count decrement.
 * `eventId` identifies the already-confirmed presentation that remains visible;
 * `reading` must be selected for that same seat. The caller must pass null when
 * the count source is masked, ambiguous, or otherwise unqualified. This proof
 * never chooses or edits the observed cards.
 */
class RepeatedPlayCountProof {
    private data class Version(
        val roundId: String,
        val seat: Seat,
        val eventId: String,
        val expectedRemaining: Int,
        val cardKey: List<String>,
    )

    private data class Candidate(
        val version: Version,
        val geometry: List<PlayGlyph>,
        var lastFrameTime: Long,
        var lastSourceTime: Long,
        var anchorReady: Boolean = false,
        var anchorLastVote: Long = -1L,
        var anchorVotes: Int = 0,
        var targetFrames: Int = 0,
        var targetLastVote: Long = -1L,
        var targetVotes: Int = 0,
    )

    private var candidate: Candidate? = null
    /** A committed version can return true only once, even if later frames repeat it. */
    private var committedVersion: Version? = null
    // Keep the high-water marks across candidate resets so a stale/repeated OCR
    // result cannot be replayed as a fresh baseline after an invalid frame.
    private var lastFrameTime=-1L
    private var lastSourceTime=-1L
    private var lastSourceCount: Int?=null

    /**
     * Returns true once: two fresh baseline readings at least 100ms apart, then
     * four uninterrupted matching-play frames including two fresh decremented
     * count readings at least 100ms apart.
     */
    @Synchronized fun observe(
        time: Long,
        roundId: String?,
        seat: Seat,
        eventId: String?,
        expectedRemaining: Int,
        play: ObservedPlay?,
        reading: RemainingHandCount?,
    ): Boolean {
        if (time < 0 || time<=lastFrameTime) {
            clearCandidate()
            return false
        }
        lastFrameTime=time
        val sourceTime=reading?.sourceTimeMs
        var repeatedSource=false
        if (sourceTime!=null && sourceTime>=0 && sourceTime<=time) {
            if(sourceTime<lastSourceTime) {
                clearCandidate()
                return false
            }
            if(sourceTime==lastSourceTime) {
                if(lastSourceCount!=reading.count) {
                    clearCandidate()
                    return false
                }
                repeatedSource=true
            } else {
                lastSourceTime=sourceTime
                lastSourceCount=reading.count
            }
        }

        val validPlay = play?.takeIf(::validPlay)
        if (roundId.isNullOrBlank() || eventId.isNullOrBlank() ||
            seat == Seat.SELF || expectedRemaining !in 1..27 || validPlay == null || reading == null ||
            reading.count !in 1..27 || reading.sourceTimeMs < 0 || reading.sourceTimeMs > time ||
            time - reading.sourceTimeMs !in 0L..1000L) {
            clearCandidate()
            return false
        }

        val cards = validPlay.cards.toList()
        val targetRemaining = expectedRemaining - cards.size
        if (cards.isEmpty() || targetRemaining !in 1..27) {
            clearCandidate()
            return false
        }
        val version = Version(roundId,seat,eventId,expectedRemaining,cardKey(cards))

        if (committedVersion == version) return false
        if (committedVersion != null) committedVersion=null

        var current = candidate
        if (current?.version != version) {
            current = null
            candidate = null
        }
        // A repeated count source may carry visible play frames, but after a
        // reset it cannot recreate the baseline it previously contributed to.
        if(repeatedSource && current==null) return false
        if (current != null && (time <= current.lastFrameTime ||
                time-current.lastFrameTime>MAX_FRAME_GAP_MS || reading.sourceTimeMs < current.lastSourceTime)) {
            clearCandidate()
            return false
        }
        if (current != null && !sameGeometry(validPlay.glyphs,current.geometry)) {
            clearCandidate()
            current = null
        }
        if (current == null) {
            current = Candidate(version,validPlay.glyphs.toList(),time,reading.sourceTimeMs)
            candidate = current
            return if (reading.count == expectedRemaining) {
                current.anchorLastVote=reading.sourceTimeMs
                current.anchorVotes=1
                false
            } else false
        }

        current.lastFrameTime=time
        current.lastSourceTime=reading.sourceTimeMs
        when (reading.count) {
            expectedRemaining -> {
                // A return to the old count invalidates a partially observed drop.
                if (current.targetFrames>0) {
                    current.anchorReady=false
                    current.anchorVotes=1
                    current.anchorLastVote=reading.sourceTimeMs
                    current.targetFrames=0
                    current.targetVotes=0
                    current.targetLastVote=-1L
                    return false
                }
                if (!current.anchorReady && reading.sourceTimeMs-current.anchorLastVote>=MIN_SOURCE_GAP_MS) {
                    current.anchorVotes++
                    current.anchorLastVote=reading.sourceTimeMs
                    if (current.anchorVotes>=REQUIRED_COUNT_VOTES) current.anchorReady=true
                }
                return false
            }
            targetRemaining -> {
                if (!current.anchorReady) {
                    clearCandidate()
                    return false
                }
                current.targetFrames++
                if (current.targetVotes==0) {
                    current.targetVotes=1
                    current.targetLastVote=reading.sourceTimeMs
                } else if (reading.sourceTimeMs-current.targetLastVote>=MIN_SOURCE_GAP_MS) {
                    current.targetVotes++
                    current.targetLastVote=reading.sourceTimeMs
                }
                if (current.targetFrames>=REQUIRED_PLAY_FRAMES && current.targetVotes>=REQUIRED_COUNT_VOTES) {
                    committedVersion=version
                    clearCandidate()
                    return true
                }
                return false
            }
            else -> {
                clearCandidate()
                return false
            }
        }
    }

    /** Clear evidence after a capture gap/reset; monotonic time fences remain to reject replayed OCR. */
    @Synchronized fun clear() {
        candidate=null
        committedVersion=null
    }

    private fun clearCandidate() { candidate=null }

    private fun validPlay(play: ObservedPlay): Boolean {
        if (!play.regionObserved || play.confidence<MIN_PLAY_CONFIDENCE || play.cards.isEmpty() ||
            play.glyphs.size!=play.cards.size) return false
        if (cardIdentityKey(play.cards)!=cardIdentityKey(play.glyphs.map { it.card })) return false
        return play.glyphs.all { glyph ->
            val b=glyph.box
            b.left.isFinite() && b.top.isFinite() && b.right.isFinite() && b.bottom.isFinite() &&
                b.left>=0f && b.top>=0f && b.right<=1f && b.bottom<=1f && b.width>0f && b.height>0f
        }
    }

    private fun cardIdentityKey(cards: List<Card>) = cards.map { "${it.rank.name}:${it.suit.name}" }.sorted()
    private fun cardKey(cards: List<Card>) = cards.map { it.colorKey() }.sorted()

    /** Match each glyph once by rank/color and the existing track geometry tolerance. */
    private fun sameGeometry(current: List<PlayGlyph>, tracked: List<PlayGlyph>): Boolean {
        if (current.size!=tracked.size || tracked.isEmpty()) return false
        val unmatched=current.toMutableList()
        for (old in tracked.sortedWith(compareBy({it.box.cx},{it.box.cy}))) {
            val match=unmatched.filter { next ->
                next.card.colorKey()==old.card.colorKey() &&
                    kotlin.math.abs(next.box.cx-old.box.cx)<=minOf(next.box.width,old.box.width)*.35f &&
                    kotlin.math.abs(next.box.cy-old.box.cy)<=minOf(next.box.height,old.box.height)*.35f &&
                    maxOf(next.box.width,old.box.width)<=minOf(next.box.width,old.box.width)*1.4f &&
                    maxOf(next.box.height,old.box.height)<=minOf(next.box.height,old.box.height)*1.4f
            }.minByOrNull { kotlin.math.abs(it.box.cx-old.box.cx)+kotlin.math.abs(it.box.cy-old.box.cy) }
                ?: return false
            unmatched.remove(match)
        }
        return unmatched.isEmpty()
    }

    private companion object {
        const val MIN_SOURCE_GAP_MS=100L
        const val MAX_FRAME_GAP_MS=500L
        const val REQUIRED_COUNT_VOTES=2
        const val REQUIRED_PLAY_FRAMES=4
        const val MIN_PLAY_CONFIDENCE=.65f
    }
}
