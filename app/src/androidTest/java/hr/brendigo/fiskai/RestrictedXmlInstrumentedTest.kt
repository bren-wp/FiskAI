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
        val malicious=linkedMapOf(
            "external DTD" to "<!DOCTYPE x SYSTEM 'https://example.invalid/external.dtd'><x/>".toByteArray(),
            "external entity" to "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>".toByteArray(),
            "internal entity" to "<!DOCTYPE x [<!ENTITY e 'expanded'>]><x>&e;</x>".toByteArray(),
            "XInclude" to "<x xmlns:i='http://www.w3.org/2001/XInclude'><i:include href='file:///etc/passwd'/></x>".toByteArray(),
            "wrong declaration encoding" to "<?xml version='1.0' encoding='UTF-16'?><x/>".toByteArray(),
            "UTF-16 bytes" to "<x/>".toByteArray(Charsets.UTF_16),
            "malformed UTF-8" to byteArrayOf(60,120,62,0xC0.toByte(),0xAF.toByte(),60,47,120,62),
            "undefined entity" to "<x>&undefined;</x>".toByteArray(),
            "literal NUL" to "<x>\u0000</x>".toByteArray(),
            "oversize" to ByteArray(65537),
            "undefined attribute entity" to "<x a='&undefined;'/>".toByteArray(),
            "bare ampersand" to "<x>a & b</x>".toByteArray(),
            "numeric NUL" to "<x>&#0;</x>".toByteArray(),
            "numeric surrogate" to "<x>&#xD800;</x>".toByteArray(),
            "out-of-range numeric reference" to "<x>&#x110000;</x>".toByteArray(),
            "stylesheet instruction" to "<?xml-stylesheet href='file:///etc/passwd'?><x/>".toByteArray(),
            "tab-separated wrong encoding" to "<?xml\tversion='1.0' encoding='UTF-16'?><x/>".toByteArray()
        )
        for((name,bytes) in malicious)assertTrue("Accepted unsafe XML: "+name,runCatching{RestrictedXml.parse(bytes,65536)}.isFailure)
    }

    @Test fun predefinedAndNumericEntitiesRemainUsable(){
        val xml="<x a='&quot;&apos;&amp;'>&lt;&gt;&amp;&#65;&#x1F600;</x>"
        val root=RestrictedXml.parse(xml.toByteArray(),65536).documentElement
        assertEquals("\"'&",root.getAttribute("a"))
        assertEquals("<>&A"+String(Character.toChars(0x1F600)),root.textContent)
    }

    @Test fun cisEchoWorksOnAndroidNotJustJvm(){
        val id="12345678-1234-1234-1234-123456789abc"
        val body="<s:Envelope xmlns:s=\""+CisEcho.SOAP+"\" xmlns:f=\""+CisEcho.NS+"\"><s:Body><f:EchoResponse>"+CisEcho.token(id)+"</f:EchoResponse></s:Body></s:Envelope>"
        assertTrue(CisEcho.matchesResponse(id,body.toByteArray()))
        assertFalse(CisEcho.matchesResponse(id,body.replace(CisEcho.token(id),"wrong").toByteArray()))
        assertFalse(CisEcho.matchesResponse(id,("<!DOCTYPE x>"+body).toByteArray()))
    }
}
