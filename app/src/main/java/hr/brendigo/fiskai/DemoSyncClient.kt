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
    /** Credentials are entered for each explicit session; no disk persistence, no redirects. */
    fun push(url:String,subject:String,token:String,events:List<JSONObject>):Int {
        check(BuildConfig.DEMO_MODE)
        val destination=endpoint(url)
        identity(subject);require(token.matches(Regex("[0-9a-f]{64}")))
        require(events.size<=ReceiptRules.MAX_ROWS)
        var acknowledged=0
        for(batch in events.chunked(50)) {
            val payload=request(subject,batch).toString().toByteArray(Charsets.UTF_8)
            require(payload.size<=LIMIT)
            val conn=destination.toURL().openConnection(Proxy.NO_PROXY) as HttpsURLConnection
            try {
                conn.instanceFollowRedirects=false
                conn.connectTimeout=10000;conn.readTimeout=15000
                conn.useCaches=false;conn.requestMethod="POST";conn.doOutput=true
                conn.setRequestProperty("Authorization","Bearer "+token)
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
                    while(true){val n=input.read(buffer);if(n<0)break;require(n>0&&n<=LIMIT-out.size());out.write(buffer,0,n)}
                    out.toByteArray()
                }
                verifyAck(subject,batch,JSONObject(bytes.toString(Charsets.UTF_8)))
                acknowledged+=batch.size
            }finally{conn.disconnect()}
        }
        return acknowledged
    }
}
