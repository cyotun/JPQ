package com.cyotun.jpq.core

import java.io.File
import javax.imageio.ImageIO

/** Private manually bounded header fixtures, independent of Android/OCR. */
fun main(args: Array<String>) {
    require(args.size==1) { "Expected a private TSV annotation file" }
    val rows=File(args[0]).readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }
    var failures=0
    rows.forEachIndexed { index,line ->
        val parts=line.split('\t'); require(parts.size==6)
        val source=ImageIO.read(File(parts[0])) ?: error("Unreadable fixture")
        val left=parts[1].toInt(); val top=parts[2].toInt(); val right=parts[3].toInt(); val bottom=parts[4].toInt()
        require(left>=0 && top>=0 && right<=source.width && bottom<=source.height && right>left && bottom>top)
        val pixels=source.getRGB(left,top,right-left,bottom-top,null,0,right-left)
        val result=tencentGradeHeaderTone(right-left,bottom-top,pixels)
        val expected=TencentGradeHeaderTone.valueOf(parts[5])
        println("fixture${index+1}: expected=$expected actual=$result pixels=${pixels.size}")
        if(result!=expected) failures++
    }
    println("grade_header_fixtures=${rows.size} mismatches=$failures; manual_ROI_development_samples_only")
    check(failures==0)
}
