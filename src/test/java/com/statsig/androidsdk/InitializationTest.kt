package com.statsig.androidsdk

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkConstructor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class InitializationTest {
    private lateinit var app: Application
    private lateinit var mockWebServer: MockWebServer
    private var user: StatsigUser = StatsigUser("test-user")
    private var initializationHits = 0

    @Before
    internal fun setup() {
        app = RuntimeEnvironment.getApplication()
        TestUtil.mockDispatchers()
        mockWebServer = MockWebServer()
        TestUtil.mockHashing()

        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path!!.contains("initialize")) {
                    initializationHits++
                    return MockResponse().setResponseCode(408)
                } else {
                    MockResponse().setResponseCode(200)
                }
            }
        }
        mockWebServer.dispatcher = dispatcher
        mockWebServer.start()
        TestUtil.setupHttp(app)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        TestUtil.reset()
    }

    @Test
    fun testDefaultInitialization() = runTest {
        val options = StatsigOptions(api = mockWebServer.url("/v1").toString())
        val client = StatsigClient()
        val details = client.initialize(app, "client-key", user, options)
        assertThat(initializationHits).isEqualTo(1)
        assertThat(details?.source).isEqualTo(EvalSource.NoValues)
        client.shutdown()
    }

    @Test
    fun testInitializationDetailsUsesNetworkSource() = runTest {
        val client = StatsigClient()
        client.statsigNetwork = TestUtil.mockNetwork()

        val details = client.initialize(app, "client-key", user, StatsigOptions())

        assertThat(details?.success).isTrue()
        assertThat(details?.source).isEqualTo(EvalSource.Network)
        client.shutdown()
    }

    @Test
    fun testInitializationDetailsUsesBootstrapSource() = runTest {
        val client = StatsigClient()

        val details = client.initialize(
            app,
            "client-key",
            user,
            StatsigOptions(
                initializeValues = mapOf("evaluated_keys" to mapOf("userID" to "test-user"))
            )
        )

        assertThat(details?.success).isTrue()
        assertThat(details?.source).isEqualTo(EvalSource.Bootstrap)
        client.shutdown()
    }

    @Test
    fun testRetry() = runTest {
        val options =
            StatsigOptions(
                api = mockWebServer.url("/v1").toString(),
                initRetryLimit = 2,
                initTimeoutMs = 10000L
            )
        val client = StatsigClient()

        client.initialize(app, "client-key", user, options)
        assertThat(initializationHits).isEqualTo(3)
        client.shutdown()
    }

    @Test
    fun testInitializeDoesNotBlockOnCachePersistence() {
        // Needs real background dispatchers for this assertion, rather than the single
        // dispatcher most of our tests are using.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkConstructor(CoroutineDispatcherProvider::class)
        every { anyConstructed<CoroutineDispatcherProvider>().io } returns Dispatchers.IO
        every { anyConstructed<CoroutineDispatcherProvider>().main } returns Dispatchers.Main
        every { anyConstructed<CoroutineDispatcherProvider>().default } returns Dispatchers.Default

        val backingStorage = TestUtil.getTestKeyValueStore(app)
        val allowCacheWrites = CountDownLatch(1)
        val cacheWriteStarted = CountDownLatch(1)

        StatsigClient.keyValueStorageFactoryOverride = { _, _ ->
            object : KeyValueStorage<String> by backingStorage {
                override suspend fun writeValue(storeName: String, key: String, value: String) {
                    if (storeName.startsWith("ondiskvaluecache")) {
                        cacheWriteStarted.countDown()
                        allowCacheWrites.await(1, TimeUnit.SECONDS)
                    }
                    backingStorage.writeValue(storeName, key, value)
                }
            }
        }

        val client = StatsigClient()
        client.statsigNetwork = TestUtil.mockNetwork()
        val executor = Executors.newSingleThreadExecutor()
        val initResult = executor.submit<InitializationDetails?> {
            runBlocking {
                client.initialize(app, "client-key", user, StatsigOptions())
            }
        }

        assertThat(initResult.get(1, TimeUnit.SECONDS)?.success).isTrue()
        assertThat(client.checkGate("always_on")).isTrue()
        assertThat(cacheWriteStarted.await(1, TimeUnit.SECONDS)).isTrue()

        allowCacheWrites.countDown()

        val gson = StatsigUtil.getOrBuildGson()
        val fullCacheKey = "${user.toHashString(gson)}:client-key"
        val storeName = TestUtil.getPerUserCacheStoreName(fullCacheKey)
        val storageKey = TestUtil.getPerUserCacheStorageKey(fullCacheKey)
        runBlocking {
            client.shutdownSuspend()
            assertThat(backingStorage.readValue(storeName, storageKey)).isNotNull()
        }
        executor.shutdownNow()
    }
}
