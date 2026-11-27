package hr.brendigo.fiskai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.UUID

data class Contact(val id: String = UUID.randomUUID().toString(), val name: String, val oib: String, val email: String, val supplier: Boolean)
data class Product(val id: String = UUID.randomUUID().toString(), val name: String, val code: String, val price: BigDecimal, val vat: BigDecimal, val service: Boolean)

class DirectoryStore(context: Context) {
    private val prefs = SecureRecords(context, "fiskai_directory")
    private fun read(key: String) = JSONArray(prefs.getString(key, "[]") ?: "[]")
    fun contacts(): List<Contact> {
        val arr = read("contacts")
        return List(arr.length()) { i -> arr.getJSONObject(i).let { Contact(it.getString("id"), it.getString("name"), it.getString("oib"), it.getString("email"), it.getBoolean("supplier")) } }
    }
    fun products(): List<Product> {
        val arr = read("products")
        return List(arr.length()) { i -> arr.getJSONObject(i).let { Product(it.getString("id"), it.getString("name"), it.getString("code"), BigDecimal(it.getString("price")), BigDecimal(it.getString("vat")), it.getBoolean("service")) } }
    }
    fun saveContacts(items: List<Contact>): Boolean {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("oib", it.oib).put("email", it.email).put("supplier", it.supplier)) }
        return prefs.write("contacts", arr.toString())
    }
    fun saveProducts(items: List<Product>): Boolean {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("code", it.code).put("price", it.price.toPlainString()).put("vat", it.vat.toPlainString()).put("service", it.service)) }
        return prefs.write("products", arr.toString())
    }
}
