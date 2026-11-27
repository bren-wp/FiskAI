package hr.brendigo.fiskai

import org.junit.Assert.*
import org.junit.Test

class KpdClientTest {
    @Test fun encodedQuery(){assertEquals("https://example.test/api.php?action=kpd&q=a%26b",KpdClient.requestUrl("https://example.test/api.php","a&b").toString())}
    @Test fun rejectsUnsafeEndpoints(){
        listOf("http://example.test/api.php","https://u:p@example.test/api.php","https://example.test/api.php?x=1","https://example.test/other","https://example.test/api.php#x").forEach{
            assertTrue(runCatching{KpdClient.requestUrl(it,"test")}.isFailure)
        }
    }
}
