package org.starfall.generative.shared.state.preferences

import android.content.Context
import kotlinx.coroutines.flow.Flow
import org.starfall.generative.shared.model.preferences.AppPreferences

/** Storage contracts supplied by the application; consumers do not construct DataStore adapters. */
interface ApplicationPreferencesOwner {
    val appPreferences: Flow<AppPreferences>
    fun stringPreferences(namespace: String): StringPreferences
}

fun Context.applicationPreferences(): ApplicationPreferencesOwner =
    applicationContext as? ApplicationPreferencesOwner
        ?: error("Application must provide ApplicationPreferencesOwner")

/** Atomic string records for existing stores with synchronous APIs and durable writes. */
interface StringPreferences {
    val all: Map<String, String>
    fun getString(key: String, default: String? = null): String?
    fun contains(key: String): Boolean
    fun update(change: MutableMap<String, String>.() -> Unit)
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun replaceAll(values: Map<String, String>)
}
