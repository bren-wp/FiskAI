package hr.brendigo.fiskai

import org.junit.Assert.*
import org.junit.Test

class RestrictedXmlTest {
    @Test fun rejectsUnknownAndMalformedReferences(){
        for(token in listOf("&unknown;","&","&amp","&#0;","&#xD800;","&#x110000;","&#9999999999;")){
            assertTrue(token,runCatching{RestrictedXml.parse(("<x>"+token+"</x>").toByteArray(),65536)}.isFailure)
        }
    }
    @Test fun acceptsXml10AndLegalReferences(){
        val xml="<?xml\tversion='1.0' encoding='utf-8' standalone='yes'?><x>&amp;&#65;&#x42;</x>"
        assertEquals("&AB",RestrictedXml.parse(xml.toByteArray(),65536).documentElement.textContent)
    }
    @Test fun rejectsUnsupportedDeclarationAndInstructions(){
        for(xml in listOf("<?xml\tversion='1.0' encoding='UTF-16'?><x/>","<?xml version='1.1'?><x/>","<?xml-stylesheet href='file:///tmp/x'?><x/>")){
            assertTrue(xml,runCatching{RestrictedXml.parse(xml.toByteArray(),65536)}.isFailure)
        }
    }
}
