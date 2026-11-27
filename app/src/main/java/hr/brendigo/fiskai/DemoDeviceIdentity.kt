package hr.brendigo.fiskai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Non-exportable installation key. Hardware support depends on the device; no attestation. */
internal object DemoDeviceIdentity {
    private const val ALIAS="fiskai.demo.sync.ec.v1"
    data class Enrollment(val publicKey:String,val fingerprint:String,val hardwareBacked:Boolean)
    private fun store()=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    @Synchronized private fun ensure():KeyStore {
        check(BuildConfig.DEMO_MODE)
        val store=store()
        if(!store.containsAlias(ALIAS)){
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore").apply{
                initialize(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        return store
    }
    fun digest(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString(""){"%02x".format(it.toInt() and 255)}
    fun enrollment():Enrollment {
        val store=ensure()
        val key=store.getKey(ALIAS,null) as PrivateKey
        val public=store.getCertificate(ALIAS).publicKey.encoded
        val info=KeyFactory.getInstance(key.algorithm,"AndroidKeyStore").getKeySpec(key,KeyInfo::class.java)
        return Enrollment(Base64.encodeToString(public,Base64.NO_WRAP),digest(public),info.isInsideSecureHardware)
    }
    fun message(action:String,subject:String,device:String,nonce:String,token:String,body:ByteArray):ByteArray {
        require(action in setOf("demo-sync","demo-sync-pull"))
        DemoSyncClient.identity(subject);DemoSyncClient.identity(device)
        require(nonce.matches(Regex("[0-9a-f]{64}"))&&token.matches(Regex("[0-9a-f]{64}")))
        return listOf("FISKAI-DEMO-PROOF-V1",action,subject,device,nonce,digest(token.toByteArray(Charsets.UTF_8)),digest(body))
            .joinToString("\n").toByteArray(Charsets.UTF_8)
    }
    fun sign(message:ByteArray):String {
        val key=ensure().getKey(ALIAS,null) as PrivateKey
        val signature=Signature.getInstance("SHA256withECDSA").apply{initSign(key);update(message)}.sign()
        return Base64.encodeToString(signature,Base64.NO_WRAP)
    }
}
