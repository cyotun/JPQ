package com.cyotun.jpq.vision

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.core.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TerminalSceneTest {
    @Test fun actualLastPlaceBannerClassifiesRevealBeforeItCanBecomeAPlay() {
        val context=InstrumentationRegistry.getInstrumentation().context
        val original=context.assets.open("fixtures/terminal-reveal.jpg").use { BitmapFactory.decodeStream(it) }
        val images=listOf(original,Bitmap.createScaledBitmap(original,1280,588,true))
        val client=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
          for(bitmap in images) {
            val text=Tasks.await(client.process(InputImage.fromBitmap(bitmap,0)),15,TimeUnit.SECONDS)
            val hints=text.textBlocks.flatMap { it.lines }.map { line -> SceneHint(line.text.replace(" ",""),line.boundingBox?.let { b ->
                GlyphBox(b.left.toFloat()/bitmap.width,b.top.toFloat()/bitmap.height,b.right.toFloat()/bitmap.width,b.bottom.toFloat()/bitmap.height)
            }) }
            hints.filter { it.text in setOf("头游","二游","三游","末游") }
                .forEach { android.util.Log.i("JPQOcrTest","width=${bitmap.width} hint=${it.text} box=${it.box}") }
            assertTrue("width=${bitmap.width} self finish",tencentSelfFinished(hints))
            assertEquals("width=${bitmap.width} "+hints.map { it.text }.filter { it.contains("游") }.toString(),TableScene.RESULT,tencentScene(hints,true,false))
          }
        } finally { client.close(); images.forEach { it.recycle() } }
    }
}
