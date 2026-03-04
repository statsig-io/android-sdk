@file:Suppress("DEPRECATION") // legacy network API's

package com.statsig.androidsdk

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class StatsigNetworkConnectivityListenerTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var shadowConnectivityManager: ShadowConnectivityManager

    @Before
    fun setUp() {
        connectivityManager =
            app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowConnectivityManager = shadowOf(connectivityManager)
    }

    @After
    fun tearDown() {
        ShadowConnectivityManager.reset()
    }

    @Test
    fun isNetworkAvailable_returnsTrueWhenInternetCapabilityExists() {
        setActiveNetworkCapabilities(
            transport = NetworkCapabilities.TRANSPORT_WIFI,
            capabilities =
            listOf(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            )
        )

        val listener = StatsigNetworkConnectivityListener(app)

        assertThat(listener.isNetworkAvailable()).isTrue()
    }

    @Test
    fun isNetworkAvailable_returnsFalseWithoutInternetCapability() {
        setActiveNetworkCapabilities(
            transport = NetworkCapabilities.TRANSPORT_WIFI,
            capabilities = emptyList()
        )

        val listener = StatsigNetworkConnectivityListener(app)

        assertThat(listener.isNetworkAvailable()).isFalse()
    }

    @Test
    fun getLogEventNetworkMetadata_returnsWifiAndValidatedInternet() {
        setActiveNetworkCapabilities(
            transport = NetworkCapabilities.TRANSPORT_WIFI,
            capabilities =
            listOf(
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
        )

        val listener = StatsigNetworkConnectivityListener(app)

        assertThat(listener.getLogEventNetworkMetadata()).isEqualTo(
            mapOf(
                StatsigNetworkConnectivityListener.NETWORK_METADATA_NET_TYPE to "wifi",
                StatsigNetworkConnectivityListener.NETWORK_METADATA_HAS_INTERNET to "true"
            )
        )
    }

    @Test
    fun getLogEventNetworkMetadata_returnsCellWithoutValidatedInternet() {
        setActiveNetworkCapabilities(
            transport = NetworkCapabilities.TRANSPORT_CELLULAR,
            capabilities =
            listOf(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            )
        )

        val listener = StatsigNetworkConnectivityListener(app)

        assertThat(listener.getLogEventNetworkMetadata()).isEqualTo(
            mapOf(
                StatsigNetworkConnectivityListener.NETWORK_METADATA_NET_TYPE to "cell",
                StatsigNetworkConnectivityListener.NETWORK_METADATA_HAS_INTERNET to "false"
            )
        )
    }

    @Test
    fun getLogEventNetworkMetadata_returnsNoneWhenNoTransportExists() {
        setActiveNetworkCapabilities(
            transport = null,
            capabilities = emptyList()
        )

        val listener = StatsigNetworkConnectivityListener(app)

        assertThat(listener.getLogEventNetworkMetadata()).isEqualTo(
            mapOf(
                StatsigNetworkConnectivityListener.NETWORK_METADATA_NET_TYPE to "none",
                StatsigNetworkConnectivityListener.NETWORK_METADATA_HAS_INTERNET to "false"
            )
        )
    }

    @Test
    fun isNetworkAvailable_usesLegacyActiveNetworkInfo() {
        shadowConnectivityManager.setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                true
            )
        )

        val listener = StatsigNetworkConnectivityListener(app)

        assertThat(
            runWithLollipop {
                listener.isNetworkAvailable()
            }
        ).isTrue()
    }

    @Test
    fun getLogEventNetworkMetadata_mapsLegacyWifi() {
        shadowConnectivityManager.setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.DISCONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                false
            )
        )

        val listener = StatsigNetworkConnectivityListener(app)

        assertThat(
            runWithLollipop {
                listener.getLogEventNetworkMetadata()
            }
        ).isEqualTo(
            mapOf(
                StatsigNetworkConnectivityListener.NETWORK_METADATA_NET_TYPE to "wifi",
                StatsigNetworkConnectivityListener.NETWORK_METADATA_HAS_INTERNET to "false"
            )
        )
    }

    @Test
    fun getLogEventNetworkMetadata_mapsLegacyCellType() {
        shadowConnectivityManager.setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_MOBILE,
                0,
                true,
                true
            )
        )

        val listener = StatsigNetworkConnectivityListener(app)

        assertThat(
            runWithLollipop {
                listener.getLogEventNetworkMetadata()
            }
        ).isEqualTo(
            mapOf(
                StatsigNetworkConnectivityListener.NETWORK_METADATA_NET_TYPE to "cell",
                StatsigNetworkConnectivityListener.NETWORK_METADATA_HAS_INTERNET to "true"
            )
        )
    }

    private fun setActiveNetworkCapabilities(transport: Int?, capabilities: List<Int>) {
        val networkCapabilities = ShadowNetworkCapabilities.newInstance()
        val shadowNetworkCapabilities = shadowOf(networkCapabilities)
        transport?.let { shadowNetworkCapabilities.addTransportType(it) }
        capabilities.forEach { shadowNetworkCapabilities.addCapability(it) }
        shadowConnectivityManager.setNetworkCapabilities(
            connectivityManager.activeNetwork,
            networkCapabilities
        )
    }

    private fun <T> runWithLollipop(block: () -> T): T {
        val originalSdkInt = ReflectionHelpers.getStaticField<Int>(
            Build.VERSION::class.java,
            "SDK_INT"
        )
        return try {
            ReflectionHelpers.setStaticField(
                Build.VERSION::class.java,
                "SDK_INT",
                Build.VERSION_CODES.LOLLIPOP_MR1
            )
            block()
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", originalSdkInt)
        }
    }
}
