package com.cyotun.jpq.vision

import com.aijpq.pokerrecorder.runtime.preprocess.ActiveRegion

/** Audited original exit-probe ROI retention. Only geometry is retained; compose
 * always reads the current source Bitmap and the model always reads current pixels. */
class ReferenceExitRegionCache {
    private val regions=linkedMapOf<String,ActiveRegion>()
    fun merge(time: Long,current: List<ActiveRegion>): List<ActiveRegion> {
        val normal=current.filter { it.source=="normal_active_region" && it.recordState!="exit_probe" }
        val iterator=regions.entries.iterator()
        while(iterator.hasNext()) {
            val old=iterator.next().value.bbox
            if(normal.any { region -> val b=region.bbox
                val overlap=(minOf(old.right,b.right)-maxOf(old.left,b.left)).coerceAtLeast(0).toLong()*
                    (minOf(old.bottom,b.bottom)-maxOf(old.top,b.top)).coerceAtLeast(0)
                old.area>0 && overlap.toDouble()/old.area>=.2
            }) iterator.remove()
        }
        current.filter { it.recordState=="exit_probe" }.forEach { region ->
            val key=region.trackId?.takeIf { it.isNotBlank() } ?: region.regionId.takeIf { it.isNotBlank() }
            if(key!=null) regions[key]=region
        }
        // Bound geometry storage; omitted paper remains protected by the full-raster guard.
        while(regions.size>32) regions.remove(regions.keys.first())
        val present=current.map { it.trackId?.takeIf { id -> id.isNotBlank() } ?: it.regionId }.toSet()
        return current+regions.filterKeys { it !in present }.values.map { r ->
            ActiveRegion(r.regionId,r.trackId,r.bbox,r.supportRatio,r.stableFrameCount,r.recordState,r.samplePointCount,
                r.evidencePointCount,r.source,r.areaType,r.observationId,time,r.colorBootstrapRuleId,
                r.colorBootstrapCandidateBox,r.colorBootstrapAdjacentWhiteBox)
        }
    }
    fun clear() { regions.clear() }
}
