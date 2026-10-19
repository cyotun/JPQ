package com.cyotun.jpq.vision

import android.content.Context
import android.graphics.*
import com.cyotun.jpq.core.*
import org.tensorflow.lite.Interpreter
import java.nio.*
import java.security.MessageDigest
import kotlin.math.*
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.TensorBuffer

data class CardDetection(val card: Card,val confidence: Float,val box: RectF)
enum class DetectorProfile(val asset: String,val sha256: String,val width: Int,val height: Int,val anchors: Int) {
    FULL("cards.tflite","87493b13b285027b7ee0f727bc9ca5b9a2aec8c7e0e730e43a79417121f3a90c",1280,640,16800),
    PACKED_320("packed_320.tflite","c9863253fc04c956de0893df1b9a794973c8f714fb456cfd83bd491a58cfde84",320,320,2100),
    PACKED_640("packed_640.tflite","7d00e992bc0b8ddc2ac7b29fab5c4e1c52ec6768f749c7f41b4ff820ec211a21",640,640,8400)
}
class LocalCardDetector(context: Context,forceBackend: String?=null,val profile: DetectorProfile=DetectorProfile.FULL): AutoCloseable {
    private val power=context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    private val model=context.assets.open("models/${profile.asset}").use { it.readBytes() }.also {
        val sha=MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) }
        check(sha==profile.sha256)
    }
    private val buffer=ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder()).apply { put(model); rewind() }
    private var interpreter: Interpreter?=null
    private var compiled: CompiledModel?=null
    private var gpuInputs=emptyList<TensorBuffer>()
    private var gpuOutputs=emptyList<TensorBuffer>()
    val backend: String
    private val owner=Thread.currentThread()
    private var closed=false
    private val shape=intArrayOf(1,profile.height,profile.width,3)
    private val outputShape=intArrayOf(1,32,profile.anchors)
    private val w=shape[2]; private val h=shape[1]; private val n=outputShape[2]
    private val input=ByteBuffer.allocateDirect(w*h*3*4).order(ByteOrder.nativeOrder())
    private val output=ByteBuffer.allocateDirect(outputShape.reduce(Int::times)*4).order(ByteOrder.nativeOrder())
    private val floats=FloatArray(w*h*3)
    private val cpuOutput=FloatArray(32*n)
    var preprocessMs=0L; private set
    var inferenceMs=0L; private set
    var decodeMs=0L; private set
    var maxClassScore=0f; private set
    var cacheHit=false; private set
    var inferenceCount=0L; private set
    private val pixels=IntArray(w*h)
    private val previousPixels=IntArray(w*h)
    private var previousWidth=0
    private var previousHeight=0
    private var previousDetections: List<CardDetection>?=null
    private val canvasBitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
    private val canvas=Canvas(canvasBitmap)
    private val paint=Paint()
    init {
        if(forceBackend=="GPU") {
            check(profile==DetectorProfile.FULL) { "Packed GPU requires separate validation" }
            check(power.isInteractive) { "GPU validation requires an interactive display" }
        }
        // A native GPU constructor stalled on the supported phone even while
        // unlocked. It cannot be cancelled on this process's vision thread, so
        // historical qualification must never select it during app warm-up or
        // capture. GPU stays an explicit stopped-capture diagnostic operation.
        val wanted=forceBackend ?: "CPU"
        if(wanted=="GPU") {
            try {
                compiled=CompiledModel.create(context.assets,"models/${profile.asset}",CompiledModel.Options(Accelerator.GPU))
                gpuInputs=compiled!!.createInputBuffers(); gpuOutputs=compiled!!.createOutputBuffers()
                require(gpuInputs.size==1 && gpuOutputs.size==1)
            } catch(error: Exception) {
                releaseGpu()
                close(); throw error
            }
        }
        if(compiled==null) {
            try {
                interpreter=Interpreter(buffer,Interpreter.Options().setNumThreads(4).setUseXNNPACK(true))
                check(interpreter!!.getInputTensor(0).shape().contentEquals(shape)); check(interpreter!!.getOutputTensor(0).shape().contentEquals(outputShape))
            } catch(error: Throwable) { close(); throw error }
        }
        backend=if(compiled!=null) "GPU" else "CPU"
    }
    fun detect(source: Bitmap): List<CardDetection> {
        check(Thread.currentThread()===owner)
        check(!closed) { "Detector already closed" }
        check(compiled==null || power.isInteractive) { "GPU inference requires an interactive display" }
        val start=android.os.SystemClock.elapsedRealtime()
        val placement=LetterboxGeometry.fit(source.width,source.height,w,h)
        val scale=placement.scale; val rw=placement.width; val rh=placement.height
        val dx=placement.left.toFloat(); val dy=placement.top.toFloat()
        canvas.drawColor(Color.BLACK); canvas.drawBitmap(source,null,RectF(dx,dy,dx+rw,dy+rh),paint)
        canvasBitmap.getPixels(pixels,0,w,0,0,w,h)
        // These are complete model-input pixels, not a perceptual hash or a sampled ROI.
        // Fresh captures still pass through stability/ownership checks even when inference is identical.
        val cached=previousDetections
        if(cached!=null && previousWidth==source.width && previousHeight==source.height && pixels.contentEquals(previousPixels)) {
            preprocessMs=android.os.SystemClock.elapsedRealtime()-start; inferenceMs=0; decodeMs=0; cacheHit=true
            return cached.map { it.copy(box=RectF(it.box)) }
        }
        cacheHit=false
        var pos=0; for(p in pixels) { floats[pos++]=((p shr 16) and 255)/255f; floats[pos++]=((p shr 8) and 255)/255f; floats[pos++]=(p and 255)/255f }
        val prepared=android.os.SystemClock.elapsedRealtime(); preprocessMs=prepared-start
        val values: FloatArray
        if(compiled!=null) {
            gpuInputs[0].writeFloat(floats); compiled!!.run(gpuInputs,gpuOutputs); values=gpuOutputs[0].readFloat(); require(values.size==32*n)
        } else {
            input.rewind(); input.asFloatBuffer().put(floats); output.rewind(); interpreter!!.run(input,output); output.rewind(); output.asFloatBuffer().get(cpuOutput); values=cpuOutput
        }
        val inferred=android.os.SystemClock.elapsedRealtime(); inferenceMs=inferred-prepared
        inferenceCount++
        val proposals=ArrayList<CardDetection>()
        maxClassScore=0f
        for(i in 0 until n) {
            var score=0f; var label=0
            for(c in 0 until 28) { val v=values[(c+4)*n+i]; if(v>score) { score=v; label=c } }
            if(score>maxClassScore) maxClassScore=score
            if(score<.55f) continue
            val cx=values[i]*w; val cy=values[n+i]*h; val bw=values[2*n+i]*w; val bh=values[3*n+i]*h
            val box=RectF(((cx-bw/2-dx)/scale/source.width).coerceIn(0f,1f),((cy-bh/2-dy)/scale/source.height).coerceIn(0f,1f),
                ((cx+bw/2-dx)/scale/source.width).coerceIn(0f,1f),((cy+bh/2-dy)/scale/source.height).coerceIn(0f,1f))
            if(box.width()<=0 || box.height()<=0) continue
            proposals.add(CardDetection(card(label),score,box))
        }
        val selected=ArrayList<CardDetection>()
        for(p in proposals.sortedByDescending { it.confidence }) if(selected.none { iou(it.box,p.box)>.45f }) selected.add(p)
        decodeMs=android.os.SystemClock.elapsedRealtime()-inferred
        pixels.copyInto(previousPixels); previousWidth=source.width; previousHeight=source.height
        previousDetections=selected.map { it.copy(box=RectF(it.box)) }
        return selected
    }
    /** Owned diagnostic copy; callers must recycle it. Normal recognition never allocates this. */
    fun copyInputForProbe(): Bitmap {
        check(Thread.currentThread()===owner && !closed)
        return checkNotNull(canvasBitmap.copy(Bitmap.Config.ARGB_8888,false))
    }
    private fun card(label: Int): Card {
        if(label>=26) return Card(if(label==26) Rank.BIG else Rank.SMALL,Suit.NONE)
        val ranks=listOf(Rank.A,Rank.TWO,Rank.THREE,Rank.FOUR,Rank.FIVE,Rank.SIX,Rank.SEVEN,Rank.EIGHT,Rank.NINE,Rank.TEN,Rank.J,Rank.Q,Rank.K)
        return Card(ranks[label%13],if(label<13) Suit.RED else Suit.BLACK)
    }
    private fun iou(a: RectF,b: RectF): Float {
        val area=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
        return area/(a.width()*a.height()+b.width()*b.height()-area).coerceAtLeast(.000001f)
    }
    private fun releaseGpu() {
        val inputs=gpuInputs; val outputs=gpuOutputs; val model=compiled
        gpuInputs=emptyList(); gpuOutputs=emptyList(); compiled=null
        (inputs+outputs).forEach { buffer ->
            runCatching { buffer.close() }.onFailure { android.util.Log.w("JPQCapture","gpu_buffer_close_failed",it) }
        }
        runCatching { model?.close() }.onFailure { android.util.Log.w("JPQCapture","gpu_model_close_failed",it) }
    }
    override fun close() {
        check(Thread.currentThread()===owner) { "Detector must close on its owning thread" }
        if(closed) return
        closed=true
        val cpu=interpreter; interpreter=null
        runCatching { cpu?.close() }.onFailure { android.util.Log.w("JPQCapture","cpu_model_close_failed",it) }
        releaseGpu()
        previousDetections=null
        canvasBitmap.recycle()
    }
    companion object {
        const val MODEL_SHA="87493b13b285027b7ee0f727bc9ca5b9a2aec8c7e0e730e43a79417121f3a90c"
        const val RUNTIME_VERSION="2.2.0"
    }
}
