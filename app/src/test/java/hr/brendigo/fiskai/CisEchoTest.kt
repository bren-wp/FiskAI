package hr.brendigo.fiskai

import org.junit.Assert.*
import org.junit.Test

class CisEchoTest {
    private val id="12345678-1234-1234-1234-123456789abc"
    private fun response(body:String)=("<s:Envelope xmlns:s=\""+CisEcho.SOAP+"\" xmlns:f=\""+CisEcho.NS+"\"><s:Body>"+body+"</s:Body></s:Envelope>").toByteArray()
    @Test fun matchingEchoAccepted() {
        assertTrue(CisEcho.matchesResponse(id,response("<f:EchoResponse>"+CisEcho.token(id)+"</f:EchoResponse>")))
    }
    @Test fun unrelatedEchoRejected() {
        assertFalse(CisEcho.matchesResponse(id,response("<f:EchoResponse>wrong</f:EchoResponse>")))
    }
    @Test fun faultRejected() {
        assertFalse(CisEcho.matchesResponse(id,response("<s:Fault><faultstring>error</faultstring></s:Fault>")))
    }
    @Test fun duplicateBodyPayloadRejected() {
        val value="<f:EchoResponse>"+CisEcho.token(id)+"</f:EchoResponse>"
        assertFalse(CisEcho.matchesResponse(id,response(value+value)))
    }
    @Test fun entityRejected() {
        assertFalse(CisEcho.matchesResponse(id,"<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><x>&x;</x>".toByteArray()))
    }
    @Test fun oversizedRejected() {
        assertFalse(CisEcho.matchesResponse(id,ByteArray(CisEcho.LIMIT+1)))
    }
}
