package com.cyotun.jpq.vision

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.RoundRepository
import com.cyotun.jpq.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoundSuitPersistenceTest {
    @Test fun versionOneUpgradePreservesRoundAndSeparateSuitEvidenceSurvivesCheckpoint() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="suit-migration-${UUID.randomUUID()}.db"
        val path=context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val baseline=Rank.entries.filterNot { it.isJoker }.flatMap { rank ->
            listOf(Card(rank,Suit.RED),Card(rank,Suit.BLACK),Card(rank,Suit.RED))
        }.take(27)
        val ledger=RoundLedger().apply { begin("fixture",baseline) }
        try {
            SQLiteDatabase.openOrCreateDatabase(path,null).use { old ->
                old.execSQL("CREATE TABLE rounds(id TEXT PRIMARY KEY,touched INTEGER NOT NULL,state TEXT NOT NULL)")
                old.execSQL("INSERT INTO rounds VALUES(?,?,?)",arrayOf<Any>("legacy",1,
                    JSONObject().put("roundId","legacy").put("phase","FINISHED").put("events",org.json.JSONArray()).toString()))
                old.version=1
            }
            RoundRepository(context,name).use { repository ->
                assertEquals("legacy",JSONObject(repository.export()).getJSONArray("rounds").getJSONObject(0).getString("roundId"))
                repository.save(ledger)
                val metadata=JSONObject().put("ruleConfirmed",false).put("source","rendered_suits")
                repository.saveSuitEvidence("fixture",metadata)
                repository.save(ledger) // main checkpoint must not erase side-channel annotations
                val exported=JSONObject(repository.export()).getJSONArray("rounds").getJSONObject(0)
                assertEquals(27,exported.getJSONArray("initial").length())
                assertEquals("rendered_suits",exported.getJSONObject("suitEvidence").getString("source"))
                repository.clear()
                assertEquals(0,JSONObject(repository.export()).getJSONArray("rounds").length())
                assertEquals(0,repository.readableDatabase.rawQuery("SELECT COUNT(*) FROM suit_evidence",null).use { it.moveToFirst(); it.getInt(0) })
            }
        } finally { context.deleteDatabase(name) }
    }
}
