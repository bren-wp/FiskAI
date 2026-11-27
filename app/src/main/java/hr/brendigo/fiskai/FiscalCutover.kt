package hr.brendigo.fiskai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal object FiscalCutover {
    const val PREVIOUS_DB="fiskai_fiscal_room.db"
    fun active(sql:SQLiteDatabase):Boolean=sql.rawQuery("SELECT 1 FROM fiscal_cutover WHERE id=1",null).use{it.moveToFirst()}
    fun digest(plain:String):String=MessageDigest.getInstance("SHA-256").digest(plain.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun count(sql:SQLiteDatabase,table:String):Long {
        require(table in setOf("fiscal_document","fiscal_outbox","demo_receipt"))
        return sql.rawQuery("SELECT COUNT(*) FROM "+table,null).use{check(it.moveToFirst());it.getLong(0)}
    }

    // Copies the previous outbox only if every document exactly matches its immutable legacy snapshot.
    fun ensure(context:Context,db:RecordDatabase,previousName:String?=PREVIOUS_DB)=SecureRecords.withRecordLock {
        val sql=db.writableDatabase
        sql.beginTransaction()
        try {
            if(!active(sql)) {
                val previous=previousName?.let{context.getDatabasePath(it)}?.takeIf{it.isFile}
                val queues=if(previous==null) JSONObject() else SQLiteDatabase.openDatabase(previous.path,null,SQLiteDatabase.OPEN_READONLY).use{old->
                    require(old.version==1) { "Unexpected previous Room version" }
                    val result=JSONObject()
                    old.rawQuery("SELECT d.id,d.draftId,d.year,d.premise,d.registerNo,d.number,d.created,d.payload,d.digest,q.state,q.created,q.updated,q.claimToken,q.reason FROM fiscal_document d JOIN fiscal_outbox q ON q.documentId=d.id",null).use{c->
                        while(c.moveToNext()) {
                            sql.rawQuery("SELECT draft_id,year,premise,register_no,number,created,payload FROM demo_receipt WHERE id=?",arrayOf(c.getString(0))).use{source->
                                require(source.moveToFirst() && source.getString(0)==c.getString(1) && source.getInt(1)==c.getInt(2) &&
                                    source.getString(2)==c.getString(3) && source.getString(3)==c.getString(4) && source.getLong(4)==c.getLong(5) &&
                                    source.getLong(5)==c.getLong(6) && source.getString(6)==c.getString(7))
                                val plain=SecureRecords(context,"fiskai_demo_receipts",db).openSnapshot(c.getString(0),source.getString(6))
                                require(digest(plain)==c.getString(8))
                            }
                            result.put(c.getString(0),JSONObject().put("state",c.getString(9)).put("created",c.getLong(10)).put("updated",c.getLong(11))
                                .put("claimToken",if(c.isNull(12))JSONObject.NULL else c.getString(12)).put("reason",if(c.isNull(13))JSONObject.NULL else c.getString(13)))
                        }
                    }
                    require(result.length().toLong()==count(old,"fiscal_document") && result.length().toLong()==count(old,"fiscal_outbox"))
                    result
                }
                populate(context,db,sql,queues)
            }
            sql.setTransactionSuccessful()
        } finally {sql.endTransaction()}
    }

    // Used by cutover and by restore inside the same physical database transaction.
    fun populate(context:Context,db:RecordDatabase,sql:SQLiteDatabase,queues:JSONObject) {
        require(count(sql,"fiscal_document")==0L && count(sql,"fiscal_outbox")==0L)
        val secure=SecureRecords(context,"fiskai_demo_receipts",db)
        data class Scope(val year:Int,val premise:String,val register:String)
        val last=mutableMapOf<Scope,Long>()
        sql.rawQuery("SELECT year,premise,register_no,last_no FROM demo_sequence",null).use{c->
            while(c.moveToNext()) {
                val n=c.getLong(3);require(n in 1L..999999999L)
                last[Scope(c.getInt(0),c.getString(1),c.getString(2))]=n
            }
        }
        val seen=mutableMapOf<Scope,Long>()
        val used=mutableSetOf<String>()
        sql.rawQuery("SELECT id,draft_id,year,premise,register_no,number,created,payload FROM demo_receipt ORDER BY year,premise,register_no,number",null).use{c->
            while(c.moveToNext()) {
                val id=c.getString(0);val draft=c.getString(1);val year=c.getInt(2);val premise=c.getString(3);val register=c.getString(4);val number=c.getLong(5);val created=c.getLong(6);val ciphertext=c.getString(7)
                val scope=Scope(year,premise,register)
                require(number==(seen[scope]?:0L)+1 && number<=(last[scope]?:0L))
                seen[scope]=number
                val plain=secure.openSnapshot(id,ciphertext)
                val p=JSONObject(plain)
                require(p.getString("environment")=="DEMO" && p.getString("draft")==draft && p.getString("premise")==premise &&
                    p.getString("register")==register && p.getLong("created")==created)
                val bound=p.has("number")&&p.has("year")
                if(bound)require(p.getLong("number")==number&&p.getInt("year")==year)
                val q=queues.optJSONObject(id)
                if(q!=null)used.add(id)
                val state=q?.getString("state")?:"ARCHIVED"
                require(bound||state=="ARCHIVED")
                val row=ContentValues().apply{put("id",id);put("draftId",draft);put("year",year);put("premise",premise);put("registerNo",register);put("number",number);put("created",created);put("payload",ciphertext);put("digest",digest(plain))}
                sql.insertOrThrow("fiscal_document",null,row)
                sql.insertOrThrow("fiscal_outbox",null,queueValues(id,q,created))
            }
        }
        require(seen==last && used==queues.keys().asSequence().toSet())
        sql.execSQL("INSERT OR IGNORE INTO fiscal_cutover(id) VALUES(1)")
    }
    private fun queueValues(id:String,q:JSONObject?,created:Long):ContentValues {
        if(q!=null)require(q.keys().asSequence().toSet()==setOf("state","created","updated","claimToken","reason"))
        val state=q?.getString("state")?:"ARCHIVED"
        val token=if(q==null||q.isNull("claimToken"))null else q.getString("claimToken")
        val reason=if(q==null||q.isNull("reason"))null else q.getString("reason")
        require(state in setOf("ARCHIVED","QUEUED","SENDING","UNCERTAIN"))
        if(state in setOf("ARCHIVED","QUEUED"))require(token==null&&reason==null)
        else {
            require(token!=null && java.util.UUID.fromString(token).toString()==token)
            if(state=="SENDING")require(reason==null) else require(reason in setOf("INTERRUPTED","TRANSPORT_ERROR","UNVERIFIED_RESPONSE"))
        }
        if(q!=null)require(q.getLong("created")>0 && q.getLong("updated")>=q.getLong("created"))
        return ContentValues().apply{
            put("documentId",id);put("state",if(state=="SENDING")"UNCERTAIN" else state)
            put("created",q?.getLong("created")?:created);put("updated",if(state=="SENDING")System.currentTimeMillis() else q?.getLong("updated")?:created)
            put("claimToken",token);put("reason",if(state=="SENDING")"INTERRUPTED" else reason)
        }
    }

    // Portable backup: canonical receipts and exact queue metadata from the unified file.
    fun export(context:Context,db:RecordDatabase,sql:SQLiteDatabase,journals:JSONObject):JSONObject {
        check(active(sql))
        val secure=SecureRecords(context,"fiskai_demo_receipts",db)
        val receipts=JSONArray();val queues=JSONObject()
        data class Scope(val year:Int,val premise:String,val register:String)
        val seq=mutableMapOf<Scope,Long>()
        sql.rawQuery("SELECT d.id,d.draftId,d.year,d.premise,d.registerNo,d.number,d.created,d.payload,d.digest,q.state,q.created,q.updated,q.claimToken,q.reason FROM fiscal_document d JOIN fiscal_outbox q ON q.documentId=d.id ORDER BY d.year,d.premise,d.registerNo,d.number",null).use{c->
            while(c.moveToNext()) {
                require(receipts.length()<20_000)
                val id=c.getString(0);val year=c.getInt(2);val premise=c.getString(3);val register=c.getString(4);val n=c.getLong(5)
                val scope=Scope(year,premise,register);require(n==(seq[scope]?:0L)+1);seq[scope]=n
                val plain=secure.openSnapshot(id,c.getString(7));require(digest(plain)==c.getString(8))
                receipts.put(JSONObject().put("id",id).put("draft_id",c.getString(1)).put("year",year).put("premise",premise).put("register_no",register).put("number",n).put("created",c.getLong(6)).put("payload",JSONObject(plain)))
                queues.put(id,JSONObject().put("state",c.getString(9)).put("created",c.getLong(10)).put("updated",c.getLong(11)).put("claimToken",if(c.isNull(12))JSONObject.NULL else c.getString(12)).put("reason",if(c.isNull(13))JSONObject.NULL else c.getString(13)))
            }
        }
        require(receipts.length().toLong()==count(sql,"fiscal_document") && receipts.length().toLong()==count(sql,"fiscal_outbox"))
        val sequences=JSONArray()
        for((scope,n) in seq)sequences.put(JSONObject().put("year",scope.year).put("premise",scope.premise).put("register_no",scope.register).put("last_no",n))
        journals.put("demo_receipt",receipts).put("demo_sequence",sequences)
        LedgerBackup(context,db).validate(journals)
        return queues
    }
}
