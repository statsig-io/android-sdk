package com.statsig.androidsdk

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.junit.WireMockRule
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class ErrorBoundaryNetworkConnectivityTest {
    private lateinit var eb: ErrorBoundary
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var network: StatsigNetworkImpl

    private lateinit var conMan: ConnectivityManager
    private lateinit var shadowConMan: ShadowConnectivityManager

    private var ebCalled = false

    // Dynamic port so this test can run in parallel with other wiremock tests
    @Rule
    @JvmField
    val wireMockRule = WireMockRule(options().dynamicPort())

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    internal fun setup() {
        clearAllMocks()
        val dispatcher = TestUtil.mockDispatchers()
        val coroutineScope = TestScope(dispatcher)

        ebCalled = false
        eb = mockk()
        every { eb.logException(any()) } answers {
            ebCalled = true
        }
        conMan = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowConMan = Shadows.shadowOf(conMan)
        shadowConMan.setDefaultNetworkActive(false)
        val gson = StatsigUtil.getOrBuildGson()

        stubFor(
            post(urlMatching("/initialize"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
        )
        val store =
            Store(
                coroutineScope,
                TestUtil.getTestKeyValueStore(app),
                StatsigUser(),
                "client-apikey",
                StatsigOptions(),
                gson
            )
        network =
            StatsigNetworkImpl(
                app,
                "client-key",
                TestUtil.getTestKeyValueStore(app),
                StatsigOptions(),
                mockk(),
                coroutineScope,
                store,
                gson = gson
            )
    }

    @Config(sdk = [Build.VERSION_CODES.M])
    @Test
    fun testAndroidM_ErrorBoundaryNotHitWhenNoNetwork(): Unit = runBlocking {
        makeNetworkRequest()
        assertFalse(ebCalled)
    }

    @Config(sdk = [Build.VERSION_CODES.M])
    @Test
    fun testAndroidM_ErrorBoundaryIsNotHitWhenNetworkExists(): Unit = runBlocking {
        setNetworkInternetCapabilities()
        makeNetworkRequest()
        assertFalse(ebCalled)
    }

    private suspend fun makeNetworkRequest() {
        try {
            network.initializeImpl(
                wireMockRule.baseUrl(),
                StatsigUser(),
                null,
                StatsigMetadata(),
                ContextType.INITIALIZE,
                null,
                1,
                50,
                HashAlgorithm.NONE,
                mapOf(),
                null
            )
        } catch (e: Exception) {
            // noop
        }
    }

    private fun setNetworkInternetCapabilities() {
        val networkCapabilities: NetworkCapabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(networkCapabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowConMan.setNetworkCapabilities(conMan.activeNetwork, networkCapabilities)
    }
}
