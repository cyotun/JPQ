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

enum class TencentGradeTeam(val label: String) { OWN("我方"), OTHER("对方") }
enum class TencentGradeHeaderTone { ACTIVE, INACTIVE, UNKNOWN }

/** MLKit may include the table separator beside an otherwise exact team label. */
fun tencentGradeHudToken(text: String): String {
    val compact=text.filterNot(Char::isWhitespace)
    val label=compact.trim { it=='|' || it=='丨' }
    return if(label in setOf("我方","对方","我方对方")) label else compact
}

fun tencentGradeHeaderPixelColour(pixel: Int): Int {
    val r=pixel ushr 16 and 255; val g=pixel ushr 8 and 255; val b=pixel and 255
    if((pixel ushr 24 and 255)<200 || minOf(r,g,b)>210 || maxOf(r,g,b)<70) return 0
    if(r>=120 && r>g*1.25f && r>b*1.15f) return 2
    if(b>=100 && b>r*1.3f && b>=g*1.02f && maxOf(r,g,b)-minOf(r,g,b)>=45) return 1
    return 0
}

/** White lettering does not establish a header colour; its background must agree. */
fun tencentGradeHeaderTone(width: Int,height: Int,argb: IntArray): TencentGradeHeaderTone {
    if(width<4 || height<3 || width*height!=argb.size) return TencentGradeHeaderTone.UNKNOWN
    var eligible=0; var active=0; var gray=0; var coloredRows=0
    for(y in 0 until height) {
        var rowActive=0
        for(x in 0 until width) {
            val p=argb[y*width+x]; val r=p ushr 16 and 255; val g=p ushr 8 and 255; val b=p and 255
            if((p ushr 24 and 255)<200 || minOf(r,g,b)>210 || maxOf(r,g,b)<70) continue
            eligible++
            if(tencentGradeHeaderPixelColour(p)!=0) { active++; rowActive++ }
            if(maxOf(r,g,b)-minOf(r,g,b)<=25) gray++
        }
        if(rowActive>=width*.20f) coloredRows++
    }
    if(eligible<16 || eligible<argb.size*.20f) return TencentGradeHeaderTone.UNKNOWN
    return when {
        active>=16 && active>=eligible*.50f && coloredRows>=height*.45f -> TencentGradeHeaderTone.ACTIVE
        gray>=eligible*.75f -> TencentGradeHeaderTone.INACTIVE
        else -> TencentGradeHeaderTone.UNKNOWN
    }
}

private fun validGradeBox(box: GlyphBox?) = box != null &&
    listOf(box.left, box.top, box.right, box.bottom).all { it.isFinite() } &&
    box.left >= 0f && box.top >= 0f && box.right <= 1f && box.bottom <= 1f &&
    box.width > 0f && box.height > 0f

private fun gradePoint(text: String): Rank? = Rank.entries.firstOrNull {
    !it.isJoker && it.label == text.uppercase()
}

/** Actual character boxes from one OCR element, not an estimated split of its word box. */
fun tencentGradeSymbolTokens(symbols: List<SceneHint>): List<SceneHint> {
    val hud=symbols.filter { validGradeBox(it.box) && it.box!!.cx in .055f.. .23f && it.box.cy<=.13f }
        .sortedBy { it.box!!.left }
    val points=hud.filter { gradePoint(it.text)!=null }.toMutableList()
    val tens=hud.filter { it.text=="1" }.mapNotNull { one ->
        val a=one.box!!
        val zero=hud.filter { token ->
            val b=token.box!!
            token.text=="0" && abs(a.cy-b.cy)<=minOf(a.height,b.height)*.25f &&
            minOf(a.height,b.height)>=maxOf(a.height,b.height)*.65f &&
            b.left-a.right in -a.width*.25f..a.width*1.5f
        }.singleOrNull() ?: return@mapNotNull null
        one to zero
    }
    tens.filter { pair -> tens.count { it.second==pair.second }==1 }.forEach { (one,zero) ->
        val a=one.box!!; val b=zero.box!!
        points.add(SceneHint("10",GlyphBox(a.left,minOf(a.top,b.top),b.right,maxOf(a.bottom,b.bottom))))
    }
    // A border can join the label's OCR word. Combine only the actual two
    // Chinese character boxes on the same row; a rank below may sort between
    // them in X and must not suppress a genuine label or become part of it.
    hud.filter { it.text in setOf("我","对") }.forEach { one ->
        val a=one.box!!
        val next=hud.filter { token ->
            val b=token.box!!
            token.text=="方" && abs(a.cy-b.cy)<=minOf(a.height,b.height)*.25f &&
                minOf(a.height,b.height)>=maxOf(a.height,b.height)*.65f &&
                b.left-a.right in -a.width*.25f..a.width*.5f
        }.singleOrNull()
        next?.box?.let { b -> points.add(SceneHint(one.text+"方",GlyphBox(a.left,minOf(a.top,b.top),b.right,maxOf(a.bottom,b.bottom)))) }
    }
    return points.distinct()
}

private fun gradeUnion(boxes: List<GlyphBox>): GlyphBox? = boxes.takeIf { it.isNotEmpty() }?.let {
    GlyphBox(it.minOf { b -> b.left }, it.minOf { b -> b.top },
        it.maxOf { b -> b.right }, it.maxOf { b -> b.bottom })
}

/**
 * The grey upper-left panel is only a team's upgrade record. Only a blue/red
 * active panel may supply a current physical heart grade. Different scores do
 * not by themselves declare two wild ranks: active colours and a known mode
 * must agree, or visible green card-corner evidence or a
 * positioned current-deal announcement must explicitly name the effective grade.
 * The random-grade announcement takes precedence over the team's upgrade scores.
 */
fun tencentRoundGradeReading(hints: List<SceneHint>, cornerRanks: Set<Rank> = emptySet(),
    mode: WildcardMode = WildcardMode.UNKNOWN,
    activeHudTeams: Set<TencentGradeTeam> = emptySet()): TencentGradeReading {
    val positioned = hints.filter { validGradeBox(it.box) }.map {
        SceneHint(tencentGradeHudToken(it.text), it.box)
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
    val region = gradeUnion(labels.filter { label -> activeHudTeams.any { it.label==label.text } }.mapNotNull { it.box })?.let {
        // Only the actually coloured current-grade panel is an obstruction
        // target. Its grey neighbour is a team's upgrade record, not this deal.
        GlyphBox((it.left-.008f).coerceAtLeast(0f),(it.top-.005f).coerceAtLeast(0f),
            (it.right+.008f).coerceAtMost(1f),(it.bottom+.07f).coerceAtMost(.15f))
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
                    // MLKit's tight box for a visible HUD 3 is about .0073 wide.
                    d.width in .004f.. .07f && abs(d.cx - b.cx) <= maxOf(.014f, b.width * .55f) &&
                    // A single point between merged labels cannot represent both teams.
                    labels.filter { it.text != label }.none { other ->
                        abs(d.cx-other.box!!.cx) <= abs(d.cx-b.cx)+.002f
                    } &&
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
    val own = if(TencentGradeTeam.OWN in activeHudTeams) labelRank("我方") else null
    val other = if(TencentGradeTeam.OTHER in activeHudTeams) labelRank("对方") else null
    val hudPoints = setOfNotNull(own, other)
    val completeActiveHud=activeHudTeams.isNotEmpty() && activeHudTeams.all {
        if(it==TencentGradeTeam.OWN) own!=null else other!=null
    }
    if (mode == WildcardMode.SINGLE && corners.size == 1) {
        if(completeActiveHud && hudPoints!=corners)
            return TencentGradeReading(null,"HUD_CORNER_CONFLICT",region,true,corners)
        return TencentGradeReading(corners, "CORNER_SINGLE", region, physicalCornerRanks = corners)
    }
    if (mode == WildcardMode.DOUBLE && corners.size == 2) {
        if(completeActiveHud && !corners.containsAll(hudPoints))
            return TencentGradeReading(null,"HUD_CORNER_CONFLICT",region,true,corners)
        return TencentGradeReading(corners,
            if (own != null && other != null && hudPoints == corners) "HUD_CORNER_DOUBLE" else "CORNER_DOUBLE",
            region, physicalCornerRanks = corners)
    }
    if(completeActiveHud && hudPoints.size==1 &&
        (mode!=WildcardMode.DOUBLE || activeHudTeams.size==2) && (corners.isEmpty() || corners==hudPoints))
        return TencentGradeReading(hudPoints,if(activeHudTeams.size==2) "HUD_EQUAL" else "HUD_ACTIVE",region,physicalCornerRanks=corners)
    if(completeActiveHud && mode==WildcardMode.DOUBLE && hudPoints.size==2 && corners.isEmpty())
        return TencentGradeReading(hudPoints,"HUD_ACTIVE_DOUBLE",region)
    if (completeActiveHud && hudPoints.size==1 && corners.isNotEmpty() && corners != hudPoints)
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
        source == "HUD_EQUAL" || source == "HUD_ACTIVE" || source == "HUD_ACTIVE_DOUBLE" -> 1
        else -> 0
    }

    fun observe(now: Long, sourceTime: Long?, reading: TencentGradeReading): WildcardRuleEvidence {
        if (now < 0 || sourceTime == null || sourceTime < 0 || sourceTime <= lastSource ||
            sourceTime > now || now - sourceTime > 2000) return current()
        lastSource = sourceTime
        val ranks = reading.ranks?.takeIf { it.size in 1..2 && it.none(Rank::isJoker) }?.toSet()
        val sourcePriority = if (!reading.conflict && ranks != null &&
            reading.source in setOf("HUD_EQUAL", "HUD_ACTIVE", "HUD_ACTIVE_DOUBLE") &&
            reading.physicalCornerRanks == ranks) {
            // The parser has already required a complete coloured HUD and
            // applied the mode constraints. Exact agreement with this deal's
            // physical corner evidence is stronger than an uncorroborated HUD.
            // Otherwise a transient HUD/corner contradiction raises the fence
            // to 2 forever, even after both independent sources agree again.
            // Keep the original source name and OCR time: one cached HUD result
            // still cannot provide multiple recovery votes. This does not infer
            // SINGLE from a lone corner or lower an announcement's fence of 3.
            2
        } else priority(reading.source)
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
