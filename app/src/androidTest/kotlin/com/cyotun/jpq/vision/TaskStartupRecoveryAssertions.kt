package com.cyotun.jpq.vision

import com.cyotun.jpq.core.TaskStartupRecoveryPolicy
import org.json.JSONArray
import org.json.JSONObject

/** Verifies real service lifecycle records, not just a policy's recovery request. */
internal fun verifyStartupRecoverySequence(records: List<JSONObject>,requireRequest: Boolean): JSONArray {
    val requests=records.filter { it.optString("type")=="task_startup_input_recovery" }
    val surfaces=records.filter { it.optString("type")=="task_startup_surface" }
    val frames=records.filter { it.optString("type")=="frame" && it.optString("detectorProfile")=="FULL" }
    val rounds=records.filter { it.optString("type")=="ledger" && !it.isNull("roundId") }
    check(requests.size<=2) { "Startup recovery exceeded the per-session budget" }
    if(requireRequest) check(requests.isNotEmpty()) { "No automatic startup recovery was exercised" }
    check(surfaces.size==requests.size*3) { "Each automatic request must detach, reattach and accept a fresh frame" }
    val result=JSONArray()
    requests.forEachIndexed { index,request ->
        check(request.getString("captureScope")=="APP_TASK" && request.getString("phase")=="WAITING")
        check(request.getInt("handCount")==0)
        val source=frames.single { it.getLong("imageTimestampNs")==request.getLong("imageTimestampNs") }
        check(source.getString("scene")=="UNKNOWN" && source.getJSONArray("hand").length()==0)
        when(request.getString("reason")) {
            "MOSTLY_BLACK" -> check(TaskStartupRecoveryPolicy.isSuspiciousBlackFraction(request.getDouble("blackFraction").toFloat()))
            "NO_RECOGNIZABLE_STARTUP" -> Unit // Continuous eight-second eligibility is checked by the policy tests.
            else -> error("Unknown startup recovery reason")
        }
        check(rounds.none { it.getLong("elapsedMs")<=request.getLong("elapsedMs") })
        if(index>0) check(request.getLong("elapsedMs")-requests[index-1].getLong("elapsedMs")>=15_000L)
        val cycle=surfaces.subList(index*3,index*3+3)
        check(cycle.map { it.getString("stage") }==listOf("detached","reattached","first_frame"))
        check(cycle.map { it.getLong("generation") }.distinct().size==1)
        check(cycle.map { it.getInt("recoveryId") }.distinct()==listOf(index+1))
        check(cycle.all { it.getString("captureScope")=="APP_TASK" && it.getString("sessionId")==request.getString("sessionId") })
        check(cycle.first().getLong("elapsedMs")>=request.getLong("elapsedMs"))
        check(cycle.zipWithNext().all { (before,after) -> after.getLong("elapsedMs")>=before.getLong("elapsedMs") })
        val first=cycle.last()
        check(first.getLong("visibilityEpoch")>cycle.first().getLong("visibilityEpoch"))
        check(frames.any { it.getLong("imageTimestampNs")>=first.getLong("imageTimestampNs") &&
            it.getLong("visibilityEpoch")>=first.getLong("visibilityEpoch") }) {
            "No production model frame was processed after the reattached surface supplied pixels"
        }
        result.put(JSONObject().put("reason",request.getString("reason"))
            .put("requestImageTimestampNs",request.getLong("imageTimestampNs")).put("stages",JSONArray(cycle)))
    }
    return result
}
