package me.phie.tawc.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Where local-network mode listens: the device's private IPv4 addresses
 * (Wi-Fi, Ethernet, its own hotspot, a VPN's private range), never a
 * cellular interface. A carrier can hand out private (10.x) addresses
 * too, so cellular is excluded by interface, not by address.
 */
object LocalNetwork {
    const val PORT = 2222

    fun addresses(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val cellular = try {
            cm?.allNetworks.orEmpty().mapNotNull { n ->
                val caps = cm?.getNetworkCapabilities(n) ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cm.getLinkProperties(n)?.interfaceName else null
            }.toSet()
        } catch (_: SecurityException) {
            emptySet() // the name prefixes below still apply
        }
        val ifaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        return ifaces
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            // Names too: the cellular list misses a network that's down
            // right now, and clat (v4-rmnet*) stacks on the cellular one.
            .filter { it.name !in cellular && CELLULAR_PREFIXES.none { p -> it.name.startsWith(p) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { "${it.hostAddress}:$PORT" }
            .distinct()
    }

    private val CELLULAR_PREFIXES = listOf("rmnet", "v4-rmnet", "ccmni", "v4-ccmni", "seth", "pdp")
}
