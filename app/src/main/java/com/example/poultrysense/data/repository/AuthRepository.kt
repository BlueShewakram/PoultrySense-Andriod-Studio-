package com.example.poultrysense.data.repository

import com.example.poultrysense.data.FirebaseConfig
import com.example.poultrysense.data.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Thrown when the database is reachable but has no profile document for the user. */
class ProfileNotFoundException : Exception("Profile not found in database")

/**
 * Authentication + Firebase Realtime Database profile persistence.
 *
 * The cloud copy under /users/{uid} is the source of truth so that a farmer can
 * uninstall the app, install it again and sign in with the same account and get
 * the exact same profile (name, farm name, avatar) back.
 */
class AuthRepository {

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val database by lazy { FirebaseConfig.database() }
    private val usersRef get() = database.getReference("users")

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val isUserLoggedIn: Boolean
        get() = auth.currentUser != null

    suspend fun login(email: String, password: String): Result<FirebaseUser> {
        return try {
            val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
            val user = result.user ?: throw Exception("Authentication returned empty user session.")
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signUp(
        firstName: String,
        lastName: String,
        email: String,
        password: String,
        farmName: String
    ): Result<User> {
        return try {
            val cleanEmail = email.trim()
            val cleanFirstName = firstName.trim()
            val cleanLastName = lastName.trim()
            val cleanFarmName = farmName.trim()

            // 1. Create Firebase Auth user
            val authResult = auth.createUserWithEmailAndPassword(cleanEmail, password).await()
            val firebaseUser = authResult.user ?: throw Exception("User creation failed.")

            val fullName = listOf(cleanFirstName, cleanLastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")

            // 2. Keep Firebase Auth display name in sync (fallback for profile restore)
            try {
                val profileUpdates = UserProfileChangeRequest.Builder()
                    .setDisplayName(fullName)
                    .build()
                firebaseUser.updateProfile(profileUpdates).await()
            } catch (_: Exception) {}

            // 3. Construct user model from the real sign-up date (no hardcoded dates)
            val cal = Calendar.getInstance()
            val createdAt = cal.timeInMillis
            val monthName = SimpleDateFormat("MMMM", Locale.getDefault()).format(Date(createdAt))
            val formattedDate = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(createdAt))

            val userModel = User(
                uid = firebaseUser.uid,
                firstName = cleanFirstName,
                lastName = cleanLastName,
                email = cleanEmail,
                farmName = cleanFarmName,
                role = "",
                avatarUrl = "",
                createdAt = createdAt,
                createdDay = cal.get(Calendar.DAY_OF_MONTH),
                createdMonth = cal.get(Calendar.MONTH) + 1,
                createdMonthName = monthName,
                createdYear = cal.get(Calendar.YEAR),
                formattedDate = formattedDate,
                scansCount = 0,
                activeDays = 1,
                updatedAt = createdAt
            )

            // The actual database write is performed by ProfileRepository so the
            // caller can report (and retry) a failed cloud save instead of losing it.
            Result.success(userModel)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Writes the complete profile document to the database.
     * setValue (not updateChildren) is used so stale, device-specific fields
     * such as an old photoBase64 or file:// avatar URL are removed.
     */
    suspend fun saveUserProfile(uid: String, user: User): Result<Unit> {
        return try {
            val userRef = usersRef.child(uid)
            val completed = withTimeoutOrNull(PROFILE_TIMEOUT_MS) {
                userRef.setValue(user.toMap()).await()
            }
            if (completed == null) {
                Result.failure(Exception("Timed out while saving profile to the database"))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateAvatarUrl(uid: String, avatarUrl: String): Result<Unit> {
        return try {
            val userRef = usersRef.child(uid)
            userRef.child("avatarUrl").setValue(avatarUrl).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateDisplayName(name: String): Result<Unit> {
        return try {
            val firebaseUser = auth.currentUser
                ?: return Result.failure(Exception("No signed-in user"))
            val updates = UserProfileChangeRequest.Builder()
                .setDisplayName(name.trim())
                .build()
            firebaseUser.updateProfile(updates).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reads the profile from the database. If the database has no document yet
     * (for example an account created while offline), [seed] is written first.
     */
    suspend fun ensureUserProfile(firebaseUser: FirebaseUser, seed: User? = null): Result<User> {
        val existing = getUserProfile(firebaseUser.uid)
        if (existing.isSuccess) return existing

        val newProfile = seed ?: userFromFirebase(firebaseUser)
        val save = saveUserProfile(firebaseUser.uid, newProfile)
        return if (save.isSuccess) {
            Result.success(newProfile)
        } else {
            Result.failure(save.exceptionOrNull() ?: Exception("Unable to save profile"))
        }
    }

    suspend fun getUserProfile(uid: String): Result<User> {
        return try {
            val userRef = usersRef.child(uid)
            val snapshot = withTimeoutOrNull(PROFILE_TIMEOUT_MS) { userRef.get().await() }
                ?: return Result.failure(Exception("Timed out while loading profile"))
            if (!snapshot.exists()) {
                return Result.failure(ProfileNotFoundException())
            }
            val map = snapshot.value as? Map<*, *>
                ?: return Result.failure(Exception("Unexpected profile format"))
            Result.success(mapToUser(uid, map))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> {
        return try {
            auth.sendPasswordResetEmail(email.trim()).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun signOut() {
        auth.signOut()
    }

    /** Builds a profile from Firebase Auth metadata only when the database has none. */
    fun userFromFirebase(firebaseUser: FirebaseUser): User {
        val displayName = firebaseUser.displayName.orEmpty().trim()
        val parts = displayName.split(" ").filter { it.isNotBlank() }
        val firstName = parts.firstOrNull().orEmpty()
        val lastName = if (parts.size > 1) parts.drop(1).joinToString(" ") else ""

        val createdAt = firebaseUser.metadata?.creationTimestamp ?: System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = createdAt }
        return User(
            uid = firebaseUser.uid,
            firstName = firstName,
            lastName = lastName,
            email = firebaseUser.email.orEmpty(),
            farmName = "",
            role = "",
            avatarUrl = "",
            createdAt = createdAt,
            createdDay = cal.get(Calendar.DAY_OF_MONTH),
            createdMonth = cal.get(Calendar.MONTH) + 1,
            createdMonthName = SimpleDateFormat("MMMM", Locale.getDefault()).format(Date(createdAt)),
            createdYear = cal.get(Calendar.YEAR),
            formattedDate = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(createdAt)),
            scansCount = 0,
            activeDays = 1,
            updatedAt = createdAt
        )
    }

    private fun mapToUser(uid: String, map: Map<*, *>): User {
        val createdAt = (map["createdAt"] as? Number)?.toLong()
            ?: auth.currentUser?.metadata?.creationTimestamp
            ?: System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = createdAt }
        val rawAvatar = (map["avatarUrl"] as? String)?.takeIf { it.isNotBlank() }
            ?: (map["photoBase64"] as? String)?.takeIf { it.isNotBlank() }
            ?: (map["photoUrl"] as? String).orEmpty()

        val displayName = auth.currentUser?.displayName.orEmpty()
        val fallbackParts = displayName.split(" ").filter { it.isNotBlank() }

        return User(
            uid = uid,
            firstName = (map["firstName"] as? String)?.takeIf { it.isNotBlank() }
                ?: fallbackParts.firstOrNull().orEmpty(),
            lastName = (map["lastName"] as? String)?.takeIf { it.isNotBlank() }
                ?: (if (fallbackParts.size > 1) fallbackParts.drop(1).joinToString(" ") else ""),
            email = (map["email"] as? String)?.takeIf { it.isNotBlank() }
                ?: (auth.currentUser?.email ?: ""),
            farmName = (map["farmName"] as? String).orEmpty(),
            role = (map["role"] as? String).orEmpty(),
            avatarUrl = rawAvatar,
            createdAt = createdAt,
            // Derive every displayed date from createdAt so stale/hardcoded values
            // in older profile documents can never resurface in the UI.
            createdDay = cal.get(Calendar.DAY_OF_MONTH),
            createdMonth = cal.get(Calendar.MONTH) + 1,
            createdMonthName = SimpleDateFormat("MMMM", Locale.getDefault()).format(Date(createdAt)),
            createdYear = cal.get(Calendar.YEAR),
            formattedDate = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(createdAt)),
            scansCount = (map["scansCount"] as? Number)?.toInt() ?: 0,
            activeDays = (map["activeDays"] as? Number)?.toInt() ?: 1,
            updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L
        )
    }

    private companion object {
        const val PROFILE_TIMEOUT_MS = 12_000L
    }
}
