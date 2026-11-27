package hr.brendigo.fiskai

import android.content.Context
import hr.brendigo.fiskai.room.FiscalDocument
import hr.brendigo.fiskai.room.FiscalRoomDatabase
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Phase one: explicit local preparation only. The existing ledger remains the
 * authority for numbering; no production SOAP sender is connected. */
internal class FiscalOutbox(
    context:Context,
    private val legacy:RecordDatabase=RecordDatabase.shared(context),
    private val room:FiscalRoomDatabase=FiscalRoomDatabase.shared(context)
) {
    private val secure=SecureRecords(context,"fiskai_demo_receipts",legacy)
    private val dao get()=room.queue()
    fun prepare(receiptId:String):String = SecureRecords.withRecordLock {
        require(UUID.fromString(receiptId).toString()==receiptId)
        val row=legacy.readableDatabase.query("demo_receipt",arrayOf("id","draft_id","year","premise","register_no","number","created","payload"),"id=?",arrayOf(receiptId),null,null,null).use { c ->
            require(c.moveToFirst())
            DemoReceipt(c.getString(0),c.getString(1),c.getInt(2),c.getString(3),c.getString(4),c.getLong(5),c.getLong(6),c.getString(7))
        }
        val plain=secure.openSnapshot(row.id,row.snapshot)
        val p=JSONObject(plain)
        // Older snapshots did not authenticate the allocated number. Do not silently upgrade them.
        require(p.getString("environment")=="DEMO" && p.getString("draft")==row.draftId &&
            p.getString("premise")==row.premise && p.getString("register")==row.register &&
            p.getLong("created")==row.created && p.getInt("year")==row.year && p.getLong("number")==row.number)
        val digest=MessageDigest.getInstance("SHA-256").digest(plain.toByteArray(Charsets.UTF_8))
            .joinToString(""){ "%02x".format(it.toInt() and 255) }
        val document=FiscalDocument().apply {
            id=row.id;draftId=row.draftId;year=row.year;premise=row.premise;registerNo=row.register
            number=row.number;created=row.created;payload=row.snapshot;this.digest=digest
        }
        dao.enqueue(document,System.currentTimeMillis()).state
    }
    fun status(id:String):String?=dao.entry(id)?.state
}
