package com.cyotun.jpq.vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.AppState
import com.cyotun.jpq.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No projection or game input: exercises round boundaries on an isolated analyzer. */
@RunWith(AndroidJUnit4::class)
class GradeHintLifecycleTest {
    private fun withAnalyzer(block: (StraightFlushAnalyzer)->Unit) {
        check(!AppState.running) { "Do not run lifecycle probes during capture" }
        val region=AppState.gradeRegion
        val counter=AppState.counterState
        val flush=AppState.straightFlushState
        val wildcards=AppState.straightFlushIncludesWildcards
        val candidates=AppState.straightFlushCandidateRanks
        val analyzer=StraightFlushAnalyzer(InstrumentationRegistry.getInstrumentation().targetContext,
            Any(),{ true },{ 0L },{})
        try { block(analyzer) } finally {
            analyzer.close()
            AppState.gradeRegion=region
            AppState.publishCounterState(counter)
            AppState.publishStraightFlush(StraightFlushResult(flush,candidateRanks=candidates),wildcards)
            AppState.notifyChanged()
        }
    }
    private fun ledger(): RoundLedger = RoundLedger().apply {
        begin("hint-lifecycle",Rank.entries.filterNot { it.isJoker }.flatMap { rank ->
            listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } }
        }.take(27))
    }

    @Test fun waitingForAnotherDealClearsPreviousHudBeforeAnyFreshFrame() = withAnalyzer { analyzer ->
        AppState.gradeRegion=GlyphBox(.075f,.004f,.125f,.104f)
        analyzer.onLedgerChanged(RoundLedger().snapshot())
        assertNull(AppState.gradeRegion)
    }

    @Test fun baselineWithoutThisDealsPrecedingEvidenceCannotReuseOldHud() = withAnalyzer { analyzer ->
        AppState.gradeRegion=GlyphBox(.125f,.004f,.175f,.104f)
        analyzer.onLedgerChanged(ledger().snapshot())
        assertNull(AppState.gradeRegion)
    }

    @Test fun sameDealIncludingTeammateObservationRetainsItsMeasuredHudUntilFinished() = withAnalyzer { analyzer ->
        val ledger=ledger()
        analyzer.onLedgerChanged(ledger.snapshot())
        val observed=GlyphBox(.125f,.004f,.175f,.104f)
        AppState.gradeRegion=observed
        analyzer.onLedgerChanged(ledger.snapshot())
        assertEquals(observed,AppState.gradeRegion)
        analyzer.onLedgerChanged(ledger.selfFinished())
        assertEquals(observed,AppState.gradeRegion)
        analyzer.onLedgerChanged(ledger.finish())
        assertNull(AppState.gradeRegion)
    }
}
