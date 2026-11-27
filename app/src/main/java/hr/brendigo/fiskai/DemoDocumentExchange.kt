package hr.brendigo.fiskai

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Explicit signed export of immutable DEMO receipts for offline server review.
 * Not a backup, production invoice import or fiscal submission. Contains test document contents.
 */
internal object DemoDocumentExchange {
    private const val LIMIT=1024*1024
    fun export(context:Context,db:RecordDatabase,owner:String,subject:String,allowEmpty:Boolean=false):String=SecureRecords.withRecordLock {
        check(BuildConfig.DEMO_MODE)
        require(validOib(owner));DemoSyncClient.identity(subject)
        val sql=db.writableDatabase
        sql.beginTransaction()
        try {
            check(FiscalCutover.active(sql))
            val secure=SecureRecords(context,"fiskai_demo_receipts",db)
            val rows=JSONArray()
            var bytes=0
            sql.rawQuery("SELECT id,draftId,year,premise,registerNo,number,created,payload,digest FROM fiscal_document ORDER BY year,premise,registerNo,number",null).use{c->
                while(c.moveToNext()){
                    val id=DemoSyncClient.identity(c.getString(0))
                    val plain=secure.openSnapshot(id,c.getString(7))
                    require(FiscalCutover.digest(plain)==c.getString(8))
                    val p=JSONObject(plain)
                    require(p.getString("environment")=="DEMO"&&p.getString("draft")==c.getString(1)&&
                        p.getString("premise")==c.getString(3)&&p.getString("register")==c.getString(4)&&p.getLong("created")==c.getLong(6))
                    if(p.getString("sellerOib")!=owner)continue
                    // Legacy unbound receipts cannot be exported as authenticated numbered documents.
                    require(p.getInt("year")==c.getInt(2)&&p.getLong("number")==c.getLong(5))
                    require(rows.length()<1000)
                    bytes=Math.addExact(bytes,plain.toByteArray(Charsets.UTF_8).size)
                    require(bytes<=LIMIT)
                    rows.put(JSONObject().put("documentId",id).put("digest",c.getString(8)).put("snapshot",plain))
                }
            }
            require(allowEmpty||rows.length()>0)
            val enrollment=DemoDeviceIdentity.enrollment()
            val body=JSONObject().put("protocol","FISKAI-DEMO-DOCUMENTS-V1").put("subjectId",subject)
                .put("ownerOib",owner).put("keySha256",enrollment.fingerprint).put("documents",rows)
                .toString().toByteArray(Charsets.UTF_8)
            require(body.size<=LIMIT)
            val envelope=JSONObject().put("payload",Base64.encodeToString(body,Base64.NO_WRAP))
                .put("signature",DemoDeviceIdentity.sign(body)).toString()
            sql.setTransactionSuccessful()
            envelope
        }finally{sql.endTransaction()}
    }
}
