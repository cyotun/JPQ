package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.cyotun.jpq.core.GlyphBox
import com.cyotun.jpq.core.SceneHint
import com.cyotun.jpq.core.tencentGradeSymbolTokens
import com.cyotun.jpq.core.tencentGradeHudToken
import java.util.concurrent.atomic.AtomicBoolean

data class OcrLine(val text: String,val box: Rect?)
data class SceneText(val lines: List<OcrLine>,val timeMs: Long,val width: Int,val height: Int,val visibilityEpoch: Long=0L,
                     val gradeElements: List<OcrLine> = emptyList(),
                     val metadataElements: List<OcrLine> = emptyList(),
                     val remainingCountPixels: RemainingCountPixels? = null,
                     val remainingCountElements: List<OcrLine> = emptyList())
/** OCR never blocks card inference. Its owned immutable snapshot survives the next frame. */
class SceneOcr: AutoCloseable {
    private val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val busy=AtomicBoolean(false)
    private val resultLock=Any()
    @Volatile private var latest: SceneText?=null
    @Volatile private var closed=false
    private var requestedAt=0L
    fun request(bitmap: Bitmap,now: Long,visibilityEpoch: Long=0L,captureRemainingCounts: Boolean=false) {
        if(closed || now-requestedAt<250 || !busy.compareAndSet(false,true)) return
        requestedAt=now
        var copy: SceneOcrImage?=null
        try {
            val sourceWidth=bitmap.width; val sourceHeight=bitmap.height
            // Ordinary play/startup keeps its original allocation path. These
            // optional patches are needed only while reconciling an interruption.
            val countPixels=if(captureRemainingCounts) RemainingCountPixels.capture(bitmap) else null
            val owned=SceneOcrImage(bitmap,includeRemainingCounts=captureRemainingCounts)
            copy=owned
            recognizer.process(InputImage.fromBitmap(owned.bitmap,0)).addOnCompleteListener { task ->
                try {
                    if(task.isSuccessful) {
                        val result=task.result
                        val elements=result.textBlocks.flatMap { it.lines }.flatMap { it.elements }
                        // Use the recognizer's actual character boxes for merged
                        // HUD points such as JJ. Never divide a word box into
                        // guessed equal halves, and never split 10 into 1/0.
                        val hudSymbols=elements.filter { owned.toSource(it.boundingBox)!=null && !owned.isRemainingCount(it.boundingBox) }.flatMap { element ->
                            tencentGradeSymbolTokens(element.symbols.map { symbol ->
                                SceneHint(symbol.text,owned.symbolToSource(symbol.boundingBox,element.boundingBox)?.let { b -> GlyphBox(
                                    b.left.toFloat()/sourceWidth,b.top.toFloat()/sourceHeight,
                                    b.right.toFloat()/sourceWidth,b.bottom.toFloat()/sourceHeight) })
                            })
                        }.map { token ->
                            val b=token.box!!
                            OcrLine(token.text,Rect((b.left*sourceWidth).toInt(),(b.top*sourceHeight).toInt(),
                                kotlin.math.ceil(b.right*sourceWidth).toInt(),kotlin.math.ceil(b.bottom*sourceHeight).toInt()))
                        }
                        val text=SceneText(result.textBlocks.flatMap { it.lines }.filter { owned.isScene(it.boundingBox) }.map { line ->
                            OcrLine(line.text.replace(" ",""),owned.toSource(line.boundingBox))
                        },now,sourceWidth,sourceHeight,visibilityEpoch,
                            elements
                                .filter { owned.isScene(it.boundingBox) && it.text.replace(" ","")=="级" }.map { element ->
                                    OcrLine("级",owned.toSource(element.boundingBox))
                                },
                            (elements.filter { !owned.isRemainingCount(it.boundingBox) }.map { OcrLine(it.text,owned.toSource(it.boundingBox)) }+
                                result.textBlocks.flatMap { it.lines }.filter { !owned.isScene(it.boundingBox) && !owned.isRemainingCount(it.boundingBox) }
                                    .map { OcrLine(it.text,owned.toSource(it.boundingBox)) })
                                .filter { element ->
                                    val compact=tencentGradeHudToken(element.text).uppercase()
                                    element.box!=null && (compact in setOf("我方","对方","我方对方","2","3","4","5","6","7","8","9","10","J","Q","K","A") ||
                                        Regex("^(?:我方|对方)(?:[2-9AJQK]|10)$").matches(compact))
                                }.map { element ->
                                    OcrLine(tencentGradeHudToken(element.text),element.box)
                                }.distinct()+hudSymbols,remainingCountPixels=countPixels,
                            remainingCountElements=result.textBlocks.flatMap { it.lines }.filter { owned.isRemainingCount(it.boundingBox) }
                                .map { OcrLine(it.text.replace(" ",""),owned.toSource(it.boundingBox)) })
                        synchronized(resultLock) { if(!closed) latest=text }
                    }
                } catch(error: Exception) { android.util.Log.w("JPQVision","ocr_result_unavailable",error) }
                finally { owned.close(); busy.set(false) }
            }
        } catch(error: Throwable) {
            copy?.close(); busy.set(false)
            if(error !is Exception) throw error
            android.util.Log.w("JPQVision","ocr_request_unavailable",error)
        }
    }
    fun snapshot(width: Int,height: Int,now: Long,visibilityEpoch: Long=0L): SceneText? = synchronized(resultLock) {
        if(closed) null else latest?.takeIf {
            it.width==width && it.height==height && it.visibilityEpoch==visibilityEpoch && now-it.timeMs in 0L..1500L
        }
    }
    override fun close() {
        synchronized(resultLock) {
            if(closed) return
            closed=true; latest=null
        }
        runCatching { recognizer.close() }.onFailure { android.util.Log.w("JPQVision","ocr_close_failed",it) }
    }
}
