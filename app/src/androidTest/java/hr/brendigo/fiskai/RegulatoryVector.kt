package hr.brendigo.fiskai

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject

/** Synthetic public fixture only; no production key material. */
internal object RegulatoryVector {
    val data:JSONObject get()=InstrumentationRegistry.getInstrumentation().context.assets
        .open("regulatory-vector.json").bufferedReader().use{JSONObject(it.readText())}
    fun bytes(v:JSONObject,key:String):ByteArray=Base64.decode(v.getString(key),Base64.NO_WRAP)
    fun resolver(v:JSONObject=data):HistoricalLegalDecisionResolver {
        require(v.getString("fixture")=="SYNTHETIC_TEST_ONLY")
        val catalogs=v.getJSONArray("catalogs")
        return HistoricalLegalDecisionResolver.verified(bytes(v,"history"),bytes(v,"historySignature"),bytes(v,"publicKey"),
            v.getString("publicKeySha256"),v.getInt("sequence"),v.getString("head"),v.getString("catalogSha256"),
            List(catalogs.length()){Base64.decode(catalogs.getString(it),Base64.NO_WRAP)})
    }
}
