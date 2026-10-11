package com.cyotun.jpq.vision

import android.content.Context
import android.graphics.*
import android.media.Image
import android.os.SystemClock
import com.cyotun.jpq.*
import com.cyotun.jpq.core.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/** Bundled CPU detector and bundled Chinese OCR. No screenshot upload. */
class TencentRecognizer(context: Context): FrameRecognizer {
    private val diagnostics=context.getSharedPreferences("jpq-diagnostics",Context.MODE_PRIVATE)
    private val diagnosticFolder=java.io.File(context.filesDir,"diagnostic")
    private var lastDiagnostic=0L
    private val detector=LocalCardDetector(context)
    private val ocr=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val repository=RoundRepository(context)
    private val observer=RoundObserver(AppState.ledger) { snapshot ->
        repository.save(AppState.ledger)
        android.util.Log.i("JPQRound","phase=${snapshot.phase} remaining=${snapshot.remaining?.values?.sum()} plays=${listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).map { snapshot.played(it) }}")
        AppState.message=when(snapshot.phase) {
            RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED -> "正在记牌"
            RoundPhase.UNAVAILABLE -> snapshot.reason ?: "等待下一局"
            else -> "进入游戏房间后，会自动开始记牌"
        }; AppState.notifyChanged()
    }
    override val ready=true
    private var ambiguousFrames=0
    private var closed=false
    override fun observe(image: Image) {
        if(closed) return
        if(image.width<=image.height) { observer.observe(TableFrame(SystemClock.elapsedRealtime(),TableScene.UNKNOWN,null)); return }
        val plane=image.planes[0]; val row=ByteArray(plane.rowStride); val pixels=IntArray(image.width*image.height)
        val data=plane.buffer.duplicate()
        for(y in 0 until image.height) {
            data.position(y*plane.rowStride); val length=minOf(row.size,data.remaining()); data.get(row,0,length)
            for(x in 0 until image.width) {
                val p=x*plane.pixelStride
                pixels[y*image.width+x]=Color.argb(255,row[p].toInt() and 255,row[p+1].toInt() and 255,row[p+2].toInt() and 255)
            }
        }
        val bitmap=Bitmap.createBitmap(image.width,image.height,Bitmap.Config.ARGB_8888).apply { setPixels(pixels,0,image.width,0,0,image.width,image.height) }
        val maskingCanvas=Canvas(bitmap); val maskingPaint=Paint().apply { color=Color.rgb(38,38,38) }
        listOfNotNull(AppState.barMask,AppState.adjustmentMask).forEach { mask ->
            if(mask.screenWidth==image.width && mask.screenHeight==image.height) maskingCanvas.drawRect(mask.rectangle,maskingPaint)
        }
        try {
            val detections=detector.detect(bitmap).filter { it.box.centerY()>.14f && it.box.width()<.12f && it.box.height()>.02f }
            // OCR is synchronous on the capture worker; failure is unobserved, never "empty".
            val text=runCatching { Tasks.await(ocr.process(InputImage.fromBitmap(bitmap,0)),1500,TimeUnit.MILLISECONDS) }.getOrNull()
            if(text==null) { observer.observe(TableFrame(SystemClock.elapsedRealtime(),TableScene.UNKNOWN,null)); return }
            val lines=text.textBlocks.flatMap { it.lines }
            val tableLines=lines.filter { (it.boundingBox?.centerY() ?: 0)>image.height*.12f }
            val all=tableLines.joinToString(" ") { it.text.replace(" ","") }
            val scene=when {
                listOf("进贡","还贡","还牌","贡牌").any { all.contains(it) } -> TableScene.TRIBUTE
                all.contains("队友手牌") || all.contains("对家手牌") -> TableScene.TEAMMATE
                listOf("再来一局","继续游戏","本局结束","本局结算").any { all.contains(it) } -> TableScene.RESULT
                listOf("快速开始","私人房","经典","组队").count { all.contains(it) }>=3 && detections.none { it.box.centerY()>.55f } -> TableScene.LOBBY
                detections.isNotEmpty() || all.contains("一键理") || (all.contains("理牌") && all.contains("聊天")) -> TableScene.TABLE
                else -> TableScene.UNKNOWN
            }
            // Same geometric upward search read in the reference Guandan script.
            val handDetections=detections.filter { it.box.centerY()>=.65f }.toMutableList()
            val remaining=detections.filter { it !in handDetections }.toMutableList()
            val avgHeight=detections.map { it.box.height() }.average().toFloat()
            val avgWidth=detections.map { it.box.width() }.average().toFloat()
            var frontier=handDetections.toList()
            while(frontier.isNotEmpty()) {
                val found=remaining.filter { p -> frontier.any { s ->
                    val dy=s.box.top-p.box.bottom
                    dy>=0 && dy<avgHeight*1.5f && kotlin.math.abs(p.box.centerX()-s.box.centerX())<avgWidth*.5f
                } }
                handDetections.addAll(found); remaining.removeAll(found.toSet()); frontier=found
            }
            val hand=handDetections.sortedWith(compareBy({ it.box.centerX() },{ it.box.centerY() })).map { it.card }
            val passing=tableLines.filter { it.text.contains("不出") || it.text.contains("不要") }.mapNotNull { l ->
                val b=l.boundingBox ?: return@mapNotNull null
                when { b.centerY()<image.height*.3 -> Seat.OPPOSITE; b.centerX()<image.width*.4 -> Seat.PREVIOUS; b.centerX()>image.width*.65 -> Seat.NEXT; else -> null }
            }.toSet()
            val removed=AppState.ledger.currentHand().toMutableList()
            val subset=hand.all { removed.remove(it) }
            val glyphs=remaining.filter { it.box.centerY()<.65f }.map { p -> PlayGlyph(p.card,p.confidence,GlyphBox(p.box.left,p.box.top,p.box.right,p.box.bottom)) }
            val ownership=assignPlayGroups(glyphs,passing,if(subset && scene!=TableScene.TEAMMATE && !AppState.adjusting) removed else emptyList())
            if(diagnostics.getBoolean("enabled",false) && SystemClock.elapsedRealtime()-lastDiagnostic>=2000) {
                android.util.Log.i("JPQVision","scene=$scene hand=${hand.map { it.colorKey() }} passing=$passing groups=${ownership.seats.mapValues { (_,p) -> p.map { it.card.colorKey() } }} ambiguous=${ownership.ambiguous.size}")
            }
            if(ownership.ambiguous.isNotEmpty() && scene !in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.TRIBUTE)) {
                ambiguousFrames++
                if(ambiguousFrames>=4 && AppState.ledger.snapshot().remaining!=null) observer.interrupted("出牌归属无法完整确认，等待下一局")
                observer.observe(TableFrame(SystemClock.elapsedRealtime(),scene,if(scene==TableScene.TEAMMATE || AppState.adjusting) null else hand))
                return
            }
            ambiguousFrames=0
            val observed=ownership.seats.mapValues { (_,cards) -> ObservedPlay(cards.sortedBy { it.box.cx }.map { it.card },cards.minOfOrNull { it.confidence } ?: 1f) }
            observer.observe(TableFrame(SystemClock.elapsedRealtime(),scene,if(scene==TableScene.TEAMMATE || AppState.adjusting) null else hand,observed))
        } finally {
            val now=SystemClock.elapsedRealtime()
            if(diagnostics.getBoolean("enabled",false) && now-lastDiagnostic>=2000) {
                lastDiagnostic=now
                runCatching {
                    diagnosticFolder.mkdirs()
                    val output=java.io.File(diagnosticFolder,"${System.currentTimeMillis()}.jpg")
                    output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,75,it) }
                    val files=diagnosticFolder.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
                    var size=files.sumOf { it.length() }
                    for(file in files) { if(size<=100L*1024*1024) break; val bytes=file.length(); if(file.delete()) size-=bytes }
                }
            }
            bitmap.recycle()
        }
    }
    override fun close() { if(!closed) { closed=true; repository.save(AppState.ledger); repository.close(); ocr.close(); detector.close() } }
}
