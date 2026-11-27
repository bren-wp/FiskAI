package hr.brendigo.fiskai

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.Proxy
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

/** Explicit upload of existing immutable DEMO metadata. No fiscal submission. */
internal object DemoSyncClient {
    private const val LIMIT=65536
    private const val SNAPSHOT_LIMIT=2*1024*1024
    private val fields=listOf("eventId","receiptId","documentId","evidenceSha256","eventDate","amountCents","ruleVersion","deadlineDate")
    fun endpoint(value:String):URI {
        require(value.length<=512 && value==value.trim())
        val uri=URI(value)
        require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null &&
            uri.rawQuery==null && uri.rawFragment==null && uri.port in listOf(-1,443))
        require(uri.rawPath.matches(Regex("/(?:[A-Za-z0-9_-]+/)*api[.]php")))
        return URI(uri.toASCIIString()+"?action=demo-sync")
    }
    fun identity(value:String):String {
        require(UUID.fromString(value).toString()==value && value!="00000000-0000-0000-0000-000000000000")
        return value
    }
    fun request(subject:String,events:List<JSONObject>):JSONObject {
        identity(subject)
        require(events.size in 1..50)
        val rows=events.map{source->
            require(source.getString("environment")=="DEMO" && source.getString("kind")=="DEMO_PAYMENT_PREPARATION")
            JSONObject().apply{fields.forEach{put(it,source.get(it))}}
        }
        require(rows.map{identity(it.getString("eventId"))}.distinct().size==rows.size)
        return JSONObject().put("protocol",1).put("subjectId",subject).put("events",JSONArray(rows))
    }
    fun verifyAck(subject:String,events:List<JSONObject>,response:JSONObject) {
        require(response.keys().asSequence().toSet()==setOf("protocol","subjectId","status","accepted"))
        require(response.get("protocol")==1 && response.get("subjectId")==subject && response.get("status")=="DEMO_METADATA_STORED")
        val accepted=response.getJSONArray("accepted")
        require(accepted.length()==events.size && events.indices.all{accepted.get(it)==events[it].getString("eventId")})
    }
    fun metadata(row:JSONObject):JSONObject {
        require(row.keys().asSequence().toSet()==fields.toSet())
        for(key in fields.filter{it!="amountCents"})require(row.get(key) is String)
        identity(row.getString("eventId"));identity(row.getString("documentId"))
        for(key in listOf("receiptId","evidenceSha256"))require(row.getString(key).matches(Regex("[0-9a-f]{64}")))
        val amount=row.get("amountCents")
        require((amount is Int||amount is Long)&&(amount as Number).toLong() in 1L..999999999999L)
        val date=row.getString("eventDate")
        val version=requireNotNull(LegalRuleCatalog.rules[LegalCompliance.validateDate(date)]).version
        require(row.getString("ruleVersion")==version)
        val deadline=FiscalReportingRules.deadline(FiscalReportingRules.Event.PAYMENT,date)
        require(deadline.date!=null&&row.getString("deadlineDate")==deadline.date)
        return JSONObject().apply{fields.forEach{put(it,row.get(it))}}
    }
    fun snapshot(subject:String,response:JSONObject):List<JSONObject> {
        identity(subject)
        require(response.keys().asSequence().toSet()==setOf("protocol","subjectId","status","events"))
        require(response.get("protocol")==1&&response.get("subjectId")==subject&&response.get("status")=="DEMO_METADATA_SNAPSHOT")
        val array=response.getJSONArray("events")
        require(array.length()<=ReceiptRules.MAX_ROWS)
        val rows=List(array.length()){metadata(array.getJSONObject(it))}
        for(key in listOf("eventId","receiptId"))require(rows.map{it.getString(key)}.distinct().size==rows.size)
        return rows
    }
    /** Compare canonical metadata; never apply remote rows to the business ledger. */
    fun reconcile(local:List<JSONObject>,remote:List<JSONObject>,prior:List<JSONObject>):Int {
        val byId=remote.associateBy{it.getString("eventId")}
        val byReceipt=remote.associateBy{it.getString("receiptId")}
        require(byId.size==remote.size&&byReceipt.size==remote.size)
        for(row in prior) {
            val current=byId[row.getString("eventId")]
            check(current!=null&&metadata(current).toString()==metadata(row).toString()){"Server history changed"}
        }
        var matched=0
        for(source in local) {
            val row=metadata(JSONObject().apply{fields.forEach{put(it,source.get(it))}})
            val current=byId[row.getString("eventId")]
            val receipt=byReceipt[row.getString("receiptId")]
            if(current!=null||receipt!=null) {
                check(current!=null&&receipt!=null&&metadata(current).toString()==row.toString()&&
                    receipt.getString("eventId")==row.getString("eventId")){"Immutable metadata conflict"}
                matched++
            }
        }
        return matched
    }
    fun pull(url:String,subject:String,token:String):List<JSONObject> {
        check(BuildConfig.DEMO_MODE);identity(subject)
        val destination=URI(endpoint(url).toASCIIString().removeSuffix("demo-sync")+"demo-sync-pull")
        return snapshot(subject,exchange(destination,token,JSONObject().put("protocol",1).put("subjectId",subject),SNAPSHOT_LIMIT))
    }
    private fun exchange(destination:URI,token:String,request:JSONObject,responseLimit:Int):JSONObject {
        val subject=identity(request.getString("subjectId"))
        val action=destination.rawQuery.removePrefix("action=")
        require(action in setOf("demo-sync","demo-sync-pull"))
        val challenge=URI(destination.toASCIIString().substringBefore("?")+"?action=demo-sync-challenge")
        val response=exchangeRaw(challenge,token,JSONObject().put("protocol",1).put("subjectId",subject).toString().toByteArray(Charsets.UTF_8),LIMIT)
        require(response.keys().asSequence().toSet()==setOf("protocol","subjectId","deviceId","nonce"))
        require(response.get("protocol")==1&&response.get("subjectId")==subject)
        val device=identity(response.getString("deviceId"))
        val nonce=response.getString("nonce")
        val payload=request.toString().toByteArray(Charsets.UTF_8)
        val signature=DemoDeviceIdentity.sign(DemoDeviceIdentity.message(action,subject,device,nonce,token,payload))
        return exchangeRaw(destination,token,payload,responseLimit,nonce,signature)
    }
    private fun exchangeRaw(destination:URI,token:String,payload:ByteArray,responseLimit:Int,nonce:String?=null,signature:String?=null):JSONObject {
        require(token.matches(Regex("[0-9a-f]{64}")))
        require(payload.size<=LIMIT)
        val conn=destination.toURL().openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        try {
            conn.instanceFollowRedirects=false
            conn.connectTimeout=10000;conn.readTimeout=15000
            conn.useCaches=false;conn.requestMethod="POST";conn.doOutput=true
            conn.setRequestProperty("Authorization","Bearer "+token)
            if(nonce!=null&&signature!=null){
                conn.setRequestProperty("X-FiskAI-Nonce",nonce)
                conn.setRequestProperty("X-FiskAI-Signature",signature)
            }
            conn.setRequestProperty("Content-Type","application/json")
            conn.setRequestProperty("Accept","application/json")
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use{it.write(payload)}
            when(conn.responseCode) {
                200->Unit
                401,403->throw SecurityException("Device authorization refused")
                409->throw IllegalStateException("Immutable event conflict")
                else->throw java.io.IOException("Synchronization not acknowledged")
            }
            require(conn.contentType?.substringBefore(';')?.trim()?.lowercase()=="application/json")
            val bytes=conn.inputStream.use{input->
                val out=ByteArrayOutputStream();val buffer=ByteArray(4096)
                while(true){val n=input.read(buffer);if(n<0)break;require(n>0&&n<=responseLimit-out.size());out.write(buffer,0,n)}
                out.toByteArray()
            }
            val parser=org.json.JSONTokener(bytes.toString(Charsets.UTF_8))
            val response=parser.nextValue() as JSONObject
            require(parser.nextClean()=='\u0000')
            return response
        }finally{conn.disconnect()}
    }
    /** Credentials are entered for each explicit session; no disk persistence, no redirects. */
    fun push(url:String,subject:String,token:String,events:List<JSONObject>):Int {
        check(BuildConfig.DEMO_MODE)
        val destination=endpoint(url)
        identity(subject);require(token.matches(Regex("[0-9a-f]{64}")))
        require(events.size<=ReceiptRules.MAX_ROWS)
        var acknowledged=0
        for(batch in events.chunked(50)) {
            verifyAck(subject,batch,exchange(destination,token,request(subject,batch),LIMIT))
            acknowledged+=batch.size
        }
        return acknowledged
    }
}
