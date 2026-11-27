package hr.brendigo.fiskai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Explicit full settlement of one local DEMO receipt with one confirmed DEMO payment.
 * Not an eInvoice, an eReporting submission or a production payment allocation.
 * The same physical SQLite transaction is used for references and encrypted evidence.
 */
internal object PaymentPreparationStore {
    private const val TABLE="demo_payment_preparation"
    private const val NS="fiskai_demo_payment_preparation"

    private fun receipts(context:Context,db:RecordDatabase,sql:SQLiteDatabase):Map<String,ReceiptEvidence> {
        val (bank,events)=ReceiptStore.export(context,db,sql)
        return ReceiptStore.validate(bank,events).receipts.associateBy{it.id}
    }
    private fun uuid(value:String) { require(UUID.fromString(value).toString()==value) }
    private fun snapshot(context:Context,db:RecordDatabase,sql:SQLiteDatabase,receipt:ReceiptEvidence,document:String,event:String):JSONObject {
        uuid(document);uuid(event)
        require(receipt.id.matches(Regex("[0-9a-f]{64}")))
        val secure=SecureRecords(context,"fiskai_demo_receipts",db)
        val digest=sql.rawQuery("SELECT payload,digest,created,draftId,year,number,premise,registerNo FROM fiscal_document WHERE id=?",arrayOf(document)).use{c->
            require(c.moveToFirst())
            val plain=secure.openSnapshot(document,c.getString(0))
            require(FiscalCutover.digest(plain)==c.getString(1))
            val p=JSONObject(plain)
            require(p.getString("environment")=="DEMO" && p.getString("sellerOib")==receipt.owner &&
                p.getString("draft")==c.getString(3) && p.getLong("created")==c.getLong(2) &&
                p.getInt("year")==c.getInt(4) && p.getLong("number")==c.getLong(5) &&
                p.getString("premise")==c.getString(6) && p.getString("register")==c.getString(7))
            require(ReceiptRules.cents(p.getString("total"))==receipt.cents && receipt.cents>0)
            val date=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).apply{timeZone=TimeZone.getTimeZone("Europe/Zagreb")}.format(Date(c.getLong(2)))
            require(receipt.date>=date)
            c.getString(1)
        }
        val deadline=FiscalReportingRules.deadline(FiscalReportingRules.Event.PAYMENT,receipt.date)
        require(deadline.kind==FiscalReportingRules.DeadlineKind.MONTHLY_STATUTORY_DATE)
        val rule= requireNotNull(LegalRuleCatalog.rules[LegalCompliance.validateDate(receipt.date)])
        val receiptDigest=FiscalCutover.digest(ReceiptRules.receiptJson(receipt).toString())
        return JSONObject().put("eventId",event).put("environment","DEMO").put("kind","DEMO_PAYMENT_PREPARATION")
            .put("receiptId",receipt.id).put("documentId",document).put("documentDigest",digest)
            .put("receiptDigest",receiptDigest).put("evidenceSha256",FiscalCutover.digest("DEMO_PAYMENT_V1\u0000"+digest+"\u0000"+receiptDigest))
            .put("eventDate",receipt.date).put("amountCents",receipt.cents).put("ruleVersion",rule.version)
            .put("deadlineDate",requireNotNull(deadline.date))
    }
    private fun same(actual:JSONObject,expected:JSONObject):Boolean =
        actual.keys().asSequence().toSet()==expected.keys().asSequence().toSet() &&
        expected.keys().asSequence().all { key ->
            val a=actual.get(key);val e=expected.get(key)
            if(e is Long || e is Int) (a is Long || a is Int) && (a as Number).toLong()==(e as Number).toLong()
            else a==e
        }
    private fun checkSnapshot(context:Context,db:RecordDatabase,sql:SQLiteDatabase,rows:Map<String,ReceiptEvidence>,j:JSONObject):JSONObject {
        val expected=snapshot(context,db,sql,requireNotNull(rows[j.getString("receiptId")]),j.getString("documentId"),j.getString("eventId"))
        require(same(j,expected))
        return expected
    }
    private fun insert(context:Context,db:RecordDatabase,sql:SQLiteDatabase,j:JSONObject) {
        val id=j.getString("receiptId")
        sql.insertOrThrow(TABLE,null,ContentValues().apply{
            put("receiptId",id);put("documentId",j.getString("documentId"));put("eventId",j.getString("eventId"))
            put("payload",SecureRecords(context,NS,db).sealSnapshot(id,j.toString()))
        })
    }

    data class Choice(val id:String,val label:String,val customer:String,val date:String)
    data class Selection(val choices:List<Choice>,val linked:Boolean)

    /** Recent eligible DEMO documents only. No automatic selection or allocation. */
    fun selection(context:Context,db:RecordDatabase,receiptId:String):Selection=SecureRecords.withRecordLock {
        check(BuildConfig.DEMO_MODE)
        val sql=db.writableDatabase
        sql.beginTransaction()
        try {
            if(!FiscalCutover.active(sql)) {
                sql.setTransactionSuccessful()
                return@withRecordLock Selection(emptyList(),false)
            }
            val rows=receipts(context,db,sql)
            val receipt=requireNotNull(rows[receiptId])
            val existing=sql.rawQuery("SELECT documentId,eventId,payload FROM "+TABLE+" WHERE receiptId=?",arrayOf(receiptId)).use{c->
                if(!c.moveToFirst())null else {
                    val j=JSONObject(SecureRecords(context,NS,db).openSnapshot(receiptId,c.getString(2)))
                    require(j.getString("receiptId")==receiptId && j.getString("documentId")==c.getString(0) && j.getString("eventId")==c.getString(1))
                    checkSnapshot(context,db,sql,rows,j)
                    c.getString(0)
                }
            }
            fun choice(id:String):Choice=sql.rawQuery("SELECT number,premise,registerNo,year,created,payload FROM fiscal_document WHERE id=?",arrayOf(id)).use{c->
                require(c.moveToFirst())
                val p=JSONObject(SecureRecords(context,"fiskai_demo_receipts",db).openSnapshot(id,c.getString(5)))
                val date=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).apply{timeZone=TimeZone.getTimeZone("Europe/Zagreb")}.format(Date(c.getLong(4)))
                Choice(id,c.getLong(0).toString()+"/"+c.getString(1)+"/"+c.getString(2)+" ("+c.getInt(3)+")",p.getString("customer"),date)
            }
            val choices=if(existing!=null)listOf(choice(existing)) else {
                val ids=sql.rawQuery("SELECT id FROM fiscal_document WHERE NOT EXISTS(SELECT 1 FROM demo_payment_preparation p WHERE p.documentId=fiscal_document.id) ORDER BY created DESC,id DESC LIMIT 100",null).use{c->
                    buildList{while(c.moveToNext())add(c.getString(0))}
                }
                ids.mapNotNull{id->
                    try {
                        snapshot(context,db,sql,receipt,id,"00000000-0000-4000-8000-000000000001")
                        choice(id)
                    } catch(_:IllegalArgumentException){null} catch(_:org.json.JSONException){null}
                }
            }
            sql.setTransactionSuccessful()
            Selection(choices,existing!=null)
        }finally{sql.endTransaction()}
    }

    /** Explicitly invoked only in isolated DEMO build; never sends or claims a server event. */
    fun prepare(context:Context,db:RecordDatabase,receiptId:String,documentId:String):String=SecureRecords.withRecordLock {
        check(BuildConfig.DEMO_MODE) { "Preparation requires isolated DEMO" }
        val sql=db.writableDatabase
        sql.beginTransaction()
        try {
            check(FiscalCutover.active(sql))
            val rows=receipts(context,db,sql)
            val receipt=requireNotNull(rows[receiptId])
            val existing=sql.rawQuery("SELECT receiptId,documentId,eventId,payload FROM "+TABLE+" WHERE receiptId=? OR documentId=?",arrayOf(receiptId,documentId)).use{c->
                if(!c.moveToFirst())null else {
                    require(c.getString(0)==receiptId && c.getString(1)==documentId)
                    val event=c.getString(2)
                    val j=JSONObject(SecureRecords(context,NS,db).openSnapshot(receiptId,c.getString(3)))
                    require(j.getString("eventId")==event && j.getString("receiptId")==receiptId && j.getString("documentId")==documentId)
                    checkSnapshot(context,db,sql,rows,j)
                    require(!c.moveToNext());event
                }
            }
            val event=existing?:UUID.randomUUID().toString().also{insert(context,db,sql,snapshot(context,db,sql,receipt,documentId,it))}
            sql.setTransactionSuccessful()
            event
        } finally {sql.endTransaction()}
    }
    /** Called under record lock within the workspace transaction. */
    fun export(context:Context,db:RecordDatabase,sql:SQLiteDatabase):JSONArray {
        val result=JSONArray()
        sql.rawQuery("SELECT receiptId,documentId,eventId,payload FROM "+TABLE+" ORDER BY receiptId",null).use{c->
            if(!c.moveToFirst())return result
            check(BuildConfig.DEMO_MODE && FiscalCutover.active(sql))
            val rows=receipts(context,db,sql)
            val secure=SecureRecords(context,NS,db)
            do {
                require(result.length()<ReceiptRules.MAX_ROWS)
                val j=JSONObject(secure.openSnapshot(c.getString(0),c.getString(3)))
                require(j.getString("receiptId")==c.getString(0) && j.getString("documentId")==c.getString(1) && j.getString("eventId")==c.getString(2))
                result.put(checkSnapshot(context,db,sql,rows,j))
            } while(c.moveToNext())
        }
        return result
    }
    fun restore(context:Context,db:RecordDatabase,sql:SQLiteDatabase,array:JSONArray) {
        check(BuildConfig.DEMO_MODE && FiscalCutover.active(sql))
        require(array.length() in 1..ReceiptRules.MAX_ROWS)
        require(export(context,db,sql).length()==0)
        val receipts=receipts(context,db,sql)
        val rows=List(array.length()){checkSnapshot(context,db,sql,receipts,array.getJSONObject(it))}
        for(key in listOf("receiptId","documentId","eventId"))require(rows.map{it.getString(key)}.distinct().size==rows.size)
        rows.forEach{insert(context,db,sql,it)}
    }
}
