package com.statsig.androidsdk

import android.app.Application
import io.mockk.coEvery
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val CONFIG_NAME = "gen_config"
private const val GENERATION_COUNT = 200
private const val TORN_READ_THREAD_COUNT = 32
private const val EXCEPTION_READ_THREAD_COUNT = 16
private const val EXCEPTION_READ_ITERATIONS_PER_THREAD = 300
private const val EXCEPTION_UPDATE_ITERATIONS = 60
private const val EXCEPTION_SUSPEND_UPDATE_ITERATIONS = 15

// Keep this generous: torn-read detection is the assertion; timeout only catches hangs.
private const val AWAIT_TIMEOUT_SECONDS = 60L
private const val STICKY_MUTATION_EXPERIMENT_NAME = "sticky_mutation_exp"
private const val STICKY_MUTATION_READER_THREAD_COUNT = 16
private const val STICKY_MUTATION_ITERATIONS_PER_READER = 300

@RunWith(RobolectricTestRunner::class)
class StatsigThreadSafetyTest {

    private lateinit var client: StatsigClient
    private lateinit var app: Application
    private lateinit var network: StatsigNetwork

    @Before
    fun setup() {
        TestUtil.mockDispatchers()
        app = RuntimeEnvironment.getApplication()
        network = TestUtil.mockNetwork()
        client = StatsigClient()
        client.statsigNetwork = network
    }

    /** Every observed config value/lcut pair must belong to one generation. */
    @Test
    fun testConcurrentGetConfigDuringUpdateUserNeverObservesTornGeneration() {
        val lcutForGeneration = (0 until GENERATION_COUNT).associateWith { it.toLong() + 1000L }
        coEvery {
            network.initialize(
                api = any(),
                user = any(),
                sinceTime = any(),
                metadata = any(),
                coroutineScope = any(),
                contextType = any(),
                diagnostics = any(),
                hashUsed = any(),
                previousDerivedFields = any(),
                fullChecksum = any()
            )
        } coAnswers {
            val user = secondArg<StatsigUser>()
            val generation = (user.userID?.removePrefix("gen_user_")?.toIntOrNull() ?: 0)
                .coerceIn(0, GENERATION_COUNT - 1)
            TestUtil.makeInitializeResponse(
                dynamicConfigs = mapOf(
                    CONFIG_NAME to
                        APIDynamicConfig(CONFIG_NAME, mapOf("gen" to generation.toString()))
                ),
                time = lcutForGeneration.getValue(generation)
            )
        }
        TestUtil.startStatsigClientAndWait(app, client, "client-key", StatsigUser("gen_user_0"))

        val tornReads = Collections.synchronizedList(mutableListOf<String>())
        val exceptions = Collections.synchronizedList(mutableListOf<Throwable>())
        val readersDone = CountDownLatch(TORN_READ_THREAD_COUNT)
        val updaterDone = CountDownLatch(1)

        val readers = (1..TORN_READ_THREAD_COUNT).map {
            daemonThread {
                try {
                    while (updaterDone.count > 0) {
                        checkOneReadForTornGeneration(
                            client,
                            lcutForGeneration,
                            tornReads,
                            exceptions
                        )
                    }
                    repeat(20) {
                        checkOneReadForTornGeneration(
                            client,
                            lcutForGeneration,
                            tornReads,
                            exceptions
                        )
                    }
                } finally {
                    readersDone.countDown()
                }
            }
        }

        val updater = daemonThread {
            try {
                (1 until GENERATION_COUNT).forEach { generation ->
                    try {
                        client.updateUserAsync(StatsigUser("gen_user_$generation"))
                    } catch (t: Throwable) {
                        exceptions.add(RuntimeException("updater generation $generation failed", t))
                    }
                }
            } finally {
                updaterDone.countDown()
            }
        }

        updater.start()
        readers.forEach { it.start() }

        val updaterFinished = updaterDone.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val readersFinished = readersDone.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (!readersFinished || !updaterFinished) {
            fail(
                "Threads did not finish within $AWAIT_TIMEOUT_SECONDS s " +
                    "(readersFinished=$readersFinished, updaterFinished=$updaterFinished). " +
                    "This is itself evidence of a hang/deadlock caused by unsynchronized access."
            )
        }

        assertEquals(
            "No exceptions should be thrown by concurrent reads/updates. " +
                "First failure: ${exceptions.firstOrNull()}",
            0,
            exceptions.size
        )

        assertTrue(
            "getConfig observed a torn read: a config value from one generation paired with " +
                "an EvalDetails.lcut from a different generation. This proves Store.currentCache " +
                "was read non-atomically across getConfigData()/getGlobalEvalDetails() while " +
                "updateUserAsync concurrently reassigned it. First few torn reads: " +
                tornReads.take(5),
            tornReads.isEmpty()
        )
    }

    /** Covers the network-refresh write path by driving Store.save directly. */
    @Test
    fun testConcurrentGetConfigDuringStoreSaveNeverObservesTornGeneration() {
        val storeCoroutineScope = TestScope(TestUtil.mockDispatchers())
        val storeUser = StatsigUser("store_save_user")
        val store = Store(
            storeCoroutineScope,
            InMemoryKeyValueStorage(),
            storeUser,
            "client-key",
            StatsigOptions(),
            StatsigUtil.getOrBuildGson()
        )
        store.syncLoadFromLocalStorage()

        val lcutForGeneration = (0 until GENERATION_COUNT).associateWith { it.toLong() + 1000L }
        val responseForGeneration = (0 until GENERATION_COUNT).associateWith { generation ->
            TestUtil.makeInitializeResponse(
                dynamicConfigs = mapOf(
                    CONFIG_NAME to
                        APIDynamicConfig(CONFIG_NAME, mapOf("gen" to generation.toString()))
                ),
                time = lcutForGeneration.getValue(generation)
            )
        }

        val tornReads = Collections.synchronizedList(mutableListOf<String>())
        val exceptions = Collections.synchronizedList(mutableListOf<Throwable>())
        val readersDone = CountDownLatch(TORN_READ_THREAD_COUNT)
        val updaterDone = CountDownLatch(1)

        val readers = (1..TORN_READ_THREAD_COUNT).map {
            daemonThread {
                try {
                    while (updaterDone.count > 0) {
                        checkOneStoreReadForTornGeneration(
                            store,
                            lcutForGeneration,
                            tornReads,
                            exceptions
                        )
                    }
                    repeat(20) {
                        checkOneStoreReadForTornGeneration(
                            store,
                            lcutForGeneration,
                            tornReads,
                            exceptions
                        )
                    }
                } finally {
                    readersDone.countDown()
                }
            }
        }

        val updater = daemonThread {
            try {
                (0 until GENERATION_COUNT).forEach { generation ->
                    try {
                        runBlocking {
                            store.save(responseForGeneration.getValue(generation), storeUser)
                        }
                    } catch (t: Throwable) {
                        exceptions.add(RuntimeException("updater generation $generation failed", t))
                    }
                }
            } finally {
                updaterDone.countDown()
            }
        }

        updater.start()
        readers.forEach { it.start() }

        val updaterFinished = updaterDone.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val readersFinished = readersDone.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (!readersFinished || !updaterFinished) {
            fail(
                "Threads did not finish within $AWAIT_TIMEOUT_SECONDS s " +
                    "(readersFinished=$readersFinished, updaterFinished=$updaterFinished). " +
                    "This is itself evidence of a hang/deadlock caused by unsynchronized access."
            )
        }

        assertEquals(
            "No exceptions should be thrown by concurrent reads/updates. " +
                "First failure: ${exceptions.firstOrNull()}",
            0,
            exceptions.size
        )

        assertTrue(
            "getConfig observed a torn read: a config value from one generation paired with " +
                "an EvalDetails.lcut from a different generation. This proves Store.currentCache " +
                "was read non-atomically while Store.save (the updateUserImpl/network-refresh " +
                "path's mutation) concurrently reassigned it. First few torn reads: " +
                tornReads.take(5),
            tornReads.isEmpty()
        )
    }

    /** Sticky mutations and persistence must not race on the sticky experiment map. */
    @Test
    fun testConcurrentStickyExperimentMutationDuringPersistThrowsNoException() {
        val storeCoroutineScope = TestScope(TestUtil.mockDispatchers())
        val stickyUser = StatsigUser("sticky_mutation_user")
        val store = Store(
            storeCoroutineScope,
            InMemoryKeyValueStorage(),
            stickyUser,
            "client-key",
            StatsigOptions(),
            StatsigUtil.getOrBuildGson()
        )
        store.syncLoadFromLocalStorage()
        runBlocking {
            store.save(
                TestUtil.makeInitializeResponse(
                    dynamicConfigs = mapOf(
                        STICKY_MUTATION_EXPERIMENT_NAME to APIDynamicConfig(
                            STICKY_MUTATION_EXPERIMENT_NAME,
                            mapOf("key" to "v0"),
                            isDeviceBased = false,
                            isUserInExperiment = true,
                            isExperimentActive = true
                        )
                    )
                ),
                stickyUser
            )
        }

        val exceptions = Collections.synchronizedList(mutableListOf<Throwable>())
        val readersDone = CountDownLatch(STICKY_MUTATION_READER_THREAD_COUNT)
        val persisterDone = CountDownLatch(1)

        val readers = (1..STICKY_MUTATION_READER_THREAD_COUNT).map {
            daemonThread {
                try {
                    repeat(STICKY_MUTATION_ITERATIONS_PER_READER) { iteration ->
                        try {
                            store.getExperiment(
                                STICKY_MUTATION_EXPERIMENT_NAME,
                                keepDeviceValue = iteration % 2 == 0
                            )
                        } catch (t: Throwable) {
                            exceptions.add(t)
                        }
                    }
                } finally {
                    readersDone.countDown()
                }
            }
        }

        val persister = daemonThread {
            try {
                while (readersDone.count > 0) {
                    try {
                        runBlocking { store.persistStickyValues() }
                    } catch (t: Throwable) {
                        exceptions.add(t)
                    }
                }
            } finally {
                persisterDone.countDown()
            }
        }

        persister.start()
        readers.forEach { it.start() }

        val readersFinished = readersDone.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val persisterFinished = persisterDone.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (!readersFinished || !persisterFinished) {
            fail(
                "Threads did not finish within $AWAIT_TIMEOUT_SECONDS s " +
                    "(readersFinished=$readersFinished, persisterFinished=$persisterFinished). " +
                    "This is itself evidence of a hang caused by unsynchronized map access."
            )
        }

        assertEquals(
            "No exceptions should be thrown by concurrent sticky-map mutation/serialization. " +
                "First failure: ${exceptions.firstOrNull()}",
            0,
            exceptions.size
        )
    }

    /** Supplementary stress pass across the public read APIs and both update paths. */
    @Test
    fun testConcurrentReadsAcrossAllApisAndBothUpdatePathsThrowNoExceptions() {
        TestUtil.startStatsigClientAndWait(app, client, "client-key", StatsigUser("initial_user"))

        val exceptions = Collections.synchronizedList(mutableListOf<Throwable>())
        val readersDone = CountDownLatch(EXCEPTION_READ_THREAD_COUNT)
        val updaterDone = CountDownLatch(1)

        val readers = (1..EXCEPTION_READ_THREAD_COUNT).map { threadIndex ->
            daemonThread {
                try {
                    hammerAllReadApis(threadIndex, exceptions)
                } finally {
                    readersDone.countDown()
                }
            }
        }

        val updater = daemonThread {
            try {
                repeat(EXCEPTION_UPDATE_ITERATIONS) { updateIndex ->
                    try {
                        client.updateUserAsync(StatsigUser("updated_user_$updateIndex"))
                    } catch (t: Throwable) {
                        exceptions.add(
                            RuntimeException("updateUserAsync iteration $updateIndex failed", t)
                        )
                    }
                }
            } finally {
                updaterDone.countDown()
            }
        }

        val suspendUpdateJob = client.statsigScope.launch {
            repeat(EXCEPTION_SUSPEND_UPDATE_ITERATIONS) { updateIndex ->
                try {
                    client.updateUser(StatsigUser("suspend_updated_user_$updateIndex"))
                } catch (t: Throwable) {
                    exceptions.add(RuntimeException("updateUser iteration $updateIndex failed", t))
                }
            }
        }

        readers.forEach { it.start() }
        updater.start()

        val readersFinished = readersDone.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val updaterFinished = updaterDone.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        suspendUpdateJob.cancel()

        if (!readersFinished || !updaterFinished) {
            fail(
                "Threads did not finish within $AWAIT_TIMEOUT_SECONDS s " +
                    "(readersFinished=$readersFinished, updaterFinished=$updaterFinished). " +
                    "This is itself evidence of a hang/deadlock caused by unsynchronized access. " +
                    "Exceptions collected so far: ${exceptions.size}, first: ${exceptions.firstOrNull()}"
            )
        }

        assertEquals(
            "No exceptions should be thrown by concurrent reads/updates. " +
                "First failure: ${exceptions.firstOrNull()}",
            0,
            exceptions.size
        )
    }

    private fun checkOneReadForTornGeneration(
        client: StatsigClient,
        lcutForGeneration: Map<Int, Long>,
        tornReads: MutableList<String>,
        exceptions: MutableList<Throwable>
    ) {
        try {
            val config = client.getConfig(CONFIG_NAME)
            val observedGeneration = config.getString("gen", null)?.toIntOrNull()
            val observedLcut = config.getEvalDetails().lcut
            if (observedGeneration != null && observedLcut != null) {
                val expectedLcut = lcutForGeneration[observedGeneration]
                if (expectedLcut != null && expectedLcut != observedLcut) {
                    tornReads.add(
                        "value from generation $observedGeneration " +
                            "(expected lcut=$expectedLcut) but observed lcut=$observedLcut"
                    )
                }
            }
        } catch (t: Throwable) {
            exceptions.add(t)
        }
    }

    private fun checkOneStoreReadForTornGeneration(
        store: Store,
        lcutForGeneration: Map<Int, Long>,
        tornReads: MutableList<String>,
        exceptions: MutableList<Throwable>
    ) {
        try {
            val config = store.getConfig(CONFIG_NAME)
            val observedGeneration = config.getString("gen", null)?.toIntOrNull()
            val observedLcut = config.getEvalDetails().lcut
            if (observedGeneration != null && observedLcut != null) {
                val expectedLcut = lcutForGeneration[observedGeneration]
                if (expectedLcut != null && expectedLcut != observedLcut) {
                    tornReads.add(
                        "value from generation $observedGeneration " +
                            "(expected lcut=$expectedLcut) but observed lcut=$observedLcut"
                    )
                }
            }
        } catch (t: Throwable) {
            exceptions.add(t)
        }
    }

    private fun hammerAllReadApis(threadIndex: Int, exceptions: MutableList<Throwable>) {
        repeat(EXCEPTION_READ_ITERATIONS_PER_THREAD) { iteration ->
            try {
                client.checkGate("always_on!")
                client.getConfig("test_config!")
                client.getExperiment("exp!", keepDeviceValue = true)
                client.getLayer("allocated_layer!", keepDeviceValue = true)
                client.getParameterStore("test_param_store")
            } catch (t: Throwable) {
                exceptions.add(
                    RuntimeException("reader-$threadIndex iteration $iteration failed", t)
                )
            }
        }
    }

    private fun daemonThread(block: () -> Unit): Thread = Thread(block).apply { isDaemon = true }
}
