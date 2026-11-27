package hr.brendigo.fiskai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal class FiscalSetupStore(context: Context) {
    private val records = SecureRecords(context,"fiskai_fiscal_setup")
    fun read(): FiscalSetup {
        val root = JSONObject(records.getString("settings","{}"))
        fun <T> readArray(key: String, decode: (JSONObject) -> T): List<T> {
            val a = root.optJSONArray(key) ?: JSONArray()
            return List(a.length()) { decode(a.getJSONObject(it)) }
        }
        // Reapply domain validation on persisted data before exposing it.
        var result = FiscalSetup()
        readArray("premises") { Premise(it.getString("code"),it.getString("name"),it.getString("address")) }
            .forEach { result = result.withPremise(it) }
        readArray("registers") { Register(it.getString("premise"),it.getString("number"),it.getString("name")) }
            .forEach { result = result.withRegister(it) }
        readArray("operators") { Operator(it.getString("code"),it.getString("name"),it.getString("oib")) }
            .forEach { result = result.withOperator(it) }
        return result
    }
    fun save(value: FiscalSetup): Boolean {
        val p = JSONArray(); val r = JSONArray(); val o = JSONArray()
        value.premises.forEach { p.put(JSONObject().put("code",it.code).put("name",it.name).put("address",it.address)) }
        value.registers.forEach { r.put(JSONObject().put("premise",it.premise).put("number",it.number).put("name",it.name)) }
        value.operators.forEach { o.put(JSONObject().put("code",it.code).put("name",it.name).put("oib",it.oib)) }
        return records.write("settings",JSONObject().put("premises",p).put("registers",r).put("operators",o).toString())
    }
}
