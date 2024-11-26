import com.statsig.androidsdk.fetchTxtRecords
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class DnsTxtQueryTest {
    @Test
    fun testTxtRecords() = runBlocking {
        try {
            val records = fetchTxtRecords()
            assertTrue(records.any { it.contains("i=") })
            assertTrue(records.any { it.contains("d=") })
            assertTrue(records.any { it.contains("e=") })
        } catch (e: IOException) {
            fail("Test failed due to exception: ${e.message}")
        }
    }
}
