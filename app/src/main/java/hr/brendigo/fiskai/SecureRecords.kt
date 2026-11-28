package hr.brendigo.fiskai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private records, authenticated encryption and in-place migration of alpha plaintext.
 *  An unreadable record is never replaced with an empty value. */
internal class SecureRecords(
    context: Context,
    private val namespace: String,
    private val database: RecordDatabase = RecordDatabase.shared(context)
) {
    private val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    companion object {
        private const val ALIAS = "fiskai.local.records.v1"
        private const val PREFIX = "gcm1:"
        private val lock = Any()
        // All operations needing both encryption and SQL acquire this lock before the transaction.
        internal fun <T> withRecordLock(block:()->T):T = synchronized(lock,block)
        private data class BackupRecord(val namespace:String,val name:String,val empty:String) {
            val id get()="$namespace/$name"
        }
        private val backupRecords=listOf(
            BackupRecord("fiskai_drafts","drafts","[]"),
            BackupRecord("fiskai_directory","contacts","[]"),
            BackupRecord("fiskai_directory","products","[]"),
            BackupRecord("fiskai_business","profile","{}"),
            BackupRecord("fiskai_fiscal_setup","settings","{}"),
            BackupRecord("fiskai_incoming","invoices","[]"),
            BackupRecord("fiskai_receipts","bank","[]"),
            BackupRecord("fiskai_receipts","events","[]")
        )

        internal fun exportWorkspace(context:Context,database:RecordDatabase=RecordDatabase.shared(context)):String = synchronized(lock) {
            val records=org.json.JSONObject()
            val receiptExport=ReceiptStore.export(context,database,database.readableDatabase)
            for(item in backupRecords) {
                val value=if(item.id==ReceiptStore.BANK)receiptExport.first.toString() else if(item.id==ReceiptStore.EVENTS)receiptExport.second.toString() else if(item.namespace=="fiskai_incoming")IncomingInvoiceStore.exportArray(context,database).toString() else SecureRecords(context,item.namespace,database).getString(item.name,item.empty)
                records.put(item.id,org.json.JSONTokener(value).nextValue())
            }
            val sql=database.writableDatabase
            sql.beginTransaction()
            try {
                val journals=LedgerBackup(context,database).export(sql)
                val root=org.json.JSONObject().put("version",2).put("demo",BuildConfig.DEMO_MODE).put("records",records).put("journals",journals)
                if(FiscalCutover.active(sql))root.put("version",3).put("roomOutbox",FiscalCutover.export(context,database,sql,journals))
                val payments=PaymentPreparationStore.export(context,database,sql)
                if(payments.length()>0)root.put("version",5).put("paymentPreparations",payments)
                val result=root.toString()
                require(result.toByteArray(Charsets.UTF_8).size<=PortableBackup.MAX_PLAINTEXT)
                sql.setTransactionSuccessful()
                result
            } finally { sql.endTransaction() }
        }

        // Legacy v1 workspace or v2 workspace with journals; never overwrite existing records.
        internal fun restoreWorkspace(context:Context,json:String,database:RecordDatabase=RecordDatabase.shared(context)) = synchronized(lock) {
            val root=org.json.JSONObject(json)
            val version=root.getInt("version")
            require(version in 1..5 && root.getBoolean("demo")==BuildConfig.DEMO_MODE)
            val expected=when(version){1->setOf("version","demo","records");2->setOf("version","demo","records","journals");3->setOf("version","demo","records","journals","roomOutbox");else->setOf("version","demo","records","journals","roomOutbox","paymentPreparations")}
            require(root.keys().asSequence().toSet()==expected)
            val queues=if(version>=3)root.getJSONObject("roomOutbox") else org.json.JSONObject()
            val payments=if(version>=4)root.getJSONArray("paymentPreparations").also{require(BuildConfig.DEMO_MODE && it.length() in 1..ReceiptRules.MAX_ROWS)} else null
            val ledger=LedgerBackup(context,database)
            val journals=if(version>=2)root.getJSONObject("journals").also(ledger::validate) else null
            val payload=root.getJSONObject("records")
            // Earlier backups lack the incoming-invoice collection.
            if(!payload.has("fiskai_incoming/invoices"))payload.put("fiskai_incoming/invoices",org.json.JSONArray())
            for(key in listOf(ReceiptStore.BANK,ReceiptStore.EVENTS))if(!payload.has(key))payload.put(key,org.json.JSONArray())
            require(payload.keys().asSequence().toSet()==backupRecords.map { it.id }.toSet())
            ReceiptStore.validate(payload.getJSONArray(ReceiptStore.BANK),payload.getJSONArray(ReceiptStore.EVENTS))
            val incoming=backupRecords.map { item ->
                val value=payload.get(item.id)
                require(if(item.empty=="[]") value is org.json.JSONArray else value is org.json.JSONObject)
                val target=SecureRecords(context,item.namespace,database)
                val encoded=value.toString()
                target.validateJson(encoded)
                Triple(item,target,encoded)
            }
            // Validate imported domain objects with the same decoders used by live storage.
            WorkspaceValidation.validate(payload)
            // Migrate old preferences first, so an unmigrated existing record cannot be overwritten.
            for((item,target,_) in incoming) {
                if(item.namespace=="fiskai_incoming")require(IncomingInvoiceStore.exportArray(context,database).length()==0)
                else if(item.namespace!="fiskai_receipts")require(target.getString(item.name,item.empty)==item.empty)
            }
            val certificate=SecureRecords(context,"fiskai_test_certificate",database)
            require(certificate.getString("credential","{}")=="{}")
            val sealed=incoming.map { (item,target,encoded) -> item to target.encrypt(item.name,encoded) }
            val sql=database.writableDatabase
            sql.beginTransaction()
            try {
                for(table in listOf("demo_receipt","demo_sequence","stock_event","stock_balance","cis_echo","fiscal_document","fiscal_outbox","demo_payment_preparation","legal_decision_ledger")) {
                    sql.rawQuery("SELECT 1 FROM "+table+" LIMIT 1",null).use { require(!it.moveToFirst()) }
                }
                // Refuse unknown records, including certificates, instead of overwriting them.
                sql.query("records",arrayOf("namespace","record_key"),null,null,null,null,null).use { cursor ->
                    while(cursor.moveToNext()) require(backupRecords.any { it.namespace==cursor.getString(0)&&it.name==cursor.getString(1) })
                }
                for((item,encrypted) in sealed) if(item.namespace !in setOf("fiskai_incoming","fiskai_receipts"))database.write(item.namespace,item.name,encrypted)
                IncomingInvoiceStore.restoreArray(context,database,sql,payload.getJSONArray("fiskai_incoming/invoices"))
                ReceiptStore.restore(context,database,sql,payload.getJSONArray(ReceiptStore.BANK),payload.getJSONArray(ReceiptStore.EVENTS))
                if(journals!=null)ledger.restore(sql,journals)
                if(version>=3||FiscalCutover.active(sql))FiscalCutover.populate(context,database,sql,queues)
                if(payments!=null)PaymentPreparationStore.restore(context,database,sql,payments,legacyFull=version==4)
                sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
        }

        private fun key(create: Boolean): SecretKey = synchronized(lock) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(ALIAS, null)
            if(existing != null) return@synchronized existing as SecretKey
            check(create) { "Encryption key unavailable" }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build())
            }.generateKey()
        }
    }
    private fun aad(name: String) = ("FiskAI:" + namespace + ":" + name + ":v1").toByteArray(Charsets.UTF_8)
    private fun encrypt(name: String, data: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(aad(name))
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == 12)
        return PREFIX + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }
    private fun decrypt(name: String, data: String): String {
        val raw = Base64.decode(data.removePrefix(PREFIX), Base64.NO_WRAP)
        require(raw.size >= 28) { "Invalid encrypted record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, raw.copyOfRange(0,12)))
        cipher.updateAAD(aad(name))
        return cipher.doFinal(raw.copyOfRange(12,raw.size)).toString(Charsets.UTF_8)
    }
    // Seal before opening a SQL transaction to preserve lock ordering.
    internal fun sealSnapshot(id: String, value: String): String = synchronized(lock) {
        validateJson(value)
        encrypt("snapshot:"+id,value)
    }
    internal fun openSnapshot(id: String, value: String): String = synchronized(lock) {
        check(value.startsWith(PREFIX))
        decrypt("snapshot:"+id,value).also(::validateJson)
    }
    private fun validateJson(value: String) {
        val parser = org.json.JSONTokener(value)
        val decoded = parser.nextValue()
        require(decoded is org.json.JSONArray || decoded is org.json.JSONObject)
        require(parser.nextClean() == '\u0000')
    }
    private fun migrate(name: String) {
        database.migrate(namespace,name) {
            val legacy = prefs.getString(name,null)
            if(legacy == null) null
            else if(legacy.startsWith(PREFIX)) {
                validateJson(decrypt(name,legacy))
                legacy
            } else {
                validateJson(legacy)
                encrypt(name,legacy)
            }
        }
        // Only remove the legacy value after the SQLite transaction committed.
        // On failed cleanup the next call retries; the database is authoritative.
        if(prefs.contains(name)) check(prefs.edit().remove(name).commit()) { "Legacy cleanup failed" }
    }
    fun getString(name: String, default: String): String = synchronized(lock) {
        migrate(name)
        val current = database.read(namespace,name) ?: return@synchronized default
        check(current.startsWith(PREFIX)) { "Unencrypted database record" }
        decrypt(name,current).also(::validateJson)
    }
    fun write(name: String, value: String): Boolean = synchronized(lock) {
        runCatching {
            validateJson(value)
            migrate(name)
            database.read(namespace,name)?.let { current ->
                check(current.startsWith(PREFIX))
                validateJson(decrypt(name,current))
            }
            database.write(namespace,name,encrypt(name,value))
            true
        }.getOrDefault(false)
    }
}
