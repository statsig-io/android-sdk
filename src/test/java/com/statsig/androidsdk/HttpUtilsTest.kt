package com.statsig.androidsdk

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.lang.RuntimeException
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
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
        TestUtil.reset()
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

    @Test
    fun dohFallbackDns_error_fallsBackToSystem() {
        val mockDoh = mockk<DnsOverHttps>()
        val mockSysDns = mockk<Dns>()
        val dohDnsWithFallback = DohDnsWithSystemFallback(mockDoh, mockSysDns)
        val fakeHostName = "www.fake.com"
        every { mockDoh.lookup(any()) } throws UnknownHostException()
        every { mockSysDns.lookup(any()) } answers { Dns.SYSTEM.lookup(fakeHostName) }

        dohDnsWithFallback.lookup(fakeHostName)

        verify { mockSysDns.lookup(fakeHostName) }
    }

    @Test
    fun dohFallbackDns_noError_doesNotTouchSystemDns() {
        val mockDoh = mockk<DnsOverHttps>()
        val mockSysDns = mockk<Dns>()
        val dohDnsWithFallback = DohDnsWithSystemFallback(mockDoh, mockSysDns)
        val fakeHostName = "www.fake.com"
        every { mockDoh.lookup(any()) } answers { Dns.SYSTEM.lookup(fakeHostName) }
        every { mockSysDns.lookup(any()) } throws RuntimeException("No interaction expected")

        dohDnsWithFallback.lookup(fakeHostName)

        verify(exactly = 0) { mockSysDns.lookup(fakeHostName) }
    }

    private class TestInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            // no actual behavior
            return chain.proceed(chain.request())
        }
    }
}
