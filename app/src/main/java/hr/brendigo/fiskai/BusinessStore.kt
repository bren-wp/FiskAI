package hr.brendigo.fiskai

import android.content.Context
import org.json.JSONObject

data class BusinessProfile(
    val name: String = "",
    val oib: String = "",
    val address: String = "",
    val city: String = "",
    val vatRegistered: Boolean = false,
    val iban: String = ""
)
internal data class InitialData(val drafts: List<Draft>, val contacts: List<Contact>, val products: List<Product>, val business: BusinessProfile)
internal class BusinessStore(context: Context) {
    private val records = SecureRecords(context,"fiskai_business")
    fun read(): BusinessProfile {
        val j = JSONObject(records.getString("profile","{}"))
        return BusinessProfile(j.optString("name"),j.optString("oib"),j.optString("address"),j.optString("city"),j.optBoolean("vatRegistered"),j.optString("iban"))
    }
    fun save(b: BusinessProfile): Boolean = records.write("profile",JSONObject().put("name",b.name).put("oib",b.oib).put("address",b.address).put("city",b.city).put("vatRegistered",b.vatRegistered).put("iban",b.iban).toString())
}
