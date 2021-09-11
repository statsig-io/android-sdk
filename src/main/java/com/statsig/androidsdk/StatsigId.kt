package com.statsig.androidsdk

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.UUID

internal object StatsigId {
    private const val STABLE_ID_KEY: String = "STABLE_ID"

    private lateinit var sessionID: String
    private lateinit var stableID: String

    fun getSessionID(): String {
        if (!this::sessionID.isInitialized) {
            sessionID = UUID.randomUUID().toString()
        }

        return sessionID
    }

    fun getNewSessionID(): String {
        sessionID = UUID.randomUUID().toString()
        return sessionID
    }

    fun getNewStableID(prefs: SharedPreferences): String {
        stableID = UUID.randomUUID().toString()
        prefs.edit { putString(STABLE_ID_KEY, stableID) }
        return stableID
    }

    fun getStableID(prefs: SharedPreferences): String {
        if (!this::stableID.isInitialized && prefs.contains(STABLE_ID_KEY)) {
            stableID = prefs.getString(STABLE_ID_KEY, null) ?: return getNewStableID(prefs)
        }
        return getNewStableID(prefs)
    }
}
