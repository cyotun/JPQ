package com.cyotun.jpq.updates

/** The daily manifest limit must not strand an already verified pending release. */
internal object UpdateCheckPolicy {
    enum class Action { FETCH, RESUME_PENDING, THROTTLED }
    fun next(manual: Boolean,now: Long,lastChecked: Long,pending: Boolean): Action = when {
        manual || lastChecked<=0 || now<lastChecked || now-lastChecked>=86_400_000L -> Action.FETCH
        pending -> Action.RESUME_PENDING
        else -> Action.THROTTLED
    }
}
