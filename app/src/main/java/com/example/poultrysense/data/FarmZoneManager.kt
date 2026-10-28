package com.example.poultrysense.data

import android.content.Context
import android.content.SharedPreferences
import com.example.poultrysense.data.models.BarnZone
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Singleton repository managing registered poultry barns, pen zones,
 * dynamic camera/sensor hardware assignments, active selection persistence,
 * and Firebase Realtime Database cloud synchronization.
 */
object FarmZoneManager {

    private const val PREFS_NAME = "poultrysense_farm_zone_prefs"
    private const val KEY_SELECTED_ZONE_ID = "key_selected_zone_id"
    private const val KEY_CUSTOM_ZONES_JSON = "key_custom_zones_json"
    private const val KEY_ASSIGNED_CAMERA_ZONE_ID = "key_assigned_camera_zone_id"
    private const val KEY_ASSIGNED_SENSOR_ZONE_ID = "key_assigned_sensor_zone_id"
    private const val KEY_DEFAULT_ZONE_DELETED = "key_default_zone_deleted"

    private const val DEFAULT_ZONE_ID = "zone_1"

    /** The farm map and dashboard support a maximum of three barns. */
    const val MAX_BARNS = 3

    /**
     * The starter zone is generated from the farmer's real farm name instead of
     * shipping demo barns ("Barn Alpha", "Barn Beta") in the UI.
     * It represents the single camera + sensor station that PoultrySense ships with.
     */
    private fun getDefaultZone(context: Context): BarnZone {
        val farmName = UserPreferences.getCachedUser(context)?.farmName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "My Farm"

        return BarnZone(
            id = DEFAULT_ZONE_ID,
            barnName = farmName,
            zoneName = "Zone 1 (Main Pen)",
            totalCapacity = 0,
            hasLiveHardware = true,
            hasCamera = true,
            hasSensor = true,
            cameraName = "Camera 1",
            sensorName = "ESP32 Sensor 1",
            cameraChannelId = 1,
            firebaseTelemetryPath = "sensor_data/live",
            description = "Primary monitoring station"
        )
    }

    private val listeners = CopyOnWriteArrayList<(BarnZone) -> Unit>()
    private val gson = Gson()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns the zone ID where the physical camera is assigned (defaults to the starter zone).
     */
    fun getAssignedCameraZoneId(context: Context): String {
        return migrateLegacyZoneId(
            getPrefs(context).getString(KEY_ASSIGNED_CAMERA_ZONE_ID, DEFAULT_ZONE_ID)
        )
    }

    /**
     * Returns the zone ID where the ESP32 sensor is assigned (defaults to the starter zone).
     */
    fun getAssignedSensorZoneId(context: Context): String {
        return migrateLegacyZoneId(
            getPrefs(context).getString(KEY_ASSIGNED_SENSOR_ZONE_ID, DEFAULT_ZONE_ID)
        )
    }

    /** Maps IDs from the old demo barns onto the real starter zone. */
    private fun migrateLegacyZoneId(raw: String?): String {
        return when (raw) {
            null, "", "barn_alpha_zone_1", "barn_alpha_zone_2", "barn_beta_zone_1" -> DEFAULT_ZONE_ID
            else -> raw
        }
    }

    /**
     * Reassigns Camera 1 to the specified barn zone.
     * Guaranteed: only this zone will stream; all other zones will be in standby.
     */
    fun assignCameraToZone(context: Context, zoneId: String) {
        getPrefs(context).edit().putString(KEY_ASSIGNED_CAMERA_ZONE_ID, zoneId).apply()
        val active = getActiveZone(context)
        notifyListeners(active)
        syncZonesToFirebase(context)
    }

    /**
     * Reassigns ESP32 #1 Sensor to the specified barn zone.
     */
    fun assignSensorToZone(context: Context, zoneId: String) {
        getPrefs(context).edit().putString(KEY_ASSIGNED_SENSOR_ZONE_ID, zoneId).apply()
        val active = getActiveZone(context)
        notifyListeners(active)
        syncZonesToFirebase(context)
    }

    /**
     * Retrieves all available barns and zones with dynamic camera and sensor hardware states.
     */
    fun getAllZones(context: Context): List<BarnZone> {
        val assignedCamId = getAssignedCameraZoneId(context)
        val assignedSensId = getAssignedSensorZoneId(context)

        val prefs = getPrefs(context)
        val allRaw = mutableListOf<BarnZone>()
        if (!prefs.getBoolean(KEY_DEFAULT_ZONE_DELETED, false)) {
            allRaw.add(getDefaultZone(context))
        }
        allRaw.addAll(getCustomZones(context))

        // Never leave the farm with zero barns.
        if (allRaw.isEmpty()) {
            prefs.edit().putBoolean(KEY_DEFAULT_ZONE_DELETED, false).apply()
            allRaw.add(getDefaultZone(context))
        }

        return allRaw.map { raw ->
            val hasCam = raw.id == assignedCamId
            val hasSens = raw.id == assignedSensId
            raw.copy(
                hasLiveHardware = hasCam || hasSens,
                hasCamera = hasCam,
                hasSensor = hasSens,
                cameraName = if (hasCam) "Camera 1" else "No Camera Assigned",
                sensorName = if (hasSens) "ESP32 Sensor" else "No Sensor Assigned",
                cameraChannelId = if (hasCam) 1 else 0
            )
        }
    }

    /**
     * Gets the currently active selected BarnZone. Defaults to the farm's starter zone.
     */
    fun getActiveZone(context: Context): BarnZone {
        val prefs = getPrefs(context)
        val selectedId = migrateLegacyZoneId(prefs.getString(KEY_SELECTED_ZONE_ID, DEFAULT_ZONE_ID))
        val all = getAllZones(context)
        return all.find { it.id == selectedId } ?: all.firstOrNull() ?: getDefaultZone(context)
    }

    /**
     * Sets the active BarnZone and broadcasts the change to all registered listeners.
     */
    fun setActiveZone(context: Context, zone: BarnZone) {
        getPrefs(context).edit()
            .putString(KEY_SELECTED_ZONE_ID, zone.id)
            .apply()

        notifyListeners(zone)
        syncZonesToFirebase(context)
    }

    /**
     * Adds a new custom barn/zone with assignable camera and sensor.
     */
    fun canAddBarn(context: Context): Boolean = getAllZones(context).size < MAX_BARNS

    fun addCustomZone(
        context: Context,
        barnName: String,
        zoneName: String,
        capacity: Int,
        hasCamera: Boolean = false,
        hasSensor: Boolean = false,
        description: String = "Custom Poultry Pen"
    ): BarnZone? {
        if (!canAddBarn(context)) return null
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
            cameraName = if (hasCamera) "Camera 1" else "No Camera Assigned",
            sensorName = if (hasSensor) "ESP32 Sensor" else "No Sensor Assigned",
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

        if (hasCamera) {
            assignCameraToZone(context, id)
        }
        if (hasSensor) {
            assignSensorToZone(context, id)
        }

        syncZonesToFirebase(context)
        return newZone
    }

    /**
     * Removes a barn by ID. The last remaining barn cannot be deleted.
     */
    fun deleteBarn(context: Context, zoneId: String): Boolean {
        val all = getAllZones(context)
        if (all.size <= 1) return false

        val prefs = getPrefs(context)
        if (zoneId == DEFAULT_ZONE_ID) {
            prefs.edit().putBoolean(KEY_DEFAULT_ZONE_DELETED, true).apply()
        } else {
            val currentCustom = getCustomZones(context).toMutableList()
            currentCustom.removeAll { it.id == zoneId }
            prefs.edit().putString(KEY_CUSTOM_ZONES_JSON, gson.toJson(currentCustom)).apply()
        }

        val remaining = getAllZones(context)
        if (remaining.isEmpty()) return false
        val fallback = remaining.first()

        if (prefs.getString(KEY_SELECTED_ZONE_ID, DEFAULT_ZONE_ID) == zoneId) {
            setActiveZone(context, fallback)
        }
        if (getAssignedCameraZoneId(context) == zoneId) {
            assignCameraToZone(context, fallback.id)
        }
        if (getAssignedSensorZoneId(context) == zoneId) {
            assignSensorToZone(context, fallback.id)
        }

        syncZonesToFirebase(context)
        return true
    }

    /** Kept for older callers. */
    fun deleteCustomZone(context: Context, zoneId: String) {
        deleteBarn(context, zoneId)
    }

    private fun getCustomZones(context: Context): List<BarnZone> {
        val prefs = getPrefs(context)
        val customJson = prefs.getString(KEY_CUSTOM_ZONES_JSON, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<BarnZone>>() {}.type
            gson.fromJson(customJson, type) ?: emptyList()
        } catch (_: Exception) {
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

    /**
     * Clean Cloud Sync to Firebase Realtime Database: /farms/zones
     */
    fun syncZonesToFirebase(context: Context) {
        try {
            val zones = getAllZones(context)
            val uid = UserPreferences.getCachedUser(context)?.uid
            val dbRef = if (uid.isNullOrBlank()) {
                FirebaseConfig.database().getReference("farms").child("zones")
            } else {
                FirebaseConfig.database().getReference("users").child(uid).child("farms").child("zones")
            }
            dbRef.setValue(zones)
        } catch (_: Exception) {
            // Safe fallback when offline or Firebase credentials not active
        }
    }
}
