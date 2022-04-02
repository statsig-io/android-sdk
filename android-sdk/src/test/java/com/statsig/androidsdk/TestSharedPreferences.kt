package com.statsig.androidsdk

import android.content.SharedPreferences

class TestSharedPreferences : SharedPreferences {
    val editor = TestEditor()
    class TestEditor : SharedPreferences.Editor {
        var values: MutableMap<String, String> = mutableMapOf()
        private var tempValues: MutableMap<String, String> = mutableMapOf()
        private var removedValues: MutableSet<String> = mutableSetOf()
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null && value != null) {
                tempValues[key] = value
            }
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { return this }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor { return this }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor { return this }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { return this }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { return this }

        override fun remove(key: String?): SharedPreferences.Editor {
            tempValues.remove(key)
            if (key != null) {
                removedValues.add(key)
            }
            return this
        }

        override fun clear(): SharedPreferences.Editor { return this }

        override fun commit(): Boolean {
            tempValues.forEach { (k, v) -> values[k] = v }
            tempValues = mutableMapOf()

            removedValues.forEach { k -> values.remove(k) }
            removedValues = mutableSetOf()
            return true
        }

        override fun apply() {
            this.commit()
            System.out.println("TestSharedPreferences values: " + values)
        }

        fun getString(key: String): String? {
            return values[key]
        }
    }

    override fun getAll(): MutableMap<String, *> { return hashMapOf<String, Any?>() }

    override fun getString(key: String?, defValue: String?): String? {
        if (key != null) {
            return edit().getString(key)
        }
        return null
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String> { return mutableSetOf() }

    override fun getInt(key: String?, defValue: Int): Int { return 0 }

    override fun getLong(key: String?, defValue: Long): Long { return 0L }

    override fun getFloat(key: String?, defValue: Float): Float { return 0F }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean { return false }

    override fun contains(key: String?): Boolean { return false }

    override fun edit(): TestEditor { return editor }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}
