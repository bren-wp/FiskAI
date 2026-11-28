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

/** Explicit allocations of confirmed DEMO payments to immutable local documents.
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
    private data class Checked(val json:JSONObject,val total:Long)
    private fun snapshot(context:Context,db:RecordDatabase,sql:SQLiteDatabase,receipt:ReceiptEvidence,document:String,event:String,
        catalogDigest:String=LegalRuleCatalog.CATALOG_SHA256,resolver:HistoricalLegalDecisionResolver?=null):Checked {
        uuid(document);uuid(event)
        require(receipt.id.matches(Regex("[0-9a-f]{64}")))
        val secure=SecureRecords(context,"fiskai_demo_receipts",db)
        val (digest,total)=sql.rawQuery("SELECT payload,digest,created,draftId,year,number,premise,registerNo FROM fiscal_document WHERE id=?",arrayOf(document)).use{c->
            require(c.moveToFirst())
            val plain=secure.openSnapshot(document,c.getString(0))
            require(FiscalCutover.digest(plain)==c.getString(1))
            val p=JSONObject(plain)
            require(p.getString("environment")=="DEMO" && p.getString("sellerOib")==receipt.owner &&
                p.getString("draft")==c.getString(3) && p.getLong("created")==c.getLong(2) &&
                p.getInt("year")==c.getInt(4) && p.getLong("number")==c.getLong(5) &&
                p.getString("premise")==c.getString(6) && p.getString("register")==c.getString(7))
            val total=ReceiptRules.cents(p.getString("total"))
            require(total>0 && receipt.cents in 1..total)
            val date=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).apply{timeZone=TimeZone.getTimeZone("Europe/Zagreb")}.format(Date(c.getLong(2)))
            require(receipt.date>=date)
            c.getString(1) to total
        }
        val rule=if(catalogDigest==LegalRuleCatalog.CATALOG_SHA256) requireNotNull(LegalRuleCatalog.resolve(receipt.date))
            else requireNotNull(requireNotNull(resolver){"Independently verified historical catalog required"}
                .resolveByDigest(catalogDigest,receipt.date,LegalCompliance.Subject.UNKNOWN,
                    LegalCompliance.Scope.STANDARD_DOMESTIC_B2B).rule)
        // Frozen nominal monthly payment policy for formats 0–3; not a legal authorization.
        val deadline=FiscalReportingRules.nominalMonthlyV1(receipt.date)
        val receiptDigest=FiscalCutover.digest(ReceiptRules.receiptJson(receipt).toString())
        return Checked(JSONObject().put("eventId",event).put("environment","DEMO").put("kind","DEMO_PAYMENT_PREPARATION")
            .put("receiptId",receipt.id).put("documentId",document).put("documentDigest",digest)
            .put("receiptDigest",receiptDigest).put("evidenceSha256",FiscalCutover.digest("DEMO_PAYMENT_V1\u0000"+digest+"\u0000"+receiptDigest))
            .put("eventDate",receipt.date).put("amountCents",receipt.cents).put("ruleVersion",rule.version)
            .put("deadlineDate",requireNotNull(deadline.date))
            .put("legalDecisionFormat",3).put("catalogSha256",catalogDigest)
            .put("ruleFrom",rule.from).put("ruleUntil",rule.until)
            .put("legalSource","https://narodne-novine.nn.hr/clanci/sluzbeni/2025_06_89_1233.html")
            .put("deadlineKind",deadline.kind.name),total)
    }
    private fun same(actual:JSONObject,expected:JSONObject):Boolean =
        actual.keys().asSequence().toSet()==expected.keys().asSequence().toSet() &&
        expected.keys().asSequence().all { key ->
            val a=actual.get(key);val e=expected.get(key)
            if(e is Long || e is Int) (a is Long || a is Int) && (a as Number).toLong()==(e as Number).toLong()
            else a==e
        }
    private fun checkSnapshot(context:Context,db:RecordDatabase,sql:SQLiteDatabase,rows:Map<String,ReceiptEvidence>,j:JSONObject,
        resolver:HistoricalLegalDecisionResolver?=null):Checked {
        val format=j.optInt("legalDecisionFormat",0)
        require(format in 0..3)
        val digest=if(format==3)j.getString("catalogSha256") else LegalRuleCatalog.CATALOG_SHA256
        val expected=snapshot(context,db,sql,requireNotNull(rows[j.getString("receiptId")]),j.getString("documentId"),j.getString("eventId"),digest,resolver)
        if(j.optInt("legalDecisionFormat",0)<3)expected.json.remove("catalogSha256")
        if (!j.has("legalDecisionFormat")) {
            // Legacy evidence is not retroactively supplemented.
            for (key in listOf("legalDecisionFormat","ruleFrom","ruleUntil","legalSource","deadlineKind")) expected.json.remove(key)
        }
        else if(j.getInt("legalDecisionFormat") in 1..2)expected.json.put("legalDecisionFormat",j.getInt("legalDecisionFormat"))
        require(same(j,expected.json))
        return expected
    }
    private fun insert(context:Context,db:RecordDatabase,sql:SQLiteDatabase,j:JSONObject) {
        val id=j.getString("receiptId")
        sql.insertOrThrow(TABLE,null,ContentValues().apply{
            put("receiptId",id);put("documentId",j.getString("documentId"));put("eventId",j.getString("eventId"))
            put("payload",SecureRecords(context,NS,db).sealSnapshot(id,j.toString()))
        })
        LegalDecisionStore.insert(context,db,sql,j)
    }

    private fun validateTotals(rows:List<Checked>,legacyFull:Boolean=false):Map<String,Long> {
        val totals=mutableMapOf<String,Long>()
        for(key in listOf("receiptId","eventId"))require(rows.map{it.json.getString(key)}.distinct().size==rows.size)
        if(legacyFull)require(rows.map{it.json.getString("documentId")}.distinct().size==rows.size)
        for(row in rows) {
            val id=row.json.getString("documentId")
            val amount=row.json.getLong("amountCents")
            if(legacyFull)require(amount==row.total)
            val sum=Math.addExact(totals[id]?:0L,amount)
            require(sum<=row.total) { "Document overpayment" }
            totals[id]=sum
        }
        return totals
    }
    private fun load(context:Context,db:RecordDatabase,sql:SQLiteDatabase,receipts:Map<String,ReceiptEvidence>,resolver:HistoricalLegalDecisionResolver?=null):List<Checked> {
        val result=mutableListOf<Checked>()
        val secure=SecureRecords(context,NS,db)
        sql.rawQuery("SELECT receiptId,documentId,eventId,payload FROM "+TABLE+" ORDER BY receiptId",null).use{c->
            while(c.moveToNext()) {
                require(result.size<ReceiptRules.MAX_ROWS)
                val j=JSONObject(secure.openSnapshot(c.getString(0),c.getString(3)))
                require(j.getString("receiptId")==c.getString(0) && j.getString("documentId")==c.getString(1) && j.getString("eventId")==c.getString(2))
                LegalDecisionStore.verify(context,db,sql,j)
                result.add(checkSnapshot(context,db,sql,receipts,j,resolver))
            }
        }
        validateTotals(result)
        return result
    }
    data class Choice(val id:String,val label:String,val customer:String,val date:String,val remainingCents:Long)
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
            val receipts=receipts(context,db,sql)
            val receipt=requireNotNull(receipts[receiptId])
            val existing=load(context,db,sql,receipts)
            val totals=validateTotals(existing)
            val linked=existing.singleOrNull{it.json.getString("receiptId")==receiptId}
            fun choice(id:String,remaining:Long):Choice=sql.rawQuery("SELECT number,premise,registerNo,year,created,payload FROM fiscal_document WHERE id=?",arrayOf(id)).use{c->
                require(c.moveToFirst())
                val p=JSONObject(SecureRecords(context,"fiskai_demo_receipts",db).openSnapshot(id,c.getString(5)))
                val date=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).apply{timeZone=TimeZone.getTimeZone("Europe/Zagreb")}.format(Date(c.getLong(4)))
                Choice(id,c.getLong(0).toString()+"/"+c.getString(1)+"/"+c.getString(2)+" ("+c.getInt(3)+")",p.getString("customer"),date,remaining)
            }
            val choices=if(linked!=null) {
                val id=linked.json.getString("documentId")
                listOf(choice(id,linked.total-(totals[id]?:0L)))
            } else {
                val ids=sql.rawQuery("SELECT id FROM fiscal_document ORDER BY created DESC,id DESC LIMIT 100",null).use{c->
                    buildList{while(c.moveToNext())add(c.getString(0))}
                }
                ids.mapNotNull{id->
                    try {
                        val checked=snapshot(context,db,sql,receipt,id,"00000000-0000-4000-8000-000000000001")
                        val remaining=checked.total-(totals[id]?:0L)
                        if(receipt.cents<=remaining)choice(id,remaining) else null
                    } catch(_:IllegalArgumentException){null} catch(_:org.json.JSONException){null}
                }
            }
            sql.setTransactionSuccessful()
            Selection(choices,linked!=null)
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
            val existing=load(context,db,sql,rows)
            val linked=existing.singleOrNull{it.json.getString("receiptId")==receiptId}
            val event=if(linked!=null) {
                require(linked.json.getString("documentId")==documentId)
                linked.json.getString("eventId")
            } else {
                require(existing.size<ReceiptRules.MAX_ROWS)
                UUID.randomUUID().toString().also{
                    val checked=snapshot(context,db,sql,receipt,documentId,it)
                    validateTotals(existing+checked)
                    insert(context,db,sql,checked.json)
                }
            }
            sql.setTransactionSuccessful()
            event
        } finally {sql.endTransaction()}
    }
    /** Called under record lock within the workspace transaction. */
    fun export(context:Context,db:RecordDatabase,sql:SQLiteDatabase,resolver:HistoricalLegalDecisionResolver?=null):JSONArray {
        val empty=sql.rawQuery("SELECT 1 FROM "+TABLE+" LIMIT 1",null).use{!it.moveToFirst()}
        if(empty)return JSONArray()
        check(BuildConfig.DEMO_MODE && FiscalCutover.active(sql))
        return JSONArray().apply{load(context,db,sql,receipts(context,db,sql),resolver).forEach{put(it.json)}}
    }
    fun restore(context:Context,db:RecordDatabase,sql:SQLiteDatabase,array:JSONArray,legacyFull:Boolean,resolver:HistoricalLegalDecisionResolver?=null) {
        check(BuildConfig.DEMO_MODE && FiscalCutover.active(sql))
        require(array.length() in 1..ReceiptRules.MAX_ROWS)
        require(export(context,db,sql).length()==0)
        val receipts=receipts(context,db,sql)
        val rows=List(array.length()){checkSnapshot(context,db,sql,receipts,array.getJSONObject(it),resolver)}
        validateTotals(rows,legacyFull)
        rows.forEach{insert(context,db,sql,it.json)}
    }
}
