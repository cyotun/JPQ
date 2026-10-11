package com.cyotun.jpq

import com.cyotun.jpq.core.*

/** Explicit adjustment-only sample. Never installed in AppState.ledger. */
object PreviewData {
    val snapshot: RoundSnapshot by lazy {
        val totals=listOf(1,1,4,6,5,3,0,1,1,0,0,4,4,4,6)
        val rows=listOf("Q·6·77722·AAA99·3","9·4·小·66688·JJJ1010·大","6·99944·K·J·10101088·7")
        val used=mutableMapOf<Rank,Int>()
        var seq=0L
        val plays=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).mapIndexed { i,seat ->
            seat to rows[i].split("·").map { group ->
                val cards=Regex("10|[大小AKQJ2-9]").findAll(group).map { match ->
                    val rank=Rank.entries.first { it.label==match.value }
                    val n=used[rank] ?: 0; used[rank]=n+1
                    Card(rank,if(rank.isJoker) Suit.NONE else Suit.entries[n%4])
                }.toList()
                PlayEvent("adjustment-preview","preview-${++seq}",seq,seat,cards)
            }
        }.toMap()
        RoundSnapshot("adjustment-preview",RoundPhase.ACTIVE,Rank.entries.zip(totals).toMap(),plays,null)
    }
}
