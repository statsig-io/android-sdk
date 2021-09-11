package com.statsig.androidsdk

import android.content.SharedPreferences

class TestSharedPreferences : SharedPreferences {

    class TestEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor { return this }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { return this }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor { return this }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor { return this }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { return this }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { return this }

        override fun remove(key: String?): SharedPreferences.Editor { return  this }

        override fun clear(): SharedPreferences.Editor { return this }

        override fun commit(): Boolean { return false }

        override fun apply() {}
    }

    override fun getAll(): MutableMap<String, *> { return hashMapOf<String, Any?>() }

    override fun getString(key: String?, defValue: String?): String? { return null }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String> { return mutableSetOf() }

    override fun getInt(key: String?, defValue: Int): Int { return 0 }

    override fun getLong(key: String?, defValue: Long): Long { return 0L }

    override fun getFloat(key: String?, defValue: Float): Float { return 0F }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean { return false }

    override fun contains(key: String?): Boolean { return false }

    override fun edit(): SharedPreferences.Editor { return TestEditor() }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}
