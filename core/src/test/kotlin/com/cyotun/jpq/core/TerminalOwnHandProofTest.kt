package com.cyotun.jpq.core

import kotlin.test.*

class TerminalOwnHandProofTest {
    private val own=listOf(Card(Rank.TEN,Suit.RED))+List(3) { Card(Rank.TEN,Suit.BLACK) }
    private fun snapshot(): RoundSnapshot {
        val deck=Rank.entries.flatMap { rank -> if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
            else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } } }.toMutableList()
        own.forEach { deck.remove(it) }
        var offset=0
        val sizes=mapOf(Seat.SELF to 23,Seat.OPPOSITE to 27,Seat.NEXT to 27,Seat.PREVIOUS to 25)
        val plays=sizes.entries.mapIndexed { index,(seat,n) ->
            val cards=deck.subList(offset,offset+n).toList(); offset+=n
            seat to listOf(PlayEvent("round","$seat",index.toLong(),seat,cards))
        }.toMap()
        val known=own+plays.values.flatten().flatMap { it.cards }
        val counts=known.groupingBy { it.rank }.eachCount()
        return RoundSnapshot("round",RoundPhase.ACTIVE,Rank.entries.associateWith { it.capacity-(counts[it] ?: 0) },plays,null)
    }
    private fun play(cards: List<Card> = own,confidence: Float=.90f): ObservedPlay {
        val glyphs=cards.mapIndexed { i,c -> PlayGlyph(c,confidence,GlyphBox(.4386f+i*.0245f,.3243f,.457f+i*.0245f,.3707f)) }
        return ObservedPlay(cards,confidence,glyphs)
    }
    private fun frame(time: Long,self: ObservedPlay?=play(),finished: Boolean=false,
                      scene: TableScene=TableScene.TABLE,hand: List<Card>?=emptyList())=TableFrame(
        time,scene,hand,mapOf(Seat.SELF to self,Seat.PREVIOUS to ObservedPlay(emptyList(),1f),
            Seat.OPPOSITE to ObservedPlay(emptyList(),1f,regionObserved=false),
            Seat.NEXT to ObservedPlay(emptyList(),1f,regionObserved=false)),selfFinished=finished)
    private fun observe(proof: TerminalOwnHandProof,frame: TableFrame,snapshot: RoundSnapshot=snapshot(),
                        phase: RoundPhase=RoundPhase.ACTIVE)=proof.observe(frame,snapshot,own,phase)

    @Test fun realFourTensCrossTheShortOwnAnimationWithEveryUnfinishedOutsideFrameVisible() {
        val proof=TerminalOwnHandProof()
        val active=snapshot()
        val recovering=active.copy(phase=RoundPhase.RECOVERING,remaining=null,reason="画面暂不完整")
        assertNull(observe(proof,frame(16978662,self=play(emptyList()),hand=own),active))
        assertNull(observe(proof,frame(16978929,self=play(own.take(1),.9039f)),active))
        assertNull(observe(proof,frame(16979178,self=play(confidence=.8912f)),active))
        assertNull(observe(proof,frame(16979445,self=play(own.take(1),.8929f)),active))
        assertNull(observe(proof,frame(16979710,self=ObservedPlay(emptyList(),1f,regionObserved=false),
            scene=TableScene.UNKNOWN),active))
        assertNull(observe(proof,frame(16979962,self=play(List(4) { Card(Rank.TEN,Suit.RED) },.8908f)),active))
        val first=play(confidence=.8688f)
        assertNull(observe(proof,frame(16980225,self=first),recovering))
        assertEquals(first,observe(proof,frame(16980492,self=play(confidence=.8909f),finished=true),recovering))
        assertNull(observe(proof,frame(16980550,finished=true),recovering))
    }

    @Test fun completionTextPartialAndResultSceneAloneCannotProveAnExit() {
        for(p in listOf(null,play(own.take(1)),play(confidence=.79f))) {
            val proof=TerminalOwnHandProof()
            for(time in listOf(1000L,1250L,1500L)) assertNull(observe(proof,frame(time,self=p,finished=true)))
        }
        val proof=TerminalOwnHandProof()
        assertNull(observe(proof,frame(1000)))
        assertNull(observe(proof,frame(1250,finished=true)))
        assertNull(observe(proof,frame(1500,finished=true,scene=TableScene.RESULT)))
    }

    @Test fun unconfirmedOutsideSingleAndAnAlreadyFinishedOpponentPairCannotQualify() {
        val active=snapshot()
        val missingSingle=active.copy(plays=active.plays+(Seat.OPPOSITE to active.plays.getValue(Seat.OPPOSITE).map {
            it.copy(cards=it.cards.dropLast(1)) }))
        val opponentsDone=active.copy(plays=active.plays+
            (Seat.PREVIOUS to listOf(PlayEvent("round","prev-full",10,Seat.PREVIOUS,List(27) { Card(Rank.THREE,Suit.RED) })))+
            (Seat.OPPOSITE to emptyList()))
        for(snapshot in listOf(missingSingle,opponentsDone)) {
            val proof=TerminalOwnHandProof()
            for(time in listOf(1000L,1250L,1500L)) assertNull(observe(proof,frame(time,finished=true),snapshot))
        }
    }

    @Test fun everyOutsideFrameIncludingUnknownMustBeDirectlyEmptyWithoutAGap() {
        for(mode in listOf("blind","weak","nonempty","glyphOnly","gap","timeGap","mask","ambiguity","scene","hand")) {
            val proof=TerminalOwnHandProof()
            assertNull(observe(proof,frame(1000)))
            var bad=frame(if(mode=="timeGap") 1501 else 1250,scene=TableScene.UNKNOWN)
            bad=when(mode) {
                "blind" -> bad.copy(plays=bad.plays+(Seat.PREVIOUS to ObservedPlay(emptyList(),1f,regionObserved=false)))
                "weak" -> bad.copy(plays=bad.plays+(Seat.PREVIOUS to ObservedPlay(emptyList(),.64f)))
                "nonempty" -> bad.copy(plays=bad.plays+(Seat.PREVIOUS to play(listOf(Card(Rank.J,Suit.RED)))))
                "glyphOnly" -> bad.copy(plays=bad.plays+(Seat.PREVIOUS to ObservedPlay(emptyList(),1f,play().glyphs)))
                "gap" -> bad.copy(captureGap=true)
                "mask" -> bad.copy(incompleteReason="悬浮条遮住出牌区",localizedOverlaySeats=setOf(Seat.SELF))
                "ambiguity" -> bad.copy(ambiguousPresentation=true)
                "scene" -> bad.copy(scene=TableScene.TEAMMATE)
                "hand" -> bad.copy(hand=null)
                else -> bad
            }
            assertNull(observe(proof,bad))
            // A broken path cannot start again from the same empty-hand version.
            for(time in listOf(1700L,1900L,2100L)) assertNull(observe(proof,frame(time,finished=true)))
        }
    }

    @Test fun rankNewSlotDuplicateSlotAndMovedSlotBreakThePhysicalAnchor() {
        val full=play()
        val duplicate=full.copy(glyphs=listOf(full.glyphs[0],full.glyphs[0])+full.glyphs.drop(2))
        val moved=full.copy(glyphs=full.glyphs.map { it.copy(box=it.box.copy(left=it.box.left+.07f,right=it.box.right+.07f)) })
        val invalid=full.copy(glyphs=full.glyphs.map { it.copy(box=it.box.copy(left=Float.NaN)) })
        for(bad in listOf(play(own+Card(Rank.TEN,Suit.RED)),play(List(4) { Card(Rank.J,Suit.RED) }),
            duplicate,moved,invalid,full.copy(glyphs=full.glyphs.dropLast(1)),play(emptyList()))) {
            val proof=TerminalOwnHandProof()
            assertNull(observe(proof,frame(1000)))
            assertNull(observe(proof,frame(1250,self=bad)))
            for(time in listOf(1500L,1750L,2000L)) assertNull(observe(proof,frame(time,finished=true)))
        }
    }

    @Test fun twoPostAnchorFullSourcesNeedTheirOwnSpanAndTheFinalPositiveFinishSignal() {
        val proof=TerminalOwnHandProof()
        assertNull(observe(proof,frame(1000,finished=true))) // Initial anchor is not a post-animation vote.
        assertNull(observe(proof,frame(1250)))
        repeat(3) { assertNull(observe(proof,frame(1250,finished=true))) }
        assertNull(observe(proof,frame(1330,finished=true)))
        assertEquals(own,observe(proof,frame(1350,finished=true))?.cards)
        val noFinish=TerminalOwnHandProof()
        for(time in listOf(1000L,1250L,1500L)) assertNull(observe(noFinish,frame(time)))
    }

    @Test fun firstAnchorDeadlineCannotBeExtendedAndSuspensionCannotCreateANewAnchor() {
        val proof=TerminalOwnHandProof()
        assertNull(observe(proof,frame(1000)))
        for(time in 1250L..2500L step 250L) assertNull(observe(proof,frame(time)))
        assertNull(observe(proof,frame(2501,finished=true)))
        val paused=snapshot().copy(phase=RoundPhase.RECOVERING,remaining=null)
        val noAnchor=TerminalOwnHandProof()
        for(time in listOf(1000L,1250L,1500L)) assertNull(observe(noAnchor,frame(time,finished=true),paused))
    }

    @Test fun eventHandVersionResetAndReturnedHandCannotReuseTheOldVotes() {
        for(mode in listOf("event","knownHand","reset","returnedHand","phase")) {
            val proof=TerminalOwnHandProof()
            val active=snapshot()
            assertNull(observe(proof,frame(1000),active))
            assertNull(observe(proof,frame(1250),active))
            val changed=if(mode=="event") active.copy(plays=active.plays+(Seat.PREVIOUS to
                active.plays.getValue(Seat.PREVIOUS).map { it.copy(eventId="changed") })) else active
            if(mode=="reset") proof.reset()
            val nextFrame=if(mode=="returnedHand") frame(1400,hand=own) else frame(1400,finished=true)
            assertNull(proof.observe(nextFrame,changed,if(mode=="knownHand") own.dropLast(1) else own,
                if(mode=="phase") RoundPhase.SELF_FINISHED else RoundPhase.ACTIVE))
            val paused=changed.copy(phase=RoundPhase.RECOVERING,remaining=null)
            assertNull(observe(proof,frame(1600,finished=true),paused))
        }
    }
}
