package com.cyotun.jpq

import android.content.*
import android.database.sqlite.*
import com.cyotun.jpq.core.*
import org.json.*

/** Atomic local checkpoints; no capture buffers, remote transport, or guessed resumption. */
class RoundRepository(context: Context): SQLiteOpenHelper(context,"rounds.db",null,1) {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE rounds(id TEXT PRIMARY KEY, touched INTEGER NOT NULL, state TEXT NOT NULL)") }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) = Unit
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
        val a=JSONArray(); readableDatabase.rawQuery("SELECT state FROM rounds ORDER BY touched DESC",null).use { q -> while(q.moveToNext()) a.put(JSONObject(q.getString(0))) }
        return JSONObject().put("schemaVersion",1).put("rounds",a).toString(2)
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
    fun clear() { writableDatabase.delete("rounds",null,null) }
    private fun cards(values: List<Card>)=JSONArray(values.map { JSONObject().put("rank",it.rank.name).put("colorOrSuit",it.suit.name) })
}
