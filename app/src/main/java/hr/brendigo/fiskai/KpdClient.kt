package hr.brendigo.fiskai

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

internal data class KpdEntry(val code:String,val name:String)
internal data class KpdResult(val entries:List<KpdEntry>,val imported:String,val hash:String)
internal object KpdClient {
    internal fun requestUrl(endpoint:String,query:String):URI {
        val uri=URI(endpoint.trim())
        require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.fragment==null && uri.rawQuery==null)
        require(uri.path.endsWith("/api.php") && uri.port in listOf(-1,443) && uri.rawPath==uri.path)
        require(query.isNotBlank() && query.toByteArray(Charsets.UTF_8).size<=160)
        return URI(uri.toASCIIString()+"?action=kpd&q="+URLEncoder.encode(query.trim(),"UTF-8"))
    }
    fun search(endpoint:String,query:String):KpdResult {
        val connection=requestUrl(endpoint,query).toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects=false
            connection.connectTimeout=10000
            connection.readTimeout=10000
            connection.useCaches=false
            connection.setRequestProperty("Accept","application/json")
            require(connection.responseCode==200)
            require(connection.contentType?.substringBefore(";")?.trim()?.equals("application/json",true)==true)
            val bytes=connection.inputStream.use { stream ->
                val out=ByteArrayOutputStream()
                val buffer=ByteArray(8192)
                while(true){
                    val n=stream.read(buffer)
                    if(n<0)break
                    require(n<=524288-out.size())
                    out.write(buffer,0,n)
                }
                out.toByteArray()
            }
            val result=JSONObject(bytes.toString(Charsets.UTF_8))
            val source=result.getJSONObject("source")
            val hash=source.getString("sha256")
            require(hash.matches(Regex("[0-9a-f]{64}")))
            val items=result.getJSONArray("items")
            require(items.length()<=100)
            val rows=List(items.length()){i->
                val row=items.getJSONObject(i)
                val code=requireNotNull(normalizeKpd(row.getString("code")))
                val name=row.getString("name_hr")
                require(name.isNotBlank() && name.length<=1000 && name.none{it.isISOControl()})
                KpdEntry(code,name)
            }
            return KpdResult(rows,source.getString("imported_at").take(40),hash)
        }finally{connection.disconnect()}
    }
}
