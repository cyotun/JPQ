package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.RectF
import com.aijpq.pokerrecorder.runtime.preprocess.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Opt-in native update/compose adapter. No existing recognition path constructs this. */
class ReferenceRegionPreprocessor(
    private val session: NativeActiveRegionSession,
    private val nativeConfig: NativePreprocessConfig = NativePreprocessConfig(),
    private val packingConfig: PackingConfig = PackingConfig()
): AutoCloseable {
    enum class Route { PACKED_320, PACKED_640, FULL_FALLBACK, NO_ACTIVE }
    data class PackingConfig(
        val effectiveMinSamplePoints: Int = 304,
        val sourcePadding: Int = 20,
        val outerPadding: Int = 8,
        val tileGap: Int = 8,
        val allowCompact320: Boolean = false,
        val overlaySplit: Boolean = true,
        val overlapPx: Int = 48
    )
    data class Plan(
        val route: Route, val manifest: PackingManifest?, val inputRegionCount: Int,
        val acceptedRegionIds: List<String>, val filteredRegionIds: List<String>,
        val invalidRegionIds: List<String>, val overflowRegionIds: List<String>,
        val reason: String?, val variant: String = "reference_shelf_subset"
    ) {
        val requiresFallback: Boolean get() = route == Route.FULL_FALLBACK
    }
    data class Preparation(val native: NativePreprocessResult?, val plan: Plan, val packed: PackedFrame?): AutoCloseable {
        override fun close() { packed?.close() }
    }
    data class ProjectedDetection(val detection: CardDetection, val tile: PackedTile)
    data class Projection(
        val detections: List<ProjectedDetection>, val droppedIndices: List<Int>,
        val clippedToTileCount: Int
    ) { val requiresFallback: Boolean get() = droppedIndices.isNotEmpty() || clippedToTileCount>0 }

    fun prepare(source: Bitmap, frameIndex: Long, timestampMs: Long): Preparation {
        var result: NativePreprocessResult? = null
        return try {
            result=session.update(source,frameIndex,timestampMs,nativeConfig)
            val plan=plan(result.regions,source.width,source.height,packingConfig)
            val packed=plan.manifest?.let { session.composePacked(source,it) }
            Preparation(result,plan,packed)
        } catch (error: LinkageError) {
            Preparation(result,failure(result,"native_linkage:${error.javaClass.simpleName}"),null)
        } catch (error: Exception) {
            Preparation(result,failure(result,"native_preprocess:${error.javaClass.simpleName}"),null)
        }
    }

    private fun failure(result: NativePreprocessResult?, reason: String) = Plan(Route.FULL_FALLBACK,null,
        result?.regions?.size ?: 0,emptyList(),emptyList(),emptyList(),result?.regions?.map { it.regionId }.orEmpty(),reason)

    fun reset(reason: String) = session.reset(reason)
    override fun close() = session.close()

    companion object {
        private data class SourceTile(
            val region: ActiveRegion, val box: IntBox, val splitGroup: String? = null,
            val part: String? = null, val overlayGroup: String? = null,
            val overlayIndex: Int? = null, val overlayCount: Int? = null, val overlap: Int? = null
        )

        /** Pure geometry. It can be exercised without constructing/loading the native session. */
        fun plan(regions: List<ActiveRegion>, sourceWidth: Int, sourceHeight: Int,
                 config: PackingConfig = PackingConfig()): Plan {
            require(sourceWidth > 0 && sourceHeight > 0)
            require(config.effectiveMinSamplePoints >= 0 && config.sourcePadding >= 0)
            require(config.outerPadding in 0..64 && config.tileGap in 0..64 && config.overlapPx >= 0)
            // Lua uses the area>=400 source-pixel fallback only when sample_point_count is
            // absent/non-numeric. Native snapshots always expose an int samplePointCount,
            // and the original Java post-filter compares that count alone (not an OR).
            val filtered=regions.filter { it.samplePointCount < config.effectiveMinSamplePoints }
            val accepted=regions.filter { it.samplePointCount >= config.effectiveMinSamplePoints }
            val invalid=accepted.filter { region ->
                val b=region.bbox
                region.regionId.isBlank() || !region.supportRatio.isFinite() ||
                    b.left < 0 || b.top < 0 || b.right > sourceWidth || b.bottom > sourceHeight ||
                    b.right <= b.left || b.bottom <= b.top
            }.map { it.regionId }.toMutableList()
            invalid.addAll(accepted.groupBy { it.regionId }.filterValues { it.size > 1 }.keys)
            fun outcome(route: Route, manifest: PackingManifest?, reason: String?, overflow: List<String> = emptyList()) =
                Plan(route,manifest,regions.size,accepted.map { it.regionId },filtered.map { it.regionId },invalid.distinct(),overflow.distinct(),reason)
            if(invalid.isNotEmpty()) return outcome(Route.FULL_FALLBACK,null,"invalid_source_regions")
            if(accepted.isEmpty()) return outcome(Route.NO_ACTIVE,null,"no_effective_regions")
            val canonical=canonicalFrame(sourceWidth,sourceHeight)
            val tiles=accepted.flatMap { split(it,sourceWidth,sourceHeight,config) }
            if(tiles.any { it.box.width <= 0 || it.box.height <= 0 })
                return outcome(Route.FULL_FALLBACK,null,"invalid_split_geometry",accepted.map { it.regionId })
            // Prove complete placement at 640 first; never drop a tile to make a canvas fit.
            val large=layout(tiles,640,canonical,config)
                ?: return outcome(Route.FULL_FALLBACK,null,"packing_overflow_640",accepted.map { it.regionId })
            // The 320 attempt uses exactly the same source scale, without extra downsampling.
            val compact=if(config.allowCompact320) layout(tiles,320,canonical,config) else null
            val manifest=compact ?: large
            val represented=manifest.tiles.map { it.sourceRegionId }.toSet()
            val missing=accepted.map { it.regionId }.filter { it !in represented }
            if(missing.isNotEmpty()) return outcome(Route.FULL_FALLBACK,null,"packing_missing_regions",missing)
            return outcome(if(manifest.canvasSize == 320) Route.PACKED_320 else Route.PACKED_640,manifest,null)
        }

        private fun canonicalFrame(width: Int,height: Int): CanonicalPackingFrame {
            val longSide=max(width,height).toDouble(); val shortSide=min(width,height).toDouble()
            val square=longSide/shortSide <= 1.35
            val shortLimit=if(square) 1280 else 640
            val fit=min(1.0,min(1280.0/longSide,shortLimit/shortSide))
            return CanonicalPackingFrame(if(square) "square_like" else "rectangular",1280,shortLimit,
                fit,max(.25,fit),fit < .25)
        }

        private fun split(region: ActiveRegion,width: Int,height: Int,config: PackingConfig): List<SourceTile> {
            val b=region.bbox; val p=config.sourcePadding.toLong()
            val box=IntBox((b.left.toLong()-p).coerceAtLeast(0).toInt(),(b.top.toLong()-p).coerceAtLeast(0).toInt(),
                (b.right.toLong()+p).coerceAtMost(width.toLong()).toInt(),(b.bottom.toLong()+p).coerceAtMost(height.toLong()).toInt())
            if(!config.overlaySplit) return listOf(SourceTile(region,box))
            val hand=region.source == "hand_active_region_1fps" || region.areaType == "dealer_hand"
            val aspect=box.width.toDouble()/box.height
            if(aspect < (if(hand) 2.0 else 4.0) || box.width.toDouble()/width < (if(hand) .2 else .45))
                return listOf(SourceTile(region,box))
            val tileWidth=if(hand) max((width*.34).roundToInt(),box.height) else (box.width+1)/2
            val overlap=config.overlapPx; val step=max(1,tileWidth-overlap)
            val count=if(hand) ((max(1,box.width-overlap)+step-1L)/step).toInt().coerceIn(2,6) else 2
            val group=(if(hand) "overlay-" else "split-")+region.regionId
            return (0 until count).map { index ->
                val left=if(index == 0) box.left else max(box.left,box.left+index*step-overlap)
                val right=if(index == count-1) box.right else min(box.right,box.left+index*step+tileWidth)
                SourceTile(region,IntBox(left,box.top,right,box.bottom),group,
                    if(hand) index.toString() else if(index == 0) "left" else "right",
                    if(hand) group else null,if(hand) index else null,if(hand) count else null,if(hand) overlap else null)
            }
        }

        /** Audited shelf placement with several safe orderings; no extra scale search/upscaling. */
        private fun layout(source: List<SourceTile>,canvas: Int,canonical: CanonicalPackingFrame,config: PackingConfig): PackingManifest? {
            val orderings=listOf(source,source.sortedWith(compareByDescending<SourceTile> { it.box.height }.thenByDescending { it.box.width }),
                source.sortedByDescending { it.box.area },source.sortedByDescending { it.box.width })
            val candidates=orderings.mapNotNull { order ->
                var x=config.outerPadding; var y=config.outerPadding; var rowHeight=0
                val packed=ArrayList<PackedTile>()
                for((index,tile) in order.withIndex()) {
                    val w=(tile.box.width*canonical.scale).roundToInt().coerceAtLeast(1)
                    val h=(tile.box.height*canonical.scale).roundToInt().coerceAtLeast(1)
                    if(w > canvas-2*config.outerPadding || h > canvas-2*config.outerPadding) return@mapNotNull null
                    if(x+w > canvas-config.outerPadding && x > config.outerPadding) {
                        x=config.outerPadding; y+=rowHeight+config.tileGap; rowHeight=0
                    }
                    if(y+h > canvas-config.outerPadding) return@mapNotNull null
                    val r=tile.region
                    packed.add(PackedTile("tile-$index",r.regionId,tile.box,IntBox(x,y,x+w,y+h),canonical.scale,
                        tile.splitGroup,tile.part,r.trackId,r.recordState,r.source,r.areaType,r.observationId,
                        tile.overlayGroup,tile.overlayIndex,tile.overlayCount,tile.overlap))
                    x+=w+config.tileGap; rowHeight=max(rowHeight,h)
                }
                val bounds=IntBox(packed.minOf { it.packedBBox.left },packed.minOf { it.packedBBox.top },
                    packed.maxOf { it.packedBBox.right },packed.maxOf { it.packedBBox.bottom })
                PackingManifest(canvas,packed,canonical.scale,"canonical_frame_fit",canonical,bounds,
                    packed.sumOf { it.packedBBox.area.toLong() }.toDouble()/(canvas.toLong()*canvas))
            }
            return candidates.minByOrNull { it.layoutBounds!!.area }
        }

        /** Detections must already be normalized to the packed Bitmap, after model letterbox reversal. */
        fun project(detections: List<CardDetection>,manifest: PackingManifest,sourceWidth: Int,sourceHeight: Int): Projection {
            require(sourceWidth > 0 && sourceHeight > 0 && manifest.canvasSize in setOf(320,640))
            val output=ArrayList<ProjectedDetection>(); val dropped=ArrayList<Int>(); var clipped=0
            for((index,detection) in detections.withIndex()) {
                val d=detection.box
                if(!detection.confidence.isFinite() || !listOf(d.left,d.top,d.right,d.bottom).all { it.isFinite() } || d.width() <= 0 || d.height() <= 0) {
                    dropped.add(index); continue
                }
                // The audited Java mapper converts packed corners to integer pixels first.
                val packed=RectF((d.left*manifest.canvasSize).toInt().toFloat(),(d.top*manifest.canvasSize).toInt().toFloat(),
                    (d.right*manifest.canvasSize).toInt().toFloat(),(d.bottom*manifest.canvasSize).toInt().toFloat())
                val tile=manifest.tiles.maxByOrNull { intersectionArea(packed,it.packedBBox) }
                if(tile == null || intersectionArea(packed,tile.packedBBox) <= 0) { dropped.add(index); continue }
                val p=tile.packedBBox; val s=tile.sourceBBox
                if(p.width <= 0 || p.height <= 0 || s.width <= 0 || s.height <= 0 ||
                    s.left < 0 || s.top < 0 || s.right > sourceWidth || s.bottom > sourceHeight) { dropped.add(index); continue }
                val left=max(packed.left,p.left.toFloat()); val top=max(packed.top,p.top.toFloat())
                val right=min(packed.right,p.right.toFloat()); val bottom=min(packed.bottom,p.bottom.toFloat())
                if(left != packed.left || top != packed.top || right != packed.right || bottom != packed.bottom) clipped++
                fun sx(value: Float)=(s.left+((value-p.left).toDouble()*s.width/p.width).toInt()).coerceIn(s.left,s.right).toFloat()/sourceWidth
                fun sy(value: Float)=(s.top+((value-p.top).toDouble()*s.height/p.height).toInt()).coerceIn(s.top,s.bottom).toFloat()/sourceHeight
                val mapped=RectF(sx(left),sy(top),sx(right),sy(bottom))
                if(mapped.width() <= 0 || mapped.height() <= 0) { dropped.add(index); continue }
                output.add(ProjectedDetection(detection.copy(box=mapped),tile))
            }
            return Projection(output,dropped,clipped)
        }

        private fun intersectionArea(box: RectF,tile: IntBox): Float =
            max(0f,min(box.right,tile.right.toFloat())-max(box.left,tile.left.toFloat())) *
                max(0f,min(box.bottom,tile.bottom.toFloat())-max(box.top,tile.top.toFloat()))
    }
}
