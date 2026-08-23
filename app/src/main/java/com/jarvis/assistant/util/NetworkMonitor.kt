package com.jarvis.assistant.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Cheap synchronous connectivity check used to decide, before ever calling DeepSeek,
 *  whether cloud reasoning is even reachable — core of the offline-first fallback. */
class NetworkMonitor(private val context: Context) {
    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
