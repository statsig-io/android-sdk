package com.statsig.android_sdk_testbed

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.HandlerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.statsig.android_sdk_testbed.databinding.ActivityMainBinding
import com.statsig.androidsdk.Statsig
import kotlinx.coroutines.*
import java.lang.Exception
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
  private val TAG = "DLOOMB"

  private lateinit var binding: ActivityMainBinding
  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private val executorService: ExecutorService = Executors.newFixedThreadPool(4)
  private val handlerThread = HandlerThread("Custom Handler")

  private val handler: Handler

  init {
    handlerThread.start()
    handler = HandlerCompat.createAsync(handlerThread.looper)
  }

  suspend fun statsigInit() {
    val key = BuildConfig.STATSIG_CLIENT_KEY
    if (!key.startsWith("client-")) {
      throw Exception("Invalid Client Key")
    }
    Statsig.initialize(application, BuildConfig.STATSIG_CLIENT_KEY)
  }

  private fun statsigCheckGate() {
    val gate = Statsig.checkGate("test_gate")
    Log.d(TAG, "Gate Result: $gate ${Thread.currentThread().name}")

    scope.launch(Dispatchers.Main) {
      Toast.makeText(application, "Gate Result: $gate", Toast.LENGTH_SHORT).show()
    }
  }

  private fun statsigLog() {
    Statsig.logEvent("test_event")
  }

  private fun statsigConfig() {
    val config = Statsig.getConfig("test_config")

    Log.d(TAG, "Config Result: ${config.getName()} ${Thread.currentThread().name}")

    scope.launch(Dispatchers.Main) {
      Toast.makeText(application, "Config Result: ${config.getName()}", Toast.LENGTH_SHORT).show()
    }
  }

  private fun statsigExperiment() {
    val experiment = Statsig.getExperiment("test_experiment")

    Log.d(TAG, "Exp Result: ${experiment.getName()} ${Thread.currentThread().name}")
    scope.launch(Dispatchers.Main) {
      Toast.makeText(application, "Exp Result: ${experiment.getName()}", Toast.LENGTH_SHORT).show()
    }
  }

  private suspend fun loopCall(action: suspend () -> Unit) {
    for (i in 0..4) {
      action.invoke()
    }
  }

  private fun launchMain(action: suspend () -> Unit) {
    scope.launch { loopCall(action) }
  }

  private fun launchDefault(action: suspend () -> Unit) {
    scope.launch(Dispatchers.Default) { loopCall(action) }
  }

  private fun launchIO(action: suspend () -> Unit) {
    scope.launch(Dispatchers.IO) { loopCall(action) }
  }

  @OptIn(ObsoleteCoroutinesApi::class)
  private fun launchSingleThreaded(action: suspend () -> Unit) {
    scope.launch(newSingleThreadContext("Single Thread")) { loopCall(action) }
  }

  private fun launchExecutor(action: suspend () -> Unit) {
    executorService.execute {
      runBlocking {
        loopCall(action)
      }
    }
  }

  private fun launchHandler(action: suspend () -> Unit) {
    handler.post { runBlocking { loopCall(action) } }
  }

  private suspend fun stressTest() {
    for (i in 0..10) {
      statsigCheckGate()
      statsigConfig()
      statsigExperiment()
      statsigLog()
      delay(1L * (1..2).random())
    }
  }

  private val configs =
      arrayOf(
        Pair(null, "Start"),
        Pair({ runBlocking { scope.launch { statsigInit() }}}, "Start - Blocking (Once)"),
        Pair({ launchMain { statsigInit() } }, "Start - Main"),
        Pair({ launchDefault { statsigInit() } }, "Start - Default"),
        Pair({ launchIO { statsigInit() } }, "Start - IO"),
        Pair({ launchSingleThreaded { statsigInit() } }, "Start - Single Thread"),

        Pair(null, "Gates"),
        Pair({
          launchMain { statsigCheckGate() }
          launchDefault { statsigCheckGate() }
          launchIO { statsigCheckGate() }
          launchSingleThreaded { statsigCheckGate() }
          launchExecutor { statsigCheckGate() }
          launchHandler { statsigCheckGate() }
        }, "Check Gate - All"),
        Pair({ launchMain { statsigCheckGate() } }, "Check Gate - Main"),
        Pair({ launchDefault { statsigCheckGate() } }, "Check Gate - Default"),
        Pair({ launchIO { statsigCheckGate() } }, "Check Gate - IO"),
        Pair({ launchSingleThreaded { statsigCheckGate() } }, "Check Gate - Single Thread"),

        Pair(null, "Logging"),
        Pair({
          launchMain { statsigLog() }
          launchDefault { statsigLog() }
          launchIO { statsigLog() }
          launchSingleThreaded { statsigLog() }
          launchExecutor { statsigLog() }
          launchHandler { statsigLog() }
        }, "Log Event - All"),
        Pair({ launchMain { statsigLog() } }, "Log Event - Main"),
        Pair({ launchDefault { statsigLog() } }, "Log Event - Default"),
        Pair({ launchIO { statsigLog() } }, "Log Event - IO"),
        Pair({ launchSingleThreaded { statsigLog() } }, "Log Event - Single Thread"),

        Pair(null, "Configs"),
        Pair({
          launchMain { statsigConfig() }
          launchDefault { statsigConfig() }
          launchIO { statsigConfig() }
          launchSingleThreaded { statsigConfig() }
          launchExecutor { statsigConfig() }
          launchHandler { statsigConfig() }
        }, "Get Config - All"),
        Pair({ launchMain { statsigConfig() } }, "Get Config - Main"),
        Pair({ launchDefault { statsigConfig() } }, "Get Config - Default"),
        Pair({ launchIO { statsigConfig() } }, "Get Config - IO"),
        Pair({ launchSingleThreaded { statsigConfig() } }, "Get Config - Single Thread"),

        Pair(null, "Experiments"),
        Pair({
          launchMain { statsigExperiment() }
          launchDefault { statsigExperiment() }
          launchIO { statsigExperiment() }
          launchSingleThreaded { statsigExperiment() }
          launchExecutor { statsigExperiment() }
          launchHandler { statsigExperiment() }
        }, "Get Experiment - All"),
        Pair({ launchMain { statsigExperiment() } }, "Get Experiment - Main"),
        Pair({ launchDefault { statsigExperiment() } }, "Get Experiment - Default"),
        Pair({ launchIO { statsigExperiment() } }, "Get Experiment - IO"),
        Pair({ launchSingleThreaded { statsigExperiment() } }, "Get Experiment - Single Thread"),

        Pair(null, "Extreme Test - Turn off the internet!"),
        Pair({ executorService.execute {
          runBlocking {
            awaitAll(
              async { launchMain { stressTest() } },
              async { launchMain { stressTest() } },
              async { launchDefault { stressTest() } },
              async { launchDefault { stressTest() } },
              async { launchExecutor { stressTest() } },
              async { launchExecutor { stressTest() } },
              async { launchHandler { stressTest() } },
              async { launchHandler { stressTest() } },
              async { launchIO { stressTest() } },
              async { launchIO { stressTest() } },
              async { launchSingleThreaded { stressTest() } },
              async { launchSingleThreaded { stressTest() } },
              async { stressTest() })
          }
        } }, "I know what I'm doing"),
      )

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    val recycler = binding.recyclerView
    recycler.layoutManager = LinearLayoutManager(this)

    val adapter =
        RecyclerViewAdapter(configs) { position ->
          for (i in 0..1) {
            configs[position].first?.invoke()
          }
        }
    recycler.adapter = adapter
  }
}
