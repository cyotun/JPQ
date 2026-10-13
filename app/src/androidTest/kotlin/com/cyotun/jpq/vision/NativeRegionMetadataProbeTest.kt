package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aijpq.pokerrecorder.runtime.preprocess.*
import com.cyotun.jpq.AppState
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRegionMetadataProbeTest {
    @Test fun exportRegionGeometryForAnExplicitFixture() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("referenceNativeProbe")=="true")
        check(!AppState.running)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(checkNotNull(args.getString("fixturePath")))
        require(file.isAbsolute && file.isFile)
        val decoded=checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
        val source=checkNotNull(decoded.copy(Bitmap.Config.ARGB_8888,true)); decoded.recycle()
        try {
            NativeActiveRegionSession().use { session ->
                var result: NativePreprocessResult?=null
                val now=SystemClock.elapsedRealtime()
                repeat(4) { result=session.update(source,it+1L,now+100*it,NativePreprocessConfig()) }
                val data=checkNotNull(result)
                val json=JSONObject().put("fixture",file.name).put("width",data.frameWidth).put("height",data.frameHeight)
                    .put("nativeMs",data.metrics.totalMs).put("regions",JSONArray(data.regions.map { r ->
                        JSONObject().put("id",r.regionId).put("bbox",JSONArray(listOf(r.bbox.left,r.bbox.top,r.bbox.right,r.bbox.bottom)))
                            .put("samplePoints",r.samplePointCount).put("evidencePoints",r.evidencePointCount)
                            .put("source",r.source).put("areaType",r.areaType ?: JSONObject.NULL)
                            .put("recordState",r.recordState).put("supportRatio",r.supportRatio)
                    }))
                val folder=File(context.getExternalFilesDir(null),"reference-region-probe").apply { mkdirs() }
                File(folder,file.nameWithoutExtension+".json").writeText(json.toString(2))
            }
        } finally { source.recycle() }
    }
}
