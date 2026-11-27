package hr.brendigo.fiskai

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.Proxy
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

internal object CisEcho {
    const val ENDPOINT = "https://cistest.apis-it.hr:8449/FiskalizacijaServiceTest"
    const val NS = "http://www.apis-it.hr/fin/2012/types/f73"
    const val SOAP = "http://schemas.xmlsoap.org/soap/envelope/"
    const val LIMIT = 65536
    fun token(id: String): String = "FiskAI-echo-" + UUID.fromString(id).toString()
    fun request(id: String): ByteArray =
        ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<s:Envelope xmlns:s=\""+SOAP+"\" xmlns:f=\""+NS+"\"><s:Body><f:EchoRequest>"+
        token(id)+"</f:EchoRequest></s:Body></s:Envelope>").toByteArray(Charsets.UTF_8)

    fun matchesResponse(id: String, data: ByteArray): Boolean = runCatching {
        require(data.size in 1..LIMIT)
        // Reject DOCTYPE/entity declarations before parsing, including non-UTF-8/NUL forms.
        val text = data.toString(Charsets.UTF_8)
        require(!text.contains('\u0000') && !text.contains("<!") )
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External entity forbidden") }
        val root = builder.parse(ByteArrayInputStream(data)).documentElement
        fun children(e: Element): List<Element> = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }
        require(root.namespaceURI == SOAP && root.localName == "Envelope")
        val envelopeChildren=children(root)
        require(envelopeChildren.all { it.namespaceURI==SOAP && it.localName in listOf("Header","Body") })
        val body = envelopeChildren.single { it.namespaceURI == SOAP && it.localName == "Body" }
        val result = children(body).single()
        require(result.namespaceURI == NS && result.localName == "EchoResponse" && children(result).isEmpty())
        result.textContent == token(id)
    }.getOrDefault(false)

    /** No production URL, no user-controlled payload, no redirects and default platform TLS trust. */
    fun send(id: String): String {
        val connection = URL(ENDPOINT).openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type","text/xml; charset=utf-8")
            connection.setRequestProperty("SOAPAction","\"\"")
            val body = request(id)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if(status != 200) return "HTTP_" + status
            val response = connection.inputStream.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while(true) {
                    val count = stream.read(buffer)
                    if(count < 0) break
                    require(count <= LIMIT-out.size())
                    out.write(buffer,0,count)
                }
                out.toByteArray()
            }
            return if(matchesResponse(id,response)) "MATCH" else "INVALID_RESPONSE"
        } finally { connection.disconnect() }
    }
}
