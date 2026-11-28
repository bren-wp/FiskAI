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
    data class VerifiedStock(val balances:Map<String,Long>,val recent:List<StockEvent>)
    private fun verified(sql:android.database.sqlite.SQLiteDatabase):VerifiedStock {
        val computed=mutableMapOf<String,Long>()
        val recent=java.util.ArrayDeque<StockEvent>()
        var count=0
        sql.rawQuery("SELECT id,product_id,delta,quantity_after,created,payload FROM stock_event ORDER BY rowid ASC",null).use{c->
            while(c.moveToNext()){
                require(++count<=20000){"Stock verification capacity exceeded"}
                val id=c.getString(0);val product=c.getString(1)
                require(UUID.fromString(id).toString()==id&&UUID.fromString(product).toString()==product)
                val delta=c.getLong(2);val after=c.getLong(3);val created=c.getLong(4)
                val value=JSONObject(secure.openSnapshot(id,c.getString(5)))
                val next=Math.addExact(computed[product]?:0L,delta)
                require(delta!=0L&&created>0&&next>=0&&next==after)
                require(value.getString("productId")==product&&value.getLong("delta")==delta)
                if(value.has("quantityAfter"))require(value.getLong("quantityAfter")==after&&value.getLong("created")==created)
                computed[product]=next
                if(recent.size==50)recent.removeFirst()
                recent.addLast(StockEvent(id,product,delta,after,created,value.getString("product"),value.getString("reason")))
            }
        }
        val balances=mutableMapOf<String,Long>()
        sql.rawQuery("SELECT product_id,quantity FROM stock_balance",null).use{c->
            while(c.moveToNext()){
                require(balances.size<20000)
                val id=c.getString(0);val quantity=c.getLong(1)
                require(UUID.fromString(id).toString()==id&&quantity>=0&&balances.put(id,quantity)==null)
            }
        }
        require(computed==balances){"Stock balance mismatch"}
        return VerifiedStock(computed.toMap(),recent.toList().asReversed())
    }
    fun verifiedSnapshot():VerifiedStock=SecureRecords.withRecordLock {
        val sql=db.writableDatabase
        sql.beginTransaction()
        try {
            val result=verified(sql)
            sql.setTransactionSuccessful()
            result
        }finally{sql.endTransaction()}
    }
    fun balance():Map<String,Long> = verifiedSnapshot().balances
    fun post(id:String,product:Product,quantity:BigDecimal,incoming:Boolean,reason:String):Long = SecureRecords.withRecordLock {
        require(UUID.fromString(id).toString()==id&&UUID.fromString(product.id).toString()==product.id)
        require(!product.service&&!product.archived&&reason.isNotBlank()&&reason.length<=500)
        val amount=StockQuantity.encode(quantity)
        require(amount>0)
        val delta=if(incoming)amount else -amount
        val now=System.currentTimeMillis()
        val sql=db.writableDatabase
        sql.beginTransaction()
        try{
            val state=verified(sql)
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
            val count=sql.rawQuery("SELECT COUNT(*) FROM stock_event",null).use{it.moveToFirst();it.getLong(0)}
            require(count<20000){"Stock verification capacity exceeded"}
            val old=state.balances[product.id]?:0L
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
    fun history():List<StockEvent> = verifiedSnapshot().recent
}
