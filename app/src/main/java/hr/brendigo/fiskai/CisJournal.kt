package hr.brendigo.fiskai

import android.content.ContentValues
import android.content.Context
import java.util.UUID

internal data class CisAttempt(val id:String,val created:Long,val state:String,val outcome:String?)
internal class CisJournal(context: Context, private val db: RecordDatabase=RecordDatabase.shared(context)) {
    fun enqueue(): String {
        val id=UUID.randomUUID().toString()
        val values=ContentValues().apply { put("id",id);put("created",System.currentTimeMillis());put("state","QUEUED") }
        db.writableDatabase.insertOrThrow("cis_echo",null,values)
        return id
    }
    fun claim(id:String):Boolean {
        val values=ContentValues().apply {put("state","SENDING")}
        return db.writableDatabase.update("cis_echo",values,"id=? AND state='QUEUED'",arrayOf(id))==1
    }
    fun finish(id:String,outcome:String):Boolean {
        val values=ContentValues().apply {
            put("state",if(outcome=="MATCH") "VERIFIED" else "UNCERTAIN")
            put("outcome",outcome)
            put("completed",System.currentTimeMillis())
        }
        return db.writableDatabase.update("cis_echo",values,"id=? AND state='SENDING'",arrayOf(id))==1
    }
    fun recoverInterrupted() {
        val values=ContentValues().apply {put("state","UNCERTAIN");put("outcome","INTERRUPTED");put("completed",System.currentTimeMillis())}
        db.writableDatabase.update("cis_echo",values,"state IN ('QUEUED','SENDING')",null)
    }
    fun history():List<CisAttempt> = db.readableDatabase.query("cis_echo",arrayOf("id","created","state","outcome"),null,null,null,null,"created DESC","50").use { c ->
        buildList { while(c.moveToNext()) add(CisAttempt(c.getString(0),c.getLong(1),c.getString(2),c.getString(3))) }
    }
}
