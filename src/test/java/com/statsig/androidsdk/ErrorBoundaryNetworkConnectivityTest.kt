package com.statsig.androidsdk

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.junit.WireMockRule
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier

class ErrorBoundaryNetworkConnectivityTest {
    private lateinit var eb: ErrorBoundary
    private lateinit var app: Application
    private lateinit var network: StatsigNetworkImpl
    private lateinit var connectivityManager: ConnectivityManager

    private var ebCalled = false

    @Rule
    @JvmField
    val wireMockRule = WireMockRule()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    internal fun setup() {
        clearAllMocks()

        ebCalled = false
        eb = mockk()
        every { eb.logException(any()) } answers {
            ebCalled = true
        }

        app = mockk()
        TestUtil.stubAppFunctions(app)
        connectivityManager = mockk()
        every { app.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager

        stubFor(
            post(urlMatching("/initialize"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )

        network = StatsigNetworkImpl(app, "client-key", eb, app.getSharedPreferences("", Context.MODE_PRIVATE), StatsigOptions(), mockk(), TestUtil.coroutineScope)
    }

    @Test
    fun testAndroidM_ErrorBoundaryNotHitWhenNoNetwork(): Unit = runBlocking {
        setAndroidVersion(Build.VERSION_CODES.M)
        makeNetworkRequest()
        assertFalse(ebCalled)
    }

    @Test
    fun testAndroidM_ErrorBoundaryIsHitWhenNetworkExists(): Unit = runBlocking {
        setAndroidVersion(Build.VERSION_CODES.M)
        setNetworkInternetCapabilities()
        makeNetworkRequest()
        assertTrue(ebCalled)
    }

    @Test
    fun testBelowAndroidM_ErrorBoundaryIsHitWhenNetworkExists(): Unit = runBlocking {
        setAndroidVersion(Build.VERSION_CODES.KITKAT)
        setActiveNetworkInfo(true)
        makeNetworkRequest()
        assertTrue(ebCalled)
    }

    @Test
    fun testBelowAndroidM_ErrorBoundaryNotHitWhenNoNetwork(): Unit = runBlocking {
        setAndroidVersion(Build.VERSION_CODES.KITKAT)
        setActiveNetworkInfo(false)
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
                null,
            )
        } catch (e: Exception) {
            // noop
        }
    }

    private fun setActiveNetworkInfo(value: Boolean) {
        val info: NetworkInfo = mockk()
        every {
            connectivityManager.activeNetworkInfo
        } returns info

        every {
            info.isConnectedOrConnecting
        } returns value
    }

    private fun setNetworkInternetCapabilities() {
        val net: Network = mockk()
        val capa: NetworkCapabilities = mockk()

        every {
            connectivityManager.activeNetwork
        } returns net

        every {
            connectivityManager.getNetworkCapabilities(net)
        } returns capa

        every {
            capa.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } returns true
    }

    private fun setAndroidVersion(version: Int) {
        val field = Build.VERSION::class.java.getField("SDK_INT")
        field.isAccessible = true
        Field::class.java.getDeclaredField("modifiers").apply {
            isAccessible = true
            setInt(field, field.modifiers and Modifier.FINAL.inv())
        }
        field.set(null, version)
    }
}
