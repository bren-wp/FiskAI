package hr.brendigo.fiskai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.UUID

data class Draft(val id: String = UUID.randomUUID().toString(), val kind: String, val customer: String, val oib: String, val lines: List<Line>) {
    val total: BigDecimal get() = lines.fold(BigDecimal.ZERO) { acc, line -> acc + line.total }
}
class DraftStore(context: Context) {
    private val prefs = context.getSharedPreferences("fiskai_drafts", Context.MODE_PRIVATE)
    fun read(): List<Draft> {
        val arr = JSONArray(prefs.getString("drafts", "[]"))
        return List(arr.length()) { i ->
            val item = arr.getJSONObject(i)
            val lines = item.getJSONArray("lines")
            Draft(item.getString("id"), item.getString("kind"), item.getString("customer"), item.getString("oib"), List(lines.length()) { j ->
                val row = lines.getJSONObject(j)
                Line(row.getString("description"), BigDecimal(row.getString("quantity")), BigDecimal(row.getString("price")), BigDecimal(row.getString("vat")))
            })
        }
    }
    fun save(items: List<Draft>): Boolean {
        val arr = JSONArray()
        items.forEach { draft ->
            val lines = JSONArray()
            draft.lines.forEach { line -> lines.put(JSONObject().put("description", line.description).put("quantity", line.quantity.toPlainString()).put("price", line.unitPrice.toPlainString()).put("vat", line.vatRate.toPlainString())) }
            arr.put(JSONObject().put("id", draft.id).put("kind", draft.kind).put("customer", draft.customer).put("oib", draft.oib).put("lines", lines))
        }
        return prefs.edit().putString("drafts", arr.toString()).commit()
    }
}
