package com.cyotun.jpq.core

import kotlin.math.min

/**
 * Tencent shows the physical corner suit; a paired heart has a gold symbol in
 * that same slot. A complete ordinary symbol is positive natural-card evidence,
 * rather than leaving every card in a pair open to wildcard substitution.
 * These are candidates: the caller must still associate and vote the presentation.
 */
fun tencentPhysicalWildcardIdentity(card: Card,pixels: SuitSymbolPixels,
    observation: SuitSymbolObservation,rule: WildcardRuleEvidence): Boolean? {
    if(card.rank.isJoker) return false
    if(!pixels.complete || observation.suit==null || observation.reason!="shape_matched") return null
    if(observation.goldHeart) return true
    if(!rule.confirmed || rule.ranks.isNullOrEmpty()) return null
    // A partly read gold marker cannot be declared natural merely because a
    // classifier matched another component. In particular, black-colour input
    // must not discard gold pixels and thereby prove a false negative.
    if(pixels.argb.any { pixel ->
        val r=pixel ushr 16 and 255; val g=pixel ushr 8 and 255; val b=pixel and 255
        (pixel ushr 24)>=240 && r>=165 && g>=125 && b<.72f*min(r,g) && r-g<105
    }) return null
    return card.rank in rule.ranks && observation.suit==Suit.HEART
}
