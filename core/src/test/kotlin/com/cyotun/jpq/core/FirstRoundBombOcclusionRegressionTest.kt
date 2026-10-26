package com.cyotun.jpq.core

import kotlin.test.*

class FirstRoundBombOcclusionRegressionTest {
    private fun cards(text: String)=text.takeIf { it.isNotEmpty() }?.split('|')?.map { label ->
        val fields=label.split(':')
        Card(Rank.valueOf(fields[0]),if(fields.size==1) Suit.NONE else Suit.valueOf(fields[1]))
    }.orEmpty()
    private fun replay(useOcclusionEvidence: Boolean): RoundSnapshot {
        val baseline=javaClass.getResourceAsStream("/first-round-opening.csv")!!.bufferedReader().useLines { lines ->
            cards(lines.last().split(',',limit=3)[2])
        }
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        observer.observe(TableFrame(0,TableScene.LOBBY,null))
        for(time in listOf(100L,200L,300L)) observer.observe(TableFrame(time,TableScene.TABLE,baseline,
            Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }))
        // Focused replay from first-round frames 855..890 after the six-card presentation was
        // complete. Times/groups are original; video confirms that five model-empty frames
        // were a bomb covering the still-present opposite cards, rather than a new-play boundary.
        // Earlier plays are deliberately outside this fixture; this is not a whole-round verdict.
        javaClass.getResourceAsStream("/first-round-bomb-occlusion.csv")!!.bufferedReader().useLines { lines ->
            lines.drop(1).forEach { row ->
                val fields=row.split(',',limit=4)
                val observed=!useOcclusionEvidence || fields[3].toBoolean()
                observer.observe(TableFrame(600+fields[0].toLong(),TableScene.TABLE,null,mapOf(
                    Seat.PREVIOUS to ObservedPlay(cards(fields[1]),.99f),
                    Seat.OPPOSITE to ObservedPlay(cards(fields[2]),.99f,regionObserved=observed),
                    Seat.NEXT to ObservedPlay(emptyList(),1f),
                )))
            }
        }
        return ledger.snapshot()
    }
    @Test fun bombOcclusionCannotReopenAndDeductTheOldSixCards() {
        val snapshot=replay(true)
        assertEquals(RoundPhase.ACTIVE,snapshot.phase)
        assertEquals(6,snapshot.played(Seat.OPPOSITE)); assertEquals(4,snapshot.played(Seat.PREVIOUS))
        assertEquals(1,snapshot.plays[Seat.OPPOSITE]!!.size)
        assertEquals(71,snapshot.remaining!!.values.sum())
    }
    @Test fun evenAnIncorrectClearObstructionCannotPoisonTheLedgerWithACapacityConflict() {
        val snapshot=replay(false)
        assertEquals(RoundPhase.ACTIVE,snapshot.phase)
        assertEquals(6,snapshot.played(Seat.OPPOSITE)); assertEquals(4,snapshot.played(Seat.PREVIOUS))
        assertEquals(71,snapshot.remaining!!.values.sum())
    }
}
