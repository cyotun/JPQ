package com.cyotun.jpq.core

import kotlin.math.abs

/** Positioned evidence for this deal, never an inference from an earlier deal. */
data class TencentGradeReading(
    val ranks: Set<Rank>?,
    val source: String,
    val hudRegion: GlyphBox?,
    val conflict: Boolean = false,
    val physicalCornerRanks: Set<Rank> = emptySet(),
)

private fun validGradeBox(box: GlyphBox?) = box != null &&
    listOf(box.left, box.top, box.right, box.bottom).all { it.isFinite() } &&
    box.left >= 0f && box.top >= 0f && box.right <= 1f && box.bottom <= 1f &&
    box.width > 0f && box.height > 0f

private fun gradePoint(text: String): Rank? = Rank.entries.firstOrNull {
    !it.isJoker && it.label == text.uppercase()
}

private fun gradeUnion(boxes: List<GlyphBox>): GlyphBox? = boxes.takeIf { it.isNotEmpty() }?.let {
    GlyphBox(it.minOf { b -> b.left }, it.minOf { b -> b.top },
        it.maxOf { b -> b.right }, it.maxOf { b -> b.bottom })
}

/**
 * The two upper-left scores may be team levels. Equal scores give one current
 * physical heart grade. Different scores do not by themselves declare two wild
 * ranks: a known mode and visible green card-corner evidence must agree, or a
 * positioned current-deal announcement must explicitly name the effective grade.
 * The random-grade announcement takes precedence over the team's upgrade scores.
 */
fun tencentRoundGradeReading(hints: List<SceneHint>, cornerRanks: Set<Rank> = emptySet(),
    mode: WildcardMode = WildcardMode.UNKNOWN): TencentGradeReading {
    val positioned = hints.filter { validGradeBox(it.box) }.map {
        SceneHint(it.text.filterNot(Char::isWhitespace), it.box)
    }.distinct()
    val corners = cornerRanks.filterNot { it.isJoker }.toSet()
    val hud = positioned.filter { it.box!!.cx in .055f.. .23f && it.box.cy <= .13f }.flatMap { hint ->
        if (hint.text == "我方对方") {
            val b = hint.box!!
            listOf(SceneHint("我方", GlyphBox(b.left,b.top,b.cx,b.bottom)),
                SceneHint("对方", GlyphBox(b.cx,b.top,b.right,b.bottom)))
        } else listOf(hint)
    }
    val labels = hud.mapNotNull { hint ->
        if (hint.text == "我方" || hint.text == "对方") hint
        else listOf("我方","对方").singleOrNull { label ->
            hint.text.startsWith(label) && gradePoint(hint.text.removePrefix(label)) != null &&
                hint.box!!.height in .045f.. .13f
        }?.let { label ->
            val b=hint.box!!
            SceneHint(label,GlyphBox(b.left,b.top,b.right,b.top+b.height*.35f))
        }
    }
    val region = gradeUnion(labels.mapNotNull { it.box })?.let {
        // One visible panel still locates its adjacent panel when a mask hides
        // the other label; otherwise an overlay on that panel would disappear
        // from the very region used to report its obstruction.
        val seenOwn = labels.any { b -> b.text == "我方" }
        val seenOther = labels.any { b -> b.text == "对方" }
        val missingLeft = if (!seenOwn && seenOther) .05f else 0f
        val missingRight = if (seenOwn && !seenOther) .05f else 0f
        GlyphBox((it.left - .008f - missingLeft).coerceAtLeast(0f), (it.top - .005f).coerceAtLeast(0f),
            (it.right + .008f + missingRight).coerceAtMost(1f), (it.bottom + .07f).coerceAtMost(.15f))
    }
    val announcement = Regex("^(?:本局(?:随机)?(?:级牌|打)|本轮(?:随机)?级牌|本局随机打)(?:为|是|[:：])?([2-9AJQK]|10)(?:[、,/和与]([2-9AJQK]|10))?$")
    val announced = positioned.filter { it.box!!.cx in .25f.. .75f && it.box.cy in .10f.. .65f }
        .mapNotNull { hint -> announcement.matchEntire(hint.text.uppercase())?.let { match ->
            setOfNotNull(gradePoint(match.groupValues[1]), gradePoint(match.groupValues[2]))
        } }.distinct()
    if (announced.size > 1) return TencentGradeReading(null, "ANNOUNCEMENT_CONFLICT", region, true)
    announced.singleOrNull()?.let { points ->
        if (corners.isNotEmpty() && !points.containsAll(corners))
            return TencentGradeReading(null, "ANNOUNCEMENT_CORNER_CONFLICT", region, true)
        return TencentGradeReading(points, "ANNOUNCEMENT", region, physicalCornerRanks = corners)
    }

    fun labelRank(label: String): Rank? {
        val anchors = labels.filter { it.text == label }
        val candidates = anchors.flatMap { anchor ->
            val b = anchor.box!!
            hud.filter { token ->
                val d = token.box!!
                gradePoint(token.text) != null && d.height in .018f.. .08f &&
                    d.width in .008f.. .07f && abs(d.cx - b.cx) <= maxOf(.014f, b.width * .55f) &&
                    d.top >= b.bottom - .008f && d.cy <= b.bottom + .075f
            }.mapNotNull { gradePoint(it.text) }
        }.distinct()
        // Merged OCR is allowed only when the entire compact HUD box is exactly
        // its label and one ordinary point. Nicknames and extra digits are rejected.
        val merged = hud.mapNotNull { token ->
            if (token.box!!.height in .045f.. .13f && token.text.startsWith(label))
                gradePoint(token.text.removePrefix(label)) else null
        }
        return (candidates + merged).distinct().singleOrNull()
    }
    val own = labelRank("我方"); val other = labelRank("对方")
    val hudPoints = setOfNotNull(own, other)
    if (mode == WildcardMode.SINGLE && corners.size == 1) {
        return TencentGradeReading(corners, "CORNER_SINGLE", region, physicalCornerRanks = corners)
    }
    if (mode == WildcardMode.DOUBLE && corners.size == 2) {
        return TencentGradeReading(corners,
            if (own != null && other != null && hudPoints == corners) "HUD_CORNER_DOUBLE" else "CORNER_DOUBLE",
            region, physicalCornerRanks = corners)
    }
    if (own != null && own == other && (corners.isEmpty() || corners == hudPoints))
        return TencentGradeReading(hudPoints, "HUD_EQUAL", region, physicalCornerRanks = corners)
    if (own != null && own == other && corners.isNotEmpty() && corners != hudPoints)
        return TencentGradeReading(null, "HUD_CORNER_CONFLICT", region, true, corners)
    return TencentGradeReading(null, "UNAVAILABLE", region)
}

/** Fresh independent votes establish a deal's rule; temporary masking never erases it. */
class RoundGradeTracker {
    private var confirmed: Set<Rank>? = null
    private var confirmedPriority = 0
    private var priorityFence = 0
    private var candidate: Set<Rank>? = null
    private var candidatePriority = 0
    private var votes = 0
    private var first = -1L
    private var candidateLast = -1L
    private var lastSource = -1L

    fun clearCandidateVotes() {
        candidate = null; candidatePriority = 0; votes = 0; first = -1; candidateLast = -1
    }
    fun reset() {
        confirmed = null; confirmedPriority = 0; priorityFence = 0
        clearCandidateVotes(); lastSource = -1
    }
    fun current() = WildcardRuleEvidence(confirmed?.toSet(), confirmed != null)

    private fun priority(source: String) = when {
        source.startsWith("ANNOUNCEMENT") -> 3
        source == "CORNER_SINGLE" || source == "CORNER_DOUBLE" || source.startsWith("HUD_CORNER") -> 2
        source == "HUD_EQUAL" -> 1
        else -> 0
    }

    fun observe(now: Long, sourceTime: Long?, reading: TencentGradeReading): WildcardRuleEvidence {
        if (now < 0 || sourceTime == null || sourceTime < 0 || sourceTime <= lastSource ||
            sourceTime > now || now - sourceTime > 2000) return current()
        lastSource = sourceTime
        val ranks = reading.ranks?.takeIf { it.size in 1..2 && it.none(Rank::isJoker) }?.toSet()
        val sourcePriority = priority(reading.source)
        if (reading.conflict) {
            if (reading.source == "HUD_CORNER_CONFLICT" && reading.physicalCornerRanks.isNotEmpty() &&
                ((confirmedPriority == 3 && confirmed?.containsAll(reading.physicalCornerRanks) == true) ||
                    (candidatePriority == 3 && candidate?.containsAll(reading.physicalCornerRanks) == true)))
                return current()
            // A qualified physical contradiction is meaningful even when the
            // actual-deal announcement has higher precedence than the HUD.
            if (sourcePriority >= maxOf(confirmedPriority,candidatePriority,priorityFence) || sourcePriority >= 2) {
                priorityFence = maxOf(priorityFence,confirmedPriority,candidatePriority,sourcePriority)
                confirmed = null; confirmedPriority = 0; clearCandidateVotes()
            }
            return current()
        }
        if (ranks == null) {
            clearCandidateVotes()
            return current()
        }
        if (sourcePriority == 2 && ((confirmedPriority == 3 && confirmed != null && confirmed != ranks) ||
                (candidatePriority == 3 && candidate != null && candidate != ranks))) {
            priorityFence = maxOf(priorityFence,confirmedPriority,candidatePriority)
            confirmed = null; confirmedPriority = 0; clearCandidateVotes()
            return current()
        }
        if (sourcePriority == 0 || sourcePriority < maxOf(confirmedPriority,candidatePriority,priorityFence))
            return current()
        // Once a stronger current-deal source has been seen, a temporary absence
        // must not turn the team's progress scores into an effective-grade source.
        priorityFence = maxOf(priorityFence,sourcePriority)
        val gap = if (candidateLast < 0) 0L else sourceTime - candidateLast
        if (confirmed != null && confirmed != ranks) confirmed = null
        if (candidate != ranks || candidatePriority != sourcePriority || gap > 1500) {
            candidate = ranks; candidatePriority = sourcePriority; votes = 1; first = sourceTime
        } else votes++
        candidateLast = sourceTime
        if (votes >= 3 && sourceTime - first >= 500) {
            confirmed = ranks; confirmedPriority = sourcePriority; priorityFence = sourcePriority
        }
        return current()
    }
}
