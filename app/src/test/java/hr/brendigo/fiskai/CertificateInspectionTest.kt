package hr.brendigo.fiskai

import org.junit.Assert.*
import org.junit.Test

class CertificateInspectionTest {
    @Test fun exactLimitAccepted() {
        val bytes = byteArrayOf(1,2,3)
        assertArrayEquals(bytes,readBounded(bytes.inputStream(),3))
    }
    @Test(expected=IllegalArgumentException::class) fun oversizedRejected() {
        readBounded(byteArrayOf(1,2,3,4).inputStream(),3)
    }
    @Test(expected=IllegalArgumentException::class) fun emptyRejected() {
        readBounded(byteArrayOf().inputStream(),3)
    }
    @Test fun malformedPkcs12Rejected() {
        assertTrue(runCatching { CertificateInspection.inspect(byteArrayOf(1,2,3),charArrayOf('x')) }.isFailure)
    }
}
