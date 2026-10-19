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

    @Test fun sameDealIncludingFinalCommitRetainsItsMeasuredHudUntilWaiting() = withAnalyzer { analyzer ->
        val ledger=ledger()
        analyzer.onLedgerChanged(ledger.snapshot())
        val observed=GlyphBox(.125f,.004f,.175f,.104f)
        AppState.gradeRegion=observed
        analyzer.onLedgerChanged(ledger.snapshot())
        assertEquals(observed,AppState.gradeRegion)
        analyzer.onLedgerChanged(ledger.selfFinished())
        assertEquals(observed,AppState.gradeRegion)
        analyzer.onLedgerChanged(ledger.finish())
        assertEquals(observed,AppState.gradeRegion)
        analyzer.onLedgerChanged(ledger.waitForNext())
        assertNull(AppState.gradeRegion)
    }

    @Test fun finalTeammateCommitCannotRepaintAnExcludedStraightFlushGold() = withAnalyzer { analyzer ->
        val ledger=ledger()
        analyzer.onLedgerChanged(ledger.snapshot())
        analyzer.onLedgerChanged(ledger.selfFinished())
        analyzer.publishResult(ledger.snapshot(),StraightFlushResult(StraightFlushState.IMPOSSIBLE,
            candidateRanks=emptySet()),true)
        analyzer.onLedgerChanged(ledger.finish())
        assertEquals(StraightFlushState.IMPOSSIBLE,AppState.straightFlushState)
        assertTrue(AppState.straightFlushIncludesWildcards)
        assertTrue(AppState.straightFlushCandidateRanks.isEmpty())
        analyzer.onLedgerChanged(ledger.waitForNext())
        assertEquals(StraightFlushState.UNKNOWN,AppState.straightFlushState)
        assertEquals(Rank.entries.filterNot { it.isJoker }.toSet(),AppState.straightFlushCandidateRanks)
    }

    @Test fun pendingTeammateSuitReadKeepsBothTheIconAndAllRankHeadersUnchanged() = withAnalyzer { analyzer ->
        val ledger=ledger()
        analyzer.onLedgerChanged(ledger.snapshot())
        analyzer.onLedgerChanged(ledger.selfFinished())
        analyzer.publishResult(ledger.snapshot(),StraightFlushResult(StraightFlushState.IMPOSSIBLE),true,true)
        val raw=StraightFlushResult(StraightFlushState.UNKNOWN,reason="suit_uncertain")
        analyzer.publishResult(ledger.snapshot(),raw,true)
        assertEquals(StraightFlushState.UNKNOWN,raw.state)
        assertEquals(StraightFlushState.IMPOSSIBLE,AppState.straightFlushState)
        assertTrue(AppState.straightFlushCandidateRanks.isEmpty())
        val ranks=setOf(Rank.TEN,Rank.J,Rank.Q,Rank.K,Rank.A)
        analyzer.publishResult(ledger.snapshot(),StraightFlushResult(StraightFlushState.POSSIBLE,candidateRanks=ranks),true)
        assertEquals(StraightFlushState.IMPOSSIBLE,AppState.straightFlushState)
        analyzer.publishResult(ledger.snapshot(),StraightFlushResult(StraightFlushState.POSSIBLE,candidateRanks=ranks),true,true)
        assertEquals(StraightFlushState.POSSIBLE,AppState.straightFlushState)
        assertEquals(ranks,AppState.straightFlushCandidateRanks)
    }
}
