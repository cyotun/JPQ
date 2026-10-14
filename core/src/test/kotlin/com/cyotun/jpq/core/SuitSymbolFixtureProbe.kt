package com.cyotun.jpq.core

import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Offline private-image probe; annotations must be independently read from the source image.
 * Four columns: id, cropPath, manually read suit or NONE, rank color.
 * Ten/eleven columns: id, screenshotPath, suit or NONE, rank color, HAND/TABLE,
 * normalized rank left/top/right/bottom, rank confidence, optional normalized masks.
 * Masks use left,top,right,bottom;left,top,right,bottom. Full-image mode independently
 * reproduces the production crop geometry, so a carefully chosen manual crop cannot hide
 * a misplaced production ROI. This is offline JVM verification, not Android runtime QA.
 */
fun main(args: Array<String>) {
    require(args.size in 1..2) { "Pass a private TSV annotation file and optional output report" }
    var accepted = 0; var abstained = 0; var negative = 0; var wrong = 0
    val output=mutableListOf<String>()
    fun record(line: String) { output.add(line); println(line) }
    File(args[0]).readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
        val cells = line.split('\t'); require(cells.size==4 || cells.size in 10..11) { "Invalid annotation column count" }
        val image = requireNotNull(ImageIO.read(File(cells[1]))) { "Image could not be decoded: ${cells[0]}" }
        val expected = cells[2].takeUnless { it == "NONE" }?.let(Suit::valueOf)
        val color=Suit.valueOf(cells[3])
        val (pixels,geometry)=if(cells.size==4) {
            SuitSymbolPixels(image.width,image.height,image.getRGB(0,0,image.width,image.height,null,0,image.width),color) to "manual"
        } else fixtureScreenshotCrop(image,cells,color)
        val result = SuitSymbolClassifier.classify(pixels)
        when {
            result.suit != null && result.suit != expected -> wrong++
            result.suit != null -> accepted++
            expected == null -> negative++
            else -> abstained++
        }
        record("${cells[0]}\tmanual=$expected\tprediction=${result.suit}\tconfidence=${result.confidence}\tmargin=${result.margin}\tgold=${result.goldHeart}\treason=${result.reason}\troi=$geometry")
    }
    record("accepted=$accepted abstained=$abstained negativeRejected=$negative wrong=$wrong")
    if(args.size==2) File(args[1]).apply { parentFile?.mkdirs(); writeText(output.joinToString("\n")+"\n",Charsets.UTF_8) }
    check(wrong == 0) { "A suit was read incorrectly; do not qualify the reader" }
}

private fun fixtureScreenshotCrop(image: BufferedImage,cells: List<String>,color: Suit): Pair<SuitSymbolPixels,String> {
    fun unavailable(reason: String)=SuitSymbolPixels(0,0,IntArray(0),color,false,reason) to reason
    val confidence=cells[9].toFloat()
    if(confidence<.65f) return unavailable("rank_unconfirmed")
    val box=GlyphBox(cells[5].toFloat(),cells[6].toFloat(),cells[7].toFloat(),cells[8].toFloat())
    if(listOf(box.left,box.top,box.right,box.bottom).any { !it.isFinite() }) return unavailable("rank_geometry")
    val left=box.left*image.width; val right=box.right*image.width
    val top=box.top*image.height; val bottom=box.bottom*image.height; val height=bottom-top
    if(!height.isFinite() || height<12f || height>110f || right<=left) return unavailable("rank_geometry")
    val crop=when(cells[4]) {
        "HAND" -> intArrayOf(floor(right-height*.04f).toInt(),floor(top+height*.06f).toInt(),
            ceil(right+height*1.05f).toInt(),ceil(bottom+height*.09f).toInt())
        "TABLE" -> intArrayOf(floor(left-height*.12f).toInt(),floor(bottom+height*.02f).toInt(),
            ceil(left+height*1.05f).toInt(),ceil(bottom+height*1.10f).toInt())
        else -> error("Invalid placement: ${cells[4]}")
    }
    val width=crop[2]-crop[0]; val cropHeight=crop[3]-crop[1]
    if(crop[0]<0 || crop[1]<0 || crop[2]>image.width || crop[3]>image.height ||
        width !in 8..128 || cropHeight !in 8..128) return unavailable("roi_clipped")
    val normalized=GlyphBox(crop[0].toFloat()/image.width,crop[1].toFloat()/image.height,
        crop[2].toFloat()/image.width,crop[3].toFloat()/image.height)
    val masks=cells.getOrNull(10).orEmpty().takeIf { it.isNotBlank() }?.split(';')?.map { mask ->
        val coordinates=mask.split(',').map(String::toFloat); require(coordinates.size==4)
        GlyphBox(coordinates[0],coordinates[1],coordinates[2],coordinates[3])
    }.orEmpty()
    if(masks.any { mask -> normalized.left<mask.right && normalized.right>mask.left &&
        normalized.top<mask.bottom && normalized.bottom>mask.top }) return unavailable("overlay_masks_suit")
    val raw=image.getRGB(crop[0],crop[1],width,cropHeight,null,0,width)
    return SuitSymbolPixels(width,cropHeight,raw,color) to crop.joinToString(",")
}
