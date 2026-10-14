package com.cyotun.jpq

import android.content.*
import android.database.sqlite.*
import com.cyotun.jpq.core.*
import org.json.*

/** Atomic local checkpoints; no capture buffers, remote transport, or guessed resumption. */
class RoundRepository(context: Context,databaseName: String="rounds.db"): SQLiteOpenHelper(context,databaseName,null,2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE rounds(id TEXT PRIMARY KEY, touched INTEGER NOT NULL, state TEXT NOT NULL)")
        createSuitEvidence(db)
    }
    private fun createSuitEvidence(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS suit_evidence(id TEXT PRIMARY KEY, state TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) { if(oldVersion<2) createSuitEvidence(db) }
    /** Separate annotations cannot overwrite the immutable rank/color round checkpoint. */
    @Synchronized fun saveSuitEvidence(id: String,state: JSONObject) {
        writableDatabase.insertWithOnConflict("suit_evidence",null,ContentValues().apply {
            put("id",id); put("state",state.toString())
        },SQLiteDatabase.CONFLICT_REPLACE)
        writableDatabase.execSQL("DELETE FROM suit_evidence WHERE id NOT IN (SELECT id FROM rounds)")
    }
    @Synchronized fun save(ledger: RoundLedger) {
        val s=ledger.snapshot(); val id=s.roundId ?: return
        val state=JSONObject().put("schemaVersion",1).put("roundId",id).put("phase",s.phase.name)
            .put("reason",s.reason ?: JSONObject.NULL).put("initial",cards(ledger.initialHand()))
        val events=JSONArray()
        s.plays.values.flatten().sortedBy { it.sequence }.forEach { p ->
            events.put(JSONObject().put("id",p.eventId).put("sequence",p.sequence).put("seat",p.seat.name).put("cards",cards(p.cards)))
        }
        state.put("events",events)
        val db=writableDatabase; db.beginTransaction()
        try {
            db.insertWithOnConflict("rounds",null,ContentValues().apply {
                put("id",id); put("touched",System.currentTimeMillis()); put("state",state.toString())
            },SQLiteDatabase.CONFLICT_REPLACE)
            db.execSQL("DELETE FROM rounds WHERE id NOT IN (SELECT id FROM rounds ORDER BY touched DESC LIMIT 21)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun export(): String {
        val a=JSONArray(); readableDatabase.rawQuery("SELECT r.state,s.state FROM rounds r LEFT JOIN suit_evidence s ON s.id=r.id ORDER BY r.touched DESC",null).use { q ->
            while(q.moveToNext()) a.put(JSONObject(q.getString(0)).apply { if(!q.isNull(1)) put("suitEvidence",JSONObject(q.getString(1))) })
        }
        return JSONObject().put("schemaVersion",2).put("rounds",a).toString(2)
    }
    fun summary(): String {
        readableDatabase.rawQuery("SELECT touched,state FROM rounds ORDER BY touched DESC LIMIT 20",null).use { q ->
            val lines=mutableListOf<String>()
            while(q.moveToNext()) {
                val j=JSONObject(q.getString(1)); val a=j.getJSONArray("events"); val totals=Seat.entries.associateWith { 0 }.toMutableMap()
                for(i in 0 until a.length()) { val p=a.getJSONObject(i); val seat=Seat.valueOf(p.getString("seat")); totals[seat]=totals[seat]!!+p.getJSONArray("cards").length() }
                val date=java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.CHINA).format(java.util.Date(q.getLong(0)))
                lines.add("$date  上${totals[Seat.PREVIOUS]} / 对${totals[Seat.OPPOSITE]} / 下${totals[Seat.NEXT]}  ${if(j.getString("phase")=="UNAVAILABLE") "未完整确认" else "已记录"}")
            }; return lines.joinToString("\n\n").ifEmpty { "暂无牌局记录" }
        }
    }
    @Synchronized fun clear() {
        val db=writableDatabase; db.beginTransaction()
        try { db.delete("rounds",null,null); db.delete("suit_evidence",null,null); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }
    private fun cards(values: List<Card>)=JSONArray(values.map { JSONObject().put("rank",it.rank.name).put("colorOrSuit",it.suit.name) })
}
