package com.cyotun.jpq.vision

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aijpq.pokerrecorder.runtime.preprocess.*
import com.cyotun.jpq.AppState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Explicit geometry experiment. Rectangles belong to JPQ, not a recovered game config. */
@RunWith(AndroidJUnit4::class)
class NativeHandScanProbeTest {
    @Test fun inspectTheOriginalSpecialHandScanner() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativeHandScanProbe")=="true")
        check(!AppState.running)
        val instrument=InstrumentationRegistry.getInstrumentation()
        val result=CompletableFuture<Unit>()
        check(VisionRuntime.handler.post {
            try {
                val bitmap=instrument.context.assets.open("fixtures/case1.jpg").use { BitmapFactory.decodeStream(it) }
                try {
                    NativeActiveRegionSession().use { session ->
                        val time=SystemClock.elapsedRealtime()
                        var regular: NativePreprocessResult?=null
                        repeat(4) { regular=session.update(bitmap,it+1L,time+it*100L,NativePreprocessConfig()) }
                        val rows=JSONArray()
                        fun describe(regions: List<ActiveRegion>)=JSONArray(regions.map { r ->
                            JSONObject().put("id",r.regionId).put("bbox",JSONArray(listOf(r.bbox.left,r.bbox.top,r.bbox.right,r.bbox.bottom)))
                                .put("sample",r.samplePointCount).put("source",r.source).put("area",r.areaType ?: JSONObject.NULL)
                        })
                        val rectangles=listOf(doubleArrayOf(.10,.58,.78,.32),doubleArrayOf(.10,.35,.78,.55))
                        rectangles.forEach { rect ->
                            val scan=session.detectHandRegions(4,time+300,rect)
                            rows.put(JSONObject().put("rect",JSONArray(rect.toList())).put("regions",describe(scan.regions))
                                .put("scanMs",scan.metrics.totalMs))
                        }
                        val output=File(instrument.targetContext.getExternalFilesDir(null),"reference-hand-scan").apply { mkdirs() }
                        File(output,"probe.json").writeText(JSONObject().put("kind","special_scan_geometry_only")
                            .put("configuredRectsOrigin","JPQ_explicit_probe_not_original_game_configuration")
                            .put("width",bitmap.width).put("height",bitmap.height).put("regular",describe(regular!!.regions))
                            .put("scans",rows).toString(2))
                    }
                } finally { bitmap.recycle() }
                result.complete(Unit)
            } catch(error: Throwable) { result.completeExceptionally(error) }
        })
        result.get(30,TimeUnit.SECONDS)
    }
}
