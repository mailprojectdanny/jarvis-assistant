package com.jarvis.assistant.memory

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class MemoryFact(val key: String, val value: String, val createdAtEpochMs: Long)

@Serializable
data class RoutineCommand(val trigger: String, val actions: List<String>)

/**
 * Everything here is stored locally, encrypted via Android Keystore
 * (AES256-GCM through Jetpack Security). Nothing in this class ever
 * touches the network — that boundary lives entirely in DeepSeekClient,
 * which only ever receives the current utterance/short context, never
 * this store.
 */
class MemoryStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "jarvis_memory_encrypted",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun remember(key: String, value: String) {
        val fact = MemoryFact(key, value, System.currentTimeMillis())
        prefs.edit().putString("fact:${key.lowercase()}", json.encodeToString(fact)).apply()
    }

    fun recall(key: String): String? {
        val raw = prefs.getString("fact:${key.lowercase()}", null) ?: return null
        return runCatching { json.decodeFromString<MemoryFact>(raw).value }.getOrNull()
    }

    fun forget(key: String) {
        prefs.edit().remove("fact:${key.lowercase()}").apply()
    }

    fun allFacts(): List<MemoryFact> =
        prefs.all.entries
            .filter { it.key.startsWith("fact:") }
            .mapNotNull { (_, v) -> runCatching { json.decodeFromString<MemoryFact>(v as String) }.getOrNull() }

    fun saveRoutine(routine: RoutineCommand) {
        prefs.edit().putString("routine:${routine.trigger.lowercase()}", json.encodeToString(routine)).apply()
    }

    fun getRoutine(trigger: String): RoutineCommand? {
        val raw = prefs.getString("routine:${trigger.lowercase()}", null) ?: return null
        return runCatching { json.decodeFromString<RoutineCommand>(raw) }.getOrNull()
    }

    /** Full wipe — exposed in the Privacy dashboard's "delete everything" control. */
    fun deleteAll() {
        prefs.edit().clear().apply()
    }
}
