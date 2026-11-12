package com.cyotun.jpq.core

import kotlin.test.*

class CaptureGapFenceTest {
    private class Frame(val epoch: Long,val capturedMs: Long=200L): AutoCloseable {
        override fun close() = Unit
    }

    @Test fun trackedRoundEligibilityIncludesRecoveryEvenThoughItsPoolIsUnavailable() {
        val eligible=setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED,RoundPhase.RECOVERING)
        for(phase in RoundPhase.entries) {
            assertEquals(phase in eligible,CaptureGapFence.hasTrackedRound(phase,"existing-round"),phase.name)
            for(roundId in listOf(null,""," ")) assertFalse(CaptureGapFence.hasTrackedRound(phase,roundId))
        }
        val hand=Rank.entries.filterNot { it.isJoker }.flatMap { rank ->
            listOf(Card(rank,Suit.RED),Card(rank,Suit.BLACK))
        }+Card(Rank.SMALL,Suit.NONE)
        val ledger=RoundLedger().apply { begin("recovering",hand) }
        ledger.suspendForRecovery("ordinary recognition uncertainty")
        val snapshot=ledger.snapshot()
        assertNull(snapshot.remaining)
        assertTrue(CaptureGapFence.hasTrackedRound(snapshot.phase,snapshot.roundId))
    }

    @Test fun replacementNewEpochFrameCheckpointsBeforeAnAlreadyQueuedDrainCanObserveIt() {
        val tasks=ArrayDeque<Runnable>()
        val guard=Any(); val owner=Any(); val fence=CaptureGapFence<Any>()
        val order=mutableListOf<String>()
        fun flush() = synchronized(guard) {
            fence.take(1L,owner)?.let { order.add("gap:${it.timeMs}") }
        }
        val dispatcher=LatestFrameDispatcher<Frame>({ tasks.add(it); true },{ frame ->
            synchronized(guard) { flush() }
            order.add("observe:${frame.epoch}")
        })
        dispatcher.offer(Frame(1L)) // Its drain is queued before the gap callback.
        synchronized(guard) { fence.mark(1L,owner,100L,"hidden",true) }
        tasks.add(Runnable { flush() })
        dispatcher.offer(Frame(3L)) // Visibility returned before the worker was scheduled.
        while(tasks.isNotEmpty()) tasks.removeFirst().run()
        assertEquals(listOf("gap:100","observe:3"),order)
        dispatcher.close()
    }

    @Test fun sameEpochPendingSourcesBeforeTheFaultStayRejectedAfterTheCheckpointWasTaken() {
        val tasks=ArrayDeque<Runnable>(); val owner=Any(); val fence=CaptureGapFence<Any>()
        val order=mutableListOf<String>(); val rejected=mutableListOf<Long>()
        fun flush() { fence.take(1L,owner)?.let { order.add("gap:${it.timeMs}") } }
        val dispatcher=LatestFrameDispatcher<Frame>({ tasks.add(it); true },{ frame ->
            flush()
            if(fence.acceptsSource(1L,owner,frame.capturedMs)) order.add("observe:${frame.capturedMs}")
            else rejected.add(frame.capturedMs)
        })
        dispatcher.offer(Frame(1L,900L))
        fence.mark(1L,owner,1000L,"copy failed",false)
        tasks.add(Runnable { flush() })
        while(tasks.isNotEmpty()) tasks.removeFirst().run()
        dispatcher.offer(Frame(1L,950L))
        while(tasks.isNotEmpty()) tasks.removeFirst().run()
        dispatcher.offer(Frame(1L,1000L))
        while(tasks.isNotEmpty()) tasks.removeFirst().run()
        dispatcher.offer(Frame(1L,1001L))
        while(tasks.isNotEmpty()) tasks.removeFirst().run()
        assertEquals(listOf(900L,950L,1000L),rejected)
        assertEquals(listOf("gap:1000","observe:1001"),order)
        dispatcher.close()
    }

    @Test fun callbackAndConsumerShareOnePendingGapAndRetainItsFirstSourceFence() {
        val owner=Any(); val fence=CaptureGapFence<Any>()
        fence.mark(1L,owner,100L,"copy failed",false)
        fence.mark(1L,owner,120L,"resized",true)
        val gap=assertNotNull(fence.take(1L,owner))
        assertEquals(100L,gap.timeMs)
        assertEquals("resized",gap.reason)
        assertTrue(gap.inputDiscontinuity)
        assertFalse(fence.acceptsSource(1L,owner,110L))
        assertFalse(fence.acceptsSource(1L,owner,120L))
        assertTrue(fence.acceptsSource(1L,owner,121L))
        assertNull(fence.take(1L,owner))
    }

    @Test fun laterFaultsAdvanceTheSourceCutoffAfterTakeWithoutLeakingAcrossOwnersOrSessions() {
        val previous=Any(); val next=Any(); val fence=CaptureGapFence<Any>()
        fence.mark(1L,previous,1000L,"first",false)
        assertNotNull(fence.take(1L,previous))
        fence.mark(1L,previous,1100L,"second",false)
        assertFalse(fence.acceptsSource(1L,previous,1050L))
        assertFalse(fence.acceptsSource(1L,previous,1100L))
        assertTrue(fence.acceptsSource(1L,previous,1101L))
        assertTrue(fence.acceptsSource(1L,next,1L))
        fence.mark(1L,next,10L,"replacement owner",false)
        assertNull(fence.take(1L,previous))
        assertTrue(fence.acceptsSource(1L,next,11L))
        assertTrue(fence.acceptsSource(3L,next,1L))
        fence.clear()
        assertTrue(fence.acceptsSource(1L,next,1L))
    }

    @Test fun oldCallbacksCannotConsumeANewSessionOrRecognizerGap() {
        val previous=Any(); val next=Any(); val fence=CaptureGapFence<Any>()
        fence.mark(1L,previous,100L,"old",true)
        fence.clear()
        fence.mark(3L,next,200L,"new",false)
        assertNull(fence.take(1L,previous))
        assertNull(fence.take(3L,previous))
        val gap=assertNotNull(fence.take(3L,next))
        assertEquals(200L,gap.timeMs)
        assertFalse(gap.inputDiscontinuity)
        fence.mark(3L,next,300L,"stop",true)
        fence.clear()
        assertNull(fence.take(3L,next))
    }
}
