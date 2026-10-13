package com.cyotun.jpq.core

/** Image coverage evidence only. This cannot identify a card, a rank or an empty seat. */
object PackedCoverageEvidence {
    data class Result(val uncoveredSurface: Boolean,val sampleCount: Int=0,val box: GlyphBox?=null)

    /** Fresh neutral card-paper outside packed tiles requires a complete raster observation. */
    fun evaluate(pixels: IntArray,width: Int,height: Int,covered: List<GlyphBox>): Result {
        require(width>0 && height>0 && pixels.size==width*height)
        val eligible=BooleanArray(pixels.size)
        // Current Tencent card presentations are between its avatar columns.
        val left=(width*.135f).toInt(); val right=(width*.865f).toInt().coerceAtMost(width-1)
        val top=(height*.13f).toInt(); val bottom=(height*.55f).toInt().coerceAtMost(height-1)
        for(y in top..bottom) for(x in left..right) {
            val cx=(x+.5f)/width; val cy=(y+.5f)/height
            if(covered.any { cx>=it.left && cx<it.right && cy>=it.top && cy<it.bottom }) continue
            val rgb=pixels[y*width+x]
            val r=(rgb ushr 16) and 255; val g=(rgb ushr 8) and 255; val b=rgb and 255
            val lo=minOf(r,g,b); val hi=maxOf(r,g,b)
            eligible[y*width+x]=lo>=150 && hi-lo<=30
        }
        val queue=IntArray(pixels.size)
        for(y in top..bottom) for(x in left..right) {
            val seed=y*width+x
            if(!eligible[seed]) continue
            var read=0; var end=1; queue[0]=seed; eligible[seed]=false
            var minX=x; var maxX=x; var minY=y; var maxY=y
            while(read<end) {
                val index=queue[read++]; val px=index%width; val py=index/width
                minX=minOf(minX,px); maxX=maxOf(maxX,px); minY=minOf(minY,py); maxY=maxOf(maxY,py)
                for(dy in -1..1) for(dx in -1..1) {
                    val nx=px+dx; val ny=py+dy
                    if(nx !in left..right || ny !in top..bottom) continue
                    val next=ny*width+nx
                    if(eligible[next]) { eligible[next]=false; queue[end++]=next }
                }
            }
            if(end>=16 && maxX-minX+1>=4 && maxY-minY+1>=6)
                return Result(true,end,GlyphBox(minX.toFloat()/width,minY.toFloat()/height,
                    (maxX+1f)/width,(maxY+1f)/height))
        }
        return Result(false)
    }
}
