package com.statsig.androidsdk

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.statsig.androidsdk.InitializeResponse.SuccessfulInitializeResponse
import io.mockk.coEvery
import io.mockk.every
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// Overlapping user switches where an older call's response lands after a newer user is live.
@RunWith(RobolectricTestRunner::class)
class UpdateUserRaceTest {
    private lateinit var app: Application
    private lateinit var client: StatsigClient
    private lateinit var network: StatsigNetwork
    private val heldInitializes = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val pollFlows =
        ConcurrentHashMap<String, MutableSharedFlow<SuccessfulInitializeResponse?>>()
    private var networkDown = false
    private var valuesUpdatedCount = 0

    private val user0 = StatsigUser("user-0")
    private val userA = StatsigUser("user-a")
    private val userB = StatsigUser("user-b")

    private val options = StatsigOptions(
        enableAutoValueUpdate = true,
        lifetimeCallback = object : IStatsigLifetimeCallback {
            override fun onValuesUpdated() {
                valuesUpdatedCount++
            }
        }
    )

    private fun responseFor(tag: String) = TestUtil.makeInitializeResponse(
        mapOf(),
        mapOf(
            "a_config!" to APIDynamicConfig(
                "a_config!",
                mutableMapOf("key" to "${tag}_value"),
                "default"
            )
        ),
        mapOf(),
        time = 1748624742000,
        hasUpdates = true
    )

    @Before
    fun setup() {
        TestUtil.mockDispatchers()
        app = RuntimeEnvironment.getApplication()
        TestUtil.mockHashing()
        network = TestUtil.mockNetwork()
        coEvery {
            network.initialize(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val userID = secondArg<StatsigUser>().userID!!
            heldInitializes.remove(userID)?.await()
            if (networkDown) {
                InitializeResponse.FailedInitializeResponse(
                    InitializeFailReason.NetworkError,
                    null,
                    500
                )
            } else {
                responseFor(userID)
            }
        }
        every {
            network.pollForChanges(any(), any(), any(), any(), any())
        } answers {
            pollFlows.getOrPut(secondArg<StatsigUser>().userID!!) { MutableSharedFlow() }
        }
        client = StatsigClient()
        client.statsigNetwork = network
    }

    @After
    fun tearDown() {
        runBlocking { client.shutdownSuspend() }
        TestUtil.reset()
    }

    // The next /initialize for this user waits until the returned gate completes.
    private fun holdInitialize(userID: String) =
        CompletableDeferred<Unit>().also { heldInitializes[userID] = it }

    private fun land(gate: CompletableDeferred<Unit>) {
        gate.complete(Unit)
        runBlocking { client.getStore().awaitPendingSave() }
    }

    private fun startOn(user: StatsigUser) {
        TestUtil.startStatsigClientAndWait(app, client, "client-key", user, options, network)
    }

    private fun value() = client.getConfig("a_config").getString("key", "default")

    private fun source() = client.getConfig("a_config").getEvalDetails().source

    @Test
    fun overlappingUpdateUserAsync_staleResponseIsNotAppliedToNewUser() {
        startOn(user0)
        val gateA = holdInitialize("user-a")

        client.updateUserAsync(userA)
        client.updateUserAsync(userB)
        assertThat(value()).isEqualTo("user-b_value")

        land(gateA)

        assertThat(value()).isEqualTo("user-b_value")
        assertThat(source()).isEqualTo(EvalSource.Network)
    }

    @Test
    fun overlappingUpdateUser_staleResponseIsNotAppliedToNewUser() = runBlocking {
        startOn(user0)
        val gateA = holdInitialize("user-a")

        val jobA = launch(start = CoroutineStart.UNDISPATCHED) { client.updateUser(userA) }
        client.updateUser(userB)
        assertThat(value()).isEqualTo("user-b_value")

        gateA.complete(Unit)
        jobA.join()

        assertThat(value()).isEqualTo("user-b_value")
    }

    @Test
    fun overlappingUpdateUserAsync_staleResponseIsNotPersistedForNewUser() {
        startOn(user0)
        val gateA = holdInitialize("user-a")
        client.updateUserAsync(userA)
        client.updateUserAsync(userB)
        land(gateA)
        runBlocking { client.shutdownSuspend() }

        // Cold start as B with no network: values must come from B's own disk cache.
        networkDown = true
        client = StatsigClient()
        startOn(userB)

        assertThat(source()).isEqualTo(EvalSource.Cache)
        assertThat(value()).isEqualTo("user-b_value")
    }

    @Test
    fun bootstrappedUpdateUser_doesNotOverwritePreviousUsersCachedValues() {
        startOn(userA)
        assertThat(value()).isEqualTo("user-a_value")

        val bootstrapB =
            TestUtil.makeBootstrapInitializeValues(userB, responseFor("user-b-bootstrap"))
        client.updateUserAsync(userB, null, bootstrapB)
        assertThat(value()).isEqualTo("user-b-bootstrap_value")

        // Switch back to A with no network: A must get A's own cached values.
        networkDown = true
        client.updateUserAsync(userA)

        assertThat(source()).isEqualTo(EvalSource.Cache)
        assertThat(value()).isEqualTo("user-a_value")
    }

    @Test
    fun refreshCacheAsyncForPreviousUser_doesNotOverwriteNewUser() {
        startOn(userA)
        val gateA = holdInitialize("user-a")

        runBlocking { client.refreshCacheAsync() }
        client.updateUserAsync(userB)
        land(gateA)

        assertThat(value()).isEqualTo("user-b_value")
        assertThat(source()).isEqualTo(EvalSource.Network)
    }

    @Test
    fun supersededFailure_doesNotChangeNewUsersSource() {
        startOn(user0)
        val gateA = holdInitialize("user-a")
        client.updateUserAsync(userA)
        client.updateUserAsync(userB)

        networkDown = true
        land(gateA)

        assertThat(source()).isEqualTo(EvalSource.Network)
        assertThat(value()).isEqualTo("user-b_value")
    }

    @Test
    fun droppedResponse_doesNotFireOnValuesUpdated() {
        startOn(user0)
        val gateA = holdInitialize("user-a")
        client.updateUserAsync(userA)
        client.updateUserAsync(userB)
        val countBeforeStaleResponse = valuesUpdatedCount

        land(gateA)

        assertThat(valuesUpdatedCount).isEqualTo(countBeforeStaleResponse)
    }

    @Test
    fun pollEmission_isAppliedOnlyToTheUserItWasStartedFor() = runBlocking {
        startOn(userA)
        client.updateUserAsync(userB)

        pollFlows.getValue("user-a").emit(responseFor("user-a-poll"))
        client.getStore().awaitPendingSave()
        assertThat(value()).isEqualTo("user-b_value")

        pollFlows.getValue("user-b").emit(responseFor("user-b-poll"))
        client.getStore().awaitPendingSave()
        assertThat(value()).isEqualTo("user-b-poll_value")
    }
}
