package com.cyotun.jpq.core

import kotlin.test.*

/** Real observer/ledger plus dispatcher: a late fault fences queued and later stale pixels. */
class C411CaptureRecoveryIntegrationTest {
    private class Source(val frame: TableFrame): AutoCloseable { override fun close() = Unit }
    private val hand=Rank.entries.filterNot { it.isJoker || it==Rank.Q || it==Rank.J }.flatMap {
        listOf(Card(it,Suit.RED),Card(it,Suit.BLACK),Card(it,Suit.RED))
    }.take(27)
    private fun play(rank: Rank)=ObservedPlay(listOf(Card(rank,Suit.RED)),.95f,
        listOf(PlayGlyph(Card(rank,Suit.RED),.95f,GlyphBox(.73f,.30f,.75f,.35f))))
    private fun frame(time: Long,rank: Rank?=Rank.Q)=TableFrame(time,TableScene.TABLE,hand,
        Seat.entries.associateWith { if(it==Seat.NEXT && rank!=null) play(rank) else ObservedPlay(emptyList(),1f) })

    @Test fun secondFaultCannotResumeFromQueuedOldPixelsOrJoinTheEarlierThreeVotes() {
        for(callbackFirst in listOf(false,true)) {
            val ledger=RoundLedger().apply { begin("fenced",hand) }
            val observer=RoundObserver(ledger)
            observer.observe(frame(100,null)); observer.observe(frame(200,null))
            for(t in listOf(300L,400L,500L,600L)) observer.observe(frame(t))
            val original=ledger.snapshot();val originalHand=ledger.currentHand()
            assertEquals(1,original.played(Seat.NEXT))
            observer.incomplete("first gap",700,true)
            for(t in listOf(800L,900L,1000L)) observer.observe(frame(t))
            assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)

            val tasks=ArrayDeque<Runnable>();val owner=Any();val fence=CaptureGapFence<Any>()
            var checkpoints=0;val discarded=mutableListOf<Long>()
            fun flush() {
                fence.take(1,owner)?.let { observer.incomplete(it.reason,it.timeMs,true); checkpoints++ }
            }
            val dispatcher=LatestFrameDispatcher<Source>({ tasks.add(it);true },{ source ->
                flush()
                if(fence.acceptsSource(1,owner,source.frame.timeMs)) observer.observe(source.frame)
                else discarded.add(source.frame.timeMs)
            })
            if(callbackFirst) {
                fence.mark(1,owner,1050,"second gap",false)
                tasks.add(Runnable { flush() })
                dispatcher.offer(Source(frame(1020)))
            } else {
                dispatcher.offer(Source(frame(1020)))
                fence.mark(1,owner,1050,"second gap",false)
                tasks.add(Runnable { flush() })
            }
            while(tasks.isNotEmpty()) tasks.removeFirst().run()
            dispatcher.offer(Source(frame(1040)))
            while(tasks.isNotEmpty()) tasks.removeFirst().run()
            assertEquals(listOf(1020L,1040L),discarded)
            assertEquals(1,checkpoints)
            assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
            assertEquals(original.plays,ledger.snapshot().plays)
            assertEquals(originalHand,ledger.currentHand())
            for(t in listOf(1060L,1160L,1260L)) {
                dispatcher.offer(Source(frame(t)))
                while(tasks.isNotEmpty()) tasks.removeFirst().run()
                assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
            }
            dispatcher.offer(Source(frame(1360)))
            while(tasks.isNotEmpty()) tasks.removeFirst().run()
            assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
            assertEquals(original.plays,ledger.snapshot().plays)
            assertEquals(original.remaining,ledger.snapshot().remaining)
            assertEquals(originalHand,ledger.currentHand())
            for(t in listOf(1460L,1560L,1660L,1760L)) observer.observe(frame(t,Rank.J))
            assertEquals(2,ledger.snapshot().played(Seat.NEXT))
            assertEquals(listOf(Card(Rank.J,Suit.RED)),ledger.snapshot().plays.getValue(Seat.NEXT).last().cards)
            dispatcher.close()
        }
    }
}
