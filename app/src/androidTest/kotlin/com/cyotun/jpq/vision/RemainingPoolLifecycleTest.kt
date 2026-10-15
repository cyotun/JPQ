package com.cyotun.jpq.vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cyotun.jpq.AppState
import com.cyotun.jpq.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Stopped-capture integration: old projection reads cannot version a new session. */
@RunWith(AndroidJUnit4::class)
class RemainingPoolLifecycleTest {
    private val deck=Rank.entries.flatMap { rank ->
        (if(rank.isJoker) listOf(Suit.NONE) else listOf(Suit.SPADE,Suit.HEART,Suit.CLUB,Suit.DIAMOND))
            .flatMap { suit -> List(2) { Card(rank,suit) } }
    }
    private fun snapshot(id: String): RoundSnapshot = RoundLedger().apply {
        begin(id,deck.take(27))
        commit(PlayEvent(id,"teammate-before",0,Seat.OPPOSITE,deck.drop(27).take(23)))
        selfFinished()
    }.snapshot()
    private val hand get()=deck.drop(50).take(4).map { card ->
        PlayGlyph(card,.99f,GlyphBox(.4f,.7f,.42f,.75f))
    }
    private fun confirm(snapshot: RoundSnapshot,epoch: Long,start: Long=0): RemainingPoolExclusion {
        listOf(start,start+250,start+500).forEach { AppState.observeTeammatePool(it,epoch,snapshot,hand) }
        return checkNotNull(AppState.remainingPoolFor(snapshot,epoch))
    }
    private fun isolated(block: ()->Unit) {
        check(!AppState.running) { "Do not run lifecycle checks during capture" }
        AppState.resetRemainingPool()
        try { block() } finally { AppState.resetRemainingPool() }
    }

    @Test fun retiredEpochReadAfterStopCannotPoisonTheNewEpochZeroSession() = isolated {
        val old=snapshot("old-session")
        confirm(old,8)
        AppState.resetRemainingPool()
        assertNull(AppState.remainingPoolFor(old,8))
        val fresh=snapshot("new-session")
        val accepted=confirm(fresh,0)
        assertEquals("new-session",accepted.roundId)
        assertEquals(0L,accepted.epoch)
        assertEquals(54,AppState.displayRemaining(fresh)!!.values.sum())
    }

    @Test fun FutureEpochReadCannotDiscardAnAcceptedHand() = isolated {
        val snapshot=snapshot("same-session")
        val accepted=confirm(snapshot,2)
        assertNull(AppState.remainingPoolFor(snapshot,3))
        assertEquals(accepted,AppState.remainingPoolFor(snapshot,2))
    }

    @Test fun staleEpochReadAndObservationCannotResetANewerAcceptedHand() = isolated {
        val snapshot=snapshot("same-session")
        val accepted=confirm(snapshot,4)
        assertNull(AppState.remainingPoolFor(snapshot,3))
        assertFalse(AppState.observeTeammatePool(1000,3,snapshot,hand))
        assertEquals(accepted,AppState.remainingPoolFor(snapshot,4))
    }
}
