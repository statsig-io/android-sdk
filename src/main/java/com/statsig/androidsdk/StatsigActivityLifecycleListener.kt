package com.statsig.androidsdk

import android.app.Activity
import android.app.Application
import android.os.Bundle

internal interface LifecycleEventListener {
    fun onAppFocus()
    fun onAppBlur()
}

internal class StatsigActivityLifecycleListener(
    private val application: Application,
    private val listener: LifecycleEventListener
) : Application.ActivityLifecycleCallbacks {

    private var currentActivity: Activity? = null
    private var resumed = 0
    private var paused = 0
    private var started = 0
    private var stopped = 0

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun shutdown() {
        application.unregisterActivityLifecycleCallbacks(this)
    }

    fun getCurrentActivity(): Activity? = currentActivity

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        currentActivity = activity
    }

    override fun onActivityStarted(activity: Activity) {
        ++started
        currentActivity = activity
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        ++resumed
        listener.onAppFocus()
    }

    override fun onActivityPaused(activity: Activity) {
        ++paused
        if (!this.isApplicationInForeground()) { // app is entering background
            listener.onAppBlur()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        ++stopped
        currentActivity = null
        if (!this.isApplicationVisible()) {
            listener.onAppBlur()
        }
    }

    private fun isApplicationVisible(): Boolean = started > stopped

    private fun isApplicationInForeground(): Boolean = resumed > paused

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
    }

    override fun onActivityDestroyed(activity: Activity) {
        currentActivity = null
    }
}
