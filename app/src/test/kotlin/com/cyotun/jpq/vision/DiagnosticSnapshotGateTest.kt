package com.cyotun.jpq.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticSnapshotGateTest {
    @Test fun disabledSnapshotDoesNotBuildOrWriteRecords() {
        var built = false
        var written = false
        val gate = DiagnosticSnapshotGate<String>(false) { written = true }

        gate.write { built = true; "trace" }

        assertFalse(gate.enabled)
        assertFalse(built)
        assertFalse(written)
    }

    @Test fun disabledSnapshotNeverRunsAThrowingFactory() {
        val gate = DiagnosticSnapshotGate<String>(false) {}

        gate.write { error("disabled diagnostic factory ran") }

        assertFalse(gate.enabled)
    }

    @Test fun enabledSnapshotBuildsAndWritesRecordsInOrder() {
        var builds = 0
        val records = mutableListOf<String>()
        val gate = DiagnosticSnapshotGate<String>(true) { records.add(it) }

        repeat(3) { gate.write { builds++; "trace-$builds" } }

        assertTrue(gate.enabled)
        assertEquals(3, builds)
        assertEquals(listOf("trace-1", "trace-2", "trace-3"), records)
    }
}
