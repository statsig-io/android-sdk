import com.statsig.androidsdk.TestUtil
import com.statsig.androidsdk.fetchTxtRecords
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DnsTxtQueryTest {

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        TestUtil.setupHttp(app)
    }

    @After
    fun tearDown() {
        TestUtil.reset()
    }

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
