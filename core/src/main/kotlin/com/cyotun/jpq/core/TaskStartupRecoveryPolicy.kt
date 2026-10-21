package com.cyotun.jpq.core

/**
 * Bounds startup-only requests for persistent unrecognised or mis-sized task capture.
 * The caller owns the eligibility decision; this policy only tracks persistence
 * and limits the number and spacing of requests for its lifetime.
 */
class TaskStartupRecoveryPolicy {
    private var lastObservedTimeMs: Long? = null
    private var eligibleSinceMs: Long? = null
    private var suspiciousSinceMs: Long? = null
    private var lastRequestTimeMs: Long? = null
    private var requestCount = 0

    enum class RequestReason {
        MOSTLY_BLACK,
        NO_RECOGNIZABLE_STARTUP,
    }

    /** Reason for the most recent request, or null before the first request. */
    var lastRequestReason: RequestReason? = null
        private set

    /** Whether this policy has used its full per-session request budget. */
    val exhausted: Boolean
        get() = requestCount >= MAX_REQUESTS

    /** Returns true when the caller should request a startup capture recovery. */
    fun observe(timeMs: Long, eligible: Boolean, suspicious: Boolean): Boolean {
        val previousTime = lastObservedTimeMs
        lastObservedTimeMs = timeMs

        if (previousTime != null && timeMs < previousTime) {
            // A clock rollback invalidates both persistence intervals.
            eligibleSinceMs = timeMs.takeIf { eligible }
            suspiciousSinceMs = if (eligible && suspicious) timeMs else null
            return false
        }

        if (!eligible) {
            eligibleSinceMs = null
            suspiciousSinceMs = null
            return false
        }

        val eligibleSince = eligibleSinceMs ?: timeMs.also { eligibleSinceMs = it }
        if (suspicious) {
            if (suspiciousSinceMs == null) suspiciousSinceMs = timeMs
        } else {
            // Healthy pixels reset only the black-content interval.
            suspiciousSinceMs = null
        }

        if (requestCount >= MAX_REQUESTS) return false

        val reason = when {
            suspiciousSinceMs?.let { timeMs - it >= SUSPICION_PERSISTENCE_MS } == true ->
                RequestReason.MOSTLY_BLACK
            timeMs - eligibleSince >= UNKNOWN_STARTUP_PERSISTENCE_MS ->
                RequestReason.NO_RECOGNIZABLE_STARTUP
            else -> return false
        }

        val previousRequest = lastRequestTimeMs
        if (previousRequest != null && timeMs - previousRequest < REQUEST_COOLDOWN_MS) {
            return false
        }

        requestCount++
        lastRequestTimeMs = timeMs
        lastRequestReason = reason
        // A continuing fault must earn a fresh persistence interval per request.
        eligibleSinceMs = timeMs
        suspiciousSinceMs = timeMs.takeIf { suspicious }
        return true
    }

    companion object {
        private const val SUSPICION_PERSISTENCE_MS = 3_000L
        private const val UNKNOWN_STARTUP_PERSISTENCE_MS = 8_000L
        private const val REQUEST_COOLDOWN_MS = 15_000L
        private const val MAX_REQUESTS = 2

        /** True for substantial but non-total black content, excluding empty/loading frames. */
        fun isSuspiciousBlackFraction(value: Float): Boolean =
            value.isFinite() && value >= 0.80f && value <= 0.99f
    }
}
