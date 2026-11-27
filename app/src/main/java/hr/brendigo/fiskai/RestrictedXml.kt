package hr.brendigo.fiskai

import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.w3c.dom.Document
import org.xml.sax.InputSource
import org.xml.sax.SAXException

/** Restricted UTF-8 XML profile, shared by Android UBL and CIS Echo.
 * Android's Harmony DOM factory does not implement every SAX feature.
 * Security does not depend on these optional flags: only the already checked
 * character stream is parsed, declarations (including DTD/entity) are excluded,
 * validation is off, and all external entity resolution throws. */
internal object RestrictedXml {
    fun parse(bytes:ByteArray,maxBytes:Int):Document {
        require(bytes.size in 1..maxBytes)
        val text=Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require('\u0000' !in text && "<!" !in text){"XML declarations and DTD are not supported"}
        if(text.startsWith("<?xml ")){
            val end=text.indexOf("?>")
            require(end in 6..512)
            val declaration=text.substring(0,end)
            val encoding=Regex("""\bencoding\s*=\s*(['"])([^'"]+)\1""").find(declaration)
            require(encoding==null||encoding.groupValues[2].equals("UTF-8",ignoreCase=true))
        }
        val factory=DocumentBuilderFactory.newInstance().apply{
            isNamespaceAware=true
            isValidating=false
            isExpandEntityReferences=false
            for(feature in listOf(
                "http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities"
            )){
                try{setFeature(feature,false)}
                catch(_:ParserConfigurationException){
                    // Some Android implementations do not support these flags.
                    // The restricted character stream contains no markup
                    // declarations and the resolver always refuses access.
                }
            }
        }
        val builder=factory.newDocumentBuilder()
        builder.setEntityResolver{_,_->throw SAXException("External entity forbidden")}
        // Do not pass original bytes to the parser: encoding detection must
        // not reinterpret input after the strict UTF-8 security check.
        val doc=builder.parse(InputSource(StringReader(text)))
        require(doc.doctype==null)
        require(doc.getElementsByTagNameNS("http://www.w3.org/2001/XInclude","*").length==0)
        return doc
    }
}
