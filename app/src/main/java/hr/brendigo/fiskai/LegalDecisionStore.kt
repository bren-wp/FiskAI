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
        require(payment.getInt("legalDecisionFormat")==2)
        return JSONObject().apply {
            put("format",2)
            for(key in listOf("receiptId","documentId","documentDigest","eventId","eventDate",
                "ruleVersion","ruleFrom","ruleUntil","legalSource","deadlineKind","deadlineDate",
                "evidenceSha256"))put(key,payment.getString(key))
            put("trust","LOCAL_BASELINE_REVIEW_REQUIRED")
            put("dispatchAuthorized",false)
        }
    }
    fun insert(context:Context,db:RecordDatabase,sql:SQLiteDatabase,payment:JSONObject) {
        check(sql.inTransaction())
        if(payment.optInt("legalDecisionFormat",0)!=2)return
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
        if(payment.optInt("legalDecisionFormat",0)!=2) {
            require(payload==null){"Unexpected legal decision"}
            return
        }
        check(payload!=null){"Required legal decision missing"}
        val saved=JSONObject(SecureRecords(context,NS,db).openSnapshot(id,payload))
        val expected=snapshot(payment)
        require(saved.keys().asSequence().toSet()==expected.keys().asSequence().toSet() &&
            expected.keys().asSequence().all { saved.get(it)==expected.get(it) }){"Legal decision mismatch"}
    }
}
