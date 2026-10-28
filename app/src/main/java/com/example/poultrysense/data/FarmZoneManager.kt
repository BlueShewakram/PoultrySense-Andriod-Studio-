package com.example.poultrysense.data

import android.content.Context
import android.content.SharedPreferences
import com.example.poultrysense.data.models.BarnZone
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Singleton repository managing registered poultry barns, pen zones,
 * camera/sensor hardware assignments, active selection persistence,
 * and reactive zone change broadcasts.
 */
object FarmZoneManager {

    private const val PREFS_NAME = "poultrysense_farm_zone_prefs"
    private const val KEY_SELECTED_ZONE_ID = "key_selected_zone_id"
    private const val KEY_CUSTOM_ZONES_JSON = "key_custom_zones_json"

    // Default registered barns & pens
    val DEFAULT_ZONES = listOf(
        BarnZone(
            id = "barn_alpha_zone_1",
            barnName = "Barn Alpha",
            zoneName = "Zone 1 (Main Pen)",
            totalCapacity = 50,
            hasLiveHardware = true,
            hasCamera = true,
            hasSensor = true,
            cameraName = "Cam 1 (Live AI Vision)",
            sensorName = "ESP32 #1 (DHT22 Live)",
            cameraChannelId = 1,
            firebaseTelemetryPath = "sensor_data/live",
            description = "Active physical IoT station (ESP32 DHT22 + OpenVINO AI Cam)"
        ),
        BarnZone(
            id = "barn_alpha_zone_2",
            barnName = "Barn Alpha",
            zoneName = "Zone 2 (Brooder)",
            totalCapacity = 25,
            hasLiveHardware = false,
            hasCamera = false,
            hasSensor = false,
            cameraName = "Unassigned",
            sensorName = "Unassigned",
            cameraChannelId = 0,
            firebaseTelemetryPath = "sensor_data/zones/barn_alpha_2",
            description = "Warming lamp pen • Standby station (No hardware paired)"
        ),
        BarnZone(
            id = "barn_beta_zone_1",
            barnName = "Barn Beta",
            zoneName = "Zone 1 (Layer Paddock)",
            totalCapacity = 100,
            hasLiveHardware = false,
            hasCamera = false,
            hasSensor = false,
            cameraName = "Unassigned",
            sensorName = "Unassigned",
            cameraChannelId = 0,
            firebaseTelemetryPath = "sensor_data/zones/barn_beta_1",
            description = "Layer pen • Standby station (No hardware paired)"
        )
    )

    private val listeners = CopyOnWriteArrayList<(BarnZone) -> Unit>()
    private val gson = Gson()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Retrieves all available barns and zones (defaults + user-created).
     */
    fun getAllZones(context: Context): List<BarnZone> {
        val prefs = getPrefs(context)
        val customJson = prefs.getString(KEY_CUSTOM_ZONES_JSON, null)
        val customZones: List<BarnZone> = if (!customJson.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<BarnZone>>() {}.type
                gson.fromJson(customJson, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        return DEFAULT_ZONES + customZones
    }

    /**
     * Gets the currently active selected BarnZone. Defaults to Barn Alpha Zone 1.
     */
    fun getActiveZone(context: Context): BarnZone {
        val prefs = getPrefs(context)
        val selectedId = prefs.getString(KEY_SELECTED_ZONE_ID, DEFAULT_ZONES.first().id)
        val all = getAllZones(context)
        return all.find { it.id == selectedId } ?: DEFAULT_ZONES.first()
    }

    /**
     * Sets the active BarnZone and broadcasts the change to all registered listeners.
     */
    fun setActiveZone(context: Context, zone: BarnZone) {
        getPrefs(context).edit()
            .putString(KEY_SELECTED_ZONE_ID, zone.id)
            .apply()

        notifyListeners(zone)
    }

    /**
     * Adds a new custom barn/zone with assignable camera and sensor.
     */
    fun addCustomZone(
        context: Context,
        barnName: String,
        zoneName: String,
        capacity: Int,
        hasCamera: Boolean = false,
        hasSensor: Boolean = false,
        description: String = "Custom Poultry Pen"
    ): BarnZone {
        val cleanBarn = barnName.trim().ifBlank { "New Barn" }
        val cleanZone = zoneName.trim().ifBlank { "Zone 1" }
        val id = "custom_${System.currentTimeMillis()}"

        val newZone = BarnZone(
            id = id,
            barnName = cleanBarn,
            zoneName = cleanZone,
            totalCapacity = if (capacity > 0) capacity else 50,
            hasLiveHardware = hasCamera || hasSensor,
            hasCamera = hasCamera,
            hasSensor = hasSensor,
            cameraName = if (hasCamera) "Cam 1 (Live AI Vision)" else "No Camera Assigned",
            sensorName = if (hasSensor) "ESP32 #1 (DHT22 Live)" else "No Sensor Assigned",
            cameraChannelId = if (hasCamera) 1 else 0,
            firebaseTelemetryPath = "sensor_data/zones/$id",
            description = description,
            isCustom = true
        )

        val prefs = getPrefs(context)
        val currentCustom = getCustomZones(context).toMutableList()
        currentCustom.add(newZone)

        prefs.edit()
            .putString(KEY_CUSTOM_ZONES_JSON, gson.toJson(currentCustom))
            .apply()

        return newZone
    }

    /**
     * Removes a custom zone by ID.
     */
    fun deleteCustomZone(context: Context, zoneId: String) {
        val prefs = getPrefs(context)
        val currentCustom = getCustomZones(context).toMutableList()
        currentCustom.removeAll { it.id == zoneId }

        prefs.edit()
            .putString(KEY_CUSTOM_ZONES_JSON, gson.toJson(currentCustom))
            .apply()

        // If active zone was deleted, reset to default
        if (getActiveZone(context).id == zoneId) {
            setActiveZone(context, DEFAULT_ZONES.first())
        }
    }

    private fun getCustomZones(context: Context): List<BarnZone> {
        val prefs = getPrefs(context)
        val customJson = prefs.getString(KEY_CUSTOM_ZONES_JSON, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<BarnZone>>() {}.type
            gson.fromJson(customJson, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addListener(listener: (BarnZone) -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: (BarnZone) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(zone: BarnZone) {
        for (listener in listeners) {
            try {
                listener.invoke(zone)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
