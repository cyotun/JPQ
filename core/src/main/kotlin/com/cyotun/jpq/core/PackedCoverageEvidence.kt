package com.cyotun.jpq.core

/** Image coverage evidence only. This cannot identify a card, a rank or an empty seat. */
object PackedCoverageEvidence {
    data class Result(val uncoveredSurface: Boolean,val sampleCount: Int=0,val box: GlyphBox?=null)

    /** Fresh neutral card-paper outside packed tiles requires a complete raster observation. */
    fun evaluate(pixels: IntArray,width: Int,height: Int,covered: List<GlyphBox>): Result {
        require(width>0 && height>0 && pixels.size==width*height)
        val neutral=BooleanArray(pixels.size)
        val eligible=BooleanArray(pixels.size)
        val visited=BooleanArray(pixels.size)
        // Current Tencent card presentations are between its avatar columns.
        val left=(width*.135f).toInt(); val right=(width*.865f).toInt().coerceAtMost(width-1)
        val top=(height*.13f).toInt(); val bottom=(height*.55f).toInt().coerceAtMost(height-1)
        // Keep the full source component, including covered pixels and paper beyond
        // the table-area crop, so clipping cannot erase the core supporting a thin tail.
        for(index in pixels.indices) {
            val rgb=pixels[index]
            val r=(rgb ushr 16) and 255; val g=(rgb ushr 8) and 255; val b=rgb and 255
            val lo=minOf(r,g,b); val hi=maxOf(r,g,b)
            neutral[index]=lo>=150 && hi-lo<=30
        }
        for(y in top..bottom) for(x in left..right) {
            val index=y*width+x
            if(!neutral[index]) continue
            val cx=(x+.5f)/width; val cy=(y+.5f)/height
            eligible[index]=covered.none { cx>=it.left && cx<it.right && cy>=it.top && cy<it.bottom }
        }
        val queue=IntArray(pixels.size)
        // First qualify complete neutral components. Small thin UI strokes have no
        // 3x3 core; a large thin outline remains conservative possible-paper evidence.
        for(y in top..bottom) for(x in left..right) {
            val seed=y*width+x
            if(!eligible[seed] || visited[seed]) continue
            var read=0; var end=1; queue[0]=seed; visited[seed]=true
            var minX=x; var maxX=x; var minY=y; var maxY=y
            var solid=false
            while(read<end) {
                val index=queue[read++]; val px=index%width; val py=index/width
                minX=minOf(minX,px); maxX=maxOf(maxX,px); minY=minOf(minY,py); maxY=maxOf(maxY,py)
                if(!solid && px>0 && px<width-1 && py>0 && py<height-1) {
                    solid=true
                    for(dy in -1..1) for(dx in -1..1) {
                        if(!neutral[index+dy*width+dx]) solid=false
                    }
                }
                for(dy in -1..1) for(dx in -1..1) {
                    val nx=px+dx; val ny=py+dy
                    if(nx !in 0 until width || ny !in 0 until height) continue
                    val next=ny*width+nx
                    if(neutral[next] && !visited[next]) { visited[next]=true; queue[end++]=next }
                }
            }
            val largeContour=maxX-minX+1>=12 && maxY-minY+1>=16
            if(!solid && !largeContour) for(index in 0 until end) eligible[queue[index]]=false
        }
        // Then keep the original thresholds on each connected outside remainder.
        // Disconnected small tails must not be combined into a larger invented surface.
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
