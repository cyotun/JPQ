package com.cyotun.jpq.core

import org.junit.Test
import org.junit.Assert.*
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoundedWriterTest {
    @Test fun slowStorageDoesNotBlockProducerAndCloseDrainsInOrder() {
        val entered=CountDownLatch(1); val unblock=CountDownLatch(1)
        val output=Collections.synchronizedList(mutableListOf<Int>())
        val producerDone=CountDownLatch(1)
        val writer=BoundedWriter<Int>("test-ordered",8,write={
            if(it==1) { entered.countDown(); check(unblock.await(3,TimeUnit.SECONDS)) }
            output.add(it)
        })
        try {
            writer.offer(1); assertTrue(entered.await(2,TimeUnit.SECONDS))
            Thread { writer.offer(2); writer.offer(3); writer.close(); producerDone.countDown() }.start()
            assertTrue(producerDone.await(1,TimeUnit.SECONDS))
            assertFalse(writer.awaitClosed(10)); assertFalse(writer.offer(4))
            unblock.countDown(); assertTrue(writer.awaitClosed(2000))
            assertEquals(listOf(1,2,3),output)
        } finally { unblock.countDown(); writer.close(); writer.awaitClosed(2000) }
    }
    @Test fun fullQueueReportsDroppedRecordRatherThanGrowingOrBlocking() {
        val entered=CountDownLatch(1); val unblock=CountDownLatch(1)
        val output=Collections.synchronizedList(mutableListOf<Int>())
        val lost=mutableListOf<Int>()
        val writer=BoundedWriter<Int>("test-bounded",2,write={
            if(it==0) { entered.countDown(); check(unblock.await(3,TimeUnit.SECONDS)) }; output.add(it)
        },dropped={ lost.add(it) })
        try {
            writer.offer(0); assertTrue(entered.await(2,TimeUnit.SECONDS))
            (1..4).forEach { writer.offer(it) }
            writer.close(); unblock.countDown(); assertTrue(writer.awaitClosed(2000))
            assertEquals(listOf(1,2),lost); assertEquals(listOf(0,3,4),output)
        } finally { unblock.countDown(); writer.close(); writer.awaitClosed(2000) }
    }
    @Test fun keyedCheckpointsKeepLatestCompleteRoundAndRetainOtherRounds() {
        val entered=CountDownLatch(1); val unblock=CountDownLatch(1)
        val output=Collections.synchronizedList(mutableListOf<Pair<String,Int>>())
        val writer=BoundedWriter<Pair<String,Int>>("test-checkpoint",3,key={ it.first },write={
            if(it.first=="busy") { entered.countDown(); check(unblock.await(3,TimeUnit.SECONDS)) }; output.add(it)
        })
        try {
            writer.offer("busy" to 0); assertTrue(entered.await(2,TimeUnit.SECONDS))
            writer.offer("round1" to 1); writer.offer("round1" to 27); writer.offer("round2" to 1)
            writer.close(); unblock.countDown(); assertTrue(writer.awaitClosed(2000))
            assertEquals(listOf("busy" to 0,"round1" to 27,"round2" to 1),output)
        } finally { unblock.countDown(); writer.close(); writer.awaitClosed(2000) }
    }
    @Test fun failedWriteStillDrainsLaterRecordsAndClosesResourceOnce() {
        val written=mutableListOf<Int>(); var failures=0; var closed=0
        val writer=BoundedWriter<Int>("test-failure",4,write={ if(it==1) error("disk"); written.add(it) },
            failed={ failures++ },finished={ closed++ })
        writer.offer(1); writer.offer(2); writer.close(); writer.close()
        assertTrue(writer.awaitClosed(2000)); assertEquals(listOf(2),written)
        assertEquals(1,failures); assertEquals(1,closed)
    }
}
