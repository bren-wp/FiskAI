package hr.brendigo.fiskai

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Read-only encrypted replica of complete DEMO snapshots. Never merges the issuance ledger. */
internal class DemoDocumentJournal(context:Context,private val db:RecordDatabase=RecordDatabase.shared(context)) {
    private val app=context.applicationContext
    private val secure=SecureRecords(app,"fiskai_demo_document_replica",db)
    private val dir=File(app.noBackupFilesDir,"demo-document-replica")
    data class Document(val id:String,val digest:String,val snapshot:String,val number:String)
    data class Result(val matched:Int,val remoteOnly:List<Document>)
    companion object {
        private fun rows(array:JSONArray,owner:String):List<Document> {
            require(array.length()<=1000)
            var bytes=0
            val records=List(array.length()){i->
                val row=array.getJSONObject(i)
                require(row.keys().asSequence().toSet()==setOf("documentId","digest","snapshot"))
                require(row.get("documentId") is String&&row.get("digest") is String&&row.get("snapshot") is String)
                val id=DemoSyncClient.identity(row.getString("documentId"))
                val plain=row.getString("snapshot");val digest=row.getString("digest")
                bytes=Math.addExact(bytes,plain.toByteArray(Charsets.UTF_8).size);require(bytes<=1048576)
                require(FiscalCutover.digest(plain)==digest)
                val p=JSONObject(plain)
                require(p.getString("environment")=="DEMO"&&p.getString("sellerOib")==owner)
                DemoSyncClient.identity(p.getString("draft"))
                require(p.get("year") is Int&&p.getInt("year") in 2000..9999)
                val number=p.get("number")
                require((number is Int||number is Long)&&(number as Number).toLong() in 1L..999999999L)
                val premise=p.getString("premise");val register=p.getString("register")
                require(premise.matches(Regex("[A-Za-z0-9]{1,20}"))&&register.matches(Regex("[0-9]{1,20}")))
                require(p.getLong("created")>0)
                Document(id,digest,plain,p.getInt("year").toString()+"/"+premise+"/"+register+"/"+number)
            }
            require(records.map{it.id}.distinct().size==records.size&&records.map{it.number}.distinct().size==records.size)
            return records
        }
        fun snapshot(root:JSONObject,subject:String,owner:String):List<Document> {
            require(root.keys().asSequence().toSet()==setOf("protocol","subjectId","ownerOib","status","documents"))
            require(root.get("protocol")==1&&root.get("subjectId")==subject&&root.get("ownerOib")==owner&&root.get("status")=="DEMO_DOCUMENT_SNAPSHOT")
            return rows(root.getJSONArray("documents"),owner)
        }
        fun compare(local:List<Document>,remote:List<Document>,prior:List<Document>):Result {
            val byId=remote.associateBy{it.id};val byNo=remote.associateBy{it.number}
            require(byId.size==remote.size&&byNo.size==remote.size)
            for(row in prior)check(byId[row.id]==row){"Remote document history changed"}
            val localIds=local.map{it.id}.toSet()
            for(row in local)if(byId.containsKey(row.id)||byNo.containsKey(row.number))
                check(byId[row.id]==row&&byNo[row.number]==row){"Immutable document conflict"}
            return Result(local.count{byId[it.id]==it},remote.filter{it.id !in localIds})
        }
    }
    private fun file(url:String,subject:String,owner:String):Pair<String,AtomicFile> {
        val id=FiscalCutover.digest("DEMO_DOCUMENT_REPLICA_V1\u0000"+DemoSyncClient.endpoint(url).toASCIIString()+"\u0000"+subject+"\u0000"+owner)
        check(dir.isDirectory||dir.mkdirs())
        return id to AtomicFile(File(dir,id+".checkpoint"))
    }
    private fun read(file:AtomicFile,id:String,subject:String,owner:String):List<Document> {
        val bytes=try{file.openRead().use{input->
            val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;require(n>0&&n<=4*1024*1024-out.size());out.write(buffer,0,n)}
            out.toByteArray()
        }}catch(e:java.io.FileNotFoundException){
            if(file.baseFile.exists()||File(file.baseFile.path+".bak").exists())throw e
            return emptyList()
        }
        return snapshot(JSONObject(secure.openSnapshot(id,bytes.toString(Charsets.UTF_8))),subject,owner)
    }
    fun reconcileAndSave(url:String,subject:String,owner:String,local:List<Document>,response:JSONObject):Result=SecureRecords.withRecordLock {
        check(BuildConfig.DEMO_MODE);require(validOib(owner));DemoSyncClient.identity(subject)
        val (id,file)=file(url,subject,owner)
        val remote=snapshot(response,subject,owner)
        val result=compare(local,remote,read(file,id,subject,owner))
        val bytes=secure.sealSnapshot(id,response.toString()).toByteArray(Charsets.UTF_8)
        require(bytes.size<=4*1024*1024)
        val output=file.startWrite()
        try{output.write(bytes);file.finishWrite(output)}catch(e:Throwable){file.failWrite(output);throw e}
        check(read(file,id,subject,owner)==remote)
        result
    }
    fun synchronize(url:String,subject:String,owner:String,token:String):Result {
        check(BuildConfig.DEMO_MODE)
        val envelope=DemoDocumentExchange.export(app,db,owner,subject,true)
        val body=JSONObject(String(Base64.decode(JSONObject(envelope).getString("payload"),Base64.NO_WRAP),Charsets.UTF_8))
        val local=rows(body.getJSONArray("documents"),owner)
        val before=DemoSyncClient.documentExchange(url,subject,token)
        reconcileAndSave(url,subject,owner,local,before)
        if(local.isNotEmpty()){
            val ack=DemoSyncClient.documentExchange(url,subject,token,envelope)
            require(ack.keys().asSequence().toSet()==setOf("protocol","subjectId","status","accepted"))
            require(ack.get("protocol")==1&&ack.get("subjectId")==subject&&ack.get("status")=="DEMO_DOCUMENTS_STORED")
            val accepted=ack.getJSONArray("accepted")
            require(accepted.length()==local.size&&local.indices.all{accepted.get(it)==local[it].id})
        }
        val after=DemoSyncClient.documentExchange(url,subject,token)
        check(compare(local,snapshot(after,subject,owner),snapshot(before,subject,owner)).matched==local.size)
        return reconcileAndSave(url,subject,owner,local,after)
    }
}
