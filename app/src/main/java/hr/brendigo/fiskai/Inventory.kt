package hr.brendigo.fiskai

import android.content.ContentValues
import android.content.Context
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

internal object StockQuantity {
    fun encode(value:BigDecimal):Long {
        require(value.precision()<=13 && value.scale() in 0..4)
        return value.movePointRight(4).setScale(0,RoundingMode.UNNECESSARY).longValueExact()
    }
    fun decode(value:Long):BigDecimal=BigDecimal.valueOf(value,4).stripTrailingZeros()
}
internal data class StockEvent(val id:String,val productId:String,val delta:Long,val after:Long,val created:Long,val label:String,val reason:String)
internal class Inventory(context:Context,private val db:RecordDatabase=RecordDatabase.shared(context)){
    private val secure=SecureRecords(context,"fiskai_stock",db)
    fun balance():Map<String,Long> = db.readableDatabase.query("stock_balance",arrayOf("product_id","quantity"),null,null,null,null,null).use{c->
        buildMap{while(c.moveToNext())put(c.getString(0),c.getLong(1))}
    }
    fun post(id:String,product:Product,quantity:BigDecimal,incoming:Boolean,reason:String):Long = SecureRecords.withRecordLock {
        UUID.fromString(id);UUID.fromString(product.id)
        require(!product.service&&!product.archived&&reason.isNotBlank()&&reason.length<=500)
        val amount=StockQuantity.encode(quantity)
        require(amount>0)
        val delta=if(incoming)amount else -amount
        val now=System.currentTimeMillis()
        val sql=db.writableDatabase
        sql.beginTransaction()
        try{
            sql.query("stock_event",arrayOf("product_id","delta","quantity_after","payload"),"id=?",arrayOf(id),null,null,null).use{c->
                if(c.moveToFirst()){
                    require(c.getString(0)==product.id&&c.getLong(1)==delta)
                    val previous=JSONObject(secure.openSnapshot(id,c.getString(3)))
                    require(previous.getString("productId")==product.id && previous.getLong("delta")==delta && previous.getString("reason")==reason.trim())
                    val after=c.getLong(2)
                    if(previous.has("quantityAfter"))require(previous.getLong("quantityAfter")==after)
                    sql.setTransactionSuccessful();return@withRecordLock after
                }
            }
            val old=sql.query("stock_balance",arrayOf("quantity"),"product_id=?",arrayOf(product.id),null,null,null).use{if(it.moveToFirst())it.getLong(0) else 0L}
            val next=Math.addExact(old,delta)
            require(next>=0L){"Insufficient stock"}
            val snapshot=JSONObject().put("product",product.name).put("productId",product.id).put("delta",delta).put("reason",reason.trim()).put("quantityAfter",next).put("created",now)
            val sealed=secure.sealSnapshot(id,snapshot.toString())
            val row=ContentValues().apply{put("product_id",product.id);put("quantity",next)}
            check(sql.insertWithOnConflict("stock_balance",null,row,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)!=-1L)
            val event=ContentValues().apply{put("id",id);put("product_id",product.id);put("delta",delta);put("quantity_after",next);put("created",now);put("payload",sealed)}
            sql.insertOrThrow("stock_event",null,event)
            sql.setTransactionSuccessful();next
        }finally{sql.endTransaction()}
    }
    fun history():List<StockEvent>{
        // Copy encrypted payloads out of cursor before obtaining the encryption lock.
        data class Row(val id:String,val product:String,val delta:Long,val after:Long,val created:Long,val payload:String)
        val rows=db.readableDatabase.query("stock_event",arrayOf("id","product_id","delta","quantity_after","created","payload"),null,null,null,null,"created DESC","50").use{c->
            buildList{while(c.moveToNext())add(Row(c.getString(0),c.getString(1),c.getLong(2),c.getLong(3),c.getLong(4),c.getString(5)))}
        }
        return rows.map{r->
            val value=JSONObject(secure.openSnapshot(r.id,r.payload))
            require(value.getString("productId")==r.product&&value.getLong("delta")==r.delta)
            if(value.has("quantityAfter"))require(value.getLong("quantityAfter")==r.after && value.getLong("created")==r.created)
            StockEvent(r.id,r.product,r.delta,r.after,r.created,value.getString("product"),value.getString("reason"))
        }
    }
}
