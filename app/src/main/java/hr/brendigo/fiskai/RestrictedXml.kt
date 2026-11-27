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
    // Only the five predefined XML entities and legal XML 1.0 numeric
    // references are accepted. Some Android DOM implementations silently
    // retain or drop unknown entities when expansion is disabled.
    private val reference=Regex("""&(?:amp|lt|gt|apos|quot|#[0-9]{1,10}|#x[0-9a-fA-F]{1,8});""")
    private val declaration=Regex("""<\?xml[ \t\r\n]+version[ \t\r\n]*=[ \t\r\n]*(['"])1\.0\1(?:[ \t\r\n]+encoding[ \t\r\n]*=[ \t\r\n]*(['"])(?i:UTF-8)\2)?(?:[ \t\r\n]+standalone[ \t\r\n]*=[ \t\r\n]*(['"])(?:yes|no)\3)?[ \t\r\n]*\?>""")
    private fun validCodePoint(n:Long):Boolean =
        n==9L||n==10L||n==13L||n in 0x20L..0xD7FFL||n in 0xE000L..0xFFFDL||n in 0x10000L..0x10FFFFL

    private fun checkReferences(text:String){
        var index=text.indexOf('&')
        while(index>=0){
            val match=reference.find(text,index)
            require(match!=null&&match.range.first==index){"Unknown or malformed XML entity"}
            val token=match.value
            if(token.startsWith("&#")){
                val hex=token.startsWith("&#x")
                val n=token.substring(if(hex)3 else 2,token.length-1).toLong(if(hex)16 else 10)
                require(validCodePoint(n)){"Invalid XML character reference"}
            }
            index=text.indexOf('&',match.range.last+1)
        }
    }

    fun parse(bytes:ByteArray,maxBytes:Int):Document {
        require(bytes.size in 1..maxBytes)
        val text=Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require('\u0000' !in text && "<!" !in text){"XML declarations and DTD are not supported"}
        // Restrict declarations to XML 1.0 UTF-8. Reject other processing
        // instructions, including xml-stylesheet; no secondary resources.
        val body=if(text.startsWith("<?xml")){
            val end=text.indexOf("?>")
            require(end in 6..512&&declaration.matches(text.substring(0,end+2))){"Unsupported XML declaration"}
            text.substring(end+2)
        }else text
        require("<?" !in body){"Processing instructions are forbidden"}
        checkReferences(body)
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
        builder.setErrorHandler(object:org.xml.sax.helpers.DefaultHandler(){
            override fun error(e:org.xml.sax.SAXParseException){throw e}
            override fun fatalError(e:org.xml.sax.SAXParseException){throw e}
        })
        val doc=builder.parse(InputSource(StringReader(text)))
        require(doc.doctype==null)
        require(doc.getElementsByTagNameNS("http://www.w3.org/2001/XInclude","*").length==0)
        return doc
    }
}
