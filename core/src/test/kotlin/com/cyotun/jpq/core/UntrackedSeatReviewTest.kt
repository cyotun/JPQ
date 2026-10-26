package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UntrackedSeatReviewTest {
    @Test fun explicitVisibilityFenceCannotBeBypassedWithLaterMatchingCounts() {
        val proof=UntrackedSeatReview()
        certify(proof)
        proof.interruptCapture()
        assertNull(badge(proof,23))
        assertTrue(proof.unsafe(seat))
        assertTrue(proof.pendingSeats().contains(seat))
    }
    private val seat=Seat.OPPOSITE
    private val old=Card(Rank.TWO,Suit.BLACK)
    private val firstCards=listOf(old,Card(Rank.A,Suit.BLACK),Card(Rank.Q,Suit.RED))
    private val laterCards=listOf(Card(Rank.FIVE,Suit.BLACK),Card(Rank.SIX,Suit.RED))
    private val oldBox=GlyphBox(.70f,.28f,.74f,.34f)
    private val prior=QuantityAnchoredPlayProof.PriorPresentation("old",listOf(old),listOf(PlayGlyph(old,.95f,oldBox)))
    private val oldEvent=PlayEvent("round","old",1,seat,listOf(old))
    private val snapshot=RoundSnapshot("round",RoundPhase.ACTIVE,Rank.entries.associateWith { it.capacity },
        mapOf(seat to listOf(oldEvent)),null)

    private fun play(cards: List<Card> = firstCards,tracked: Boolean=false): ObservedPlay =
        ObservedPlay(cards,.95f,cards.mapIndexed { i,c ->
            val x=(if(tracked) .70f else .20f)+i*.075f
            PlayGlyph(c,.95f,GlyphBox(x,.28f,x+.04f,.34f))
        })
    private fun frame(time: Long,p: ObservedPlay=play(),count: Int?=null,source: Long=time)=
        TableFrame(time,TableScene.TABLE,null,mapOf(seat to p),remainingHandCounts=
            count?.let { mapOf(seat to RemainingHandCount(it,source)) }.orEmpty())
    private fun observe(proof: UntrackedSeatReview,time: Long,p: ObservedPlay=play(),count: Int?=null,
        source: Long=time,s: RoundSnapshot=snapshot)=proof.observe(frame(time,p,count,source),s,seat,prior)
    private fun certify(proof: UntrackedSeatReview,start: Long=1000,p: ObservedPlay=play(),s: RoundSnapshot=snapshot) {
        listOf(0L,60L,120L,180L).forEach { assertNull(observe(proof,start+it,p,s=s)) }
    }
    private fun badge(proof: UntrackedSeatReview,count: Int,start: Long=1240,p: ObservedPlay=play(),
        s: RoundSnapshot=snapshot): UntrackedSeatReview.Resolution? {
        assertNull(observe(proof,start,p,count,s=s))
        assertNull(observe(proof,start+150,p,count,s=s))
        return observe(proof,start+300,p,count,s=s)
    }

    @Test fun directNewCardsResolveWithoutAnyOtherSeatsBadge() {
        val proof=UntrackedSeatReview()
        certify(proof)
        assertTrue(proof.holds(seat))
        val result=assertNotNull(badge(proof,23))
        assertTrue(result.expansions.isEmpty())
        assertEquals(listOf(firstCards),result.newObservedGroups.map { it.cards })
        assertEquals(firstCards,result.latestPresentation.cards)
        assertTrue(proof.holds(seat))
        assertEquals(result,observe(proof,1600,count=23)) // a rejected transaction can retry
        proof.clear(seat)
        assertFalse(proof.holds(seat))
    }

    @Test fun expansionRequiresExactOldPhysicalPrefixAndExactAddedCount() {
        val p=play(tracked=true)
        val proof=UntrackedSeatReview()
        certify(proof,p=p)
        val result=assertNotNull(badge(proof,24,p=p))
        assertEquals(mapOf("old" to firstCards),result.expansions)
        assertTrue(result.newObservedGroups.isEmpty())
        proof.clear(seat)
        val untracked=UntrackedSeatReview()
        certify(untracked)
        assertNull(badge(untracked,24))
        assertTrue(untracked.holds(seat))
    }

    @Test fun oldRowWithIndependentUnchangedBadgeCanDiscardFalseSuperset() {
        val proof=UntrackedSeatReview()
        certify(proof)
        val oldRow=ObservedPlay(prior.cards,.95f,prior.glyphs)
        val result=assertNotNull(badge(proof,26,p=oldRow))
        assertTrue(result.expansions.isEmpty())
        assertTrue(result.newObservedGroups.isEmpty())
        assertEquals(prior.cards,result.latestPresentation.cards)
        proof.clear(seat)
        val stillNew=UntrackedSeatReview()
        certify(stillNew)
        assertNull(badge(stillNew,26)) // count alone is never a no-action proof
    }

    @Test fun twoPositiveEmptyFramesAlsoSupportUnchangedInterpretation() {
        val proof=UntrackedSeatReview()
        certify(proof)
        val empty=ObservedPlay(emptyList(),1f)
        assertNull(observe(proof,1240,empty,26))
        assertNull(observe(proof,1390,empty,26))
        val result=assertNotNull(observe(proof,1540,empty,26))
        assertTrue(result.newObservedGroups.isEmpty())
        proof.clear(seat)
    }

    @Test fun strictlyAppendedOtherSeatEventsPreserveExistingCertificatesAndBadgeVotes() {
        val proof=UntrackedSeatReview()
        certify(proof)
        assertNull(observe(proof,1240,count=23))
        val other=PlayEvent("round","other",2,Seat.NEXT,listOf(Card(Rank.THREE,Suit.RED)))
        val appended=snapshot.copy(plays=snapshot.plays+mapOf(Seat.NEXT to listOf(other)))
        assertNull(observe(proof,1390,count=23,s=appended))
        assertNotNull(observe(proof,1540,count=23,s=appended))
        proof.clear(seat)
    }

    @Test fun independentlyLocalizedOtherSeatMaskDoesNotPoisonTargetCertificates() {
        val proof=UntrackedSeatReview()
        certify(proof)
        fun masked(time: Long)=frame(time,count=23).copy(
            incompleteReason="local overlay",localizedOverlaySeats=setOf(Seat.NEXT),
            plays=mapOf(seat to play(),Seat.NEXT to ObservedPlay(emptyList(),1f,regionObserved=false)))
        assertNull(proof.observe(masked(1240),snapshot,seat,prior))
        assertNull(proof.observe(masked(1390),snapshot,seat,prior))
        assertNotNull(proof.observe(masked(1540),snapshot,seat,prior))
        assertFalse(proof.unsafe(seat))
        assertEquals(setOf(seat),proof.pendingSeats())
        proof.clear(seat)
        assertTrue(proof.pendingSeats().isEmpty())
    }

    @Test fun priorRowReturnAfterSeveralGroupsCannotCreateAnotherOldEventWithoutEmptyBoundary() {
        val proof=UntrackedSeatReview()
        certify(proof)
        certify(proof,1300,play(laterCards))
        val oldRow=ObservedPlay(prior.cards,.95f,prior.glyphs)
        certify(proof,1600,oldRow)
        assertNull(badge(proof,20,1840,oldRow)) // six-card delta would require an invented old-row event
        assertTrue(proof.pendingSeats().contains(seat))
    }

    @Test fun multipleLaterDirectGroupsResolveOnlyTheirExactCombinedNetChange() {
        val proof=UntrackedSeatReview()
        certify(proof)
        val second=play(laterCards)
        certify(proof,1300,second)
        val third=play(listOf(Card(Rank.K,Suit.RED)))
        certify(proof,1600,third)
        val result=assertNotNull(badge(proof,20,1840,third))
        assertEquals(listOf(firstCards,laterCards,third.cards),result.newObservedGroups.map { it.cards })
        assertEquals(third,result.latestPresentation)
        proof.clear(seat)
    }

    @Test fun laterIdenticalGroupRequiresPositiveEmptyBoundary() {
        val proof=UntrackedSeatReview()
        certify(proof)
        val empty=ObservedPlay(emptyList(),1f)
        assertNull(observe(proof,1240,empty))
        assertNull(observe(proof,1360,empty))
        certify(proof,1420)
        val result=assertNotNull(badge(proof,20,1660))
        assertEquals(listOf(firstCards,firstCards),result.newObservedGroups.map { it.cards })
        proof.clear(seat)
    }

    @Test fun colorFlickerPartialAndSupersetAreNotLaterNewGroups() {
        val variants=listOf(play(firstCards.map { if(it==old) Card(it.rank,Suit.RED) else it }),
            play(firstCards.take(2)),play(firstCards+Card(Rank.J,Suit.BLACK)))
        variants.forEach { variant ->
            val proof=UntrackedSeatReview()
            certify(proof)
            certify(proof,1300,variant)
            assertNull(badge(proof,26-firstCards.size-variant.cards.size,1540,variant))
            assertTrue(proof.holds(seat))
        }
    }

    @Test fun cachedBadgeSourceAndDuplicateGlyphFramesCannotManufactureVotes() {
        val proof=UntrackedSeatReview()
        repeat(10) { assertNull(observe(proof,1000)) }
        assertNull(observe(proof,1180))
        assertNull(observe(proof,1240,count=23))
        assertNull(observe(proof,1300,count=23)) // only fourth distinct glyph frame certifies
        assertNull(observe(proof,1360,count=23,source=1240)) // predates certificate
        assertNull(observe(proof,1510,count=23,source=1360))
        assertNull(observe(proof,1660,count=23,source=1360))
        assertNull(observe(proof,1810,count=23,source=1360))
        assertTrue(proof.holds(seat))
        assertNull(observe(proof,1870,count=23))
        assertNotNull(observe(proof,2020,count=23))
        proof.clear(seat)
    }

    @Test fun countsCannotCreateUnseenCardsAndFreshCorrectConsensusRecoversFromNoise() {
        val proof=UntrackedSeatReview()
        certify(proof)
        assertNull(badge(proof,17)) // nine-card delta cannot be fabricated from a three-card row
        assertNull(observe(proof,1600,count=23))
        assertFalse(proof.unsafe(seat))
        assertNull(observe(proof,1750,count=23))
        assertNotNull(observe(proof,1900,count=23))
        proof.clear(seat)
        val impossible=UntrackedSeatReview()
        certify(impossible)
        assertNull(observe(impossible,1240,count=27))
        assertFalse(impossible.unsafe(seat))
        val decreasing=UntrackedSeatReview()
        certify(decreasing)
        assertNull(observe(decreasing,1240,count=23))
        assertNull(observe(decreasing,1390,count=22))
        assertNull(observe(decreasing,1540,count=21))
        assertTrue(decreasing.holds(seat))
    }

    @Test fun oneErroneousCountDoesNotLatchAnIrreversibleHandCountBound() {
        val proof=UntrackedSeatReview()
        certify(proof)
        assertNull(observe(proof,1240,count=17))
        assertNotNull(badge(proof,23,1300))
        assertFalse(proof.unsafe(seat))
        proof.clear(seat)
    }

    @Test fun futureOutOfRangeAndOutOfOrderBadgeSourcesDiscardVotesAndRecover() {
        listOf(frame(1240,count=23,source=1300),frame(1240,count=99),
            frame(1240,count=-1),frame(1390,count=23,source=1200)).forEach { erroneous ->
            val proof=UntrackedSeatReview()
            certify(proof)
            if(erroneous.timeMs==1390L) assertNull(observe(proof,1240,count=23))
            assertNull(proof.observe(erroneous,snapshot,seat,prior))
            assertFalse(proof.unsafe(seat))
            assertNotNull(badge(proof,23,1450))
            proof.clear(seat)
        }
    }

    @Test fun sameSourceDisagreementCannotVoteButLaterIndependentSourcesRecover() {
        val proof=UntrackedSeatReview()
        certify(proof)
        assertNull(observe(proof,1240,count=17))
        assertNull(observe(proof,1390,count=23,source=1240))
        assertNull(observe(proof,1540,count=23,source=1240))
        assertFalse(proof.unsafe(seat))
        assertNotNull(badge(proof,23,1600))
        proof.clear(seat)
    }

    @Test fun historyMutationCrossRoundAndCaptureGapsKeepSeatHeldButUnsafe() {
        val mutations=listOf(
            snapshot.copy(roundId="other"),
            snapshot.copy(plays=mapOf(seat to listOf(oldEvent.copy(cards=listOf(Card(Rank.A,Suit.RED)))))),
            snapshot.copy(plays=snapshot.plays+mapOf(Seat.NEXT to listOf(
                PlayEvent("round","inserted",0,Seat.NEXT,listOf(Card(Rank.THREE,Suit.RED)))))))
        mutations.forEach { mutated ->
            val proof=UntrackedSeatReview()
            certify(proof)
            assertNull(observe(proof,1240,count=23,s=mutated))
            assertTrue(proof.unsafe(seat))
            assertTrue(proof.holds(seat))
        }
        val proof=UntrackedSeatReview()
        certify(proof)
        assertNull(proof.observe(frame(1240,count=23).copy(captureGap=true),snapshot,seat,prior))
        assertTrue(proof.unsafe(seat))
        assertNull(badge(proof,23,1300))
        proof.clear(seat)
        assertFalse(proof.holds(seat))
    }

    @Test fun weakPartialUnobservedAndAmbiguousFramesRecoverOnlyAfterFreshExactRowAndLaterBadgeProof() {
        listOf(frame(1240).copy(ambiguousPresentation=true),
            frame(1240,p=play().copy(regionObserved=false)),
            frame(1240,p=play().copy(confidence=.79f)),
            frame(1240,p=play(firstCards.take(2))),
            frame(1240,p=play().copy(glyphs=play().glyphs.dropLast(1)))).forEach { fault ->
            val proof=UntrackedSeatReview()
            certify(proof)
            assertNull(proof.observe(fault,snapshot,seat,prior))
            assertFalse(proof.unsafe(seat))
            assertTrue(proof.holds(seat))
            listOf(1300L,1360L,1420L,1480L).forEach {
                assertNull(observe(proof,it,count=23))
            }
            val result=assertNotNull(badge(proof,23,1540))
            assertEquals(listOf(firstCards),result.newObservedGroups.map { it.cards })
            proof.clear(seat)
        }
    }

    @Test fun ordinaryLongFrameSpacingNeedsFourFreshGlyphFramesAndThreeLaterCountSources() {
        val proof=UntrackedSeatReview()
        certify(proof)
        assertNull(observe(proof,1240,count=23))
        assertNull(observe(proof,1390,count=23))
        assertNull(observe(proof,2000,count=23)) // old votes cannot resolve on this single return
        assertFalse(proof.unsafe(seat))
        assertNull(observe(proof,2060,count=23))
        assertNull(observe(proof,2120,count=23))
        assertNull(observe(proof,2180,count=23)) // recertification is not a badge vote
        assertNotNull(badge(proof,23,2240))
        proof.clear(seat)
    }

    @Test fun rejectedCapacityProposalStaysPendingAndDoesNotConsumeEvidence() {
        val proof=UntrackedSeatReview()
        certify(proof)
        val proposed=assertNotNull(badge(proof,23))
        // The owner may reject this proposal during its atomic inventory check.
        assertTrue(proof.holds(seat))
        assertEquals(setOf(seat),proof.pendingSeats())
        assertEquals(proposed,observe(proof,1600,count=23))
        assertTrue(proof.holds(seat))
        proof.clear(seat) // only the owner acknowledges a successful transaction
        assertFalse(proof.holds(seat))
    }

    @Test fun malformedAndOverlappingDirectGlyphsNeverStartReview() {
        val p=play()
        val malformed=listOf(p.copy(glyphs=p.glyphs.dropLast(1)),
            p.copy(glyphs=p.glyphs.map { it.copy(box=p.glyphs.first().box) }),
            p.copy(confidence=.64f))
        malformed.forEach { bad ->
            val proof=UntrackedSeatReview()
            certify(proof,p=bad)
            assertFalse(proof.holds(seat))
        }
    }

    @Test fun structurallyCompleteWeakSupersetCanHoldButOnlyStrongFramesCertifyItsCards() {
        val proof=UntrackedSeatReview()
        val weak=play().copy(confidence=.65f,glyphs=play().glyphs.map { it.copy(confidence=.65f) })
        assertNull(observe(proof,1000,weak,count=23))
        assertTrue(proof.holds(seat))
        listOf(1060L,1120L,1180L).forEach { assertNull(observe(proof,it,count=23)) }
        assertNull(observe(proof,1240,count=23)) // fourth strong frame; no weak votes counted
        assertNotNull(badge(proof,23,1300))
        proof.clear(seat)
    }
}
