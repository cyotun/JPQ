package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

data class OcrLine(val text: String,val box: Rect?)
data class SceneText(val lines: List<OcrLine>,val timeMs: Long,val width: Int,val height: Int)
/** OCR never blocks card inference. Its owned immutable snapshot survives the next frame. */
class SceneOcr: AutoCloseable {
    private val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val busy=AtomicBoolean(false)
    private val resultLock=Any()
    @Volatile private var latest: SceneText?=null
    @Volatile private var closed=false
    private var requestedAt=0L
    fun request(bitmap: Bitmap,now: Long) {
        if(closed || now-requestedAt<250 || !busy.compareAndSet(false,true)) return
        requestedAt=now
        var copy: Bitmap?=null
        try {
            val owned=bitmap.copy(Bitmap.Config.ARGB_8888,false)
            copy=owned
            recognizer.process(InputImage.fromBitmap(owned,0)).addOnCompleteListener { task ->
                try {
                    if(task.isSuccessful) {
                        val result=task.result
                        val text=SceneText(result.textBlocks.flatMap { it.lines }.map { OcrLine(it.text.replace(" ",""),it.boundingBox?.let(::Rect)) },now,owned.width,owned.height)
                        synchronized(resultLock) { if(!closed) latest=text }
                    }
                } catch(error: Exception) { android.util.Log.w("JPQVision","ocr_result_unavailable",error) }
                finally { owned.recycle(); busy.set(false) }
            }
        } catch(error: Throwable) {
            copy?.recycle(); busy.set(false)
            if(error !is Exception) throw error
            android.util.Log.w("JPQVision","ocr_request_unavailable",error)
        }
    }
    fun snapshot(width: Int,height: Int,now: Long): SceneText? = synchronized(resultLock) {
        if(closed) null else latest?.takeIf { it.width==width && it.height==height && now-it.timeMs in 0L..1500L }
    }
    override fun close() {
        synchronized(resultLock) {
            if(closed) return
            closed=true; latest=null
        }
        runCatching { recognizer.close() }.onFailure { android.util.Log.w("JPQVision","ocr_close_failed",it) }
    }
}
