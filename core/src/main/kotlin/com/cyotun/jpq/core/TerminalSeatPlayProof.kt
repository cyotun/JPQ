package com.cyotun.jpq.core

/**
 * Recovers one opponent's final play when the result scene replaces the table
 * before the normal play vote finishes. A qualified early-finish cue and a
 * directly observed partial play must establish the seat and physical slots
 * before RESULT can contribute any card identities.
 */
class TerminalSeatPlayProof(private val targetSeat: Seat? = null) {
    init { require(targetSeat != Seat.SELF) { "Terminal outside-play proof cannot target SELF" } }

    data class Recovered(val seat: Seat, val play: ObservedPlay)

    private data class Version(val roundId: String, val seat: Seat, val targetEvents: List<PlayEvent>,
                              val history: List<PlayEvent>, val remaining: Int)
    private data class Authority(
        val version: Version,
        val seat: Seat,
        var finishSourceTime: Long?,
        var ranks: List<Rank>,
        var slots: List<GlyphBox>,
        var lastPartialTime: Long,
        var lastPlaceSourceTime: Long? = null,
        var lastPlaceSeat: Seat? = null,
        var firstResultTime: Long? = null,
        var firstFullTime: Long? = null,
        var firstFull: ObservedPlay? = null,
        var latestFull: ObservedPlay? = null,
        var fullVotes: Int = 0,
        var delivered: Boolean = false
    )

    private var authority: Authority? = null
    private var lastTime = -1L

    fun clear() { authority = null; lastTime = -1L }

    /** Observe only the eligible unfinished opponent's direct play region. */
    fun observe(frame: TableFrame, snapshot: RoundSnapshot): Recovered? {
        if (frame.timeMs <= lastTime) {
            if (frame.timeMs < lastTime) clear()
            return null
        }
        lastTime = frame.timeMs
        val current = authority
        if (current != null && !sameVersion(current.version, snapshot)) {
            clear()
            return null
        }

        if (frame.scene == TableScene.RESULT) return observeResult(frame, snapshot, current)
        if (frame.scene != TableScene.TABLE) {
            if (current != null && frame.scene !in setOf(TableScene.UNKNOWN, TableScene.TEAMMATE)) clear()
            return null
        }
        if (frame.captureGap || frame.localizedOverlaySeats.isNotEmpty()) {
            clear()
            return null
        }
        // A globally ambiguous TABLE image contributes no vote but can leave
        // the last directly observed, bounded authority intact.
        if (frame.ambiguousPresentation || frame.incompleteReason == OWNERSHIP_AMBIGUITY_REASON) return null
        if (frame.incompleteReason != null) { clear(); return null }
        val id = snapshot.roundId ?: run { clear(); return null }
        if (snapshot.phase !in setOf(RoundPhase.ACTIVE, RoundPhase.SELF_FINISHED)) return null

        val eligible = (targetSeat?.let { listOf(it) } ?: listOf(Seat.PREVIOUS, Seat.OPPOSITE, Seat.NEXT)).mapNotNull { seat ->
            val remaining = 27 - snapshot.played(seat)
            val play = frame.plays[seat] ?: return@mapNotNull null
            if (remaining !in 1..27 || !validPartial(play, remaining)) return@mapNotNull null
            val cueTime = frame.observedEarlyFinishes[seat]
            val freshCue = cueTime?.takeIf { frame.timeMs - it in 0L..MAX_FINISH_CUE_AGE_MS }
            if (targetSeat == null && freshCue == null) return@mapNotNull null
            Triple(seat, freshCue, play)
        }
        if (eligible.size != 1) {
            if (eligible.size > 1) clear()
            return null
        }
        val (seat, cueTime, play) = eligible.single()
        val remaining = 27 - snapshot.played(seat)
        val version = version(snapshot, seat, remaining, id)
        val currentAuthority = authority
        if (currentAuthority == null || currentAuthority.seat != seat || currentAuthority.version != version ||
            (cueTime != null && currentAuthority.finishSourceTime != cueTime) ||
            frame.timeMs - currentAuthority.lastPartialTime !in 0L..MAX_PARTIAL_AGE_MS ||
            !retainOrExpandAuthority(play, currentAuthority)) {
            authority = Authority(version, seat, cueTime, play.cards.map { it.rank },
                play.glyphs.map { it.box }, frame.timeMs)
            return null
        }
        if (cueTime != null) currentAuthority.finishSourceTime = cueTime
        currentAuthority.lastPartialTime = frame.timeMs
        return null
    }

    /** Keep an unfinished ledger open only during this bounded proof window. */
    fun holdResult(frameTimeMs: Long, snapshot: RoundSnapshot): Boolean {
        val a = authority ?: return false
        if (!sameVersion(a.version, snapshot) || a.delivered || snapshot.phase !in setOf(RoundPhase.ACTIVE, RoundPhase.SELF_FINISHED) ||
            frameTimeMs < a.lastPartialTime || (a.firstResultTime == null && frameTimeMs - a.lastPartialTime > MAX_PARTIAL_AGE_MS) ||
            !authorized(a, frameTimeMs)) {
            clear()
            return false
        }
        val started = a.firstResultTime
        if (started == null) a.firstResultTime = frameTimeMs
        else if (frameTimeMs - started > MAX_RESULT_HOLD_MS) {
            clear()
            return false
        }
        return true
    }

    private fun observeResult(frame: TableFrame, snapshot: RoundSnapshot, a: Authority?): Recovered? {
        if (invalidFrame(frame)) { clear(); return null }
        val earlyCue = a?.let { frame.observedEarlyFinishes[it.seat]?.takeIf { cue -> frame.timeMs - cue in 0L..MAX_FINISH_CUE_AGE_MS } }
        val observedLastPlace = frame.observedLastPlaceSeat
        val lastPlaceSource = frame.observedLastPlaceSourceTimeMs?.takeIf { source ->
            source >= 0L && source <= frame.timeMs && frame.timeMs - source in 0L..MAX_LAST_PLACE_DELIVERY_AGE_MS
        }
        val freshLastPlace = observedLastPlace.takeIf { lastPlaceSource != null }
        if (a != null && targetSeat != null && earlyCue == null && freshLastPlace != null &&
            sameTeam(a.seat, freshLastPlace)) {
            // Same-team players may be assigned automatic third place after
            // their partner finishes, so this does not prove that target emptied.
            clear()
            return null
        }
        if (a != null && targetSeat != null && a.finishSourceTime == null && a.lastPlaceSourceTime == null) {
            when {
                earlyCue != null -> a.finishSourceTime = earlyCue
                freshLastPlace != null && !sameTeam(a.seat, freshLastPlace) -> {
                    a.lastPlaceSourceTime = lastPlaceSource
                    a.lastPlaceSeat = freshLastPlace
                }
                else -> return null
            }
        }
        if (a == null || !holdResult(frame.timeMs, snapshot)) return null
        val active = authority ?: return null
        val play = frame.plays[active.seat] ?: return null
        if (!validFull(play, active.version.remaining) || !containsPartial(play, active)) {
            active.firstFull = null; active.latestFull = null; active.fullVotes = 0; active.firstFullTime = null
            return null
        }
        val previous = active.latestFull
        if (previous == null || frame.timeMs - (active.firstFullTime ?: frame.timeMs) > MAX_FULL_GAP_MS ||
            !sameFull(previous, play) || !samePartialSlots(play, active)) {
            active.firstFullTime = frame.timeMs
            active.firstFull = copy(play)
            active.latestFull = copy(play)
            active.fullVotes = 1
            return null
        }
        active.latestFull = copy(play)
        active.fullVotes++
        if (active.fullVotes < REQUIRED_FULL_VOTES || frame.timeMs - (active.firstFullTime ?: frame.timeMs) < MIN_FULL_SPAN_MS) return null
        active.delivered = true
        return Recovered(active.seat, copy(active.latestFull!!))
    }

    private fun authorized(a: Authority, timeMs: Long): Boolean = when {
        a.finishSourceTime != null -> timeMs - a.finishSourceTime!! in 0L..MAX_FINISH_CUE_AGE_MS
        a.lastPlaceSourceTime != null && a.lastPlaceSeat != null && !sameTeam(a.seat, a.lastPlaceSeat!!) ->
            timeMs >= a.lastPlaceSourceTime!! && timeMs - a.lastPlaceSourceTime!! in 0L..MAX_FINISH_CUE_AGE_MS
        else -> false
    }

    private fun sameTeam(left: Seat, right: Seat) = (left == Seat.SELF || left == Seat.OPPOSITE) ==
        (right == Seat.SELF || right == Seat.OPPOSITE)

    private fun invalidFrame(frame: TableFrame) = frame.captureGap || frame.ambiguousPresentation ||
        frame.incompleteReason != null || frame.localizedOverlaySeats.isNotEmpty()

    private fun validPartial(play: ObservedPlay, remaining: Int): Boolean =
        play.regionObserved && play.confidence >= MIN_PARTIAL_CONFIDENCE && play.cards.isNotEmpty() &&
            (play.cards.size < remaining || (targetSeat!=null && play.cards.size==remaining)) &&
            paired(play) && play.glyphs.all { it.confidence >= MIN_PARTIAL_CONFIDENCE } &&
            play.cards.groupingBy { it.rank }.eachCount().all { (rank, count) -> count <= rank.capacity } && distinctSlots(play.glyphs)

    private fun validFull(play: ObservedPlay, remaining: Int): Boolean =
        play.regionObserved && play.confidence in MIN_FULL_CONFIDENCE..1f && play.cards.size == remaining &&
            paired(play) && play.glyphs.all { it.confidence in MIN_FULL_CONFIDENCE..1f } &&
            play.cards.groupingBy { it.rank }.eachCount().all { (rank, count) -> count <= rank.capacity } && distinctSlots(play.glyphs)

    private fun paired(play: ObservedPlay) = play.cards.size == play.glyphs.size &&
        play.cards.map { it.colorKey() }.sorted() == play.glyphs.map { it.card.colorKey() }.sorted() &&
        play.glyphs.all { validBox(it.box) }

    private fun retainOrExpandAuthority(play: ObservedPlay, a: Authority): Boolean {
        val newToOld = a.ranks.indices.map { i ->
            play.glyphs.indexOfFirst { glyph -> glyph.card.rank == a.ranks[i] && sameSlot(glyph.box, a.slots[i]) }
        }
        if (newToOld.any { it < 0 }) return false
        if (play.glyphs.size > a.ranks.size) {
            val matched = newToOld.toSet()
            if (matched.size != a.ranks.size) return false
            val extras = play.glyphs.indices.filter { it !in matched }
            a.ranks = play.glyphs.map { it.card.rank }
            a.slots = play.glyphs.map { it.box }
            if (extras.isEmpty()) return false
        }
        return true
    }

    private fun containsPartial(play: ObservedPlay, a: Authority): Boolean {
        val remaining = play.glyphs.toMutableList()
        return a.ranks.indices.all { i ->
            val index = remaining.indexOfFirst { glyph -> glyph.card.rank == a.ranks[i] && sameSlot(glyph.box, a.slots[i]) }
            if (index < 0) false else { remaining.removeAt(index); true }
        }
    }

    private fun samePartialSlots(play: ObservedPlay, a: Authority) = containsPartial(play, a)

    private fun sameFull(left: ObservedPlay, right: ObservedPlay): Boolean {
        if (left.cards.map { it.colorKey() }.sorted() != right.cards.map { it.colorKey() }.sorted()) return false
        val remaining = right.glyphs.toMutableList()
        return left.glyphs.all { glyph ->
            val index = remaining.indexOfFirst { old -> glyph.card.colorKey() == old.card.colorKey() && sameSlot(glyph.box, old.box) }
            if (index < 0) false else { remaining.removeAt(index); true }
        } && remaining.isEmpty()
    }

    private fun sameSlot(a: GlyphBox, b: GlyphBox) = validBox(a) && validBox(b) &&
        kotlin.math.abs(a.cx - b.cx) <= minOf(a.width, b.width) * SLOT_CENTER_FRACTION &&
        kotlin.math.abs(a.cy - b.cy) <= minOf(a.height, b.height) * SLOT_CENTER_FRACTION &&
        maxOf(a.width, b.width) <= minOf(a.width, b.width) * MAX_SLOT_SIZE_RATIO &&
        maxOf(a.height, b.height) <= minOf(a.height, b.height) * MAX_SLOT_SIZE_RATIO

    private fun distinctSlots(glyphs: List<PlayGlyph>) = glyphs.indices.all { i -> (i + 1 until glyphs.size).all { j ->
        val a = glyphs[i].box; val b = glyphs[j].box
        kotlin.math.abs(a.cx - b.cx) > minOf(a.width, b.width) * .1f ||
            kotlin.math.abs(a.cy - b.cy) > minOf(a.height, b.height) * .1f
    } }

    private fun validBox(b: GlyphBox) = listOf(b.left, b.top, b.right, b.bottom).all { it.isFinite() && it in 0f..1f } &&
        b.width > 0f && b.height > 0f

    private fun version(snapshot: RoundSnapshot, seat: Seat, remaining: Int, id: String) = Version(id, seat,
        copyEvents(snapshot.plays[seat].orEmpty()), copyEvents(snapshot.plays.values.flatten().sortedBy { it.sequence }), remaining)

    private fun sameVersion(version: Version, snapshot: RoundSnapshot): Boolean = snapshot.roundId == version.roundId &&
        27 - snapshot.played(version.seat) == version.remaining &&
        copyEvents(snapshot.plays[version.seat].orEmpty()) == version.targetEvents &&
        run {
            val current = copyEvents(snapshot.plays.values.flatten().sortedBy { it.sequence })
            current.size >= version.history.size && current.take(version.history.size) == version.history &&
                current.drop(version.history.size).all { it.sequence > (version.history.lastOrNull()?.sequence ?: -1L) }
        }

    private fun copyEvents(events: List<PlayEvent>) = events.map { it.copy(cards = it.cards.toList()) }

    private fun copy(play: ObservedPlay) = play.copy(cards = play.cards.toList(), glyphs = play.glyphs.toList())

    private companion object {
        const val MIN_PARTIAL_CONFIDENCE = .65f
        const val MIN_FULL_CONFIDENCE = .80f
        const val MAX_PARTIAL_AGE_MS = 1500L
        const val MAX_FINISH_CUE_AGE_MS = 2000L
        const val MAX_LAST_PLACE_DELIVERY_AGE_MS = 1000L
        const val MAX_RESULT_HOLD_MS = 1500L
        const val MAX_FULL_GAP_MS = 500L
        const val REQUIRED_FULL_VOTES = 2
        const val MIN_FULL_SPAN_MS = 100L
        const val SLOT_CENTER_FRACTION = .35f
        const val MAX_SLOT_SIZE_RATIO = 1.4f
        const val OWNERSHIP_AMBIGUITY_REASON = "出牌归属待核对，正在重新识别"
    }
}
