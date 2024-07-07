package com.github.devapro.pttdroid.data

import android.content.SharedPreferences

class PrefManager(
    private val preferences: SharedPreferences
) {

    fun putLong(key: String, value: Long) {
        preferences.edit().putLong(key, value).apply()
    }

    fun getLong(key: String, defaultValue: Long): Long {
        return preferences.getLong(key, defaultValue)
    }

    fun putFloat(key: String, value: Float) {
        preferences.edit().putFloat(key, value).apply()
    }

    fun getFloat(key: String, defaultValue: Float): Float {
        return preferences.getFloat(key, defaultValue)
    }

    fun putInt(key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
    }

    fun getInt(key: String, defaultValue: Int): Int {
        return preferences.getInt(key, defaultValue)
    }

    fun putString(key: String, value: String?) {
        preferences.edit().putString(key, value).apply()
    }

    fun getString(key: String, defaultValue: String?): String? {
        return preferences.getString(key, defaultValue)
    }

    fun putBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return preferences.getBoolean(key, defaultValue)
    }

    fun putDouble(key: String, value: Double) {
        preferences.edit().putLong(key, java.lang.Double.doubleToRawLongBits(value)).apply()
    }

    fun getDouble(key: String, defaultValue: Double): Double {
        return java.lang.Double.longBitsToDouble(
            preferences.getLong(key, java.lang.Double.doubleToRawLongBits(defaultValue))
        )
    }

    fun removePreference(key: String) {
        preferences.edit().remove(key).apply()
    }

    fun containsKey(key: String) = preferences.contains(key)

    fun commit() {
        preferences.edit().commit()
    }

    fun putStringSet(key: String, set: Set<String>) {
        preferences.edit().putStringSet(key, set).apply()
    }

    fun getStringSet(key: String, defaultValue: Set<String>): Set<String> {
        return preferences.getStringSet(key, defaultValue) ?: emptySet()
    }
}