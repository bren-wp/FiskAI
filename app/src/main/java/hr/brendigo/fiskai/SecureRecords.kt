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
internal class SecureRecords(context: Context, private val namespace: String) {
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
    fun getString(name: String, default: String): String = synchronized(lock) {
        val current = prefs.getString(name, null) ?: return@synchronized default
        if(current.startsWith(PREFIX)) return@synchronized decrypt(name,current)
        // Validate legacy JSON before replacing it. The original data remains on failed commit.
        org.json.JSONTokener(current).nextValue().also { require(it is org.json.JSONArray || it is org.json.JSONObject) }
        check(prefs.edit().putString(name, encrypt(name,current)).commit()) { "Migration failed" }
        current
    }
    fun write(name: String, value: String): Boolean = synchronized(lock) {
        runCatching {
            val current = prefs.getString(name,null)
            if(current != null && current.startsWith(PREFIX)) decrypt(name,current)
            prefs.edit().putString(name,encrypt(name,value)).commit()
        }.getOrDefault(false)
    }
}
