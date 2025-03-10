package com.statsig.androidsdk

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestCoroutineScope
import org.junit.Before
import org.junit.Test

class LogEventCompressionTest {
    private lateinit var app: Application
    private lateinit var testSharedPrefs: TestSharedPreferences

    @Before
    internal fun setup() {
        app = mockk()
        testSharedPrefs = TestUtil.stubAppFunctions(app)

        TestUtil.mockStatsigUtil()
        TestUtil.mockNetworkConnectivityService(app)
    }

    private fun setupNetwork(
        store: Store,
        options: StatsigOptions,
    ): StatsigNetworkImpl {
        val network =
            StatsigNetworkImpl(
                app,
                "sdk-key",
                mockk<ErrorBoundary>(),
                testSharedPrefs,
                options,
                mockk<NetworkFallbackResolver>(),
                TestCoroutineScope(),
                store,
            )
        return network
    }

    @Test
    fun testOverrideApi() {
        val api = "https://google.com/v1"
        var config = UrlConfig(Endpoint.Rgstr, api)
        val store = mockk<Store>()
        val network = setupNetwork(store, StatsigOptions(api))
        // Turn off flags
        every {
            store.getSDKFlags()
        } answers {
            mapOf("enable_log_event_compression" to false)
        }
        assert(!network.shouldCompressLogEvent(config, "$api/log_event"))

        // Turn on flags
        every {
            store.getSDKFlags()
        } answers {
            mapOf("enable_log_event_compression" to true)
        }
        assert(network.shouldCompressLogEvent(config, "$api/log_event"))
    }

    @Test
    fun testDefaultAPI() {
        val api = DEFAULT_EVENT_API
        var config = UrlConfig(Endpoint.Rgstr, api)
        val store = mockk<Store>()
        val network = setupNetwork(store, StatsigOptions(api))
        // Turn off flags
        every {
            store.getSDKFlags()
        } answers {
            mapOf("enable_log_event_compression" to false)
        }
        assert(network.shouldCompressLogEvent(config, "$api/log_event"))

        // Turn on flags
        every {
            store.getSDKFlags()
        } answers {
            mapOf("enable_log_event_compression" to true)
        }
        assert(network.shouldCompressLogEvent(config, "$api/log_event"))
    }

    @Test
    fun testDisableCompressionFromOption() {
        var api = DEFAULT_EVENT_API
        var fallbackUrl = DEFAULT_EVENT_API
        var config = UrlConfig(Endpoint.Rgstr, api, listOf(fallbackUrl))
        val store = mockk<Store>()
        var network = setupNetwork(store, StatsigOptions(api, disableLoggingCompression = true))
        // Turn on flags
        every {
            store.getSDKFlags()
        } answers {
            mapOf("enable_log_event_compression" to true)
        }
        assert(!network.shouldCompressLogEvent(config, api))

        api = "https://google.com"
        fallbackUrl = "https://chatgpt.com"
        config = UrlConfig(Endpoint.Rgstr, api, listOf(fallbackUrl))
        network = setupNetwork(store, StatsigOptions(api, disableLoggingCompression = true))
        assert(!network.shouldCompressLogEvent(config, api))
    }
}
