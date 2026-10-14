package com.cyotun.jpq.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Owned, bounded pixel copy. It never retains a capture bitmap or changes the rank ledger. */
data class SuitSymbolPixels(
    val width: Int, val height: Int, val argb: IntArray, val expectedColor: Suit,
    val complete: Boolean = true, val unavailableReason: String? = null,
)

/** A missing suit is an abstention, not evidence that the card has no suit. */
data class SuitSymbolObservation(
    val suit: Suit?, val confidence: Float, val margin: Float = 0f,
    val goldHeart: Boolean = false, val reason: String,
)

/**
 * RGB segmentation followed by comparison with original mathematical suit shapes.
 * The shapes below are equations, not pixels/templates copied from an APK or screenshot.
 * A score is a similarity measure, not a calibrated probability. Callers must vote over
 * independently associated observations; weak or obscured pixels must stay unknown.
 */
object SuitSymbolClassifier {
    private const val SIDE = 24
    private const val MIN_SCORE = .76f
    private const val MIN_MARGIN = .10f
    private val physical = listOf(Suit.SPADE, Suit.CLUB, Suit.HEART, Suit.DIAMOND)
    private val shapes = physical.associateWith { suit -> BooleanArray(SIDE * SIDE) { i ->
        shape(suit, (i % SIDE + .5) / SIDE, (i / SIDE + .5) / SIDE)
    } }

    fun classify(pixels: SuitSymbolPixels): SuitSymbolObservation {
        fun unavailable(reason: String) = SuitSymbolObservation(null, 0f, reason = reason)
        if (!pixels.complete) return unavailable(pixels.unavailableReason ?: "incomplete_roi")
        val w = pixels.width; val h = pixels.height
        if (w !in 8..128 || h !in 8..128 || pixels.argb.size != w * h) return unavailable("invalid_roi")
        val red = pixels.expectedColor in setOf(Suit.RED, Suit.HEART, Suit.DIAMOND)
        val black = pixels.expectedColor in setOf(Suit.BLACK, Suit.SPADE, Suit.CLUB)
        if (!red && !black) return unavailable("no_physical_color")
        val mask = BooleanArray(w * h); val gold = BooleanArray(w * h)
        pixels.argb.forEachIndexed { index, p ->
            if ((p ushr 24) < 240) return@forEachIndexed
            val r = p ushr 16 and 255; val g = p ushr 8 and 255; val b = p and 255
            val yellow = r >= 165 && g >= 125 && b < .72f * min(r, g) && r - g < 105
            val isRed = r >= 75 && r > g * 1.35f + 12 && r > b * 1.3f + 12
            val isBlack = max(r, max(g, b)) < 125 && max(r, max(g, b)) - min(r, min(g, b)) < 70
            mask[index] = if (red) isRed || yellow else isBlack
            gold[index] = yellow
        }
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val components = mutableListOf<IntArray>()
        for (seed in mask.indices) {
            if (!mask[seed] || visited[seed]) continue
            var end = 1; var read = 0; queue[0] = seed; visited[seed] = true
            while (read < end) {
                val point = queue[read++]; val x = point % w; val y = point / w
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx !in 0 until w || yy !in 0 until h) continue
                    val next = yy * w + xx
                    if (mask[next] && !visited[next]) { visited[next] = true; queue[end++] = next }
                }
            }
            if (end >= 12) components.add(queue.copyOf(end))
        }
        if (components.isEmpty()) return unavailable("no_symbol_component")
        val observations = components.sortedByDescending { it.size }.take(2).map { component ->
            classifyComponent(pixels, component, mask, gold, red)
        }
        val matched = observations.filter { it.suit != null }
        if (matched.size > 1) return unavailable("multiple_suit_candidates")
        return matched.singleOrNull() ?: observations.maxBy { it.confidence }
    }

    private fun classifyComponent(pixels: SuitSymbolPixels, component: IntArray,
                                  mask: BooleanArray, gold: BooleanArray, red: Boolean): SuitSymbolObservation {
        fun unavailable(reason: String) = SuitSymbolObservation(null, 0f, reason = reason)
        val w = pixels.width; val h = pixels.height
        var left = w; var top = h; var right = 0; var bottom = 0
        component.forEach { p -> left = min(left, p % w); right = max(right, p % w)
            top = min(top, p / w); bottom = max(bottom, p / w) }
        val cw = right - left + 1; val ch = bottom - top + 1
        if (min(cw, ch) < 8) return unavailable("symbol_too_small")
        if (left <= 0 || top <= 0 || right >= w - 1 || bottom >= h - 1) return unavailable("symbol_clipped")
        if (cw.toFloat() / ch !in .6f..1.45f) return unavailable("symbol_aspect")
        // A valid corner symbol sits on light neutral card paper. Teal table pixels,
        // green grade patches and dark overlays cannot themselves become symbols.
        var surroundings = 0; var paper = 0
        for (y in max(0, top - 4)..min(h - 1, bottom + 4)) {
            for (x in max(0, left - 4)..min(w - 1, right + 4)) {
                val at = y * w + x
                if (mask[at]) continue
                surroundings++
                val p = pixels.argb[at]; val r = p ushr 16 and 255; val g = p ushr 8 and 255; val b = p and 255
                if (min(r, min(g, b)) >= 145 && max(r, max(g, b)) - min(r, min(g, b)) < 45) paper++
            }
        }
        if (surroundings < 12 || paper.toFloat() / surroundings < .40f) return unavailable("card_paper_unconfirmed")
        val member = BooleanArray(w * h); component.forEach { member[it] = true }
        val normalized = BooleanArray(SIDE * SIDE) { i ->
            val x = left + min(cw - 1, ((i % SIDE + .5) * cw / SIDE).toInt())
            val y = top + min(ch - 1, ((i / SIDE + .5) * ch / SIDE).toInt())
            member[y * w + x]
        }
        val allowed = if (red) listOf(Suit.HEART, Suit.DIAMOND) else listOf(Suit.SPADE, Suit.CLUB)
        val scores = allowed.map { suit ->
            val reference = shapes.getValue(suit)
            // Black suits differ strongly at their top: a narrow spade tip versus
            // the round club lobe. Red suits tolerate more corner antialiasing.
            val localWeight = if (red) .25f else .35f
            val localRows = if (red) 8 else 4
            suit to ((1f - localWeight) * intersectionOverUnion(normalized, reference, SIDE * SIDE) +
                localWeight * intersectionOverUnion(normalized, reference, SIDE * localRows))
        }.sortedByDescending { it.second }
        val best = scores.first(); val margin = best.second - scores[1].second
        val golden = component.count { gold[it] }.toFloat() / component.size >= .15f
        val accepted = best.second >= MIN_SCORE && margin >= MIN_MARGIN && (!golden || best.first == Suit.HEART)
        return SuitSymbolObservation(if (accepted) best.first else null, best.second, margin,
            accepted && golden && best.first == Suit.HEART, if (accepted) "shape_matched" else "shape_uncertain")
    }

    private fun intersectionOverUnion(a: BooleanArray, b: BooleanArray, length: Int): Float {
        var intersection = 0; var union = 0
        for (i in 0 until length) { if (a[i] && b[i]) intersection++; if (a[i] || b[i]) union++ }
        return intersection.toFloat() / max(1, union)
    }

    private fun ellipse(x: Double, y: Double, cx: Double, cy: Double, rx: Double, ry: Double): Boolean {
        val dx = (x - cx) / rx; val dy = (y - cy) / ry
        return dx * dx + dy * dy <= 1
    }
    private fun triangle(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double,
                         cx: Double, cy: Double): Boolean {
        fun cross(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) =
            (x1 - x3) * (y2 - y3) - (x2 - x3) * (y1 - y3)
        val a = cross(x, y, ax, ay, bx, by); val b = cross(x, y, bx, by, cx, cy); val c = cross(x, y, cx, cy, ax, ay)
        return !((a < 0 || b < 0 || c < 0) && (a > 0 || b > 0 || c > 0))
    }
    private fun heart(x: Double, y: Double) = ellipse(x, y, .26, .26, .26, .26) ||
        ellipse(x, y, .74, .26, .26, .26) || triangle(x, y, .06, .31, .94, .31, .5, 1.0)
    private fun stem(x: Double, y: Double) = x in .44.. .56 && y in .65.. .92 ||
        triangle(x, y, .5, .76, .3, 1.0, .7, 1.0)
    private fun shape(suit: Suit, x: Double, y: Double): Boolean = when (suit) {
        Suit.DIAMOND -> abs(x - .5) * 2 + abs(y - .5) * 2 <= 1
        Suit.HEART -> heart(x, y)
        Suit.SPADE -> triangle(x, y, .5, 0.0, .01, .56, .99, .56) ||
            ellipse(x, y, .25, .62, .25, .235) || ellipse(x, y, .75, .62, .25, .235) || stem(x, y)
        Suit.CLUB -> ellipse(x, y, .5, .20, .255, .20) || ellipse(x, y, .25, .61, .25, .245) ||
            ellipse(x, y, .75, .61, .25, .245) || stem(x, y)
        else -> false
    }
}
