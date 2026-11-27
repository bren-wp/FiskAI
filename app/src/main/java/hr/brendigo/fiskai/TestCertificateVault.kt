package hr.brendigo.fiskai

import android.content.Context
import android.util.Base64
import org.json.JSONObject

internal data class StoredCertificate(val fingerprint: String, val validTo: Long)

/** A single optional test PKCS#12 copy. The password is not persisted.
 *  Trust and fiscal authorization are deliberately not inferred from storage. */
internal class TestCertificateVault(context: Context) {
    private val records = SecureRecords(context,"fiskai_test_certificate")
    private fun record(): JSONObject = JSONObject(records.getString("credential","{}"))
    fun summary(): StoredCertificate? {
        val value = record()
        if(value.length()==0) return null
        require(value.getInt("version")==1)
        return StoredCertificate(value.getString("fingerprint"),value.getLong("validTo"))
    }
    fun readBytes(): ByteArray {
        val value = record()
        require(value.getInt("version")==1)
        val encoded = value.getString("pkcs12")
        require(encoded.length <= ((MAX_CERTIFICATE_BYTES+2)/3)*4)
        val bytes = Base64.decode(encoded,Base64.NO_WRAP)
        if(bytes.isEmpty() || bytes.size > MAX_CERTIFICATE_BYTES) {
            bytes.fill(0)
            error("Invalid certificate size")
        }
        return bytes
    }
    fun save(bytes: ByteArray, checked: CertificateInfo): Boolean {
        require(bytes.isNotEmpty() && bytes.size <= MAX_CERTIFICATE_BYTES && checked.dateValid)
        val value = JSONObject().put("version",1)
            .put("pkcs12",Base64.encodeToString(bytes,Base64.NO_WRAP))
            .put("fingerprint",checked.fingerprint).put("validTo",checked.validTo)
        return records.write("credential",value.toString())
    }
    fun clear(): Boolean = records.write("credential","{}")
}
