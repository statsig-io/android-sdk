package com.statsig.androidsdk

import android.app.Application
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

internal suspend fun getResponseForUser(user: StatsigUser): InitializeResponse? {
  return withContext(Dispatchers.IO) {
    val isUserA = user.userID == "user-a"
    delay(if (isUserA) 100 else 200)
    TestUtil.makeInitializeResponse(
      mapOf(),
      mapOf(
        "a_config!" to APIDynamicConfig(
          "a_config!",
          mutableMapOf(
            "key" to if (isUserA) "user_a_value" else "user_b_value"
          ),
          "default",
        )
      ), mapOf()
    )
  }
}

class AsyncInitVsUpdateTest {
  lateinit var app: Application

  @Before
  internal fun setup() {
    TestUtil.mockDispatchers()

    app = mockk()
    TestUtil.stubAppFunctions(app)
    TestUtil.mockStatsigUtil()

    val network = TestUtil.mockNetwork()
    coEvery {
      network.initialize(any(), any(), any(), any(), any(), any())
    } coAnswers {
      val user = thirdArg<StatsigUser>()
      getResponseForUser(user)
    }
    Statsig.client = StatsigClient()
    Statsig.client.statsigNetwork = network
  }

  @Test
  fun testDummy() {
    val userA = StatsigUser("user-a")
    userA.customIDs = mapOf("workID" to "employee-a")

    val userB = StatsigUser("user-b")
    userB.customIDs = mapOf("workID" to "employee-b")

    val didInitializeUserA = CountDownLatch(1)
    val didInitializeUserB = CountDownLatch(1)

    val callback = object : IStatsigCallback {
      override fun onStatsigInitialize() {
        didInitializeUserA.countDown()
      }

      override fun onStatsigUpdateUser() {
        didInitializeUserB.countDown()
      }
    }

    Statsig.initializeAsync(app, "client-key", userA, callback)

    var value = Statsig.getConfig("a_config").getString("key", "default")
    assertEquals("default", value)

    didInitializeUserA.await()

    value = Statsig.getConfig("a_config").getString("key", "default")
    assertEquals("user_a_value", value)

    Statsig.updateUserAsync(userB, callback)

    didInitializeUserB.await()

    value = Statsig.getConfig("a_config").getString("key", "default")
    assertEquals("user_b_value", value)
  }

}

