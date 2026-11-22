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
    private const val KEY_ZONE_CAMERA_PREFIX = "key_zone_camera_channel_"

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
     * Returns the assigned camera channel for a specific barn zone (1, 2, 3, or 0 if unassigned).
     */
    fun getAssignedCameraChannel(context: Context, zoneId: String): Int {
        val prefs = getPrefs(context)
        val key = KEY_ZONE_CAMERA_PREFIX + zoneId
        if (prefs.contains(key)) {
            return prefs.getInt(key, 0)
        }
        // Legacy fallback: if this was the zone assigned in KEY_ASSIGNED_CAMERA_ZONE_ID
        val legacyId = getAssignedCameraZoneId(context)
        if (legacyId == zoneId) {
            return 1
        }
        // If starter zone and not deleted, default to Camera 1
        if (zoneId == DEFAULT_ZONE_ID && !prefs.getBoolean(KEY_DEFAULT_ZONE_DELETED, false)) {
            return 1
        }
        return 0
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
     * Reassigns a specific Camera channel (1, 2, or 3) to the specified barn zone, or 0 to unassign.
     * Prevents duplicate camera channel assignments across barns.
     */
    fun assignCameraToZone(context: Context, zoneId: String, channelId: Int) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()

        if (channelId in 1..MAX_BARNS) {
            // Unassign this camera channel from any other barn to avoid hardware conflict
            val all = getAllZones(context)
            for (z in all) {
                if (z.id != zoneId && getAssignedCameraChannel(context, z.id) == channelId) {
                    editor.putInt(KEY_ZONE_CAMERA_PREFIX + z.id, 0)
                }
            }
            editor.putInt(KEY_ZONE_CAMERA_PREFIX + zoneId, channelId)
            if (channelId == 1) {
                editor.putString(KEY_ASSIGNED_CAMERA_ZONE_ID, zoneId)
            }
        } else {
            // Unassign camera
            editor.putInt(KEY_ZONE_CAMERA_PREFIX + zoneId, 0)
            if (prefs.getString(KEY_ASSIGNED_CAMERA_ZONE_ID, "") == zoneId) {
                editor.remove(KEY_ASSIGNED_CAMERA_ZONE_ID)
            }
        }
        editor.apply()

        val active = getActiveZone(context)
        notifyListeners(active)
        syncZonesToFirebase(context)

        // If this zone is currently being monitored, notify Firebase of active camera device index
        if (active.id == zoneId) {
            syncActiveCameraToCloud(if (channelId > 0) channelId - 1 else -1)
        }
    }

    /**
     * Backwards-compatible overload: assigns Camera 1.
     */
    fun assignCameraToZone(context: Context, zoneId: String) {
        assignCameraToZone(context, zoneId, 1)
    }

    /**
     * Unassigns camera from a specified barn zone.
     */
    fun unassignCameraFromZone(context: Context, zoneId: String) {
        assignCameraToZone(context, zoneId, 0)
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
     * Supports up to 3 barns, each with its own assigned camera (Camera 1, 2, or 3).
     */
    fun getAllZones(context: Context): List<BarnZone> {
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
            val camChannel = getAssignedCameraChannel(context, raw.id)
            val hasCam = camChannel > 0
            val hasSens = raw.id == assignedSensId
            val camName = if (hasCam) "Camera $camChannel" else "No Camera Assigned"

            raw.copy(
                hasLiveHardware = hasCam || hasSens,
                hasCamera = hasCam,
                hasSensor = hasSens,
                cameraName = camName,
                sensorName = if (hasSens) "ESP32 Sensor" else "No Sensor Assigned",
                cameraChannelId = camChannel
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

        val updatedZone = getActiveZone(context)
        notifyListeners(updatedZone)
        syncZonesToFirebase(context)

        val camIndex = if (updatedZone.hasCamera && updatedZone.cameraChannelId > 0) {
            updatedZone.cameraChannelId - 1
        } else {
            -1
        }
        syncActiveCameraToCloud(camIndex)
    }

    /**
     * Syncs active monitoring camera device index to Firebase Realtime Database.
     */
    fun syncActiveCameraToCloud(cameraIndex: Int) {
        try {
            if (cameraIndex >= 0) {
                FirebaseConfig.database().getReference("system_control")
                    .child("active_camera")
                    .setValue(cameraIndex)
            }
        } catch (_: Exception) {
        }
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
        cameraChannelId: Int = 0,
        description: String = "Custom Poultry Pen"
    ): BarnZone? {
        if (!canAddBarn(context)) return null
        val cleanBarn = barnName.trim().ifBlank { "New Barn" }
        val cleanZone = zoneName.trim().ifBlank { "Zone 1" }
        val id = "custom_${System.currentTimeMillis()}"

        // Determine effective camera channel
        var targetCamChannel = cameraChannelId
        if (hasCamera && targetCamChannel <= 0) {
            val currentZones = getAllZones(context)
            val assignedChannels = currentZones.map { it.cameraChannelId }.toSet()
            targetCamChannel = (1..MAX_BARNS).firstOrNull { it !in assignedChannels } ?: 1
        }

        val hasActualCam = targetCamChannel > 0
        val newZone = BarnZone(
            id = id,
            barnName = cleanBarn,
            zoneName = cleanZone,
            totalCapacity = if (capacity > 0) capacity else 50,
            hasLiveHardware = hasActualCam || hasSensor,
            hasCamera = hasActualCam,
            hasSensor = hasSensor,
            cameraName = if (hasActualCam) "Camera $targetCamChannel" else "No Camera Assigned",
            sensorName = if (hasSensor) "ESP32 Sensor" else "No Sensor Assigned",
            cameraChannelId = targetCamChannel,
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

        if (hasActualCam) {
            assignCameraToZone(context, id, targetCamChannel)
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

        prefs.edit().remove(KEY_ZONE_CAMERA_PREFIX + zoneId).apply()
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
