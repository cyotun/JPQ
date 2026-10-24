package com.cyotun.jpq

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.core.*
import com.cyotun.jpq.vision.SessionTrace
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class EvidenceStorageTest {
    private fun isolated(test: (Context)->Unit) {
        check(!AppState.running)
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val id="evidence-test-${System.nanoTime()}"
        val directory=File(base.cacheDir,id).apply { check(mkdirs()) }
        val context=object: ContextWrapper(base) {
            override fun getFilesDir()=File(directory,"files").apply { mkdirs() }
            override fun getCacheDir()=directory
            override fun getDatabasePath(name: String)=File(directory,name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?)=
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,handler: DatabaseErrorHandler?)=
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
            override fun getSharedPreferences(name: String,mode: Int)=base.getSharedPreferences("$id-$name",mode)
        }
        try { test(context) } finally {
            EvidenceWork.maintain { directory.deleteRecursively() }
            listOf("jpq-diagnostics","jpq-runtime","jpq-display").forEach { base.deleteSharedPreferences("$id-$it") }
        }
    }
    private fun file(context: Context,name: String,text: String="evidence")=File(context.filesDir,name).apply {
        parentFile!!.mkdirs(); writeText(text)
    }
    private fun ledger(): RoundLedger = RoundLedger().apply {
        val hand=Rank.entries.filterNot { it.isJoker }.flatMap { rank -> List(2) { Card(rank,Suit.RED) } + List(2) { Card(rank,Suit.BLACK) } }.take(27)
        begin("round-test",hand)
    }
    @Test fun clearRemovesAllThreeEvidenceTreesAndDatabaseButPreservesOtherFiles()=isolated { c ->
        listOf("sessions/old/trace.jsonl","diagnostic/a.jpg","capture-probes/probe/nested/raw.bin").forEach { file(c,it) }
        file(c,"updates/keep.apk"); file(c,"other/keep.txt")
        RoundRepository(c).use { it.save(ledger()) }
        DiagnosticStorage.clear(c)
        listOf("sessions","diagnostic","capture-probes").forEach { assertFalse(File(c.filesDir,it).exists()) }
        assertTrue(File(c.filesDir,"updates/keep.apk").exists())
        RoundRepository(c).use { assertEquals(0,JSONObject(it.export()).getJSONArray("rounds").length()) }
    }
    @Test fun retentionDeletesOldestCompleteFoldersOnly()=isolated { c ->
        val old=file(c,"sessions/old/trace","12345678").parentFile!!
        val recent=file(c,"sessions/recent/trace","abcdef").parentFile!!
        old.setLastModified(1000); recent.setLastModified(2000)
        DiagnosticStorage.pruneDirectories(File(c.filesDir,"sessions"),8)
        assertFalse(old.exists()); assertEquals("abcdef",File(recent,"trace").readText())
    }
    @Test fun pendingImageWritesFinishBeforeClearAndCannotRecreateDeletedFolder()=isolated { c ->
        val release=CountDownLatch(1); val entered=CountDownLatch(1); val done=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>()
        val io=EvidenceExecutor("test-late-image")
        io.execute { entered.countDown(); check(release.await(3,TimeUnit.SECONDS)); file(c,"diagnostic/late.jpg") }
        assertTrue(entered.await(2,TimeUnit.SECONDS)); io.shutdown()
        Thread { try { DiagnosticStorage.clear(c) } catch(t: Throwable) { failure.set(t) } finally { done.countDown() } }.start()
        assertFalse(done.await(100,TimeUnit.MILLISECONDS)); release.countDown()
        assertTrue(done.await(3,TimeUnit.SECONDS)); assertNull(failure.get())
        assertFalse(File(c.filesDir,"diagnostic").exists())
    }
    @Test fun exportDrainsTraceAndImmutableRoundCheckpoints()=isolated { c ->
        c.getSharedPreferences("jpq-diagnostics",0).edit().putBoolean("enabled",true).commit()
        val state=ledger(); val store=AsyncRoundStore(c)
        // Suit metadata can arrive before the async rank checkpoint; do not delete it as orphaned.
        RoundRepository(c).use { it.saveSuitEvidence("round-test",JSONObject().put("proof","before-rank-save")) }
        store.save(state); state.invalidate("later live change"); store.close()
        val trace=SessionTrace(c)
        repeat(30) { trace.write(JSONObject().put("type","test").put("order",it)) }
        trace.close()
        val archive=File(c.cacheDir,"export.zip")
        archive.outputStream().use { DiagnosticArchive.write(c,it) }
        ZipFile(archive).use { zip ->
            val rounds=JSONObject(zip.getInputStream(zip.getEntry("rounds.json")).bufferedReader().readText()).getJSONArray("rounds")
            assertEquals("ACTIVE",rounds.getJSONObject(0).getString("phase"))
            assertEquals("before-rank-save",rounds.getJSONObject(0).getJSONObject("suitEvidence").getString("proof"))
            val text=zip.getInputStream(zip.getEntry("sessions/${trace.id}/trace.jsonl")).bufferedReader().readText()
            val values=text.lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.filter { it.optString("type")=="test" }.map { it.getInt("order") }.toList()
            assertEquals((0..29).toList(),values)
        }
    }
    @Test fun traceRotationRetainsOrderedUtf8RecordsAcrossRotatedFiles()=isolated { c ->
        c.getSharedPreferences("jpq-diagnostics",0).edit().putBoolean("enabled",true).commit()
        val trace=SessionTrace(c)
        val payload="牌".repeat(110_000)
        repeat(30) { trace.write(JSONObject().put("type","rotation-test").put("order",it).put("payload",payload)) }
        trace.close()
        // This deliberately writes almost 10 MiB at once, unlike a live frame.
        // Wait for the stress workload before testing the exact file contents.
        assertTrue("Synthetic trace did not drain",trace.awaitClosed(60_000))
        EvidenceWork.maintain {
            val folder=File(c.filesDir,"sessions/${trace.id}")
            val history=(6 downTo 1).mapNotNull { index ->
                File(folder,if(index==1) "trace.previous.jsonl" else "trace.previous.$index.jsonl")
                    .takeIf { it.isFile }
            }+File(folder,"trace.jsonl")
            assertTrue(history.size>=2)
            assertTrue(history.all { it.length()<=8L*1024*1024 })
            val records=history.flatMap { file -> file.readLines().filter { it.isNotBlank() }.map(::JSONObject) }
                .filter { it.optString("type")=="rotation-test" }
            assertEquals((0..29).toList(),records.map { it.getInt("order") })
            assertTrue(records.all { it.getString("payload")==payload })
        }
    }
    @Test fun traceRotationKeepsBoundedRecentWindowAndExportsEveryRetainedFile()=isolated { c ->
        c.getSharedPreferences("jpq-diagnostics",0).edit().putBoolean("enabled",true).commit()
        val trace=SessionTrace(c,maximumFileBytes=4096,maximumFileCount=3)
        val payload="牌".repeat(1000)
        repeat(30) { trace.write(JSONObject().put("type","bounded-rotation-test").put("order",it).put("payload",payload)) }
        trace.close(); assertTrue("Synthetic trace did not drain",trace.awaitClosed(10_000))
        val folder=File(c.filesDir,"sessions/${trace.id}")
        EvidenceWork.maintain {
            val expectedNames=listOf("trace.previous.2.jsonl","trace.previous.jsonl","trace.jsonl")
            val retained=expectedNames.map { File(folder,it) }
            assertTrue(retained.all { it.isFile })
            assertTrue(retained.all { it.length()<=4096 })
            val records=retained.flatMap { it.readLines().filter { line -> line.isNotBlank() }.map(::JSONObject) }
                .filter { it.optString("type")=="bounded-rotation-test" }
            assertEquals((27..29).toList(),records.map { it.getInt("order") })
        }
        val archive=File(c.cacheDir,"bounded-trace-export.zip")
        archive.outputStream().use { DiagnosticArchive.write(c,it) }
        ZipFile(archive).use { zip ->
            val prefix="sessions/${trace.id}/"
            val traceEntries=zip.entries().asSequence().map { it.name }
                .filter { it.startsWith(prefix) && it.substringAfter(prefix).startsWith("trace") }.toSet()
            assertEquals(setOf("trace.previous.2.jsonl","trace.previous.jsonl","trace.jsonl").map { prefix+it }.toSet(),traceEntries)
        }
    }
    @Test fun maintenanceBlocksCaptureAndCaptureBlocksClear()=isolated { c ->
        EvidenceWork.maintain { assertFalse(EvidenceWork.beginCapture()) }
        assertTrue(EvidenceWork.beginCapture())
        try { assertTrue(runCatching { DiagnosticStorage.clear(c) }.isFailure) }
        finally { EvidenceWork.endCapture() }
        DiagnosticStorage.clear(c)
    }
    @Test fun jsonExportDrainsLateCheckpointBeforeOpeningItsDestination()=isolated { c ->
        val release=CountDownLatch(1); val entered=CountDownLatch(1); val done=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>(); val opened=java.util.concurrent.atomic.AtomicBoolean()
        val output=java.io.ByteArrayOutputStream()
        val io=EvidenceExecutor("test-final-checkpoint")
        io.execute {
            entered.countDown(); check(release.await(3,TimeUnit.SECONDS))
            AsyncRoundStore(c).use { it.save(ledger()) }
        }
        assertTrue(entered.await(2,TimeUnit.SECONDS)); io.shutdown()
        Thread {
            try { DiagnosticArchive.export(c,false) { opened.set(true); output } }
            catch(t: Throwable) { failure.set(t) } finally { done.countDown() }
        }.start()
        try {
            assertFalse(done.await(100,TimeUnit.MILLISECONDS)); assertFalse(opened.get())
            release.countDown(); assertTrue(done.await(3,TimeUnit.SECONDS)); assertNull(failure.get())
            val rounds=JSONObject(output.toString("UTF-8")).getJSONArray("rounds")
            assertEquals(1,rounds.length()); assertEquals("round-test",rounds.getJSONObject(0).getString("roundId"))
        } finally { release.countDown() }
    }
    @Test fun activeCaptureRejectsBothExportsWithoutTruncatingDestination()=isolated { c ->
        val output=file(c,"existing-export","keep previous export")
        assertTrue(EvidenceWork.beginCapture())
        try {
            for(fullArchive in listOf(false,true)) {
                assertTrue(runCatching { DiagnosticArchive.export(c,fullArchive) { output.outputStream() } }.isFailure)
                assertEquals("keep previous export",output.readText())
            }
        } finally { EvidenceWork.endCapture() }
    }
    @Test fun successfulExportsCloseTheirDestinationExactlyOnce()=isolated { c ->
        for(fullArchive in listOf(false,true)) {
            var closes=0
            val output=object: java.io.ByteArrayOutputStream() {
                override fun close() { closes++; check(closes==1); super.close() }
            }
            DiagnosticArchive.export(c,fullArchive) { output }
            assertEquals(1,closes); assertTrue(output.size()>0)
        }
    }
    @Test fun failedRotationPreservesExistingTraceWhenReopened()=isolated { c ->
        c.getSharedPreferences("jpq-diagnostics",0).edit().putBoolean("enabled",true).commit()
        val trace=SessionTrace(c,maximumFileBytes=4096)
        // A nonempty directory at the rotation target forces rotation to fail.
        file(c,"sessions/${trace.id}/trace.previous.jsonl/blocked")
        trace.write(JSONObject().put("type","prefix-sentinel"))
        trace.write(JSONObject().put("type","too-large-for-test-limit").put("payload","x".repeat(6000)))
        trace.write(JSONObject().put("type","tail-sentinel"))
        trace.close(); assertTrue(trace.awaitClosed(5000))
        EvidenceWork.maintain {
            val types=File(c.filesDir,"sessions/${trace.id}/trace.jsonl").readLines().map { JSONObject(it).getString("type") }
            assertTrue(types.contains("session")); assertTrue(types.contains("prefix-sentinel")); assertTrue(types.contains("tail-sentinel"))
        }
    }
    @Test fun unavailableTraceDirectoryDoesNotLeakItsWriterLease()=isolated { c ->
        c.getSharedPreferences("jpq-diagnostics",0).edit().putBoolean("enabled",true).commit()
        file(c,"sessions","not a directory")
        val trace=SessionTrace(c)
        trace.write(JSONObject().put("type","storage-unavailable")); trace.close()
        assertTrue(trace.awaitClosed(5000))
        DiagnosticStorage.clear(c)
        assertFalse(File(c.filesDir,"sessions").exists())
    }
    @Test fun rejectedDatabaseInsertsAreNotReportedAsSaved()=isolated { c ->
        RoundRepository(c).use { repository ->
            val state=ledger()
            repository.save(state)
            repository.saveSuitEvidence("round-test",JSONObject().put("proof","original"))
            for(table in listOf("rounds","suit_evidence")) repository.writableDatabase.execSQL(
                "CREATE TRIGGER reject_$table BEFORE INSERT ON $table BEGIN SELECT RAISE(IGNORE); END")
            state.invalidate("not persisted")
            assertTrue(runCatching { repository.save(state) }.isFailure)
            assertTrue(runCatching { repository.saveSuitEvidence("round-test",JSONObject().put("proof","replacement")) }.isFailure)
            val saved=JSONObject(repository.export()).getJSONArray("rounds").getJSONObject(0)
            assertEquals("ACTIVE",saved.getString("phase"))
            assertEquals("original",saved.getJSONObject("suitEvidence").getString("proof"))
        }
    }
    @Test fun failedSuitCheckpointKeepsAcceptedDisplayAndCanRetryIdenticalEvidence()=isolated { c ->
        val previousResult=StraightFlushResult(AppState.straightFlushState,candidateRanks=AppState.straightFlushCandidateRanks)
        val previousWildcards=AppState.straightFlushIncludesWildcards
        val previousCounter=AppState.counterState
        val analyzer=com.cyotun.jpq.vision.StraightFlushAnalyzer(c,Any(),{true},{0L},{})
        try {
            val confirmed=GradeCounterState("round-test",setOf(Rank.TEN),GradeCountBounds(0,0),
                mapOf(Rank.TEN to GradeCountBounds(3,3)))
            AppState.publishStraightFlush(StraightFlushResult(StraightFlushState.IMPOSSIBLE),false)
            AppState.publishCounterState(confirmed)
            RoundRepository(c).use { repository ->
                repository.save(ledger())
                repository.writableDatabase.execSQL("CREATE TRIGGER reject_suit BEFORE INSERT ON suit_evidence BEGIN SELECT RAISE(IGNORE); END")
                val metadata=JSONObject().put("proof","accepted-analysis")
                assertFalse(analyzer.persistCheckpoint("round-test",metadata))
                assertEquals(StraightFlushState.IMPOSSIBLE,AppState.straightFlushState)
                assertTrue(AppState.straightFlushCandidateRanks.isEmpty()); assertEquals(confirmed,AppState.counterState)
                repository.writableDatabase.execSQL("DROP TRIGGER reject_suit")
                assertTrue(analyzer.persistCheckpoint("round-test",metadata))
                val saved=JSONObject(repository.export()).getJSONArray("rounds").getJSONObject(0)
                assertEquals("accepted-analysis",saved.getJSONObject("suitEvidence").getString("proof"))
            }
        } finally {
            analyzer.close()
            AppState.publishStraightFlush(previousResult,previousWildcards)
            AppState.publishCounterState(GradeCounterState()); AppState.publishCounterState(previousCounter)
        }
    }
}
