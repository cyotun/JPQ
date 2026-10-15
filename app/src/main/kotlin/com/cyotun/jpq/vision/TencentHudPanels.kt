package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.cyotun.jpq.core.TencentGradeTeam
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Actual source pixels, not a predicted HUD rectangle or a recognized rank. */
internal data class TencentHudPanel(
    val team: TencentGradeTeam,
    val header: Rect,
    val body: Rect,
    val active: Boolean,
)

/**
 * Finds the paired Tencent level plates when header words are not recognized.
 * The upper-left bounds only limit searching: connected foreground pixels must
 * establish both headers, both white bodies, and their common geometry.
 * Callers must reject covered panels and apply scene, freshness and mode rules.
 */
internal object TencentHudPanels {
    private const val BLUE=1
    private const val RED=2
    private const val GRAY=3
    private const val WHITE=4
    private data class Component(val tone: Int,val box: Rect,val area: Int) {
        val width get()=box.width()
        val height get()=box.height()
        val fill get()=area.toFloat()/(width*height)
    }
    private data class Plate(val header: Component,val body: Component)

    fun detect(bitmap: Bitmap): List<TencentHudPanel> {
        val width=bitmap.width; val height=bitmap.height
        if(width<=height || width<320 || height<180 || width>8192 || height>4096) return emptyList()
        val search=Rect((width*.055f).toInt(),0,ceil(width*.23f).toInt(),ceil(height*.13f).toInt())
        val sw=search.width(); val sh=search.height()
        val pixels=IntArray(sw*sh)
        bitmap.getPixels(pixels,0,sw,search.left,search.top,sw,sh)
        val tones=ByteArray(pixels.size) { pixelTone(pixels[it]).toByte() }
        val queue=IntArray(pixels.size)
        val components=ArrayList<Component>()
        for(seed in tones.indices) {
            val tone=tones[seed].toInt()
            if(tone==0) continue
            var read=0; var count=1; queue[0]=seed; tones[seed]=0
            var left=seed%sw; var right=left; var top=seed/sw; var bottom=top
            while(read<count) {
                val index=queue[read++]; val x=index%sw; val y=index/sw
                left=minOf(left,x); right=maxOf(right,x); top=minOf(top,y); bottom=maxOf(bottom,y)
                fun visit(next: Int) {
                    if(tones[next].toInt()==tone) { tones[next]=0; queue[count++]=next }
                }
                if(x>0) visit(index-1)
                if(x+1<sw) visit(index+1)
                if(y>0) visit(index-sw)
                if(y+1<sh) visit(index+sw)
            }
            // A component cut at the search edge is not a measured complete plate.
            if(left==0 || right==sw-1 || bottom==sh-1 || count<24) continue
            val component=Component(tone,Rect(left+search.left,top,right+search.left+1,bottom+1),count)
            if(component.width>=max(12f,width*.018f) && component.width<=width*.065f &&
                component.height>=max(7f,height*.012f) && component.height<=height*.09f)
                components.add(component)
        }
        val headers=components.filter { it.tone!=WHITE && it.height<=height*.06f &&
            it.width.toFloat()/it.height in 1.35f..3.5f && it.fill>=.55f }
        val bodies=components.filter { it.tone==WHITE && it.height>=height*.025f &&
            it.width.toFloat()/it.height in .75f..2.6f && it.fill>=.50f }
        val plates=headers.mapNotNull { header ->
            val tolerance=max(2f,header.width*.06f)
            val matches=bodies.filter { body ->
                abs(header.box.left-body.box.left)<=tolerance &&
                    abs(header.box.right-body.box.right)<=tolerance &&
                    body.box.top-header.box.bottom in 0..max(3f,height*.008f).toInt() &&
                    body.height.toFloat()/header.height in .75f..2.5f
            }
            matches.singleOrNull()?.let { Plate(header,it) }
        }
        val pairs=ArrayList<Pair<Plate,Plate>>()
        for(a in plates) for(b in plates) {
            if(a.header.box.left>=b.header.box.left) continue
            if(a.header.tone !in setOf(BLUE,GRAY) || b.header.tone !in setOf(RED,GRAY) ||
                (a.header.tone==GRAY && b.header.tone==GRAY)) continue
            val tolerance=max(2f,height*.006f)
            val gap=b.header.box.left-a.header.box.right
            if(gap<0 || gap>minOf(a.header.width,b.header.width)*.35f) continue
            if(minOf(a.header.width,b.header.width)<maxOf(a.header.width,b.header.width)*.85f ||
                minOf(a.header.height,b.header.height)<maxOf(a.header.height,b.header.height)*.80f ||
                minOf(a.body.height,b.body.height)<maxOf(a.body.height,b.body.height)*.80f) continue
            if(abs(a.header.box.top-b.header.box.top)>tolerance ||
                abs(a.header.box.bottom-b.header.box.bottom)>tolerance ||
                abs(a.body.box.top-b.body.box.top)>tolerance ||
                abs(a.body.box.bottom-b.body.box.bottom)>tolerance) continue
            pairs.add(a to b)
        }
        val pair=pairs.singleOrNull() ?: return emptyList()
        return listOf(
            TencentHudPanel(TencentGradeTeam.OWN,Rect(pair.first.header.box),Rect(pair.first.body.box),pair.first.header.tone==BLUE),
            TencentHudPanel(TencentGradeTeam.OTHER,Rect(pair.second.header.box),Rect(pair.second.body.box),pair.second.header.tone==RED),
        )
    }

    private fun pixelTone(pixel: Int): Int {
        if((pixel ushr 24 and 255)<220) return 0
        val r=pixel ushr 16 and 255; val g=pixel ushr 8 and 255; val b=pixel and 255
        val lo=minOf(r,g,b); val hi=maxOf(r,g,b)
        return when {
            r>=120 && r>g*1.25f && r>b*1.15f -> RED
            b>=100 && b>r*1.3f && b>=g*1.02f && hi-lo>=45 -> BLUE
            lo>=205 && hi-lo<=60 -> WHITE
            lo>=80 && hi<=205 && hi-lo<=25 -> GRAY
            else -> 0
        }
    }
}
