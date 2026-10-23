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
    @Test fun traceRotationRetainsOrderedUtf8RecordsAcrossBothFiles()=isolated { c ->
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
            val previous=File(folder,"trace.previous.jsonl"); val current=File(folder,"trace.jsonl")
            assertTrue(previous.exists()); assertTrue(current.exists())
            assertTrue(previous.length()<=8L*1024*1024); assertTrue(current.length()<=8L*1024*1024)
            val records=listOf(previous,current).flatMap { file -> file.readLines().filter { it.isNotBlank() }.map(::JSONObject) }
                .filter { it.optString("type")=="rotation-test" }
            assertEquals((0..29).toList(),records.map { it.getInt("order") })
            assertTrue(records.all { it.getString("payload")==payload })
        }
    }
    @Test fun maintenanceBlocksCaptureAndCaptureBlocksClear()=isolated { c ->
        EvidenceWork.maintain { assertFalse(EvidenceWork.beginCapture()) }
        assertTrue(EvidenceWork.beginCapture())
        try { assertTrue(runCatching { DiagnosticStorage.clear(c) }.isFailure) }
        finally { EvidenceWork.endCapture() }
        DiagnosticStorage.clear(c)
    }
}
