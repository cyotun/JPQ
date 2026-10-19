package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepeatedPlayCountProofTest {
    private val round="round-1"
    private val seat=Seat.OPPOSITE
    private val event="round-1:play-8"

    private fun play(cards: List<Card> = listOf(Card(Rank.A,Suit.CLUB)),
        centers: List<Float> = cards.indices.map { .35f+it*.08f },
        regionObserved: Boolean = true): ObservedPlay {
        val glyphs=cards.mapIndexed { index,card ->
            val cx=centers[index]
            PlayGlyph(card,.95f,GlyphBox(cx-.025f,.35f,cx+.025f,.45f))
        }
        return ObservedPlay(cards,.95f,glyphs,regionObserved)
    }

    private fun observe(proof: RepeatedPlayCountProof,time: Long,count: Int,source: Long=time,
        value: ObservedPlay?=play(),roundId: String?=round,player: Seat=seat,eventId: String?=event,
        expected: Int=2)=proof.observe(time,roundId,player,eventId,expected,value,RemainingHandCount(count,source))

    private fun baseline(proof: RepeatedPlayCountProof,expected: Int=2,visible: ObservedPlay=play(),start: Long=1000) {
        assertFalse(proof.observe(start,round,seat,event,expected,visible,RemainingHandCount(expected,start)))
        assertFalse(proof.observe(start+100,round,seat,event,expected,visible,RemainingHandCount(expected,start+100)))
    }

    private fun proveDrop(proof: RepeatedPlayCountProof,expected: Int=2,
        visible: ObservedPlay=play(cards=listOf(Card(Rank.A,Suit.SPADE))),start: Long=1200): List<Boolean> =
        (1L..4L).map { frame ->
            val source=start+(frame-1)*100L
            observe(proof,source,expected-visible.cards.size,source,visible,expected=expected)
        }

    @Test fun sameBlackAceAtSameTrackCanProveTheTwoToOneDecreaseExactlyOnce() {
        val proof=RepeatedPlayCountProof()
        val old=play(listOf(Card(Rank.A,Suit.CLUB)))
        baseline(proof,visible=old)
        val newPhysicalSuitSameColor=play(listOf(Card(Rank.A,Suit.SPADE)))
        assertEquals(listOf(false,false,false,true),proveDrop(proof,visible=newPhysicalSuitSameColor))
        assertFalse(observe(proof,1700,1)) // Locked to this confirmed display-event version.
    }

    @Test fun sameSuitDuplicatesUseCardMultiplicityAndRequireTheFullTwoCardDrop() {
        val proof=RepeatedPlayCountProof()
        val pair=play(listOf(Card(Rank.A,Suit.SPADE),Card(Rank.A,Suit.SPADE)),listOf(.32f,.62f))
        baseline(proof,expected=4,visible=pair)
        assertEquals(listOf(false,false,false,true),proveDrop(proof,expected=4,visible=pair))
        assertFalse(observe(proof,1700,2,expected=4,value=pair))
    }

    @Test fun countCannotSubstituteForTheMissingPrePlayAnchor() {
        val proof=RepeatedPlayCountProof()
        repeat(6) { frame ->
            assertFalse(observe(proof,1000L+frame*100,1,expected=2))
        }
    }

    @Test fun oneReusedOcrSourceTimeDoesNotCastMultipleVotes() {
        val proof=RepeatedPlayCountProof()
        baseline(proof)
        assertFalse(observe(proof,1200,1,source=1200))
        repeat(5) { frame -> assertFalse(observe(proof,1300L+frame*100,1,source=1200)) }
    }

    @Test fun twoHundredFiftyMillisecondOcrCadenceCanSupportHundredMillisecondPlayFrames() {
        val proof=RepeatedPlayCountProof()
        val visible=play(cards=listOf(Card(Rank.A,Suit.SPADE)))

        // Vision sees the same fresh OCR result on several 100ms frames. Those
        // frames preserve the candidate but do not cast extra count votes.
        assertFalse(observe(proof,1000,2,source=1000))
        assertFalse(observe(proof,1100,2,source=1000))
        assertFalse(observe(proof,1200,2,source=1000))
        assertFalse(observe(proof,1300,2,source=1250))

        assertFalse(observe(proof,1400,1,source=1350,value=visible))
        assertFalse(observe(proof,1500,1,source=1350,value=visible))
        assertFalse(observe(proof,1600,1,source=1350,value=visible))
        assertTrue(observe(proof,1700,1,source=1600,value=visible))
    }

    @Test fun afterAFrameClearsCandidateAnAlreadyCountedSourceCannotRebuildItsBaseline() {
        val proof=RepeatedPlayCountProof()
        assertFalse(observe(proof,1000,2,source=1000))
        assertFalse(observe(proof,1100,2,source=1100))
        assertFalse(proof.observe(1200,round,seat,event,2,null,RemainingHandCount(2,1100)))

        // The source at 1100 had already contributed to the baseline. Since
        // the candidate was cleared, replaying it cannot recreate that vote.
        assertFalse(observe(proof,1300,2,source=1100))
        assertFalse(observe(proof,1400,1,source=1400))
        repeat(4) { frame ->
            val time=1500L+frame*100
            assertFalse(observe(proof,time,1,source=time))
        }

        // A genuinely newer baseline sequence can start again.
        assertFalse(observe(proof,2000,2,source=2000))
        assertFalse(observe(proof,2100,2,source=2100))
        val visible=play(cards=listOf(Card(Rank.A,Suit.SPADE)))
        assertEquals(listOf(false,false,false,true),(1L..4L).map { frame ->
            val time=2200L+(frame-1)*100L
            observe(proof,time,1,source=time,value=visible)
        })
    }

    @Test fun oneRepeatedBaselineOcrCannotEstablishThePrePlayCount() {
        val proof=RepeatedPlayCountProof()
        assertFalse(observe(proof,1000,2,source=1000))
        repeat(5) { frame -> assertFalse(observe(proof,1100L+frame*100,2,source=1000)) }
        repeat(5) { frame -> assertFalse(observe(proof,1700L+frame*100,1)) }
    }

    @Test fun countDropLargerThanTheVisibleCardsIsNotAttributedToThisPlay() {
        val proof=RepeatedPlayCountProof()
        baseline(proof,expected=3)
        repeat(5) { frame -> assertFalse(observe(proof,1200L+frame*100,1,expected=3)) }
    }

    @Test fun geometryDriftBreaksTheAnchorAndCannotBeRepairedByRepeatingTheDrop() {
        val proof=RepeatedPlayCountProof()
        baseline(proof)
        assertFalse(observe(proof,1200,1))
        val drifted=play(centers=listOf(.55f))
        repeat(6) { frame -> assertFalse(observe(proof,1300L+frame*100,1,value=drifted)) }
    }

    @Test fun missingMaskedOrInvisibleEvidenceClearsTheCandidate() {
        val proof=RepeatedPlayCountProof()
        baseline(proof)
        assertFalse(observe(proof,1200,1))
        assertFalse(proof.observe(1300,round,seat,event,2,play(),null)) // Masked/unqualified badge source.
        repeat(5) { frame -> assertFalse(observe(proof,1400L+frame*100,1)) }

        val afterMissing=RepeatedPlayCountProof()
        baseline(afterMissing)
        assertFalse(afterMissing.observe(1200,round,seat,event,2,play(regionObserved=false),RemainingHandCount(1,1200)))
        repeat(5) { frame -> assertFalse(observe(afterMissing,1300L+frame*100,1)) }
    }

    @Test fun aLongFrameGapBreaksContinuousPlayStability() {
        val proof=RepeatedPlayCountProof()
        baseline(proof)
        assertFalse(observe(proof,1200,1))
        assertFalse(observe(proof,1801,1)) // More than the allowed continuous-observation gap.
        repeat(5) { frame -> assertFalse(observe(proof,1901L+frame*100,1)) }
    }

    @Test fun staleAndFutureSourceTimesAreRejectedAndClearVotes() {
        for (invalidSource in listOf(0L,2201L)) {
            val proof=RepeatedPlayCountProof()
            baseline(proof)
            assertFalse(observe(proof,1200,1,source=invalidSource))
            repeat(5) { frame -> assertFalse(observe(proof,1300L+frame*100,1)) }
        }
    }

    @Test fun explicitCaptureResetRequiresACompletelyNewBaseline() {
        val proof=RepeatedPlayCountProof()
        baseline(proof)
        proof.clear()
        repeat(5) { frame -> assertFalse(observe(proof,1200L+frame*100,1)) }
        baseline(proof,start=1800)
        assertEquals(listOf(false,false,false,true),proveDrop(proof,start=2000))
    }

    @Test fun changingRoundOrAnchorEventCannotReuseThePriorCountBaseline() {
        val proof=RepeatedPlayCountProof()
        baseline(proof)
        assertFalse(observe(proof,1200,1,roundId="round-2"))
        repeat(5) { frame -> assertFalse(observe(proof,1300L+frame*100,1,roundId="round-2")) }

        assertFalse(proof.observe(1800,round,seat,event,2,play(),RemainingHandCount(2,1800)))
        assertFalse(proof.observe(1900,round,seat,event,2,play(),RemainingHandCount(2,1900)))
        assertFalse(observe(proof,2000,1,eventId="round-1:play-9"))
        repeat(5) { frame -> assertFalse(observe(proof,2100L+frame*100,1,eventId="round-1:play-9")) }
    }

    @Test fun changingVisibleCardKeyOrExpectedCountRequiresANewAnchor() {
        val proof=RepeatedPlayCountProof()
        baseline(proof)
        val differentKey=play(listOf(Card(Rank.K,Suit.CLUB)))
        assertFalse(observe(proof,1200,1,value=differentKey))
        repeat(5) { frame -> assertFalse(observe(proof,1300L+frame*100,1,value=differentKey)) }

        assertFalse(proof.observe(1900,round,seat,event,2,differentKey,RemainingHandCount(2,1900)))
        assertFalse(proof.observe(2000,round,seat,event,2,differentKey,RemainingHandCount(2,2000)))
        assertEquals(listOf(false,false,false,true),proveDrop(proof,start=2100,visible=differentKey))

        // The post-commit ledger count changes the expected baseline; previous
        // votes cannot be reused for the next visible transaction.
        assertFalse(proof.observe(2600,round,seat,event,3,differentKey,RemainingHandCount(3,2600)))
        assertFalse(proof.observe(2700,round,seat,event,3,differentKey,RemainingHandCount(3,2700)))
        val nextDrop=(1L..4L).map { frame ->
            val time=2800L+(frame-1)*100L
            proof.observe(time,round,seat,event,3,differentKey,RemainingHandCount(2,time))
        }
        assertEquals(listOf(false,false,false,true),nextDrop)
    }

    @Test fun anOtherSeatCountChangeCannotProveThisSeatsVisiblePlay() {
        val proof=RepeatedPlayCountProof()
        baseline(proof,expected=2,visible=play())
        // A different seat's 2→1 badge is not supplied as this seat's reading.
        repeat(5) { frame -> assertFalse(observe(proof,1200L+frame*100,2,expected=2)) }
        // Switching the selected seat invalidates the prior anchor; a low reading
        // for that seat is not enough to become a new baseline.
        repeat(5) { frame -> assertFalse(observe(proof,1800L+frame*100,1,player=Seat.NEXT,expected=2)) }
    }
}
