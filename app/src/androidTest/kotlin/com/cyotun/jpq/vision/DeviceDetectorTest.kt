package com.cyotun.jpq.vision

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.core.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class DeviceDetectorTest {
    @Test(timeout=45000) fun cpuGpuFixtureCorrectnessAndLatency() {
        val test=InstrumentationRegistry.getInstrumentation().context
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        app.getSharedPreferences("jpq-runtime",0).edit().remove("gpuFingerprint").remove("gpuRuntime").commit()
        val originals=List(4) { index -> test.assets.open("fixtures/case${index+1}.jpg").use { BitmapFactory.decodeStream(it) } }
        val source=originals+originals.map { Bitmap.createScaledBitmap(it,1280,588,true) }
        val reference=mutableListOf<List<CardDetection>>()
        val times=mutableMapOf<String,MutableList<Long>>()
        fun checkCase(index: Int,found: List<CardDetection>) {
            val g=found.filter { it.box.centerY()>.13 && it.box.centerX() in .10f.. .88f }.map { PlayGlyph(it.card,it.confidence,GlyphBox(it.box.left,it.box.top,it.box.right,it.box.bottom)) }
            val case=index%4
            val p=partitionGuandanHand(g); assertEquals("fixture=$index",listOf(27,25,26,25)[case],p.hand.size)
            val result=assignPlayGroups(p.table,emptySet(),emptyList()); assertTrue(result.ambiguous.isEmpty())
            when(case) {
                0 -> assertEquals(listOf(Card(Rank.FOUR,Suit.RED)),result.seats[Seat.NEXT]!!.map { it.card })
                1,3 -> { assertEquals(4,result.seats[Seat.PREVIOUS]!!.size); assertTrue(result.seats[Seat.OPPOSITE]!!.isEmpty()) }
                2 -> { assertEquals(listOf(Card(Rank.K,Suit.RED)),result.seats[Seat.SELF]!!.map { it.card }); assertEquals(listOf(Card(Rank.A,Suit.RED)),result.seats[Seat.OPPOSITE]!!.map { it.card }) }
            }
        }
        LocalCardDetector(app,"CPU").use { detector ->
            android.util.Log.i("JPQBench","cpu_start")
            source.forEachIndexed { index,bitmap -> val found=detector.detect(bitmap); checkCase(index,found); reference.add(found) }
            times["CPU"]=mutableListOf(); repeat(12) { val t=SystemClock.elapsedRealtime(); detector.detect(source[4+it%4]); times["CPU"]!!.add(SystemClock.elapsedRealtime()-t) }
        }
        val cpu=times["CPU"]!!.sorted()
        android.util.Log.i("JPQBench","CPU fixtures=8 capture=1280x588 median=${cpu[cpu.size/2]} p95=${cpu.last()} ms")
        android.util.Log.i("JPQBench","cpu_fixtures_passed gpu_construct_start")
        LocalCardDetector(app,"GPU").use { detector ->
            android.util.Log.i("JPQBench","gpu_ready")
            assertEquals("GPU",detector.backend)
            source.forEachIndexed { index,bitmap ->
                val found=detector.detect(bitmap); checkCase(index,found)
                assertEquals(reference[index].map { it.card.colorKey() }.sorted(),found.map { it.card.colorKey() }.sorted())
            }
            times["GPU"]=mutableListOf(); repeat(12) { val t=SystemClock.elapsedRealtime(); detector.detect(source[4+it%4]); times["GPU"]!!.add(SystemClock.elapsedRealtime()-t) }
        }
        val gpu=times["GPU"]!!.sorted()
        android.util.Log.i("JPQBench","CPU median=${cpu[cpu.size/2]} p95=${cpu.last()} GPU median=${gpu[gpu.size/2]} p95=${gpu.last()}")
        if(gpu[gpu.size/2]<cpu[cpu.size/2]) app.getSharedPreferences("jpq-runtime",0).edit().putString("gpuFingerprint",Build.FINGERPRINT).putString("gpuModel",LocalCardDetector.MODEL_SHA).putString("gpuRuntime",LocalCardDetector.RUNTIME_VERSION).commit()
        source.forEach { it.recycle() }
    }
}
