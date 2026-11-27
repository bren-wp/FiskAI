package hr.brendigo.fiskai

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date

internal const val MAX_CERTIFICATE_BYTES = 2 * 1024 * 1024

internal fun readBounded(input: InputStream, limit: Int = MAX_CERTIFICATE_BYTES): ByteArray {
    require(limit in 1..MAX_CERTIFICATE_BYTES)
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    try {
        while(true) {
            val count = input.read(buffer)
            if(count < 0) break
            if(count == 0) continue
            require(count <= limit - result.size()) { "Certificate file too large" }
            result.write(buffer,0,count)
        }
        require(result.size() > 0) { "Empty certificate" }
        return result.toByteArray()
    } finally { buffer.fill(0) }
}

internal data class CertificateInfo(
    val subject: String,
    val issuer: String,
    val serial: String,
    val fingerprint: String,
    val validFrom: Long,
    val validTo: Long,
    val keyBits: Int,
    val dateValid: Boolean
)

internal object CertificateInspection {
    /** Inspect one local RSA signing credential; do not infer fiscal authorization or trust. */
    fun inspect(bytes: ByteArray, password: CharArray, now: Date = Date()): CertificateInfo {
        require(bytes.isNotEmpty() && bytes.size <= MAX_CERTIFICATE_BYTES)
        val store = KeyStore.getInstance("PKCS12")
        bytes.inputStream().use { store.load(it,password) }
        val candidates = mutableListOf<String>()
        val aliases = store.aliases()
        while(aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if(store.isKeyEntry(alias)) candidates += alias
        }
        require(candidates.size == 1) { "Exactly one key entry required" }
        val alias = candidates.single()
        val key = store.getKey(alias,password) as? PrivateKey ?: error("No private key")
        val cert = store.getCertificate(alias) as? X509Certificate ?: error("No X.509 certificate")
        val pub = cert.publicKey as? RSAPublicKey ?: error("RSA required")
        require(key.algorithm.equals("RSA",ignoreCase=true) && pub.modulus.bitLength() >= 2048)
        require(cert.basicConstraints < 0) { "CA certificate not supported" }
        require(cert.keyUsage?.getOrNull(0) != false) { "Digital signature not allowed" }
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(key)
        signature.update(challenge)
        val signed = signature.sign()
        try {
            signature.initVerify(pub)
            signature.update(challenge)
            require(signature.verify(signed)) { "Key mismatch" }
        } finally { challenge.fill(0); signed.fill(0) }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(java.util.Locale.ROOT,it.toInt() and 0xff) }
        return CertificateInfo(
            cert.subjectX500Principal.name,cert.issuerX500Principal.name,
            cert.serialNumber.toString(16),fingerprint,cert.notBefore.time,cert.notAfter.time,
            pub.modulus.bitLength(),runCatching { cert.checkValidity(now) }.isSuccess
        )
    }
}
