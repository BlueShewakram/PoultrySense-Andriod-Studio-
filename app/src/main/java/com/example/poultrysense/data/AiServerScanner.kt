package com.example.poultrysense.data

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object AiServerScanner {

    private val fastHttpClient = OkHttpClient.Builder()
        .connectTimeout(600, TimeUnit.MILLISECONDS)
        .readTimeout(600, TimeUnit.MILLISECONDS)
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

        // ─── STAGE 1: UDP Broadcast Discovery (< 800ms) ───────────
        notifyProgress("Searching local Wi-Fi with instant beacon...")
        val udpResult = tryUdpDiscovery()
        if (udpResult != null && verifyServer(udpResult)) {
            return@withContext udpResult
        }

        // ─── STAGE 2: Default Gateway Check (Hotspot / PC Gateway) ─
        notifyProgress("Checking router / hotspot gateway...")
        val gatewayIp = getGatewayIp(context)
        if (gatewayIp != null && verifyServer(gatewayIp)) {
            return@withContext gatewayIp
        }

        // ─── STAGE 3: Saved IP Check (Fast Reconnect) ──────────────
        val savedIp = ServerPreferences.getIp(context)
        if (savedIp.isNotEmpty() && verifyServer(savedIp)) {
            return@withContext savedIp
        }

        // ─── STAGE 4: Concurrent Subnet Sweep ───────────────────────
        val localIp = getDeviceLocalIp()
        if (localIp != null && localIp.contains(".")) {
            val subnetPrefix = localIp.substringBeforeLast(".") + "."
            notifyProgress("Scanning subnet ${subnetPrefix}x...")
            val sweepResult = sweepSubnet(subnetPrefix)
            if (sweepResult != null) {
                return@withContext sweepResult
            }
        }

        null
    }

    /**
     * Broadcasts UDP beacon on port 5005.
     */
    private fun tryUdpDiscovery(): String? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 800

            val message = "POULTRYSENSE_DISCOVER"
            val buffer = message.toByteArray()
            val packet = DatagramPacket(
                buffer,
                buffer.size,
                InetAddress.getByName("255.255.255.255"),
                5005
            )
            socket.send(packet)

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
        }
    }

    /**
     * Checks if the IP is running PoultrySense AI by hitting /status or /api/discover.
     */
    fun verifyServer(ip: String): Boolean {
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
     * Retrieves the device's local IPv4 address on Wi-Fi.
     */
    private fun getDeviceLocalIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses ?: continue
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && !host.startsWith("127.")) {
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
     * Parallel async sweep across host numbers 1..254.
     */
    private suspend fun sweepSubnet(prefix: String): String? = coroutineScope {
        val foundIp = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val stopFlag = AtomicBoolean(false)

        val deferreds = (1..254).map { hostNum ->
            async(Dispatchers.IO) {
                if (stopFlag.get()) return@async null
                val candidateIp = "$prefix$hostNum"
                if (verifyServer(candidateIp)) {
                    stopFlag.set(true)
                    foundIp.set(candidateIp)
                    candidateIp
                } else {
                    null
                }
            }
        }

        deferreds.awaitAll()
        foundIp.get()
    }
}
