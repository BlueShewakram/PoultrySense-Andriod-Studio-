package com.example.poultrysense.data

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale

object WakeOnLanHelper {

    /**
     * Sends a Wake-on-LAN (WOL) magic packet over UDP to turn on a desktop computer.
     * @param macAddressRaw MAC address string (e.g., "00:11:22:33:44:55" or "001122334455")
     * @param broadcastIp Target subnet broadcast IP (defaults to universal "255.255.255.255")
     * @return true if packet was constructed and dispatched successfully
     */
    fun sendWakeOnLan(macAddressRaw: String, broadcastIp: String = "255.255.255.255"): Boolean {
        return try {
            val macBytes = parseMacAddress(macAddressRaw) ?: return false
            val magicPacket = ByteArray(6 + 16 * macBytes.size)

            // 6 bytes of 0xFF
            for (i in 0..5) {
                magicPacket[i] = 0xFF.toByte()
            }

            // Repeat target MAC address 16 times
            var offset = 6
            while (offset < magicPacket.size) {
                System.arraycopy(macBytes, 0, magicPacket, offset, macBytes.size)
                offset += macBytes.size
            }

            val socket = DatagramSocket()
            socket.broadcast = true

            // Send to port 9 (standard WOL port)
            val address = InetAddress.getByName(broadcastIp)
            val packetPort9 = DatagramPacket(magicPacket, magicPacket.size, address, 9)
            socket.send(packetPort9)

            // Also send to port 7 (discard port alternative) for extra reliability
            val packetPort7 = DatagramPacket(magicPacket, magicPacket.size, address, 7)
            socket.send(packetPort7)

            socket.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun parseMacAddress(macStr: String): ByteArray? {
        val hex = macStr.replace(":", "").replace("-", "").replace(".", "").trim()
        if (hex.length != 12) return null
        val bytes = ByteArray(6)
        return try {
            for (i in 0 until 6) {
                bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            bytes
        } catch (_: Exception) {
            null
        }
    }
}
