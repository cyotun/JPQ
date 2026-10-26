package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EarlyFinishPlayProofTest {
    private val cards=listOf(Card(Rank.A,Suit.RED),Card(Rank.K,Suit.BLACK),Card(Rank.Q,Suit.RED),
        Card(Rank.J,Suit.BLACK),Card(Rank.TEN,Suit.RED))
    private val snapshot=RoundSnapshot("early",RoundPhase.SELF_FINISHED,Rank.entries.associateWith { if(it.isJoker) 2 else 8 },
        Seat.entries.associateWith { seat -> if(seat==Seat.PREVIOUS) listOf(
            PlayEvent("early","previous-1",1,seat,List(22) { Card(Rank.FOUR,Suit.RED) })) else emptyList() },null)

    private fun frame(time: Long,cards: List<Card> = this.cards,confidence: Float=.92f,
        glyphConfidence: Float=confidence,weakGlyphAt: Int?=null,scene: TableScene=TableScene.TEAMMATE,
        captureGap: Boolean=false,ambiguous: Boolean=false,incomplete: String?=null,
        localizedSeats: Set<Seat> = emptySet(),
        boxes: List<GlyphBox> = cards.indices.map { GlyphBox(.20f+it*.08f,.25f,.24f+it*.08f,.31f) }): TableFrame {
        val glyphs=cards.mapIndexed { i,card -> PlayGlyph(card,if(i==weakGlyphAt) .79f else glyphConfidence,boxes[i]) }
        val play=ObservedPlay(cards,confidence,glyphs)
        return TableFrame(time,scene,null,Seat.entries.associateWith { if(it==Seat.PREVIOUS) play else null },
            incompleteReason=incomplete,ambiguousPresentation=ambiguous,captureGap=captureGap,
            localizedOverlaySeats=localizedSeats)
    }

    @Test fun twoDistinctStrongExactFinalGroupsAndFreshSourceEmitOnlyOnce() {
        val proof=EarlyFinishPlayProof()
        assertNull(proof.observe(frame(627705731),snapshot,Seat.PREVIOUS,627705299))
        assertEquals(cards,proof.observe(frame(627705930),snapshot,Seat.PREVIOUS,627705299)?.cards)
        assertNull(proof.observe(frame(627706030),snapshot,Seat.PREVIOUS,627705299))
    }

    @Test fun sameTimestampCannotCastTwoVotesAndChangedGeometryRestartsProof() {
        val proof=EarlyFinishPlayProof()
        assertNull(proof.observe(frame(1000),snapshot,Seat.PREVIOUS,950))
        assertNull(proof.observe(frame(1000),snapshot,Seat.PREVIOUS,950))
        assertNull(proof.observe(frame(1100,boxes=cards.indices.map { GlyphBox(.55f+it*.08f,.25f,.59f+it*.08f,.31f) }),
            snapshot,Seat.PREVIOUS,950))
        assertEquals(cards,proof.observe(frame(1200,boxes=cards.indices.map { GlyphBox(.55f+it*.08f,.25f,.59f+it*.08f,.31f) }),
            snapshot,Seat.PREVIOUS,950)?.cards)
        assertNull(proof.observe(frame(1301),snapshot,Seat.PREVIOUS,950))
        assertNull(proof.observe(frame(1400,scene=TableScene.TABLE),snapshot.copy(phase=RoundPhase.ACTIVE),Seat.PREVIOUS,950))
    }

    @Test fun partialWeakStaleOrMaskedEvidenceCannotComplete() {
        val proof=EarlyFinishPlayProof()
        assertNull(proof.observe(frame(1000,cards=cards.take(4)),snapshot,Seat.PREVIOUS,950))
        assertNull(proof.observe(frame(1100,confidence=.79f),snapshot,Seat.PREVIOUS,950))
        assertNull(proof.observe(frame(1200,glyphConfidence=.79f),snapshot,Seat.PREVIOUS,950))
        assertNull(proof.observe(frame(1250,weakGlyphAt=3),snapshot,Seat.PREVIOUS,1200))
        assertNull(proof.observe(frame(1300,captureGap=true),snapshot,Seat.PREVIOUS,1250))
        assertNull(proof.observe(frame(1400,ambiguous=true),snapshot,Seat.PREVIOUS,1350))
        assertNull(proof.observe(frame(1500,incomplete="masked"),snapshot,Seat.PREVIOUS,1450))
        assertNull(proof.observe(frame(1600,localizedSeats=setOf(Seat.PREVIOUS)),snapshot,Seat.PREVIOUS,1550))
        assertNull(proof.observe(frame(1700),snapshot.copy(remaining=null),Seat.PREVIOUS,1650))
        assertNull(proof.observe(frame(1800),snapshot,Seat.PREVIOUS,799))
        assertNull(proof.observe(frame(1900),snapshot,Seat.PREVIOUS,1950))
    }

    @Test fun freshSourceCanAdvanceButBackwardSourceMustNotBeReused() {
        val refreshed=EarlyFinishPlayProof()
        assertNull(refreshed.observe(frame(1000),snapshot,Seat.PREVIOUS,950))
        assertEquals(cards,refreshed.observe(frame(1100),snapshot,Seat.PREVIOUS,1000)?.cards)

        val retreated=EarlyFinishPlayProof()
        assertNull(retreated.observe(frame(1000),snapshot,Seat.PREVIOUS,950))
        assertNull(retreated.observe(frame(1100),snapshot,Seat.PREVIOUS,949))
        assertNull(retreated.observe(frame(1200),snapshot,Seat.PREVIOUS,949))
        assertNull(retreated.observe(frame(1300),snapshot,Seat.PREVIOUS,1001))
        assertEquals(cards,retreated.observe(frame(1400),snapshot,Seat.PREVIOUS,1001)?.cards)
    }

    @Test fun ledgerVersionChangeCannotBorrowOldCandidate() {
        val proof=EarlyFinishPlayProof()
        assertNull(proof.observe(frame(1000),snapshot,Seat.PREVIOUS,950))
        val changed=snapshot.copy(plays=snapshot.plays + (Seat.PREVIOUS to listOf(
            snapshot.plays.getValue(Seat.PREVIOUS).single().copy(eventId="previous-reconciled"))))
        assertNull(proof.observe(frame(1100),changed,Seat.PREVIOUS,950))
        assertEquals(cards,proof.observe(frame(1200),changed,Seat.PREVIOUS,950)?.cards)
    }

    @Test fun cleanSceneAndAllowedPhaseTransitionCanShareOneProofButNeverRedeliver() {
        val proof=EarlyFinishPlayProof()
        assertNull(proof.observe(frame(1000,scene=TableScene.TEAMMATE),snapshot,Seat.PREVIOUS,950))
        val active=snapshot.copy(phase=RoundPhase.ACTIVE)
        assertEquals(cards,proof.observe(frame(1100,scene=TableScene.TABLE),active,Seat.PREVIOUS,950)?.cards)
        assertNull(proof.observe(frame(1200,scene=TableScene.TEAMMATE),snapshot,Seat.PREVIOUS,950))
    }

    @Test fun recentExactFullHandCanBeConfirmedByFreshCueOnSameSlotPartialRead() {
        val proof=EarlyFinishPlayProof()
        for(t in listOf(37246L,37490L,37741L,38022L,38271L))
            assertNull(proof.observe(frame(t),snapshot,Seat.PREVIOUS,null))

        val anchorBoxes=cards.indices.map { GlyphBox(.20f+it*.08f,.25f,.24f+it*.08f,.31f) }
        val partial=frame(38520L,cards=listOf(Card(Rank.Q,Suit.RED)),confidence=.65f,
            glyphConfidence=.65f,boxes=listOf(anchorBoxes[2]))
        assertEquals(cards,proof.observe(partial,snapshot,Seat.PREVIOUS,38520L)?.cards)
    }

    @Test fun noCueOrWrongPartialCannotTurnRetainedFullEvidenceIntoAPlay() {
        val proof=EarlyFinishPlayProof()
        for(t in listOf(1000L,1244L,1495L))
            assertNull(proof.observe(frame(t),snapshot,Seat.PREVIOUS,null))
        val wrong=frame(1600L,cards=listOf(Card(Rank.Q,Suit.RED)),confidence=.65f,
            glyphConfidence=.65f,boxes=listOf(GlyphBox(.80f,.65f,.84f,.71f)))
        assertNull(proof.observe(wrong,snapshot,Seat.PREVIOUS,1600L))

        val noCue=EarlyFinishPlayProof()
        for(t in listOf(2000L,2244L,2495L,2776L,3025L))
            assertNull(noCue.observe(frame(t),snapshot,Seat.PREVIOUS,null))
        assertNull(noCue.observe(frame(3526L),snapshot,Seat.PREVIOUS,null))
    }

    @Test fun retainedEvidenceExpiresAndCannotCrossLedgerVersionChange() {
        val expired=EarlyFinishPlayProof()
        for(t in listOf(1000L,1244L,1495L)) assertNull(expired.observe(frame(t),snapshot,Seat.PREVIOUS,null))
        assertNull(expired.observe(frame(2501L),snapshot,Seat.PREVIOUS,2501L))

        val changed=EarlyFinishPlayProof()
        for(t in listOf(3000L,3244L,3495L)) assertNull(changed.observe(frame(t),snapshot,Seat.PREVIOUS,null))
        val updated=snapshot.copy(plays=snapshot.plays + (Seat.PREVIOUS to listOf(
            snapshot.plays.getValue(Seat.PREVIOUS).single().copy(eventId="revised"))))
        assertNull(changed.observe(frame(3600L),updated,Seat.PREVIOUS,3600L))
        // The old votes cannot commit on the first changed-version frame;
        // two fresh full reads under the new version can establish a new proof.
        assertEquals(cards,changed.observe(frame(3710L),updated,Seat.PREVIOUS,3600L)?.cards)
    }
}
