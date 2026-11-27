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
