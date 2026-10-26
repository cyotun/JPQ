package com.cyotun.jpq.core

import kotlin.test.*

class TerminalOwnCompletionObserverTest {
    private val last=listOf(Card(Rank.K,Suit.BLACK),Card(Rank.K,Suit.BLACK),Card(Rank.K,Suit.BLACK),Card(Rank.K,Suit.RED))
    private val deck=Rank.entries.flatMap { rank -> if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
        else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } } }
    private fun ledger()=RoundLedger().apply {
        val prefix=deck.filter { it.rank!=Rank.K }.take(23)
        begin("terminal-own",prefix+last)
        commit(PlayEvent("terminal-own","before",1,Seat.SELF,prefix))
        val outside=deck.toMutableList().apply { initialHand().forEach { remove(it) } }.take(27)
        commit(PlayEvent("terminal-own","ally-out",2,Seat.OPPOSITE,outside))
    }
    private fun play(cards: List<Card>,confidence: Float=.9f)=ObservedPlay(cards,confidence,cards.mapIndexed { i,c ->
        PlayGlyph(c,confidence,GlyphBox(.45f+i*.025f,.32f,.47f+i*.025f,.37f)) })
    private fun frame(t: Long,hand: List<Card>?=emptyList(),own: ObservedPlay?=null,
        finished: Boolean=false,ambiguous: Boolean=false,shownOutside: Boolean=false)=TableFrame(t,TableScene.TABLE,hand,
        Seat.entries.associateWith { seat -> when {
            seat==Seat.SELF -> own
            seat==Seat.PREVIOUS && shownOutside -> play(List(3) { Card(Rank.THREE,Suit.RED) })
            else -> ObservedPlay(emptyList(),1f)
        } },selfFinished=finished,ambiguousPresentation=ambiguous,
        incompleteReason=if(ambiguous) "出牌归属待核对，正在重新识别" else null)

    @Test fun exactTailAfterOwnershipAndColorAnimationCompletesBeforeTheRoundCloses() {
        val l=ledger(); val states=mutableListOf<RoundSnapshot>(); val observer=RoundObserver(l) { states+=it }
        val remaining=l.snapshot().remaining
        observer.observe(frame(1000,hand=last))
        observer.observe(frame(1100,own=play(last)))
        observer.observe(frame(1220,ambiguous=true))
        observer.observe(frame(1340))
        observer.observe(frame(1460,finished=true,shownOutside=true))
        assertEquals(RoundPhase.ACTIVE,l.snapshot().phase)
        observer.observe(frame(1580,own=play(List(4) { Card(Rank.K,Suit.RED) }),shownOutside=true))
        observer.observe(frame(1700,own=play(last,.665f),shownOutside=true))
        for(t in listOf(1820L,1940L,2060L)) {
            observer.observe(frame(t,own=play(last),shownOutside=true,ambiguous=t==2060L))
            assertEquals(23,l.snapshot().played(Seat.SELF))
            assertEquals(RoundPhase.ACTIVE,l.snapshot().phase)
        }
        // The original finish cue is latched; the OCR need not repeat it here.
        observer.observe(frame(2180,own=play(last),shownOutside=true,ambiguous=true))
        val completed=states.last { it.phase==RoundPhase.FINISHED }
        assertEquals(27,completed.played(Seat.SELF))
        assertEquals(last,completed.plays.getValue(Seat.SELF).last().cards)
        assertEquals(0,completed.played(Seat.PREVIOUS))
        assertEquals(remaining,completed.remaining)
        assertEquals(RoundPhase.WAITING,l.snapshot().phase)
    }

    @Test fun deadlineDoesNotInventAnUnseenTailOrRemainActiveForever() {
        val l=ledger();val states=mutableListOf<RoundSnapshot>();val observer=RoundObserver(l) { states+=it }
        observer.observe(frame(1000,hand=last));observer.observe(frame(1100,own=play(last)))
        observer.observe(frame(1220,ambiguous=true));observer.observe(frame(1340))
        observer.observe(frame(1460,finished=true))
        for(t in 1580L..3140L step 120) observer.observe(frame(t))
        assertEquals(RoundPhase.ACTIVE,l.snapshot().phase)
        observer.observe(frame(3260))
        val completed=states.last { it.phase==RoundPhase.FINISHED }
        assertEquals(23,completed.played(Seat.SELF))
        assertEquals(RoundPhase.WAITING,l.snapshot().phase)
    }

    @Test fun completionWithoutTheDirectAnchorDoesNotCreateADeferWindow() {
        val l=ledger();val states=mutableListOf<RoundSnapshot>();val observer=RoundObserver(l) { states+=it }
        observer.observe(frame(1000,hand=last))
        observer.observe(frame(1100,finished=true))
        val completed=states.last { it.phase==RoundPhase.FINISHED }
        assertEquals(23,completed.played(Seat.SELF))
        assertEquals(RoundPhase.WAITING,l.snapshot().phase)
    }

    @Test fun theCompleteOwnedHandReturningDisprovesTheEarlierCompletionHint() {
        val l=ledger();val observer=RoundObserver(l)
        observer.observe(frame(1000,hand=last));observer.observe(frame(1100,own=play(last)))
        observer.observe(frame(1220,ambiguous=true));observer.observe(frame(1340,finished=true))
        assertEquals(RoundPhase.ACTIVE,l.snapshot().phase)
        observer.observe(frame(1460,hand=last))
        observer.observe(frame(1580,hand=last))
        assertEquals(RoundPhase.ACTIVE,l.snapshot().phase)
        assertEquals(23,l.snapshot().played(Seat.SELF))
    }

    @Test fun aReturningCompleteHandOverridesTheSameFramesRawFinishCue() {
        for(teammateScene in listOf(false,true)) {
            val l=ledger();val states=mutableListOf<RoundSnapshot>();val observer=RoundObserver(l) { states+=it }
            observer.observe(frame(1000,hand=last));observer.observe(frame(1100,own=play(last)))
            observer.observe(frame(1220,ambiguous=true));observer.observe(frame(1340,finished=true))
            for(t in listOf(1460L,1580L,1700L)) {
                val returned=frame(t,hand=last,finished=true)
                observer.observe(if(teammateScene) returned.copy(scene=TableScene.TEAMMATE,hand=null,
                    teammateHand=last,teammateHandConfidence=.9f) else returned)
                assertEquals(RoundPhase.ACTIVE,l.snapshot().phase)
                assertEquals(23,l.snapshot().played(Seat.SELF))
            }
            // A real later departure can still create a new direct proof.
            observer.observe(frame(1760,hand=last))
            for(t in listOf(1820L,1940L,2060L,2180L))
                observer.observe(frame(t,own=play(last),finished=t==2180L))
            val completed=states.last { it.phase==RoundPhase.FINISHED }
            assertEquals(27,completed.played(Seat.SELF))
        }
    }

    @Test fun aLaterDirtyFrameDoesNotLoseTheAlreadyPositiveCompletionCue() {
        val faults=listOf<(TableFrame)->TableFrame>(
            { it.copy(captureGap=true) },{ it.copy(incompleteReason="unobserved") },
            { it.copy(ambiguousPresentation=true,incompleteReason="other ownership fault") },
            { it.copy(scene=TableScene.UNKNOWN) })
        for(fault in faults) {
            val l=ledger();val states=mutableListOf<RoundSnapshot>();val observer=RoundObserver(l) { states+=it }
            observer.observe(frame(1000,hand=last));observer.observe(frame(1100,own=play(last)))
            observer.observe(frame(1220,ambiguous=true));observer.observe(frame(1340,finished=true))
            observer.observe(fault(frame(1460,shownOutside=true)))
            val completed=states.last { it.phase==RoundPhase.FINISHED }
            assertEquals(23,completed.played(Seat.SELF))
            assertEquals(0,completed.played(Seat.PREVIOUS))
            assertEquals(RoundPhase.WAITING,l.snapshot().phase)
        }
    }

    @Test fun aVisibilityGapCheckpointCannotReopenOutsideCollectionAfterKnownCompletion() {
        val l=ledger();val states=mutableListOf<RoundSnapshot>();val observer=RoundObserver(l) { states+=it }
        observer.observe(frame(1000,hand=last));observer.observe(frame(1100,own=play(last)))
        observer.observe(frame(1220,ambiguous=true));observer.observe(frame(1340,finished=true))
        observer.incomplete("capture visibility lost",1400,immediate=true)
        assertEquals(RoundPhase.RECOVERING,l.snapshot().phase)
        observer.observe(frame(1460,shownOutside=true))
        val completed=states.last { it.phase==RoundPhase.FINISHED }
        assertEquals(23,completed.played(Seat.SELF))
        assertEquals(0,completed.played(Seat.PREVIOUS))
        assertEquals(RoundPhase.WAITING,l.snapshot().phase)
    }

    @Test fun aFirstTeammateCueCanContinueTheExistingTableAmbiguityAuthority() {
        val l=ledger();val states=mutableListOf<RoundSnapshot>();val observer=RoundObserver(l) { states+=it }
        observer.observe(frame(1000,hand=last));observer.observe(frame(1100,own=play(last)))
        observer.observe(frame(1220,ambiguous=true))
        fun teammate(t: Long,p: ObservedPlay?=null)=frame(t,own=p).copy(scene=TableScene.TEAMMATE,hand=null,
            teammateHand=emptyList(),teammateHandConfidence=.9f)
        observer.observe(teammate(1340))
        assertEquals(RoundPhase.ACTIVE,l.snapshot().phase)
        listOf(1460L,1580L,1700L,1820L).forEach { observer.observe(teammate(it,play(last))) }
        val completed=states.last { it.phase==RoundPhase.FINISHED }
        assertEquals(27,completed.played(Seat.SELF))
    }
}
