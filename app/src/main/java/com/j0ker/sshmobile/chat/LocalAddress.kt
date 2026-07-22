package com.j0ker.sshmobile.chat

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The device's LAN address, so the user can tell a peer where to connect. The
 * desktop client never needed this — on Windows you look it up elsewhere — but
 * a phone gives no easy way to find its own address.
 *
 * Wi-Fi is preferred over cellular and tunnels: a peer on the same network can
 * only reach the wlan address.
 */
fun localIpv4Address(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        ?.hostAddress
}.getOrNull()
