package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.RectF
import com.aijpq.pokerrecorder.runtime.preprocess.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.ceil

/** Opt-in native update/compose adapter. No existing recognition path constructs this. */
class ReferenceRegionPreprocessor(
    private val session: NativeActiveRegionSession,
    private val nativeConfig: NativePreprocessConfig = NativePreprocessConfig(),
    private val packingConfig: PackingConfig = PackingConfig(),
    handSeed: DoubleArray? = null
): AutoCloseable {
    private val handSeed=handSeed?.copyOf()
    enum class Route { PACKED_320, PACKED_640, FULL_FALLBACK, NO_ACTIVE }
    data class PackingConfig(
        val effectiveMinSamplePoints: Int = 304,
        val sourcePadding: Int = 20,
        val outerPadding: Int = 8,
        val tileGap: Int = 8,
        val allowCompact320: Boolean = false,
        val overlaySplit: Boolean = true,
        val overlapPx: Int = 48,
        val maxRoiScale: Double = 2.0
    )
    data class ScaleAttempt(val canvasSize: Int, val minimumScale: Double,
        val extraDownscale: Boolean, val accepted: Boolean, val reason: String?)
    data class Plan(
        val route: Route, val manifest: PackingManifest?, val inputRegionCount: Int,
        val acceptedRegionIds: List<String>, val filteredRegionIds: List<String>,
        val invalidRegionIds: List<String>, val overflowRegionIds: List<String>,
        val reason: String?, val variant: String = "reference_java_scale_search",
        val scaleAttempts: List<ScaleAttempt> = emptyList()
    ) {
        val requiresFallback: Boolean get() = route == Route.FULL_FALLBACK
    }
    data class Preparation(val native: NativePreprocessResult?, val plan: Plan, val packed: PackedFrame?,
                           val specialHandScanMs: Double = 0.0): AutoCloseable {
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
            var handScanMs=0.0
            handSeed?.let { seed ->
                // The original native scan follows white components beyond the seed,
                // preserving tall stacked cards that global active regions can miss.
                // Scan this frame rather than reusing a one-second-old hand observation.
                val hand=session.detectHandRegions(frameIndex,timestampMs,seed)
                handScanMs=hand.metrics.totalMs
                val effectiveHand=hand.regions.filter { it.samplePointCount>=packingConfig.effectiveMinSamplePoints }
                val global=checkNotNull(result)
                val remaining=global.regions.filterNot { normal -> effectiveHand.any { special ->
                    val a=normal.bbox; val b=special.bbox
                    b.left<=a.left && b.top<=a.top && b.right>=a.right && b.bottom>=a.bottom
                } }
                result=NativePreprocessResult(global.frameWidth,global.frameHeight,global.frameIndex,global.timestampMs,
                    remaining+hand.regions,global.metrics,global.negativeUiRingMatches,global.negativeUiRingScanMs)
            }
            val combined=checkNotNull(result)
            val plan=plan(combined.regions,source.width,source.height,packingConfig)
            val packed=plan.manifest?.let { session.composePacked(source,it) }
            Preparation(combined,plan,packed,handScanMs)
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
        private const val MIN_GLOBAL_SCALE=.25
        private const val SCALE_EPSILON=1e-6
        private const val SCALE_SEARCH_STEPS=12
        private data class SourceTile(
            val region: ActiveRegion, val box: IntBox, val splitGroup: String? = null,
            val part: String? = null, val overlayGroup: String? = null,
            val overlayIndex: Int? = null, val overlayCount: Int? = null, val overlap: Int? = null,
            val tileId: String = ""
        )
        private data class ScaledTile(val source: SourceTile,val scale: Double,val width: Int,val height: Int)
        private data class CanvasAttempt(val manifest: PackingManifest?,val summary: ScaleAttempt)

        /** Pure geometry. It can be exercised without constructing/loading the native session. */
        fun plan(regions: List<ActiveRegion>, sourceWidth: Int, sourceHeight: Int,
                 config: PackingConfig = PackingConfig()): Plan {
            require(sourceWidth > 0 && sourceHeight > 0)
            require(config.effectiveMinSamplePoints >= 0 && config.sourcePadding >= 0)
            require(config.outerPadding in 0..64 && config.tileGap in 0..64 && config.overlapPx >= 0)
            require(config.maxRoiScale.isFinite() && config.maxRoiScale > 0)
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
            val attempts=ArrayList<ScaleAttempt>()
            fun outcome(route: Route, manifest: PackingManifest?, reason: String?, overflow: List<String> = emptyList()) =
                Plan(route,manifest,regions.size,accepted.map { it.regionId },filtered.map { it.regionId },invalid.distinct(),overflow.distinct(),reason,
                    scaleAttempts=attempts.toList())
            if(invalid.isNotEmpty()) return outcome(Route.FULL_FALLBACK,null,"invalid_source_regions")
            if(accepted.isEmpty()) return outcome(Route.NO_ACTIVE,null,"no_effective_regions")
            val canonical=canonicalFrame(sourceWidth,sourceHeight)
            val tiles=accepted.flatMap { split(it,sourceWidth,sourceHeight,config) }
                .mapIndexed { index,tile -> tile.copy(tileId="tile-$index") }
            if(tiles.any { it.box.width <= 0 || it.box.height <= 0 })
                return outcome(Route.FULL_FALLBACK,null,"invalid_split_geometry",accepted.map { it.regionId })
            if(!coversExpandedRegions(tiles,accepted,sourceWidth,sourceHeight,config))
                return outcome(Route.FULL_FALLBACK,null,"split_coverage_gap",accepted.map { it.regionId })
            // Keep the explicit 640 preference. Each attempt restores the original Java
            // per-tile scale bounds, ceil dimensions, orderings and 12-step ratio search.
            fun attempt(canvas: Int,extra: Boolean): PackingManifest? {
                val result=evaluateCanvas(tiles,canvas,canonical,config,extra)
                attempts.add(result.summary)
                return result.manifest
            }
            val normal640=attempt(640,false)
            val compact=if(config.allowCompact320 && normal640 != null) attempt(320,false) else null
            // The original planner permits the below-canonical search only at 640,
            // and never below .25. It retains every source tile throughout the search.
            val manifest=compact ?: normal640 ?: attempt(640,true)
                ?: return outcome(Route.FULL_FALLBACK,null,"packing_overflow_640",accepted.map { it.regionId })
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

        private fun expandedBox(region: ActiveRegion,width: Int,height: Int,config: PackingConfig): IntBox {
            val b=region.bbox; val p=config.sourcePadding.toLong()
            return IntBox((b.left.toLong()-p).coerceAtLeast(0).toInt(),(b.top.toLong()-p).coerceAtLeast(0).toInt(),
                (b.right.toLong()+p).coerceAtMost(width.toLong()).toInt(),(b.bottom.toLong()+p).coerceAtMost(height.toLong()).toInt())
        }

        private fun coversExpandedRegions(tiles: List<SourceTile>,regions: List<ActiveRegion>,width: Int,height: Int,
                                          config: PackingConfig): Boolean = regions.all { region ->
            val expected=expandedBox(region,width,height,config)
            val parts=tiles.filter { it.region.regionId == region.regionId }.map { it.box }.sortedBy { it.left }
            var right=expected.left
            parts.isNotEmpty() && parts.all { part ->
                val valid=part.top == expected.top && part.bottom == expected.bottom &&
                    part.left >= expected.left && part.right <= expected.right && part.left <= right
                right=max(right,part.right)
                valid
            } && right == expected.right
        }

        private fun split(region: ActiveRegion,width: Int,height: Int,config: PackingConfig): List<SourceTile> {
            val box=expandedBox(region,width,height,config)
            if(!config.overlaySplit) return listOf(SourceTile(region,box))
            // These are original explicit special-area markers; position alone never
            // promotes a global active region to dealer_hand.
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

        /** PackedRegionPlanner.evaluateCanvas: separate maximum scale for every tile. */
        private fun evaluateCanvas(source: List<SourceTile>,canvas: Int,canonical: CanonicalPackingFrame,
                                   config: PackingConfig,extraDownscale: Boolean): CanvasAttempt {
            val minimum=if(extraDownscale) MIN_GLOBAL_SCALE else canonical.scale
            fun rejected(reason: String)=CanvasAttempt(null,ScaleAttempt(canvas,minimum,extraDownscale,false,reason))
            val inner=canvas-2*config.outerPadding
            if(inner <= 0) return rejected("invalid_canvas")
            val sourceCap=max(SCALE_EPSILON,min(1.0,config.maxRoiScale))
            val upper=source.map { tile -> max(SCALE_EPSILON,min(sourceCap,
                min(inner.toDouble()/max(1,tile.box.width),inner.toDouble()/max(1,tile.box.height)))) }
            if(upper.any { it+SCALE_EPSILON < minimum })
                return rejected(if(extraDownscale) "tile_below_min_scale" else "requires_below_canonical_scale")
            var best=layoutAtRatio(source,upper,minimum,0.0,canvas,canonical,config,extraDownscale)
                ?: return rejected(if(extraDownscale) "layout_overflow" else "requires_below_canonical_scale")
            var low=0.0; var high=1.0
            repeat(SCALE_SEARCH_STEPS) {
                val ratio=(low+high)/2
                val candidate=layoutAtRatio(source,upper,minimum,ratio,canvas,canonical,config,extraDownscale)
                if(candidate == null) high=ratio else { low=ratio; best=candidate }
            }
            val full=layoutAtRatio(source,upper,minimum,1.0,canvas,canonical,config,extraDownscale)
            return CanvasAttempt(full ?: best,ScaleAttempt(canvas,minimum,extraDownscale,true,null))
        }

        private fun layoutAtRatio(source: List<SourceTile>,upper: List<Double>,minimum: Double,ratio: Double,
                                  canvas: Int,canonical: CanonicalPackingFrame,config: PackingConfig,
                                  extraDownscale: Boolean): PackingManifest? {
            val scaled=source.mapIndexed { index,tile ->
                val scale=minimum+(upper[index]-minimum)*ratio.coerceIn(0.0,1.0)
                ScaledTile(tile,scale,ceil(max(1,tile.box.width)*scale).toInt().coerceAtLeast(1),
                    ceil(max(1,tile.box.height)*scale).toInt().coerceAtLeast(1))
            }
            // Original orderings are descending packed area, longest side, width, height.
            val orderings=listOf(scaled.sortedByDescending { it.width.toLong()*it.height },
                scaled.sortedByDescending { max(it.width,it.height) },scaled.sortedByDescending { it.width },
                scaled.sortedByDescending { it.height }).distinct()
            val candidates=orderings.mapNotNull { order ->
                var x=config.outerPadding; var y=config.outerPadding; var rowHeight=0
                val packed=ArrayList<PackedTile>()
                for(scaledTile in order) {
                    val tile=scaledTile.source; val w=scaledTile.width; val h=scaledTile.height
                    if(w > canvas-2*config.outerPadding || h > canvas-2*config.outerPadding) return@mapNotNull null
                    if(x+w > canvas-config.outerPadding && x > config.outerPadding) {
                        x=config.outerPadding; y+=rowHeight+config.tileGap; rowHeight=0
                    }
                    if(y+h > canvas-config.outerPadding) return@mapNotNull null
                    val r=tile.region
                    packed.add(PackedTile(tile.tileId,r.regionId,tile.box,IntBox(x,y,x+w,y+h),scaledTile.scale,
                        tile.splitGroup,tile.part,r.trackId,r.recordState,r.source,r.areaType,r.observationId,
                        tile.overlayGroup,tile.overlayIndex,tile.overlayCount,tile.overlap))
                    x+=w+config.tileGap; rowHeight=max(rowHeight,h)
                }
                val bounds=IntBox(packed.minOf { it.packedBBox.left },packed.minOf { it.packedBBox.top },
                    packed.maxOf { it.packedBBox.right },packed.maxOf { it.packedBBox.bottom })
                val mode=if(extraDownscale) "canonical_extra_downscale" else if(canonical.scale >= 1.0) "original" else "canonical_frame_fit"
                PackingManifest(canvas,packed,packed.minOf { it.scale },mode,canonical,bounds,
                    packed.sumOf { it.packedBBox.area.toLong() }.toDouble()/(canvas.toLong()*canvas))
            }
            // Original compact score prioritizes used height, then width, then area.
            return candidates.minWithOrNull(compareBy<PackingManifest> {
                it.layoutBounds!!.height.toLong()*canvas+it.layoutBounds!!.width
            }.thenBy { it.layoutBounds!!.area })
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
