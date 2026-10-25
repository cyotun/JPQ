package com.cyotun.jpq.core

/**
 * Proves a directly visible second single-card play when the previous display
 * may have been hidden by an animation instead of a positively observed clear.
 *
 * The observed card supplies the identity. A seat-specific hand-count decrease
 * supplies only the independent play boundary; it is never used to infer cards.
 * Keep one instance per observer and call it for each outside seat before the
 * normal presentation deduplication path.
 */
class CountAnchoredRepeatedSingleProof {
    // Ordinary cards require the separate corner/suit reader. A joker has no
    // suit: its current strong model glyph identifies BIG/SMALL directly. Do
    // not manufacture a corner identity or a confidence margin for that path.
    private data class DirectReading(val play: ObservedPlay,val card: Card,val sourceTimeMs: Long)
    private data class Version(
        val roundId: String,
        val seat: Seat,
        val eventId: String,
        val expectedRemaining: Int,
        val oldColorKey: String,
    )

    private data class Candidate(
        val version: Version,
        val scene: TableScene,
        val phase: RoundPhase,
        val card: Card,
        var geometry: GlyphBox,
        val firstTime: Long,
        var lastFrameTime: Long,
        var lastIdentitySourceTime: Long,
        var frames: Int = 1,
        var targetSources: Int = 0,
        var lastTargetSourceTime: Long = -1L,
    )

    private data class CountSample(val reading: RemainingHandCount? = null, val invalid: Boolean = false)

    private class SeatState {
        var version: Version? = null
        var contextScene: TableScene? = null
        var contextPhase: RoundPhase? = null
        var lastFrameTime: Long = -1L
        var lastCountSourceTime: Long = -1L
        var lastCount: Int? = null
        var baselineScene: TableScene? = null
        var baselineVotes: Int = 0
        var baselineLastSourceTime: Long = -1L
        var blindGapSeen: Boolean = false
        var candidate: Candidate? = null
        var deliveredVersion: Version? = null
    }

    private val states = Seat.entries.associateWith { SeatState() }

    /**
     * Observe one frame for one outside seat. Returns the exact current
     * ObservedPlay once, only after its direct single-card reading and an
     * independently fresh per-seat count decrement agree.
     */
    @Synchronized
    fun observe(
        frame: TableFrame,
        snapshot: RoundSnapshot,
        seat: Seat,
        lastPresentationEventId: String?,
    ): ObservedPlay? {
        val state = states.getValue(seat)
        // Even a replayed/out-of-order frame must not leave its OCR source
        // available to be reused by a later timestamp.
        val countSample = consumeCountSource(state, frame, seat)
        if (frame.timeMs < 0L || frame.timeMs <= state.lastFrameTime) {
            clearEvidence(state)
            return null
        }
        state.lastFrameTime = frame.timeMs

        val version = version(snapshot, seat, lastPresentationEventId)
        if (version != state.version) {
            clearEvidence(state)
            state.version = version
        }
        if (version == null || version == state.deliveredVersion) {
            clearEvidence(state)
            return null
        }

        if (!eligibleFrame(frame, snapshot) || countSample?.invalid == true) {
            clearEvidence(state)
            state.contextScene = null
            state.contextPhase = null
            return null
        }
        if ((state.contextScene != null && state.contextScene != frame.scene) ||
            (state.contextPhase != null && state.contextPhase != snapshot.phase)) clearEvidence(state)
        state.contextScene = frame.scene
        state.contextPhase = snapshot.phase
        val freshReading = countSample?.reading
        val play = frame.plays[seat]
        val directReading = play?.let { directSingle(it, frame.timeMs) }
        val direct = directReading?.play

        var candidate = state.candidate
        if (candidate != null) {
            val directIdentity = directReading
            if (frame.scene != candidate.scene || snapshot.phase != candidate.phase || direct == null ||
                directIdentity == null ||
                !sameVersion(candidate.version, version) ||
                frame.timeMs <= candidate.lastFrameTime ||
                frame.timeMs - candidate.lastFrameTime > MAX_FRAME_GAP_MS ||
                frame.timeMs - candidate.firstTime > MAX_CANDIDATE_SPAN_MS ||
                direct.cards.single().colorKey() != candidate.card.colorKey() ||
                directIdentity.card != candidate.card ||
                // A cached identity may still be age-valid for one frame, but
                // every candidate vote needs a newly sourced direct identity;
                // otherwise this consecutive evidence chain is reset.
                directIdentity.sourceTimeMs <= candidate.lastIdentitySourceTime ||
                directIdentity.sourceTimeMs - candidate.lastIdentitySourceTime > MAX_FRAME_GAP_MS ||
                !sameGeometry(direct.glyphs.single().box, candidate.geometry)) {
                clearEvidence(state)
                candidate = null
            }
        }

        if (candidate != null) {
            val current = candidate!!
            val identity = directReading!!
            current.frames++
            current.lastFrameTime = frame.timeMs
            current.lastIdentitySourceTime = identity.sourceTimeMs
            current.geometry = identity.play.glyphs.single().box

            if (freshReading != null) {
                if (freshReading.count !in setOf(version.expectedRemaining, version.expectedRemaining - 1)) {
                    clearEvidence(state)
                    return null
                }
                if (current.targetSources > 0 && freshReading.count == version.expectedRemaining) {
                    clearEvidence(state)
                    return null
                }
                if (freshReading.count == version.expectedRemaining - 1) {
                    if (freshReading.sourceTimeMs < current.firstTime) {
                        clearEvidence(state)
                        return null
                    }
                    if (current.targetSources == 0 ||
                        freshReading.sourceTimeMs - current.lastTargetSourceTime >= MIN_SOURCE_GAP_MS) {
                        current.targetSources++
                        current.lastTargetSourceTime = freshReading.sourceTimeMs
                    }
                }
            }

            if (current.frames >= REQUIRED_DIRECT_FRAMES && current.targetSources >= REQUIRED_TARGET_SOURCES) {
                state.deliveredVersion = version
                clearCandidateAndBaseline(state)
                return direct
            }
            return null
        }

        // A blind row is only an interruption, never an empty-table or new-play
        // proof. The later direct face still needs independent post-arrival counts.
        if (play == null || !play.regionObserved) {
            state.blindGapSeen = true
            updateBaseline(state, frame, version, freshReading)
            return null
        }

        if(play.cards.isNotEmpty() && play.confidence>=.65f &&
            (play.cards.size!=1 || play.cards.single().colorKey()!=version.oldColorKey)) {
            // An intervening contradictory face cannot donate its count drop
            // to an old face that happens to reappear later.
            clearEvidence(state)
            return null
        }

        // Fresh expected-count samples can build a baseline independently of
        // glyph visibility. A count drop before the direct candidate appears
        // destroys that baseline and cannot certify a later card identity.
        val readyBeforeThisFrame = baselineReady(state, frame, version)
        if (freshReading != null && freshReading.count != version.expectedRemaining) {
            // A target-count source on the first direct frame is admissible
            // only when its own timestamp is at/after this appearance and a
            // fully established expected-count baseline already exists.
            if (freshReading.count == version.expectedRemaining - 1 && readyBeforeThisFrame &&
                direct != null && direct.cards.single().colorKey() == version.oldColorKey &&
                state.blindGapSeen && freshReading.sourceTimeMs >= frame.timeMs) {
                candidate = newCandidate(frame, snapshot, version, directReading!!)
                state.candidate = candidate
                candidate.targetSources = 1
                candidate.lastTargetSourceTime = freshReading.sourceTimeMs
                return null
            }
            clearBaseline(state)
            return null
        }
        updateBaseline(state, frame, version, freshReading)

        if (direct == null || direct.cards.single().colorKey() != version.oldColorKey ||
            !state.blindGapSeen || !baselineReady(state, frame, version)) return null
        candidate = newCandidate(frame, snapshot, version, directReading!!)
        state.candidate = candidate
        return null
    }

    /** Clear candidate/baseline evidence while retaining monotonic source fences and delivered versions. */
    @Synchronized
    fun clear() {
        states.values.forEach {
            clearEvidence(it)
            it.contextScene = null
            it.contextPhase = null
        }
    }

    private fun version(snapshot: RoundSnapshot, seat: Seat, eventId: String?): Version? {
        if (seat == Seat.SELF || snapshot.phase !in setOf(RoundPhase.ACTIVE, RoundPhase.SELF_FINISHED) ||
            snapshot.roundId.isNullOrBlank() || snapshot.remaining == null || eventId.isNullOrBlank()) return null
        val events = snapshot.plays[seat].orEmpty()
        val last = events.lastOrNull() ?: return null
        val old = last.cards.singleOrNull() ?: return null
        val played = snapshot.played(seat)
        val expected = 27 - played
        if (last.roundId != snapshot.roundId || last.seat != seat || last.eventId != eventId ||
            (old.rank.isJoker && old.suit!=Suit.NONE) || expected !in 2..27) return null
        return Version(snapshot.roundId, seat, eventId, expected, old.colorKey())
    }

    private fun eligibleFrame(frame: TableFrame, snapshot: RoundSnapshot): Boolean =
        frame.scene in setOf(TableScene.TABLE, TableScene.TEAMMATE) &&
            snapshot.phase in setOf(RoundPhase.ACTIVE, RoundPhase.SELF_FINISHED) &&
            !frame.captureGap && !frame.ambiguousPresentation && frame.incompleteReason == null

    private fun directSingle(play: ObservedPlay, time: Long): DirectReading? {
        val card = play.cards.singleOrNull() ?: return null
        val glyph = play.glyphs.singleOrNull() ?: return null
        val box = glyph.box
        if(!play.regionObserved || play.confidence !in MIN_PLAY_CONFIDENCE..1f ||
            glyph.card.colorKey()!=card.colorKey() || glyph.confidence !in MIN_PLAY_CONFIDENCE..1f || !validBox(box)) return null
        if(card.rank.isJoker) {
            if(card.suit!=Suit.NONE || glyph.card!=card || play.confidence<MIN_JOKER_CONFIDENCE ||
                glyph.confidence<MIN_JOKER_CONFIDENCE) return null
            return DirectReading(play,card,time)
        }
        val identity = play.singleCardIdentity ?: return null
        val physicalSuits = setOf(Suit.SPADE, Suit.CLUB, Suit.HEART, Suit.DIAMOND)
        val valid=identity.card.rank == card.rank && identity.card.suit in physicalSuits &&
            identity.card.colorKey() == card.colorKey() &&
            identity.confidence.isFinite() && identity.margin.isFinite() &&
            identity.confidence >= MIN_IDENTITY_CONFIDENCE && identity.margin >= MIN_IDENTITY_MARGIN &&
            identity.sourceTimeMs in 0L..time && time - identity.sourceTimeMs <= MAX_IDENTITY_AGE_MS &&
            validBox(box)
        return if(valid) DirectReading(play,identity.card,identity.sourceTimeMs) else null
    }

    private fun consumeCountSource(state: SeatState, frame: TableFrame, seat: Seat): CountSample? {
        val reading = frame.remainingHandCounts[seat] ?: return null
        if (reading.sourceTimeMs < 0L || reading.sourceTimeMs > frame.timeMs) return INVALID_COUNT
        if (reading.sourceTimeMs < state.lastCountSourceTime) return INVALID_COUNT
        if (reading.sourceTimeMs == state.lastCountSourceTime) {
            return if (state.lastCount == reading.count) null else INVALID_COUNT
        }
        state.lastCountSourceTime = reading.sourceTimeMs
        state.lastCount = reading.count
        if (reading.count !in 1..27) return INVALID_COUNT
        if (frame.timeMs - reading.sourceTimeMs > MAX_COUNT_AGE_MS) return null
        return CountSample(reading)
    }

    private fun updateBaseline(state: SeatState, frame: TableFrame, version: Version, reading: RemainingHandCount?) {
        val scene = frame.scene
        if (state.baselineScene != null && state.baselineScene != scene) clearBaseline(state)
        if (state.baselineVotes > 0 &&
            (frame.timeMs - state.baselineLastSourceTime > MAX_BASELINE_GAP_MS ||
                frame.timeMs - state.baselineLastSourceTime < 0L)) clearBaseline(state)
        if (reading == null) return
        if (reading.count != version.expectedRemaining) {
            clearBaseline(state)
            return
        }
        if (state.baselineVotes == 0) {
            state.baselineScene = scene
            state.baselineVotes = 1
            state.baselineLastSourceTime = reading.sourceTimeMs
        } else if (reading.sourceTimeMs - state.baselineLastSourceTime >= MIN_SOURCE_GAP_MS) {
            state.baselineVotes++
            state.baselineLastSourceTime = reading.sourceTimeMs
        }
    }

    private fun baselineReady(state: SeatState, frame: TableFrame, version: Version): Boolean =
        state.baselineVotes >= REQUIRED_BASELINE_SOURCES && state.baselineScene == frame.scene &&
            frame.timeMs >= state.baselineLastSourceTime &&
            frame.timeMs - state.baselineLastSourceTime <= MAX_BASELINE_TO_CANDIDATE_MS &&
            state.baselineLastSourceTime >= 0L && state.version == version

    private fun newCandidate(frame: TableFrame, snapshot: RoundSnapshot, version: Version,
                             identity: DirectReading): Candidate {
        return Candidate(
            version = version,
            scene = frame.scene,
            phase = snapshot.phase,
            card = identity.card,
            geometry = identity.play.glyphs.single().box,
            firstTime = frame.timeMs,
            lastFrameTime = frame.timeMs,
            lastIdentitySourceTime = identity.sourceTimeMs,
        )
    }

    private fun sameVersion(a: Version, b: Version) = a == b

    private fun validBox(box: GlyphBox): Boolean = listOf(box.left, box.top, box.right, box.bottom).all {
        it.isFinite() && it in 0f..1f
    } && box.width > 0f && box.height > 0f

    private fun sameGeometry(a: GlyphBox, b: GlyphBox): Boolean =
        kotlin.math.abs(a.cx - b.cx) <= minOf(a.width, b.width) * .35f &&
            kotlin.math.abs(a.cy - b.cy) <= minOf(a.height, b.height) * .35f &&
            maxOf(a.width, b.width) <= minOf(a.width, b.width) * 1.4f &&
            maxOf(a.height, b.height) <= minOf(a.height, b.height) * 1.4f

    private fun clearEvidence(state: SeatState) {
        clearCandidateAndBaseline(state)
        state.blindGapSeen = false
    }

    private fun clearCandidateAndBaseline(state: SeatState) {
        state.candidate = null
        clearBaseline(state)
    }

    private fun clearBaseline(state: SeatState) {
        state.baselineScene = null
        state.baselineVotes = 0
        state.baselineLastSourceTime = -1L
    }

    private companion object {
        const val MIN_PLAY_CONFIDENCE = .80f
        const val MIN_JOKER_CONFIDENCE = .85f
        const val MIN_IDENTITY_CONFIDENCE = .80f
        const val MIN_IDENTITY_MARGIN = .12f
        const val MAX_IDENTITY_AGE_MS = 500L
        const val MAX_COUNT_AGE_MS = 1000L
        const val MAX_BASELINE_GAP_MS = 1500L
        const val MAX_BASELINE_TO_CANDIDATE_MS = 1500L
        const val MAX_FRAME_GAP_MS = 500L
        const val MAX_CANDIDATE_SPAN_MS = 1500L
        const val MIN_SOURCE_GAP_MS = 100L
        const val REQUIRED_BASELINE_SOURCES = 2
        const val REQUIRED_DIRECT_FRAMES = 4
        const val REQUIRED_TARGET_SOURCES = 2
        val INVALID_COUNT = CountSample(invalid = true)
    }
}
