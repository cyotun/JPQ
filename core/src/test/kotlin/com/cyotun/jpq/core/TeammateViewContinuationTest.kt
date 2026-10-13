package com.cyotun.jpq.core

import kotlin.test.*

class TeammateViewContinuationTest {
    @Test fun revealedHandBeforeOcrDoesNotStopLaterTeammateAndOpponentPlays() {
        val finalOwn=listOf(Suit.BLACK,Suit.BLACK,Suit.BLACK,Suit.RED).mapIndexed { i,s ->
            PlayGlyph(Card(Rank.K,s),.90f,GlyphBox(.44f+i*.025f,.324f,.456f+i*.025f,.371f))
        }
        val rest=Rank.entries.filter { it !in setOf(Rank.K,Rank.A,Rank.TEN) }.flatMap { rank ->
            if(rank.isJoker) List(2) { Card(rank,Suit.NONE) } else List(4) { Card(rank,Suit.RED) }
        }.take(23)
        val ledger=RoundLedger(); ledger.begin("teammate-view",rest+finalOwn.map { it.card })
        ledger.commit(PlayEvent("teammate-view","own-before",0,Seat.SELF,rest))
        val observer=RoundObserver(ledger); val tracker=OwnExitPresentationTracker()
        val revealed=List(24) { Card(Rank.THREE,Suit.RED) }
        tracker.prepare(1000,TableScene.TABLE,"teammate-view")
        tracker.observe(1000,RoundPhase.ACTIVE,ledger.currentHand(),revealed,finalOwn)
        observer.observe(TableFrame(1100,TableScene.TEAMMATE,null))
        assertEquals(RoundPhase.SELF_FINISHED,ledger.snapshot().phase)
        // Two animation frames cannot replace the original exit's colour proof.
        val effect=finalOwn.map { it.copy(card=Card(Rank.K,Suit.RED)) }
        for(t in listOf(1200L,1400L)) {
            val own=tracker.prepare(t,TableScene.TEAMMATE,"teammate-view")
            assertEquals(4,assignPlayGroups(effect,emptySet(),emptyList(),true,own).ambiguous.size)
        }
        val proof=tracker.prepare(1600,TableScene.TEAMMATE,"teammate-view")
        val owned=assignPlayGroups(finalOwn,emptySet(),emptyList(),true,proof)
        assertEquals(finalOwn,owned.seats.getValue(Seat.SELF))
        tracker.observe(1600,RoundPhase.SELF_FINISHED,emptyList(),revealed,owned.seats.getValue(Seat.SELF))
        // Positive clean-empty evidence clears the completed player's old display.
        tracker.emptySurface(true,false); tracker.emptySurface(true,false)
        val teammate=listOf(Card(Rank.TEN,Suit.BLACK),Card(Rank.TEN,Suit.RED)).mapIndexed { i,c ->
            PlayGlyph(c,.91f,GlyphBox(.475f+i*.025f,.158f,.49f+i*.025f,.205f))
        }
        val enemy=List(2) { i -> PlayGlyph(Card(Rank.A,Suit.RED),.9f,GlyphBox(.15f+i*.025f,.278f,.168f+i*.025f,.327f)) }
        for(t in 2000L..2300L step 100) {
            val scene=continueTeammateScene(TableScene.TABLE,ledger.snapshot().phase)
            val groups=assignPlayGroups(teammate+enemy,emptySet(),emptyList(),scene==TableScene.TEAMMATE,
                tracker.prepare(t,scene,"teammate-view"))
            assertTrue(groups.ambiguous.isEmpty())
            observer.observe(TableFrame(t,scene,null,groups.seats.mapValues { (_,g) ->
                ObservedPlay(g.map { it.card },g.minOfOrNull { it.confidence } ?: 1f,g)
            }))
        }
        assertEquals(RoundPhase.SELF_FINISHED,ledger.snapshot().phase)
        assertEquals(2,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(2,ledger.snapshot().played(Seat.PREVIOUS))
        assertEquals(77,ledger.snapshot().remaining!!.values.sum())
        assertEquals(23,ledger.snapshot().played(Seat.SELF)) // No invented final own event.
        observer.observe(TableFrame(2500,TableScene.RESULT,null))
        assertEquals(RoundPhase.WAITING,ledger.snapshot().phase)
    }

    @Test fun completionLatchNeverOverridesNewRoundOrResultScenes() {
        assertEquals(TableScene.TEAMMATE,continueTeammateScene(TableScene.TABLE,RoundPhase.SELF_FINISHED))
        assertEquals(TableScene.TABLE,continueTeammateScene(TableScene.TABLE,RoundPhase.ACTIVE))
        for(scene in listOf(TableScene.DEALING,TableScene.TRIBUTE,TableScene.RESULT,TableScene.LOBBY,TableScene.UNKNOWN))
            assertEquals(scene,continueTeammateScene(scene,RoundPhase.SELF_FINISHED))
    }
}
