package hr.brendigo.fiskai

import android.content.ContentValues
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

internal data class DemoReceipt(val id:String,val draftId:String,val year:Int,val premise:String,val register:String,val number:Long,val created:Long,val snapshot:String)
internal class DemoLedger(context:Context,private val db:RecordDatabase=RecordDatabase.shared(context)) {
    private val secure=SecureRecords(context,"fiskai_demo_receipts",db)
    fun freeze(draft:Draft, business:BusinessProfile, setup:FiscalSetup, register:Register, operator:Operator):Long {
        require(draft.kind=="Račun" && draft.lines.isNotEmpty() && validOib(business.oib))
        require(register in setup.registers && operator in setup.operators)
        require(setup.premises.any { it.code==register.premise })
        val id=UUID.randomUUID().toString()
        val time=System.currentTimeMillis()
        val year=Calendar.getInstance(TimeZone.getTimeZone("Europe/Zagreb")).apply { timeInMillis=time }.get(Calendar.YEAR)
        val lines=JSONArray()
        draft.lines.forEach { line -> lines.put(JSONObject().put("description",line.description).put("quantity",line.quantity.toPlainString()).put("unitPrice",line.unitPrice.toPlainString()).put("vatRate",line.vatRate.toPlainString()).put("kpd",line.kpd).put("net",line.net.toPlainString()).put("vat",line.vat.toPlainString()).put("total",line.total.toPlainString())) }
        val payload=JSONObject().put("environment","DEMO").put("draft",draft.id).put("created",time).put("seller",business.name).put("sellerOib",business.oib).put("sellerAddress",business.address).put("sellerCity",business.city).put("vatRegistered",business.vatRegistered).put("customer",draft.customer).put("customerOib",draft.oib).put("operatorOib",operator.oib).put("premise",register.premise).put("register",register.number).put("total",draft.total.toPlainString()).put("lines",lines)
        val sealed=secure.sealSnapshot(id,payload.toString())
        val sql=db.writableDatabase
        sql.beginTransaction()
        try {
            sql.query("demo_receipt",arrayOf("number"),"draft_id=?",arrayOf(draft.id),null,null,null).use { if(it.moveToFirst()) { val existing=it.getLong(0);sql.setTransactionSuccessful();return existing } }
            val args=arrayOf(year.toString(),register.premise,register.number)
            val last=sql.query("demo_sequence",arrayOf("last_no"),"year=? AND premise=? AND register_no=?",args,null,null,null).use { if(it.moveToFirst())it.getLong(0) else 0L }
            require(last<999999999L)
            val next=last+1
            val sequence=ContentValues().apply{put("year",year);put("premise",register.premise);put("register_no",register.number);put("last_no",next)}
            check(sql.insertWithOnConflict("demo_sequence",null,sequence,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)!=-1L)
            val row=ContentValues().apply{put("id",id);put("draft_id",draft.id);put("year",year);put("premise",register.premise);put("register_no",register.number);put("number",next);put("created",time);put("payload",sealed)}
            sql.insertOrThrow("demo_receipt",null,row)
            sql.setTransactionSuccessful()
            return next
        } finally {sql.endTransaction()}
    }
    fun history():List<DemoReceipt> = db.readableDatabase.query("demo_receipt",arrayOf("id","draft_id","year","premise","register_no","number","created","payload"),null,null,null,null,"created DESC","100").use { c ->
        buildList { while(c.moveToNext()) add(DemoReceipt(c.getString(0),c.getString(1),c.getInt(2),c.getString(3),c.getString(4),c.getLong(5),c.getLong(6),c.getString(7))) }
    }
    fun total(row:DemoReceipt):String = JSONObject(secure.openSnapshot(row.id,row.snapshot)).getString("total")
}
