package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.memory.MemoryStore
import com.jarvis.assistant.memory.SettingsStore

class JarvisApplication : Application() {
    lateinit var memoryStore: MemoryStore
    lateinit var settingsStore: SettingsStore

    override fun onCreate() {
        super.onCreate()
        memoryStore = MemoryStore(this)
        settingsStore = SettingsStore(this)
    }
}
