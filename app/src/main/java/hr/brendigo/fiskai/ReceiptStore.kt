package hr.brendigo.fiskai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import hr.brendigo.fiskai.room.*
import org.json.JSONArray
import org.json.JSONObject

internal data class ReceiptWorkspace(val bank:List<BankEvidence>,val receipts:List<ReceiptEvidence>)
internal class ReceiptStore(private val context:Context,private val db:RecordDatabase=RecordDatabase.shared(context),
    private val room:FiscalRoomDatabase=FiscalRoomDatabase.shared(context)) {
    private val secure=SecureRecords(context,"fiskai_receipts",db)
    companion object {
        const val BANK="fiskai_receipts/bank"
        const val EVENTS="fiskai_receipts/events"
        fun validate(bank:JSONArray,events:JSONArray):ReceiptWorkspace {
            require(bank.length()<=ReceiptRules.MAX_ROWS&&events.length()<=ReceiptRules.MAX_ROWS)
            val b=List(bank.length()){ReceiptRules.decodeBank(bank.getJSONObject(it))}
            require(b.map{it.id}.distinct().size==b.size)
            val e=List(events.length()){ReceiptRules.decodeReceipt(events.getJSONObject(it),b.associateBy{r->r.id})}
            require(e.map{it.id}.distinct().size==e.size&&e.mapNotNull{it.bankId}.distinct().size==e.count{it.bankId!=null})
            return ReceiptWorkspace(b,e)
        }
        fun export(context:Context,db:RecordDatabase,sql:SQLiteDatabase):Pair<JSONArray,JSONArray> {
            val secure=SecureRecords(context,"fiskai_receipts",db)
            fun load(table:String):JSONArray {
                val out=JSONArray()
                val isEvent=table=="receipt_event"
                sql.rawQuery("SELECT id,payload"+(if(isEvent)",bankId" else "")+" FROM "+table+" ORDER BY id",null).use{c->
                    while(c.moveToNext()){
                        require(out.length()<ReceiptRules.MAX_ROWS)
                        val j=JSONObject(secure.openSnapshot(c.getString(0),c.getString(1)))
                        require(j.getString("id")==c.getString(0))
                        if(isEvent)require((if(j.isNull("bankId"))null else j.getString("bankId"))==c.getString(2))
                        out.put(j)
                    }
                }
                return out
            }
            val bank=load("bank_transaction");val events=load("receipt_event")
            validate(bank,events);return bank to events
        }
        fun restore(context:Context,db:RecordDatabase,sql:SQLiteDatabase,bank:JSONArray,events:JSONArray){
            val current=export(context,db,sql)
            require(current.first.length()==0&&current.second.length()==0)
            val rows=validate(bank,events)
            val secure=SecureRecords(context,"fiskai_receipts",db)
            for(b in rows.bank)sql.insertOrThrow("bank_transaction",null,ContentValues().apply{
                put("id",b.id);put("payload",secure.sealSnapshot(b.id,ReceiptRules.bankJson(b).toString()))
            })
            for(e in rows.receipts)sql.insertOrThrow("receipt_event",null,ContentValues().apply{
                put("id",e.id);put("bankId",e.bankId);put("payload",secure.sealSnapshot(e.id,ReceiptRules.receiptJson(e).toString()))
            })
        }
    }
    private fun ready(){db.writableDatabase;check(room.openHelper.databaseName==db.databaseName)}
    fun read():ReceiptWorkspace=SecureRecords.withRecordLock {
        ready()
        val (bank,events)=export(context,db,db.readableDatabase)
        validate(bank,events)
    }
    fun importCsv(bytes:ByteArray,business:BusinessProfile):Int=SecureRecords.withRecordLock {
        val rows=ReceiptRules.csv(bytes,business.oib,business.iban)
        val existing=read().bank.associateBy{it.id}
        for(row in rows)existing[row.id]?.let{require(it==row){"Conflicting bank transaction"}}
        val added=rows.filter{it.id !in existing}
        require(existing.size+added.size<=ReceiptRules.MAX_ROWS)
        if(added.isNotEmpty())room.receipts().insertBank(added.map{row->BankTransactionRow().apply{
            id=row.id;payload=secure.sealSnapshot(id,ReceiptRules.bankJson(row).toString())
        }})
        added.size
    }
    private fun post(row:ReceiptEvidence,current:ReceiptWorkspace):Boolean {
        current.receipts.firstOrNull{it.id==row.id}?.let{require(it==row);return false}
        require(current.receipts.size<ReceiptRules.MAX_ROWS)
        room.receipts().insertReceipt(ReceiptEventRow().apply{
            id=row.id;bankId=row.bankId;payload=secure.sealSnapshot(id,ReceiptRules.receiptJson(row).toString())
        })
        return true
    }
    fun demoPaymentSelection(receiptId:String):PaymentPreparationStore.Selection=SecureRecords.withRecordLock {
        ready()
        PaymentPreparationStore.selection(context,db,receiptId)
    }
    fun prepareDemoPayment(receiptId:String,documentId:String):String=SecureRecords.withRecordLock {
        ready()
        PaymentPreparationStore.prepare(context,db,receiptId,documentId)
    }
    fun demoSyncEvents(business:BusinessProfile):List<JSONObject> = SecureRecords.withRecordLock {
        check(BuildConfig.DEMO_MODE)
        require(validOib(business.oib))
        ready()
        val sql=db.writableDatabase
        sql.beginTransaction()
        try {
            val (bank,events)=export(context,db,sql)
            val ids=validate(bank,events).receipts.filter{it.owner==business.oib}.map{it.id}.toSet()
            val prepared=PaymentPreparationStore.export(context,db,sql)
            val result=(0 until prepared.length()).map{prepared.getJSONObject(it)}.filter{it.getString("receiptId") in ids}
            sql.setTransactionSuccessful()
            result
        }finally{sql.endTransaction()}
    }
    fun cash(business:BusinessProfile,reference:String,date:String,amount:String,note:String):Boolean=SecureRecords.withRecordLock {
        post(ReceiptRules.cash(business.oib,reference,date,ReceiptRules.cents(amount),note),read())
    }
    fun confirmBank(business:BusinessProfile,id:String,note:String):Boolean=SecureRecords.withRecordLock {
        val current=read();val bank=current.bank.single{it.id==id}
        require(bank.owner==business.oib&&bank.iban==ReceiptRules.account(business.iban))
        post(ReceiptRules.fromBank(bank,note),current)
    }
}
