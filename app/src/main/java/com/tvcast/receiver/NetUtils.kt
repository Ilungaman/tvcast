package com.tvcast.receiver

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {

    /** IPv4-адрес телевизора в локальной сети (Wi-Fi или Ethernet). */
    fun localIp(): String? {
        val candidates = mutableListOf<Pair<Int, String>>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                val priority = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    name.startsWith("en") -> 1
                    name.startsWith("rmnet") -> 8
                    name.startsWith("tun") || name.startsWith("ppp") -> 9
                    else -> 5
                }
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        candidates += priority to addr.hostAddress.orEmpty()
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return candidates.sortedBy { it.first }.firstOrNull()?.second?.takeIf { it.isNotBlank() }
    }
}
