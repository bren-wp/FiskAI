package hr.brendigo.fiskai

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object PortableBackup {
    const val MAX_PLAINTEXT = 8 * 1024 * 1024
    private val magic = "FISKBAK1".toByteArray(Charsets.US_ASCII)
    private const val iterations = 600_000
    private const val saltLength = 16
    private const val nonceLength = 12
    private const val tagLength = 16
    private val headerLength = magic.size + saltLength + nonceLength
    val maxFile = MAX_PLAINTEXT + headerLength + tagLength

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        require(password.size in 12..256)
        val spec = PBEKeySpec(password,salt,iterations,256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally { spec.clearPassword() }
    }
    fun encrypt(plain: ByteArray,password: CharArray): ByteArray {
        require(plain.isNotEmpty() && plain.size<=MAX_PLAINTEXT)
        val random=SecureRandom()
        val salt=ByteArray(saltLength).also(random::nextBytes)
        val nonce=ByteArray(nonceLength).also(random::nextBytes)
        val header=magic+salt+nonce
        val key=derive(password,salt)
        return try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce))
            cipher.updateAAD(header)
            header+cipher.doFinal(plain)
        } finally { key.fill(0) }
    }
    fun decrypt(file: ByteArray,password: CharArray): ByteArray {
        require(file.size in (headerLength+tagLength+1)..maxFile)
        require(file.copyOfRange(0,magic.size).contentEquals(magic))
        val salt=file.copyOfRange(magic.size,magic.size+saltLength)
        val nonce=file.copyOfRange(magic.size+saltLength,headerLength)
        val key=derive(password,salt)
        return try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce))
            cipher.updateAAD(file,0,headerLength)
            cipher.doFinal(file,headerLength,file.size-headerLength)
        } finally { key.fill(0) }
    }
    fun readBounded(input: InputStream): ByteArray {
        val out=ByteArrayOutputStream()
        val buffer=ByteArray(8192)
        while(true) {
            val n=input.read(buffer)
            if(n==-1)break
            if(n==0)throw java.io.IOException("No input progress")
            require(out.size()<=maxFile-n)
            out.write(buffer,0,n)
        }
        return out.toByteArray()
    }
}
