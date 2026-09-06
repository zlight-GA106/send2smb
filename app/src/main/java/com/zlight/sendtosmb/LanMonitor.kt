package com.zlight.sendtosmb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

class LanMonitor(context: Context, private val changed: (Boolean) -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    val available: Boolean get() = availableExcept(null)
    private fun availableExcept(lost: Network?): Boolean = manager.allNetworks.filterNot { it == lost }.any { network ->
        manager.getNetworkCapabilities(network)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } == true
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed(available)
        override fun onLost(network: Network) = changed(availableExcept(network))
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed(available)
    }
    fun start() {
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        manager.registerNetworkCallback(request, callback)
    }
    fun stop() { runCatching { manager.unregisterNetworkCallback(callback) } }
}
