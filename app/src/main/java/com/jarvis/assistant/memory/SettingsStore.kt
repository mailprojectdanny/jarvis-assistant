package com.jarvis.assistant.memory

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** DeepSeek API key + user prefs. Key lives ONLY in Android Keystore-backed
 *  encrypted prefs — never hard-coded, never logged, deletable at any time. */
class SettingsStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "jarvis_settings_encrypted",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getDeepSeekKey(): String? = prefs.getString(KEY_DEEPSEEK, null)
    fun setDeepSeekKey(key: String) = prefs.edit().putString(KEY_DEEPSEEK, key).apply()
    fun deleteDeepSeekKey() = prefs.edit().remove(KEY_DEEPSEEK).apply()

    fun cloudEnabled(): Boolean = prefs.getBoolean(KEY_CLOUD_ENABLED, true)
    fun setCloudEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_CLOUD_ENABLED, enabled).apply()

    fun localOnlyMode(): Boolean = prefs.getBoolean(KEY_LOCAL_ONLY, false)
    fun setLocalOnlyMode(enabled: Boolean) = prefs.edit().putBoolean(KEY_LOCAL_ONLY, enabled).apply()

    fun sessionTimeoutMs(): Long = prefs.getLong(KEY_TIMEOUT, 20_000L)
    fun setSessionTimeoutMs(ms: Long) = prefs.edit().putLong(KEY_TIMEOUT, ms).apply()

    fun overlayEnabled(): Boolean = prefs.getBoolean(KEY_OVERLAY, false)
    fun setOverlayEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_OVERLAY, enabled).apply()

    fun setupComplete(): Boolean = prefs.getBoolean(KEY_SETUP_DONE, false)
    fun setSetupComplete(done: Boolean) = prefs.edit().putBoolean(KEY_SETUP_DONE, done).apply()

    companion object {
        private const val KEY_DEEPSEEK = "deepseek_api_key"
        private const val KEY_CLOUD_ENABLED = "cloud_enabled"
        private const val KEY_LOCAL_ONLY = "local_only_mode"
        private const val KEY_TIMEOUT = "session_timeout_ms"
        private const val KEY_OVERLAY = "overlay_enabled"
        private const val KEY_SETUP_DONE = "setup_complete"
    }
}
