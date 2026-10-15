package com.cyotun.jpq.vision

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.AppState
import com.cyotun.jpq.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CaptureGapLifecycleTest {
    private fun ledger()=RoundLedger().apply {
        begin("capture-gap-test",Rank.entries.filterNot { it.isJoker }.flatMap { rank ->
            listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } }
        }.take(27))
    }

    @Test fun visibilityCallbackHidesCountsBeforeTheVisionQueueRuns() {
        check(!AppState.running)
        val ledger=ledger(); val before=ledger.snapshot()
        try {
            AppState.markCaptureUnobserved()
            assertNull(AppState.displayRemaining(before))
            assertEquals(RoundPhase.RECOVERING,AppState.displaySnapshot(before).phase)
            assertNull(AppState.displaySnapshot(before).remaining)
            // A UI freshness barrier cannot mutate or erase the evidence ledger.
            assertEquals(before,ledger.snapshot())
            ledger.suspendForRecovery("visibility gap")
            AppState.captureGapCheckpointed()
            assertNull(AppState.displaySnapshot(ledger.snapshot()).remaining)
        } finally { AppState.captureGapCheckpointed() }
    }

    @Test fun invisibleButLivingSessionStillReceivesItsRecoveryCheckpoint() {
        check(!AppState.running && AppState.ledger.snapshot().roundId==null)
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(base.cacheDir,"gap-lifecycle-${System.nanoTime()}").apply { mkdirs() }
        val context=object: ContextWrapper(base) {
            override fun getFilesDir()=File(directory,"files").apply { mkdirs() }
            override fun getDatabasePath(name: String)=File(directory,name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?)=
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,handler: DatabaseErrorHandler?)=
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
        }
        // The actual recognizer, including model setup and close, belongs to
        // the same vision thread used by CaptureService (also required by GPU).
        val task=FutureTask(Callable {
          var alive=true
          val recognizer=TencentRecognizer(context,{ alive },{ 1L },Any(),{}, { false })
          try {
            val initial=ledger().initialHand()
            AppState.ledger.begin("invisible-live-session",initial)
            recognizer.observationGap(1000,"visibility lost")
            val snapshot=AppState.ledger.snapshot()
            assertEquals(RoundPhase.RECOVERING,snapshot.phase)
            assertNull(snapshot.remaining)
            assertEquals(initial,AppState.ledger.initialHand())
            assertEquals(RoundPhase.ACTIVE,AppState.ledger.phaseBeforeRecovery())
            // A retired session must have no effect on its replacement's ledger.
            alive=false
            AppState.ledger.waitForNext()
            AppState.ledger.begin("replacement",initial)
            val replacement=AppState.ledger.snapshot()
            recognizer.observationGap(2000,"old session gap")
            assertEquals(replacement,AppState.ledger.snapshot())
          } finally {
            AppState.ledger.waitForNext(); recognizer.close()
            directory.deleteRecursively()
            AppState.captureGapCheckpointed()
          }
        })
        check(VisionRuntime.handler.post(task))
        task.get(30,TimeUnit.SECONDS)
    }
}
