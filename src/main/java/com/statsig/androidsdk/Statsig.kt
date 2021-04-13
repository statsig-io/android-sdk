package com.statsig.androidsdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.security.MessageDigest


private val completableJob = Job()
private val coroutineScope = CoroutineScope(Dispatchers.Main + completableJob)

@FunctionalInterface
interface StatsigCallback {
    fun onStatsigReady()
}


class Statsig {

    companion object {

        private const val INITIALIZE_RESPONSE_KEY : String = "INITIALIZE_RESPONSE"

        private var state : StatsigState? = null
        private var user : StatsigUser? = null

        private var callback : StatsigCallback? = null
        private lateinit var application : Application
        private lateinit var sdkKey : String
        private lateinit var options : StatsigOptions

        private lateinit var logger : StatsigLogger
        private lateinit var statsigMetadata: StatsigMetadata
        private lateinit var sharedPrefs: SharedPreferences

        @JvmOverloads
        @JvmStatic fun initialize(application: Application, sdkKey: String, callback: StatsigCallback, user: StatsigUser? = null, options: StatsigOptions? = null) {
            if (!sdkKey.startsWith("client-") && !sdkKey.startsWith("test-")) {
                throw Exception("Invalid SDK Key provided.  You must provide a client SDK Key from the API Key page of your Statsig console")
            }
            this.application = application
            this.sdkKey = sdkKey
            this.user = user
            this.callback = callback
            if (options == null) {
                this.options = StatsigOptions()
            } else {
                this.options = options
            }
            this.sharedPrefs = application.getSharedPreferences("STATSIG", Context.MODE_PRIVATE);

            this.statsigMetadata = StatsigMetadata()
            this.statsigMetadata.stableID = StatsigId.getStableID(this.sharedPrefs)
            val stringID : Int = application.applicationInfo.labelRes;
            this.statsigMetadata.appIdentifier = if(stringID == 0) application.applicationInfo.nonLocalizedLabel.toString() else application.getString(stringID)
            try {
                val pInfo: PackageInfo =
                    application.packageManager.getPackageInfo(application.packageName, 0)
                this.statsigMetadata.appVersion = pInfo.versionName
            } catch (e: PackageManager.NameNotFoundException) {}

            this.application.registerActivityLifecycleCallbacks(StatsigActivityLifecycleListener())
            this.logger = StatsigLogger(sdkKey, this.options.api, this.statsigMetadata)

            loadFromCache()

            var body = mapOf("user" to user, "statsigMetadata" to this.statsigMetadata)
            apiPost(this.options.api, "initialize", sdkKey, Gson().toJson(body), ::setState)
        }

        @JvmStatic fun checkGate(gateName: String): Boolean {
            if (this.state == null) {
                return false
            }

            val gateValue = this.state!!.checkGate(getHashedString(gateName))
            this.logger.logGateExposure(gateName, gateValue, this.user)
            return gateValue
        }

        @JvmStatic fun getConfig(configName: String): DynamicConfig? {
            if (this.state == null) {
                return null
            }
            val config = this.state!!.getConfig(getHashedString(configName))
            if (config != null) {
                this.logger.logConfigExposure(configName, config.getGroup(), this.user)
            }
            return config
        }

        @JvmOverloads
        @JvmStatic fun logEvent(eventName: String, value: Double? = null, metadata: Map<String, String>? = null) {
            var event = LogEvent(eventName)
            event.value = value
            event.metadata = metadata
            event.user = this.user
            logger.log(event)
        }

        @JvmStatic fun logEvent(eventName: String, value: String, metadata: Map<String, String>? = null) {
            var event = LogEvent(eventName)
            event.value = value
            event.metadata = metadata
            event.user = this.user
            logger.log(event)
        }

        @JvmStatic fun logEvent(eventName: String, metadata: Map<String, String>) {
            var event = LogEvent(eventName)
            event.value = null
            event.metadata = metadata
            event.user = this.user
            logger.log(event)
        }

        @JvmStatic fun updateUser(user: StatsigUser?, callback: StatsigCallback) {
            this.logger.flush()
            clearCache()
            this.user = user
            this.callback = callback
            this.statsigMetadata.stableID = StatsigId.getNewStableID(this.sharedPrefs)
            this.statsigMetadata.sessionID = StatsigId.getNewSessionID()

            var body = mapOf("user" to user, "statsigMetadata" to this.statsigMetadata)
            apiPost(options.api, "initialize", sdkKey, Gson().toJson(body), ::setState)
        }

        @JvmStatic fun shutdown() {
            this.logger.flush()
        }

        private fun loadFromCache() {
            val cachedResponse = this.sharedPrefs.getString(INITIALIZE_RESPONSE_KEY, null) ?: return
            val json = Gson().fromJson(cachedResponse, InitializeResponse::class.java)
            this.state = StatsigState(json)
        }

        private fun saveToCache(initializeData: InitializeResponse) {
            val json = Gson().toJson(initializeData)
            this.sharedPrefs.edit().putString(INITIALIZE_RESPONSE_KEY, json).commit()
        }

        private fun clearCache() {
            this.sharedPrefs.edit().remove(INITIALIZE_RESPONSE_KEY)
        }

        private fun setState(result: InitializeResponse?) {
            if (result != null) {
                state = StatsigState(result)
                saveToCache(result)
            }
            val cb = this.callback
            this.callback = null
            if (cb != null) {
                coroutineScope.launch(Dispatchers.Main) {
                    cb.onStatsigReady()
                }
            }
        }

        private fun getHashedString(gateName: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val input = gateName.toByteArray()
            val bytes = md.digest(input)
            return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }

        private class StatsigActivityLifecycleListener : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            }

            override fun onActivityStarted(activity: Activity) {
            }

            override fun onActivityResumed(activity: Activity) {
            }

            override fun onActivityPaused(activity: Activity) {
            }

            override fun onActivityStopped(activity: Activity) {
                shutdown()
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
            }

            override fun onActivityDestroyed(activity: Activity) {
            }

        }
    }

}