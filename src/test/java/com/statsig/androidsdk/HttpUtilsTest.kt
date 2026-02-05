package com.statsig.androidsdk

import android.app.Application
import com.google.common.truth.Truth.assertThat
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HttpUtilsTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        HttpUtils.okHttpClient = null
    }

    @Test
    fun maybeInitialize_initializesOnce() {
        // reality check
        assertThat(HttpUtils.okHttpClient).isNull()

        // initialize
        HttpUtils.maybeInitializeHttpClient(app)
        assertThat(HttpUtils.okHttpClient).isNotNull()
        val client = HttpUtils.okHttpClient!!

        // initialize again
        HttpUtils.maybeInitializeHttpClient(app)

        // instance has not changed
        assertThat(client).isSameInstanceAs(HttpUtils.okHttpClient)
    }

    @Test
    fun maybeInitialize_includesCustomDnsAndCache() {
        assertThat(HttpUtils.okHttpClient).isNull()
        HttpUtils.maybeInitializeHttpClient(app)

        assertThat(HttpUtils.okHttpClient).isNotNull()
        val client = HttpUtils.okHttpClient!!
        assertThat(client.cache).isNotNull()
        assertThat(client.dns).isNotEqualTo(Dns.SYSTEM)
    }

    @Test
    fun addInterceptors_addsInterceptors() {
        HttpUtils.maybeInitializeHttpClient(app)
        val previousInterceptors = HttpUtils.okHttpClient!!.interceptors

        val bogusInterceptor = TestInterceptor()
        HttpUtils.addInterceptors(listOf(bogusInterceptor))

        // new interceptor included, no interceptors dropped
        assertThat(HttpUtils.okHttpClient!!.interceptors).contains(bogusInterceptor)
        assertThat(
            HttpUtils.okHttpClient!!.interceptors
        ).containsAtLeastElementsIn(previousInterceptors)
    }

    private class TestInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            // no actual behavior
            return chain.proceed(chain.request())
        }
    }
}
