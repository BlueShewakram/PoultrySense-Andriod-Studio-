package com.example.poultrysense.data.repository

import android.content.Context
import android.util.Base64
import com.example.poultrysense.data.UserPreferences
import com.example.poultrysense.data.models.User
import com.google.firebase.auth.FirebaseUser
import java.io.File

/**
 * Owns the profile lifecycle across three places:
 *  1. Firebase Realtime Database /users/{uid}  (source of truth)
 *  2. SharedPreferences cache                  (instant, offline display)
 *  3. app-private profile_avatar.jpg           (fast Glide loading)
 *
 * The database copy is what makes the profile survive an uninstall/install.
 */
class ProfileRepository(private val context: Context) {

    private val authRepository = AuthRepository()

    /**
     * Last non-"profile missing" cloud error. Used by the sign-in screen to tell
     * the farmer when the database rules (not the app) are blocking sync.
     */
    var lastSyncError: Throwable? = null
        private set

    fun cachedUser(): User? = UserPreferences.getCachedUser(context)

    fun localAvatarFile(): File = File(context.filesDir, AVATAR_FILE_NAME)

    /**
     * Loads the freshest profile we can obtain.
     *
     * If the database has a document it wins. If it has none (new account or a
     * previously failed sync) the local cache / Firebase Auth data is used and
     * immediately written to the database so the next device can restore it.
     */
    suspend fun loadProfile(firebaseUser: FirebaseUser): User {
        val cached = cachedUser()?.takeIf { it.uid == firebaseUser.uid }
        val cloudResult = authRepository.getUserProfile(firebaseUser.uid)
        val cloud = cloudResult.getOrNull()
        lastSyncError = cloudResult.exceptionOrNull()
            ?.takeUnless { it is ProfileNotFoundException }
        // A timeout / permission error is NOT the same as "no profile yet".
        // We must never overwrite an existing cloud profile just because a read failed.
        val profileMissing = cloudResult.exceptionOrNull() is ProfileNotFoundException
        val pendingLocalEdit = UserPreferences.isPendingSync(context)

        // Prefer the local copy when it is newer (or when both are legacy rows
        // without timestamps). This recovers edits made while the database rules
        // were expired, without losing a genuinely newer cloud profile.
        val cachedIsNewer = cached != null && cloud != null &&
            (cached.updatedAt > cloud.updatedAt ||
                (cached.updatedAt == 0L && cloud.updatedAt == 0L))

        var resolved = when {
            // A failed local write means these values are the farmer's latest edit.
            cached != null && pendingLocalEdit -> mergeProfiles(cached, cloud)
            cachedIsNewer -> mergeProfiles(cached!!, cloud)
            cloud != null -> mergeProfiles(cloud, cached)
            cached != null -> cached
            else -> authRepository.userFromFirebase(firebaseUser)
        }

        // Recover an avatar that only ever made it to local storage for THIS user.
        var recoveredLocalAvatar = false
        if (resolved.avatarUrl.isBlank() && cached?.uid == firebaseUser.uid) {
            localAvatarBase64()?.let {
                resolved = resolved.copy(avatarUrl = it)
                recoveredLocalAvatar = true
            }
        }

        // Push when the cloud document is missing, when a local edit is pending,
        // or when local-only data (e.g. an avatar) was just recovered.
        val mergedNewData = cloud != null && resolved != cloud
        if (profileMissing || pendingLocalEdit || recoveredLocalAvatar || mergedNewData) {
            val saved = saveProfile(firebaseUser.uid, resolved)
            if (saved.isSuccess) resolved = saved.getOrThrow()
        }

        cacheAndHydrate(resolved)
        return resolved
    }

    /** Fills blank cloud fields from the local cache; never discards non-blank values. */
    private fun mergeProfiles(primary: User, fallback: User?): User {
        if (fallback == null) return primary
        return primary.copy(
            firstName = primary.firstName.ifBlank { fallback.firstName },
            lastName = primary.lastName.ifBlank { fallback.lastName },
            farmName = when {
                primary.farmName.isBlank() -> fallback.farmName
                // "Poultry Farm" was the old hardcoded sign-up default.
                primary.farmName == "Poultry Farm" && fallback.farmName.isNotBlank() -> fallback.farmName
                else -> primary.farmName
            },
            avatarUrl = when {
                isUsableAvatar(primary.avatarUrl) -> primary.avatarUrl
                isUsableAvatar(fallback.avatarUrl) -> fallback.avatarUrl
                else -> ""
            }
        )
    }

    /** True for portable avatars (Base64 data URI / long payload / normal URL). */
    private fun isUsableAvatar(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (url.startsWith("file://") || url.startsWith("content://")) return false
        if (url.contains("unsplash.com")) return false
        return url.startsWith("data:image") || url.startsWith("http") || url.length > 100
    }





    /** Refreshes from the database without blocking the first paint. */
    suspend fun refreshProfile(firebaseUser: FirebaseUser): User? {
        val cached = cachedUser()?.takeIf { it.uid == firebaseUser.uid }
        val pendingLocalEdit = UserPreferences.isPendingSync(context)

        val cloudResult = authRepository.getUserProfile(firebaseUser.uid)
        val cloud = cloudResult.getOrNull()
        lastSyncError = cloudResult.exceptionOrNull()
            ?.takeUnless { it is ProfileNotFoundException }

        if (pendingLocalEdit && cached != null) {
            // A previous write may have reached the server but timed out on the ack.
            // Trust the cloud timestamp when it already contains our latest edit.
            if (cloud != null && cloud.updatedAt > 0L && cloud.updatedAt >= cached.updatedAt) {
                UserPreferences.clearPendingSync(context)
                cacheAndHydrate(cloud)
                return cloud
            }

            val pushed = saveProfile(firebaseUser.uid, cached)
            // If the local edit still cannot reach the database, keep showing it.
            return if (pushed.isSuccess) pushed.getOrThrow() else cached
        }

        if (cloud != null) {
            // Recover fields that only exist locally (e.g. an avatar or edits made
            // while the database rules were expired) instead of letting the cloud
            // copy erase them.
            val cachedIsNewer = cached != null &&
                (cached.updatedAt > cloud.updatedAt ||
                    (cached.updatedAt == 0L && cloud.updatedAt == 0L))
            val merged = if (cachedIsNewer) {
                mergeProfiles(cached!!, cloud)
            } else {
                mergeProfiles(cloud, cached)
            }
            if (merged != cloud) {
                val saved = saveProfile(firebaseUser.uid, merged)
                if (saved.isSuccess) return saved.getOrThrow()
            }
            cacheAndHydrate(merged)
            return merged
        }

        // Confirmed missing document: recreate it from the best local source.
        if (cloudResult.exceptionOrNull() is ProfileNotFoundException && cached != null) {
            val saved = saveProfile(firebaseUser.uid, cached)
            if (saved.isSuccess) return saved.getOrThrow()
        }

        // Network / permission failure: keep the cached profile instead of
        // replacing it with an auth-only stub.
        return cached
    }



    /**
     * Persists the full profile to the database, then mirrors it locally.
     * On a failed database write the local copy is kept and flagged for retry.
     */
    suspend fun saveProfile(uid: String, user: User): Result<User> {
        val sanitized = user.copy(
            uid = uid,
            avatarUrl = normalizeAvatarUrl(uid, user.avatarUrl),
            updatedAt = System.currentTimeMillis()
        )

        val cloudResult = authRepository.saveUserProfile(uid, sanitized)
        if (cloudResult.isSuccess) {
            lastSyncError = null
            cacheAndHydrate(sanitized)
            UserPreferences.clearPendingSync(context)
            // Keep Firebase Auth in sync as a last-resort recovery source.
            authRepository.updateDisplayName(sanitized.fullName)
            return Result.success(sanitized)
        }

        // Keep the edit locally and retry on the next launch / network return.
        lastSyncError = cloudResult.exceptionOrNull()
        UserPreferences.saveUser(context, sanitized)
        UserPreferences.markPendingSync(context)
        hydrateAvatarFile(sanitized.avatarUrl)
        return Result.failure(
            cloudResult.exceptionOrNull() ?: Exception("Could not save profile to the database")
        )
    }

    /** Saves a newly picked avatar (local file + database) without losing other fields. */
    suspend fun saveAvatar(base64: String): Result<User> {
        val firebaseUser = authRepository.currentUser
            ?: return Result.failure(Exception("No signed-in user"))

        writeLocalAvatar(base64)

        val cached = cachedUser()?.takeIf { it.uid == firebaseUser.uid }
        if (cached != null) {
            return saveProfile(firebaseUser.uid, cached.copy(avatarUrl = base64))
        }

        val cloudResult = authRepository.getUserProfile(firebaseUser.uid)
        val cloud = cloudResult.getOrNull()
        if (cloud != null) {
            return saveProfile(firebaseUser.uid, cloud.copy(avatarUrl = base64))
        }

        if (cloudResult.exceptionOrNull() is ProfileNotFoundException) {
            val seed = authRepository.userFromFirebase(firebaseUser).copy(avatarUrl = base64)
            return saveProfile(firebaseUser.uid, seed)
        }

        // The full profile could not be read right now. Write only the avatar
        // field so the existing (remote) profile is not replaced by a stub.
        val avatarOnly = authRepository.updateAvatarUrl(firebaseUser.uid, normalizeAvatarUrl(firebaseUser.uid, base64))
        return if (avatarOnly.isSuccess) {
            Result.success(authRepository.userFromFirebase(firebaseUser).copy(avatarUrl = base64))
        } else {
            Result.failure(avatarOnly.exceptionOrNull() ?: Exception("Could not save the profile photo"))
        }
    }

    /** Retries a locally cached profile that previously failed to reach the database. */
    suspend fun syncPendingProfile(): Boolean {
        val firebaseUser = authRepository.currentUser ?: return false
        val cached = cachedUser()?.takeIf { it.uid == firebaseUser.uid } ?: return false
        if (!UserPreferences.isPendingSync(context)) return true
        return saveProfile(firebaseUser.uid, cached).isSuccess
    }

    /**
     * Converts device-private URIs into a portable Base64 data URI so the avatar
     * still resolves after the app is reinstalled on another device.
     */
    private fun normalizeAvatarUrl(uid: String, raw: String?): String {
        val value = raw.orEmpty().trim()
        // Only reuse the on-device avatar when it belongs to the same account.
        val local = if (cachedUser()?.uid == uid) localAvatarBase64().orEmpty() else ""
        return when {
            value.isEmpty() -> local
            value.startsWith("data:image") -> value
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("file://") || value.startsWith("content://") -> local
            value.length > 100 -> "data:image/jpeg;base64,$value"
            else -> local
        }
    }

    private fun localAvatarBase64(): String? {
        return try {
            val file = localAvatarFile()
            if (!file.exists() || file.length() <= 0L) return null
            "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheAndHydrate(user: User) {
        UserPreferences.saveUser(context, user)
        if (user.avatarUrl.isBlank()) {
            // Prevent an avatar left by a different account from leaking through.
            localAvatarFile().delete()
        } else {
            hydrateAvatarFile(user.avatarUrl)
        }
    }

    private fun hydrateAvatarFile(avatarUrl: String?) {
        if (avatarUrl.isNullOrBlank()) return
        if (avatarUrl.startsWith("http://") || avatarUrl.startsWith("https://")) return

        val payload = when {
            avatarUrl.startsWith("data:image") -> avatarUrl.substringAfter("base64,", "")
            avatarUrl.length > 100 -> avatarUrl
            else -> return
        }
        if (payload.isBlank()) return

        try {
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            if (bytes.isNotEmpty()) {
                writeLocalAvatar(bytes)
            }
        } catch (_: Exception) {
        }
    }

    private fun writeLocalAvatar(base64: String) {
        try {
            val payload = if (base64.contains("base64,")) base64.substringAfter("base64,") else base64
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            if (bytes.isNotEmpty()) {
                localAvatarFile().writeBytes(bytes)
            }
        } catch (_: Exception) {
        }
    }

    private fun writeLocalAvatar(bytes: ByteArray) {
        try {
            localAvatarFile().writeBytes(bytes)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val AVATAR_FILE_NAME = "profile_avatar.jpg"
    }
}
