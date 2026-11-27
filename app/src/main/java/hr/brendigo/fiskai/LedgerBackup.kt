package hr.brendigo.fiskai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.Calendar
import java.util.TimeZone

/** Portable journal representation. Caller holds the record lock and one SQL transaction.
 * No raw database copy or device-bound ciphertext is written to the export. */
internal class LedgerBackup(context:Context, database:RecordDatabase) {
    private val receipts=SecureRecords(context,"fiskai_demo_receipts",database)
    private val stock=SecureRecords(context,"fiskai_stock",database)
    private data class Table(val name:String,val text:List<String>,val numbers:List<String>,
                             val nullableText:List<String> = emptyList(),val nullableNumbers:List<String> = emptyList(),
                             val secure:SecureRecords? = null)
    private val tables=listOf(
        Table("demo_sequence",listOf("premise","register_no"),listOf("year","last_no")),
        Table("demo_receipt",listOf("id","draft_id","premise","register_no"),listOf("year","number","created"),secure=receipts),
        Table("stock_balance",listOf("product_id"),listOf("quantity")),
        Table("stock_event",listOf("id","product_id"),listOf("delta","quantity_after","created"),secure=stock),
        Table("cis_echo",listOf("id","state"),listOf("created"),listOf("outcome"),listOf("completed"))
    )
    private val limit=20_000
    fun export(sql:SQLiteDatabase):JSONObject {
        val root=JSONObject()
        var bytes=0L
        for(table in tables) {
            val result=JSONArray()
            val cols=table.text+table.numbers+table.nullableText+table.nullableNumbers+
                if(table.secure!=null)listOf("payload") else emptyList()
            sql.query(table.name,cols.toTypedArray(),null,null,null,null,"rowid ASC").use { c ->
                while(c.moveToNext()) {
                    require(result.length()<limit) { "Too many journal rows" }
                    val row=JSONObject()
                    for(name in table.text+table.nullableText) {
                        val i=c.getColumnIndexOrThrow(name)
                        row.put(name,if(c.isNull(i))JSONObject.NULL else c.getString(i))
                    }
                    for(name in table.numbers+table.nullableNumbers) {
                        val i=c.getColumnIndexOrThrow(name)
                        row.put(name,if(c.isNull(i))JSONObject.NULL else c.getLong(i))
                    }
                    table.secure?.let { row.put("payload",JSONObject(it.openSnapshot(row.getString("id"),c.getString(c.getColumnIndexOrThrow("payload"))))) }
                    bytes+=row.toString().toByteArray(Charsets.UTF_8).size
                    require(bytes<=PortableBackup.MAX_PLAINTEXT)
                    result.put(row)
                }
            }
            root.put(table.name,result)
        }
        validate(root)
        return root
    }
    private fun rows(root:JSONObject,name:String):List<JSONObject> {
        val array=root.getJSONArray(name)
        require(array.length()<=limit)
        return List(array.length()){array.getJSONObject(it)}
    }
    private fun strictLong(row:JSONObject,key:String):Long {
        val value=row.get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong()
    }
    private fun strictString(row:JSONObject,key:String):String {
        val value=row.get(key)
        require(value is String && value.length<=4096)
        return value
    }
    private fun uuid(value:String) { require(UUID.fromString(value).toString()==value) }
    private data class Sequence(val year:Long,val premise:String,val register:String)
    private fun sequence(r:JSONObject)=Sequence(strictLong(r,"year"),strictString(r,"premise"),strictString(r,"register_no"))
    fun validate(root:JSONObject) {
        require(root.keys().asSequence().toSet()==tables.map{it.name}.toSet())
        for(table in tables) {
            val expected=(table.text+table.numbers+table.nullableText+table.nullableNumbers+
                if(table.secure!=null)listOf("payload") else emptyList()).toSet()
            for(r in rows(root,table.name)) {
                require(r.keys().asSequence().toSet()==expected)
                table.text.forEach{strictString(r,it)}
                table.numbers.forEach{strictLong(r,it)}
                table.nullableText.forEach{if(!r.isNull(it))strictString(r,it)}
                table.nullableNumbers.forEach{if(!r.isNull(it))strictLong(r,it)}
                if(table.secure!=null)r.getJSONObject("payload")
            }
        }
        val sequences=mutableMapOf<Sequence,Long>()
        for(r in rows(root,"demo_sequence")) {
            val key=sequence(r)
            require(key.year in 2000L..9999L && key.premise.isNotBlank() && key.register.isNotBlank())
            val last=strictLong(r,"last_no")
            require(last in 1L..999999999L && sequences.put(key,last)==null)
        }
        val ids=mutableSetOf<String>()
        val draftIds=mutableSetOf<String>()
        val numbers=mutableMapOf<Sequence,MutableSet<Long>>()
        for(r in rows(root,"demo_receipt")) {
            val id=r.getString("id");val draft=r.getString("draft_id")
            uuid(id);uuid(draft);require(ids.add(id)&&draftIds.add(draft))
            val key=sequence(r);val number=strictLong(r,"number")
            require(number in 1L..(sequences[key]?:error("Missing sequence")))
            require(numbers.getOrPut(key){mutableSetOf()}.add(number))
            val p=r.getJSONObject("payload");val created=strictLong(r,"created")
            val year=Calendar.getInstance(TimeZone.getTimeZone("Europe/Zagreb")).apply{timeInMillis=created}.get(Calendar.YEAR).toLong()
            require(created>0 && year==key.year && p.getString("environment")=="DEMO" &&
                p.getString("draft")==draft && p.getLong("created")==created &&
                p.getString("premise")==key.premise && p.getString("register")==key.register)
            if(p.has("number"))require(p.getLong("number")==number && p.getLong("year")==key.year)
        }
        for((key,last) in sequences)require(numbers[key]?.size?.toLong()==last) { "Sequence gap" }
        val balances=mutableMapOf<String,Long>()
        for(r in rows(root,"stock_balance")) {
            val id=r.getString("product_id");uuid(id)
            val qty=strictLong(r,"quantity")
            require(qty>=0 && balances.put(id,qty)==null)
        }
        val computed=mutableMapOf<String,Long>()
        ids.clear()
        for(r in rows(root,"stock_event")) {
            val id=r.getString("id");val product=r.getString("product_id")
            uuid(id);uuid(product);require(ids.add(id))
            val delta=strictLong(r,"delta");val next=Math.addExact(computed[product]?:0L,delta)
            require(delta!=0L && next>=0 && next==strictLong(r,"quantity_after") && strictLong(r,"created")>0)
            val p=r.getJSONObject("payload")
            require(p.getString("productId")==product && p.getLong("delta")==delta)
            if(p.has("quantityAfter"))require(p.getLong("quantityAfter")==next && p.getLong("created")==strictLong(r,"created"))
            computed[product]=next
        }
        require(computed==balances) { "Stock balance mismatch" }
        ids.clear()
        for(r in rows(root,"cis_echo")) {
            val id=r.getString("id");uuid(id);require(ids.add(id))
            require(strictLong(r,"created")>0)
            when(r.getString("state")) {
                "QUEUED","SENDING"->require(r.isNull("completed")&&r.isNull("outcome"))
                "VERIFIED"->require(!r.isNull("completed")&&r.getString("outcome")=="MATCH")
                "UNCERTAIN"->require(!r.isNull("completed")&&!r.isNull("outcome")&&r.getString("outcome")!="MATCH")
                else->error("Invalid CIS state")
            }
        }
    }
    fun restore(sql:SQLiteDatabase,root:JSONObject) {
        validate(root)
        for(table in tables)for(r in rows(root,table.name)) {
            val values=ContentValues()
            for(name in table.text+table.nullableText)if(r.isNull(name))values.putNull(name) else values.put(name,r.getString(name))
            for(name in table.numbers+table.nullableNumbers)if(r.isNull(name))values.putNull(name) else values.put(name,strictLong(r,name))
            table.secure?.let{values.put("payload",it.sealSnapshot(r.getString("id"),r.getJSONObject("payload").toString()))}
            if(table.name=="cis_echo" && r.getString("state") in setOf("QUEUED","SENDING")) {
                values.put("state","UNCERTAIN");values.put("outcome","INTERRUPTED")
                values.put("completed",System.currentTimeMillis())
            }
            sql.insertOrThrow(table.name,null,values)
        }
    }
}
