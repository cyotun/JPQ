package com.cyotun.jpq.updates

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckPolicyTest {
    @Test fun aSameDayRestartResumesTheSignedPendingReleaseWithoutFetchingAgain() {
        assertEquals(UpdateCheckPolicy.Action.RESUME_PENDING,UpdateCheckPolicy.next(false,20_000,10_000,true))
        assertEquals(UpdateCheckPolicy.Action.THROTTLED,UpdateCheckPolicy.next(false,20_000,10_000,false))
    }
    @Test fun manualDailyAndFirstChecksFetchEvenWithAPendingRelease() {
        for(pending in listOf(false,true)) {
            assertEquals(UpdateCheckPolicy.Action.FETCH,UpdateCheckPolicy.next(true,20_000,10_000,pending))
            assertEquals(UpdateCheckPolicy.Action.FETCH,UpdateCheckPolicy.next(false,86_410_000,10_000,pending))
            assertEquals(UpdateCheckPolicy.Action.FETCH,UpdateCheckPolicy.next(false,20_000,0,pending))
        }
    }
    @Test fun aClockRollbackCannotBlockManifestChecksIndefinitely() {
        assertEquals(UpdateCheckPolicy.Action.FETCH,UpdateCheckPolicy.next(false,10_000,20_000,false))
    }
}
