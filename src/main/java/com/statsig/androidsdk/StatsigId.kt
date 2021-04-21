package com.statsig.androidsdk

import android.content.SharedPreferences
import java.util.*

class StatsigId {
    companion object {
        private const val STABLE_ID_KEY: String = "STABLE_ID"

        private var sessionID: String? = null;
        private var stableID: String? = null;

        fun getSessionID(): String {
            if (this.sessionID == null) {
                this.sessionID = UUID.randomUUID().toString()
            }

            return this.sessionID!!
        }

        fun getNewSessionID(): String {
            this.sessionID = UUID.randomUUID().toString()
            return this.sessionID!!
        }

        fun getNewStableID(prefs: SharedPreferences?): String {
            this.stableID = UUID.randomUUID().toString()
            if (prefs != null) {
                prefs.edit().putString(STABLE_ID_KEY, this.stableID).commit()
            }
            return this.stableID!!
        }

        fun getStableID(prefs: SharedPreferences?): String {
            if (this.stableID == null && prefs != null && prefs.contains(STABLE_ID_KEY)) {
                this.stableID = prefs.getString(STABLE_ID_KEY, null)
            }
            return this.getNewStableID(prefs)
        }
    }

}