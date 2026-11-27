package hr.brendigo.fiskai

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestrictedXmlInstrumentedTest {
    @Test fun androidDomParsesCheckedUtf8(){
        val xml="<?xml version=\"1.0\" encoding=\"UTF-8\"?><x xmlns=\"urn:test\">Račun &amp; usluga</x>"
        val doc=RestrictedXml.parse(("\uFEFF"+xml).toByteArray(),65536)
        assertEquals("urn:test",doc.documentElement.namespaceURI)
        assertEquals("Račun & usluga",doc.documentElement.textContent)
    }

    @Test fun unsafeInputsAreRejected(){
        val malicious=listOf(
            "<!DOCTYPE x SYSTEM 'https://example.invalid/external.dtd'><x/>".toByteArray(),
            "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>".toByteArray(),
            "<!DOCTYPE x [<!ENTITY e 'expanded'>]><x>&e;</x>".toByteArray(),
            "<x xmlns:i='http://www.w3.org/2001/XInclude'><i:include href='file:///etc/passwd'/></x>".toByteArray(),
            "<?xml version='1.0' encoding='UTF-16'?><x/>".toByteArray(),
            "<x/>".toByteArray(Charsets.UTF_16),
            byteArrayOf(60,120,62,0xC0.toByte(),0xAF.toByte(),60,47,120,62),
            "<x>&undefined;</x>".toByteArray(),
            "<x>\u0000</x>".toByteArray(),
            ByteArray(65537)
        )
        for(bytes in malicious)assertTrue(runCatching{RestrictedXml.parse(bytes,65536)}.isFailure)
    }

    @Test fun cisEchoWorksOnAndroidNotJustJvm(){
        val id="12345678-1234-1234-1234-123456789abc"
        val body="<s:Envelope xmlns:s=\""+CisEcho.SOAP+"\" xmlns:f=\""+CisEcho.NS+"\"><s:Body><f:EchoResponse>"+CisEcho.token(id)+"</f:EchoResponse></s:Body></s:Envelope>"
        assertTrue(CisEcho.matchesResponse(id,body.toByteArray()))
        assertFalse(CisEcho.matchesResponse(id,body.replace(CisEcho.token(id),"wrong").toByteArray()))
        assertFalse(CisEcho.matchesResponse(id,("<!DOCTYPE x>"+body).toByteArray()))
    }
}
