package hr.brendigo.fiskai

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Device-local authenticated checkpoint, not a financial ledger or a backup. */
internal class DemoSyncJournal(context:Context,private val db:RecordDatabase=RecordDatabase.shared(context)) {
    private val app=context.applicationContext
    private val secure=SecureRecords(app,"fiskai_demo_sync_checkpoint",db)
    private val directory=File(app.noBackupFilesDir,"demo-sync")
    data class Result(val matched:Int,val remoteOnly:Int,val total:Int)

    private fun scope(url:String,subject:String,owner:String):String {
        require(validOib(owner))
        return FiscalCutover.digest("DEMO_SYNC_V1\u0000"+DemoSyncClient.endpoint(url).toASCIIString()+"\u0000"+DemoSyncClient.identity(subject)+"\u0000"+owner)
    }
    private fun read(file:AtomicFile,id:String,subject:String):List<JSONObject> {
        val bytes=try{file.openRead().use{input->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;require(n>0&&n<=4*1024*1024-out.size());out.write(buffer,0,n)}
            out.toByteArray()
        }}catch(e:java.io.FileNotFoundException){
            if(file.baseFile.exists()||File(file.baseFile.path+".bak").exists())throw e
            return emptyList()
        }
        val root=JSONObject(secure.openSnapshot(id,bytes.toString(Charsets.UTF_8)))
        require(root.keys().asSequence().toSet()==setOf("scope","snapshot")&&root.getString("scope")==id)
        return DemoSyncClient.snapshot(subject,root.getJSONObject("snapshot"))
    }
    private fun write(file:AtomicFile,id:String,subject:String,rows:List<JSONObject>) {
        val snapshot=JSONObject().put("protocol",1).put("subjectId",subject).put("status","DEMO_METADATA_SNAPSHOT").put("events",JSONArray(rows))
        val root=JSONObject().put("scope",id).put("snapshot",snapshot)
        val bytes=secure.sealSnapshot(id,root.toString()).toByteArray(Charsets.UTF_8)
        require(bytes.size<=4*1024*1024)
        val stream=file.startWrite()
        try{stream.write(bytes);file.finishWrite(stream)}catch(e:Throwable){file.failWrite(stream);throw e}
        check(read(file,id,subject).map{it.toString()}==rows.map{DemoSyncClient.metadata(it).toString()})
    }
    /** Save only a complete validated snapshot; prior confirmations may never disappear silently. */
    fun reconcileAndSave(url:String,subject:String,owner:String,local:List<JSONObject>,remote:List<JSONObject>):Result=SecureRecords.withRecordLock {
        check(BuildConfig.DEMO_MODE)
        val id=scope(url,subject,owner)
        check(directory.isDirectory||directory.mkdirs())
        val file=AtomicFile(File(directory,id+".checkpoint"))
        val previous=read(file,id,subject)
        val candidate=DemoSyncClient.snapshot(subject,JSONObject().put("protocol",1).put("subjectId",subject)
            .put("status","DEMO_METADATA_SNAPSHOT").put("events",JSONArray(remote)))
        val matched=DemoSyncClient.reconcile(local,candidate,previous)
        write(file,id,subject,candidate)
        Result(matched,candidate.size-matched,candidate.size)
    }
    /** Pull, check prior state, push, then obtain a complete confirmation. No fiscal submission. */
    fun synchronize(url:String,subject:String,owner:String,token:String,local:List<JSONObject>):Result {
        check(BuildConfig.DEMO_MODE)
        val before=DemoSyncClient.pull(url,subject,token)
        reconcileAndSave(url,subject,owner,local,before)
        DemoSyncClient.push(url,subject,token,local)
        val after=DemoSyncClient.pull(url,subject,token)
        check(DemoSyncClient.reconcile(local,after,before)==local.size){"Incomplete remote confirmation"}
        return reconcileAndSave(url,subject,owner,local,after)
    }
}
