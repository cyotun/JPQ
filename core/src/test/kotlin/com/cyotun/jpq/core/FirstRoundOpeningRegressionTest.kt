package com.cyotun.jpq.core

import kotlin.test.*

class FirstRoundOpeningRegressionTest {
    @Test fun intermittentDoublingLabelsDoNotCreateThreeRoundsFromOneOpening() {
        val ledger=RoundLedger()
        val begun=mutableListOf<RoundSnapshot>()
        val observer=RoundObserver(ledger) { if(it.phase==RoundPhase.ACTIVE) begun.add(it) }
        // 261 consecutive observations from the first real round, with relative times and card
        // labels only. No screenshots, player identities, device identifiers or private paths.
        javaClass.getResourceAsStream("/first-round-opening.csv")!!.bufferedReader().useLines { lines ->
            lines.drop(1).forEach { row ->
                val fields=row.split(',',limit=3)
                val hand=fields[2].takeIf { it.isNotEmpty() }?.split('|')?.map { label ->
                    val card=label.split(':')
                    Card(Rank.valueOf(card[0]),if(card.size==1) Suit.NONE else Suit.valueOf(card[1]))
                }.orEmpty()
                observer.observe(TableFrame(fields[0].toLong(),TableScene.valueOf(fields[1]),hand,
                    Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }))
            }
        }
        assertEquals(1,begun.size)
        assertEquals(begun.single().roundId,ledger.snapshot().roundId)
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
        assertEquals(81,ledger.snapshot().remaining!!.values.sum())
        assertTrue(ledger.snapshot().plays.values.all { it.isEmpty() })
    }
    @Test fun aDifferentCompleteHandDuringDealingStillOpensANewRound() {
        val baseline=Rank.entries.filterNot { it.isJoker }.flatMap { listOf(Card(it,Suit.RED),Card(it,Suit.BLACK)) }+Card(Rank.SMALL,Suit.NONE)
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        val empty=Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }
        observer.observe(TableFrame(0,TableScene.LOBBY,null))
        for(time in 60L..4800L step 60) observer.observe(TableFrame(time,TableScene.TABLE,baseline,empty))
        val before=ledger.snapshot().roundId
        val different=baseline.drop(1)+Card(Rank.K,Suit.RED)
        for(time in 4860L..5100L step 60) observer.observe(TableFrame(time,TableScene.DEALING,different,empty))
        assertNotEquals(before,ledger.snapshot().roundId)
        assertEquals(different,ledger.initialHand())
        assertEquals(81,ledger.snapshot().remaining!!.values.sum())
    }
}
