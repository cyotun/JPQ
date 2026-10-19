package com.cyotun.jpq.core

import kotlin.test.*

class NestedCardGlyphDuplicatesTest {
    private val five=Card(Rank.FIVE,Suit.RED)
    private val full=PlayGlyph(five,.8827f,GlyphBox(.4114f,.7108f,.4287f,.7675f))
    private val fragment=PlayGlyph(five,.5763f,GlyphBox(.4118f,.7108f,.4172f,.7483f))

    @Test fun actualJRoundWeakNestedFiveFragmentIsRemovedInEitherInputOrder() {
        assertEquals(listOf(full),suppressNestedCardGlyphDuplicates(listOf(full,fragment)))
        assertEquals(listOf(full),suppressNestedCardGlyphDuplicates(listOf(fragment,full)))
        assertEquals(listOf(full,full.copy(box=GlyphBox(.44f,.7108f,.4573f,.7675f))),
            suppressNestedCardGlyphDuplicates(listOf(full,fragment,full.copy(box=GlyphBox(.44f,.7108f,.4573f,.7675f)))))
    }

    @Test fun actualJFrame1830HasEighteenHandCardsAndAnIndependentRedBlackThreePair() {
        // Entire logged frame at elapsedMs 1851745507. The last line was a
        // second detection inside the real red FIVE, rather than a nineteenth card.
        val rows="""
            THREE:BLACK,.9068,.4927,.3325,.5038,.3717
            Q:RED,.8965,.589,.5758,.6089,.6358
            SEVEN:RED,.8908,.3524,.7108,.37,.7675
            TEN:RED,.8903,.5882,.7108,.6104,.7692
            SEVEN:BLACK,.8889,.3524,.5767,.37,.635
            SEVEN:RED,.8876,.352,.6442,.37,.7008
            TEN:RED,.8873,.4682,.7108,.4916,.7692
            SEVEN:BLACK,.8872,.3524,.5108,.37,.5692
            FIVE:BLACK,.8867,.4114,.4425,.4287,.5008
            THREE:RED,.885,.4682,.3342,.4793,.3717
            FIVE:RED,.8827,.4114,.7108,.4287,.7675
            FIVE:BLACK,.8804,.4114,.5092,.4287,.5675
            SEVEN:BLACK,.8797,.3524,.4442,.37,.5025
            FIVE:RED,.8777,.4114,.5758,.4287,.6342
            FIVE:RED,.8763,.4114,.6417,.4287,.7008
            FOUR:RED,.8743,.5303,.6417,.5483,.7025
            A:RED,.87,.5882,.4442,.6104,.5008
            FOUR:RED,.8698,.5303,.7108,.5495,.7692
            J:BLACK,.8611,.5897,.6442,.6077,.7025
            K:RED,.8488,.5882,.5092,.6097,.5675
            FIVE:RED,.5763,.4118,.7108,.4172,.7483
        """.trimIndent().lines().map { line ->
            val parts=line.split(','); val name=parts[0].split(':')
            PlayGlyph(Card(Rank.valueOf(name[0]),Suit.valueOf(name[1])),parts[1].toFloat(),
                GlyphBox(parts[2].toFloat(),parts[3].toFloat(),parts[4].toFloat(),parts[5].toFloat()))
        }
        assertEquals(21,rows.size)
        val partition=partitionGuandanHand(rows)
        assertEquals(18,partition.hand.size)
        assertEquals(3,partition.hand.count { it.card==five })
        assertEquals(2,partition.hand.count { it.card==Card(Rank.FIVE,Suit.BLACK) })
        assertEquals(setOf(Card(Rank.THREE,Suit.RED),Card(Rank.THREE,Suit.BLACK)),partition.table.map { it.card }.toSet())
        val removed=listOf(Card(Rank.THREE,Suit.RED),Card(Rank.THREE,Suit.BLACK))
        val owned=assignPlayGroups(partition.table,emptySet(),removed)
        assertEquals(removed.toSet(),owned.seats.getValue(Seat.SELF).map { it.card }.toSet())
        assertTrue(owned.ambiguous.isEmpty())
        val earlier=listOf(Card(Rank.TWO,Suit.RED),Card(Rank.TWO,Suit.BLACK),
            Card(Rank.BIG,Suit.NONE),Card(Rank.BIG,Suit.NONE),Card(Rank.SMALL,Suit.NONE),
            Card(Rank.SMALL,Suit.NONE),Card(Rank.A,Suit.BLACK))
        val ledger=RoundLedger()
        ledger.begin("j-fixture",partition.hand.map { it.card }+removed+earlier)
        ledger.commit(PlayEvent("j-fixture","earlier",1L,Seat.SELF,earlier))
        assertEquals(20,ledger.currentHand().size)
        val observer=RoundObserver(ledger)
        val plays=owned.seats.mapValues { (_,glyphs) ->
            ObservedPlay(glyphs.map { it.card },glyphs.minOfOrNull { it.confidence } ?: 1f,glyphs)
        }
        repeat(4) { observer.observe(TableFrame(1851745507L+it*120L,TableScene.TABLE,partition.hand.map { it.card },plays)) }
        assertEquals(18,ledger.currentHand().size)
        assertEquals(removed.toSet(),ledger.snapshot().plays.getValue(Seat.SELF).last().cards.toSet())
        assertEquals(1,listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).map { ledger.snapshot().played(it) }.toSet().size)
        assertEquals(0,ledger.snapshot().played(Seat.OPPOSITE))
    }

    @Test fun legitimateSameCardCopiesAdjacentAndVerticallyOverlappingArePreserved() {
        val copies=listOf(
            full.copy(box=GlyphBox(.425f,.7108f,.4423f,.7675f),confidence=.58f),
            full.copy(box=GlyphBox(.4114f,.75f,.4287f,.8067f),confidence=.58f),
            full.copy(box=GlyphBox(.4114f,.67f,.4287f,.7267f),confidence=.58f),
            full.copy(box=GlyphBox(.410f,.7108f,.425f,.7483f),confidence=.58f))
        for(copy in copies) assertEquals(listOf(full,copy),suppressNestedCardGlyphDuplicates(listOf(full,copy)))
    }

    @Test fun aSimilarScoreDifferentCardOrJokerDoesNotEstablishDuplicateIdentity() {
        for(candidate in listOf(fragment.copy(confidence=.80f),fragment.copy(confidence=.64f),
            fragment.copy(card=Card(Rank.FIVE,Suit.BLACK)),fragment.copy(card=Card(Rank.SIX,Suit.RED)))) {
            assertEquals(listOf(full,candidate),suppressNestedCardGlyphDuplicates(listOf(full,candidate)))
        }
        val joker=Card(Rank.BIG,Suit.NONE)
        val pair=listOf(full.copy(card=joker),fragment.copy(card=joker))
        assertEquals(pair,suppressNestedCardGlyphDuplicates(pair))
        val weakOuter=full.copy(confidence=.79f)
        assertEquals(listOf(weakOuter,fragment),suppressNestedCardGlyphDuplicates(listOf(weakOuter,fragment)))
    }

    @Test fun everyIndependentShapeConditionMustHoldAndPartialContainmentNeverSuffices() {
        val boxes=listOf(
            GlyphBox(.4113f,.7108f,.4172f,.7483f), // crosses the left boundary
            GlyphBox(.4118f,.7107f,.4172f,.7483f), // crosses the top boundary
            GlyphBox(.4118f,.7108f,.418f,.7483f), // too wide
            GlyphBox(.4118f,.7108f,.4172f,.755f), // too tall
            GlyphBox(.4118f,.7108f,.4178f,.7533f), // area too large despite each side ratio
            GlyphBox(.4118f,.715f,.4172f,.7483f), // a separate lower print
            GlyphBox(.4114f,.7108f,.4134f,.7483f), // far from the outer center
            GlyphBox(.4118f,.7108f,.4172f,.729f)) // top sliver far above center
        for(box in boxes) {
            val candidate=fragment.copy(box=box)
            assertEquals(listOf(full,candidate),suppressNestedCardGlyphDuplicates(listOf(full,candidate)),box.toString())
        }
    }

    @Test fun malformedScoresAndGeometryAreRetainedForTheExistingValidationPath() {
        for(candidate in listOf(fragment.copy(confidence=Float.NaN),fragment.copy(confidence=-1f),
            fragment.copy(box=GlyphBox(Float.NaN,.7108f,.4172f,.7483f)),
            fragment.copy(box=GlyphBox(.4172f,.7108f,.4118f,.7483f)))) {
            assertEquals(listOf(full,candidate),suppressNestedCardGlyphDuplicates(listOf(full,candidate)))
        }
        assertTrue(suppressNestedCardGlyphDuplicates(emptyList()).isEmpty())
    }
}
