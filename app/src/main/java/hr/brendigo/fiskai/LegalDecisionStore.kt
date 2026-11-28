package hr.brendigo.fiskai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** Called under the record lock and within the payment transaction. The encrypted
 * payment contains the mandatory-ledger marker, so absence cannot silently pass. */
internal object LegalDecisionStore {
    private const val TABLE="legal_decision_ledger"
    private const val NS="fiskai_legal_decision_ledger"
    private fun snapshot(payment:JSONObject):JSONObject {
        val format=payment.getInt("legalDecisionFormat")
        require(format in 2..3)
        return JSONObject().apply {
            put("format",format)
            if(format==3) {
                val digest=payment.getString("catalogSha256")
                require(digest.matches(Regex("[0-9a-f]{64}")))
                put("catalogSha256",digest)
            }
            for(key in listOf("receiptId","documentId","documentDigest","eventId","eventDate",
                "ruleVersion","ruleFrom","ruleUntil","legalSource","deadlineKind","deadlineDate",
                "evidenceSha256"))put(key,payment.getString(key))
            put("trust","LOCAL_BASELINE_REVIEW_REQUIRED")
            put("dispatchAuthorized",false)
        }
    }
    fun insert(context:Context,db:RecordDatabase,sql:SQLiteDatabase,payment:JSONObject) {
        check(sql.inTransaction())
        if(payment.optInt("legalDecisionFormat",0) !in 2..3)return
        val id=payment.getString("receiptId")
        sql.insertOrThrow(TABLE,null,ContentValues().apply {
            put("receiptId",id)
            put("payload",SecureRecords(context,NS,db).sealSnapshot(id,snapshot(payment).toString()))
        })
    }
    fun verify(context:Context,db:RecordDatabase,sql:SQLiteDatabase,payment:JSONObject) {
        val id=payment.getString("receiptId")
        val payload=sql.rawQuery("SELECT payload FROM "+TABLE+" WHERE receiptId=?",arrayOf(id)).use {
            if(it.moveToFirst())it.getString(0) else null
        }
        if(payment.optInt("legalDecisionFormat",0) !in 2..3) {
            require(payload==null){"Unexpected legal decision"}
            return
        }
        check(payload!=null){"Required legal decision missing"}
        val saved=JSONObject(SecureRecords(context,NS,db).openSnapshot(id,payload))
        val expected=snapshot(payment)
        require(saved.keys().asSequence().toSet()==expected.keys().asSequence().toSet() &&
            expected.keys().asSequence().all { saved.get(it)==expected.get(it) }){"Legal decision mismatch"}
    }

    /** Independent audit of an authenticated local decision; does not mutate or authorize it.
     * Only the F2 catalog interval is verified, not the separate reporting-deadline policy. */
    fun auditRevision(context:Context,db:RecordDatabase,receiptId:String,resolver:HistoricalLegalDecisionResolver):HistoricalLegalDecisionResolver.Result =
        SecureRecords.withRecordLock {
            val sql=db.readableDatabase
            sql.beginTransaction()
            try {
                val payment=sql.rawQuery("SELECT payload FROM demo_payment_preparation WHERE receiptId=?",arrayOf(receiptId)).use {
                    require(it.moveToFirst())
                    JSONObject(SecureRecords(context,"fiskai_demo_payment_preparation",db).openSnapshot(receiptId,it.getString(0)))
                }
                require(payment.getString("receiptId")==receiptId && payment.getInt("legalDecisionFormat")==3)
                verify(context,db,sql,payment)
                val result=resolver.resolveByDigest(payment.getString("catalogSha256"),payment.getString("eventDate"),
                    LegalCompliance.Subject.UNKNOWN,LegalCompliance.Scope.STANDARD_DOMESTIC_B2B)
                val rule=requireNotNull(result.rule)
                require(rule.version==payment.getString("ruleVersion") && rule.from==payment.getString("ruleFrom") &&
                    rule.until==payment.getString("ruleUntil"))
                sql.setTransactionSuccessful()
                result
            } finally {sql.endTransaction()}
        }
}
