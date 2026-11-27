package hr.brendigo.fiskai

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream

class PortableBackupTest {
    private val password="Dovoljno duga lozinka".toCharArray()
    @Test fun roundTripAndTamperDetection() {
        val original="{\"version\":1,\"name\":\"Kupac č\"}".toByteArray()
        val encrypted=PortableBackup.encrypt(original,password)
        assertFalse(encrypted.toString(Charsets.UTF_8).contains("Kupac"))
        assertArrayEquals(original,PortableBackup.decrypt(encrypted,password))
        val other=PortableBackup.encrypt(original,password)
        assertFalse(encrypted.contentEquals(other))
        encrypted[encrypted.lastIndex]=(encrypted.last().toInt() xor 1).toByte()
        assertTrue(runCatching { PortableBackup.decrypt(encrypted,password) }.isFailure)
        assertTrue(runCatching { PortableBackup.decrypt(other,"Pogrešna lozinka".toCharArray()) }.isFailure)
    }
    @Test fun rejectsShortPasswordAndOversizedInput() {
        assertTrue(runCatching { PortableBackup.encrypt(byteArrayOf(1),"short".toCharArray()) }.isFailure)
        assertTrue(runCatching { PortableBackup.readBounded(ByteArrayInputStream(ByteArray(PortableBackup.maxFile+1))) }.isFailure)
    }
}
