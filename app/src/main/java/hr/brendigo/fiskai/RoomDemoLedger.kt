package hr.brendigo.fiskai

import android.content.Context
import hr.brendigo.fiskai.room.FiscalDocument
import hr.brendigo.fiskai.room.FiscalRoomDatabase
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/** Active local DEMO numbering. Migration and backup use the same physical database. */
internal class RoomDemoLedger(
    private val context:Context,
    private val db:RecordDatabase=RecordDatabase.shared(context),
    private val room:FiscalRoomDatabase=FiscalRoomDatabase.shared(context),
    private val previousName:String?=FiscalCutover.PREVIOUS_DB
) {
    private val secure=SecureRecords(context,"fiskai_demo_receipts",db)
    private fun ready()=FiscalCutover.ensure(context,db,previousName)
    private val dao get()=room.queue()
    fun freeze(draft:Draft,business:BusinessProfile,setup:FiscalSetup,register:Register,operator:Operator):Long=SecureRecords.withRecordLock {
        ready()
        require(draft.kind=="Račun"&&draft.lines.isNotEmpty()&&validOib(business.oib))
        require(register in setup.registers&&operator in setup.operators&&setup.premises.any{it.code==register.premise})
        val now=System.currentTimeMillis()
        val year=Calendar.getInstance(TimeZone.getTimeZone("Europe/Zagreb")).apply{timeInMillis=now}.get(Calendar.YEAR)
        dao.issueLocal(draft.id,year,register.premise,register.number,0,now){number->
            val id=UUID.randomUUID().toString()
            val plain=demoSnapshot(draft,business,register,operator,now).put("number",number).put("year",year).toString()
            FiscalDocument().apply {
                this.id=id;draftId=draft.id;this.year=year;premise=register.premise;registerNo=register.number
                this.number=number;created=now;payload=secure.sealSnapshot(id,plain);digest=FiscalCutover.digest(plain)
            }
        }.number
    }
    fun history():List<DemoReceipt> = SecureRecords.withRecordLock {
        ready()
        dao.history().map{DemoReceipt(it.id,it.draftId,it.year,it.premise,it.registerNo,it.number,it.created,it.payload)}
    }
    fun total(row:DemoReceipt):String=DemoLedger(context,db).total(row)
    fun status(id:String):String?=SecureRecords.withRecordLock{ready();dao.entry(id)?.state}
}
