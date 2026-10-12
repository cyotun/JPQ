package com.cyotun.jpq.core

import kotlin.test.*

class LatestFrameDispatcherTest {
    private class Frame(val id: Int): AutoCloseable {
        var releases=0
        override fun close() { releases++ }
    }
    @Test fun slowConsumerOnlyReceivesNewestPendingFrame() {
        val tasks=ArrayDeque<Runnable>(); val received=mutableListOf<Int>()
        val queue=LatestFrameDispatcher<Frame>({ tasks.add(it); true },{ received.add(it.id) })
        val frames=(1..100).map { Frame(it) }
        frames.forEach(queue::offer)
        assertEquals(1,tasks.size)
        assertTrue(frames.dropLast(1).all { it.releases==1 })
        tasks.removeFirst().run()
        assertEquals(listOf(100),received)
        assertTrue(frames.all { it.releases==1 })
        queue.close()
    }
    @Test fun captureDuringConsumptionWaitsAndIsReleasedAfterUse() {
        val tasks=ArrayDeque<Runnable>(); val received=mutableListOf<Int>()
        val first=Frame(1); val second=Frame(2); val third=Frame(3)
        lateinit var queue: LatestFrameDispatcher<Frame>
        queue=LatestFrameDispatcher({ tasks.add(it); true },{
            assertEquals(0,it.releases); received.add(it.id)
            if(it===first) { queue.offer(second); queue.offer(third) }
        })
        queue.offer(first); tasks.removeFirst().run()
        assertEquals(1,first.releases); assertEquals(1,second.releases); assertEquals(0,third.releases)
        assertEquals(1,tasks.size); tasks.removeFirst().run()
        assertEquals(listOf(1,3),received); assertEquals(1,third.releases)
        queue.close()
    }
    @Test fun shutdownRejectsNewFramesAndRetiresThePendingFrame() {
        val tasks=ArrayDeque<Runnable>(); var calls=0
        val queue=LatestFrameDispatcher<Frame>({ tasks.add(it); true },{ calls++ })
        val pending=Frame(1); val late=Frame(2)
        queue.offer(pending); queue.close(); queue.close(); queue.offer(late)
        tasks.removeFirst().run()
        assertEquals(0,calls); assertEquals(1,pending.releases); assertEquals(1,late.releases)
    }
    @Test fun failedProcessingOrRejectedSchedulerCannotLeakFrames() {
        val tasks=ArrayDeque<Runnable>(); var failures=0
        val queue=LatestFrameDispatcher<Frame>({ tasks.add(it); true },{ error("inference failure") },{ failures++ })
        val frame=Frame(1); queue.offer(frame); tasks.removeFirst().run()
        assertEquals(1,failures); assertEquals(1,frame.releases)
        queue.close()
        val rejected=Frame(2)
        LatestFrameDispatcher<Frame>({ false },{ fail("must not be called") }).use { it.offer(rejected) }
        assertEquals(1,rejected.releases)
    }
}
