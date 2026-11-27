package hr.brendigo.fiskai

import org.json.JSONArray
import org.json.JSONObject

internal fun demoSnapshot(draft:Draft,business:BusinessProfile,register:Register,operator:Operator,time:Long):JSONObject {
        val lines=JSONArray()
        draft.lines.forEach { line -> lines.put(JSONObject().put("description",line.description).put("quantity",line.quantity.toPlainString()).put("unitPrice",line.unitPrice.toPlainString()).put("vatRate",line.vatRate.toPlainString()).put("kpd",line.kpd).put("unitCode",line.unitCode).put("net",line.net.toPlainString()).put("vat",line.vat.toPlainString()).put("total",line.total.toPlainString())) }
        return JSONObject().put("environment","DEMO").put("draft",draft.id).put("created",time).put("seller",business.name).put("sellerOib",business.oib).put("sellerAddress",business.address).put("sellerCity",business.city).put("vatRegistered",business.vatRegistered).put("customer",draft.customer).put("customerOib",draft.oib).put("operatorOib",operator.oib).put("premise",register.premise).put("register",register.number).put("total",draft.total.toPlainString()).put("lines",lines)
}
