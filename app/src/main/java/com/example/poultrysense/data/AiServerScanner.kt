package com.example.poultrysense.data

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object AiServerScanner {

    private val fastHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .dispatcher(Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 64
        })
        .build()

    /**
     * One-Tap Auto-Discovery for PoultrySense AI Hub.
     * Uses a multi-stage approach (UDP Broadcast -> Gateway Check -> Subnet Sweep)
     * so normal users never need to know or type an IP address.
     */
    suspend fun discoverServer(
        context: Context,
        onProgress: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {

        val notifyProgress: suspend (String) -> Unit = { msg ->
            if (onProgress != null) {
                withContext(Dispatchers.Main) {
                    try {
                        onProgress(msg)
                    } catch (_: Exception) {}
                }
            }
        }

        // ─── STAGE 1: Saved IP Check (Fast Reconnect < 200ms) ──────────
        val savedIp = ServerPreferences.getIp(context).trim()
        if (savedIp.isNotEmpty()) {
            notifyProgress("Checking previous connection ($savedIp)...")
            if (verifyServer(savedIp)) {
                return@withContext savedIp
            }
        }

        // ─── STAGE 2: UDP Broadcast Discovery with MulticastLock ───────
        notifyProgress("Searching local Wi-Fi with instant beacon...")
        val localIp = getDeviceLocalIp()
        val subnetPrefix = if (localIp != null && localIp.contains(".")) {
            localIp.substringBeforeLast(".") + "."
        } else {
            null
        }

        val udpResult = tryUdpDiscovery(context, subnetPrefix)
        if (udpResult != null && verifyServer(udpResult)) {
            return@withContext udpResult
        }

        // ─── STAGE 3: Default Gateway Check (Hotspot / PC Gateway) ─
        notifyProgress("Checking router / hotspot gateway...")
        val gatewayIp = getGatewayIp(context)
        if (gatewayIp != null && verifyServer(gatewayIp)) {
            return@withContext gatewayIp
        }

        // ─── STAGE 4: Fast Concurrent Subnet Sweep ───────────────────────
        if (subnetPrefix != null) {
            notifyProgress("Scanning subnet ${subnetPrefix}x for AI Hub...")
            val sweepResult = sweepSubnet(subnetPrefix)
            if (sweepResult != null) {
                return@withContext sweepResult
            }
        }

        null
    }

    /**
     * Broadcasts UDP beacon on port 5005 to 255.255.255.255 and directed subnet broadcast.
     */
    private fun tryUdpDiscovery(context: Context, subnetPrefix: String?): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = try {
            wifiManager?.createMulticastLock("poultrysense_udp_lock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {
            null
        }

        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 1200

            val message = "POULTRYSENSE_DISCOVER"
            val buffer = message.toByteArray()

            // 1. Send to global broadcast
            try {
                val packetAll = DatagramPacket(
                    buffer,
                    buffer.size,
                    InetAddress.getByName("255.255.255.255"),
                    5005
                )
                socket.send(packetAll)
            } catch (_: Exception) {}

            // 2. Also send to directed subnet broadcast (e.g. 192.168.0.255)
            if (subnetPrefix != null) {
                try {
                    val directedPacket = DatagramPacket(
                        buffer,
                        buffer.size,
                        InetAddress.getByName("${subnetPrefix}255"),
                        5005
                    )
                    socket.send(directedPacket)
                } catch (_: Exception) {}
            }

            // Receive reply
            val receiveBuffer = ByteArray(1024)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(receivePacket)

            val reply = String(receivePacket.data, 0, receivePacket.length)
            if (reply.contains("poultrysense")) {
                receivePacket.address.hostAddress
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            socket?.close()
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Checks if the IP is running PoultrySense AI by hitting /status or /api/discover.
     */
    fun verifyServer(ip: String): Boolean {
        // Quick TCP port check first to prevent slow HTTP timeouts
        if (!isPortOpen(ip, 5000, 600)) {
            return false
        }
        return try {
            val url = "http://$ip:5000/status"
            val request = Request.Builder().url(url).build()
            fastHttpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Ultra-fast TCP socket connect probe.
     */
    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Retrieves the Wi-Fi default gateway IP (common for Windows Hotspot: 192.168.137.1).
     */
    private fun getGatewayIp(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcpInfo = wifiManager?.dhcpInfo ?: return null
            val ip = dhcpInfo.gateway
            if (ip == 0) return null
            String.format(
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Retrieves the device's local IPv4 address on Wi-Fi (prioritizing wlan interfaces).
     */
    private fun getDeviceLocalIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            // Prioritize Wi-Fi and Ethernet interfaces over mobile data or virtual adapters
            val sorted = interfaces.sortedByDescending {
                val n = it.name.lowercase()
                n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("ap")
            }
            for (intf in sorted) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses ?: continue
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && !host.startsWith("127.") && !host.startsWith("169.254.")) {
                            return host
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parallel async sweep across host numbers 1..254 using fast TCP socket probes.
     */
    private suspend fun sweepSubnet(prefix: String): String? = coroutineScope {
        val foundIp = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val stopFlag = AtomicBoolean(false)

        val deferreds = (1..254).map { hostNum ->
            async(Dispatchers.IO) {
                if (stopFlag.get()) return@async null
                val candidateIp = "$prefix$hostNum"
                if (isPortOpen(candidateIp, 5000, 350)) {
                    if (verifyServer(candidateIp)) {
                        stopFlag.set(true)
                        foundIp.set(candidateIp)
                        candidateIp
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        }

        deferreds.awaitAll()
        foundIp.get()
    }
}
