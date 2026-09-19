package com.example.poultrysense.data

import android.content.Context

object  ServerPreferences {

    private const val PREFS_NAME = "poultrysense_server_prefs"
    private const val KEY_SERVER_IP = "server_ip"
    private const val DEFAULT_IP = "192.168.137.1"
    private const val PORT = "5000"

    /** Returns the full base URL, e.g. "http://192.168.1.50:5000" */
    fun getBaseUrl(context: Context): String {
        val ip = getIp(context)
        return "http://$ip:$PORT"
    }

    /** Returns just the saved IP address string */
    fun getIp(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_IP, DEFAULT_IP) ?: DEFAULT_IP
    }

    /** Saves an IP address string (automatically strips http://, ports, and trailing slashes) */
    fun saveIp(context: Context, ip: String) {
        val clean = ip.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore(":")
            .trimEnd('/')
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER_IP, clean).apply()
    }
}
