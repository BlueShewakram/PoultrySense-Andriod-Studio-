package com.example.poultrysense.data.repository

import com.example.poultrysense.data.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

class AuthRepository {

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val realtimeDb: FirebaseDatabase by lazy {
        FirebaseDatabase.getInstance("https://poultrysense-app-default-rtdb.asia-southeast1.firebasedatabase.app")
    }

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val isUserLoggedIn: Boolean
        get() = auth.currentUser != null

    suspend fun login(email: String, password: String): Result<FirebaseUser> {
        return try {
            val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
            val user = result.user ?: throw Exception("Authentication returned empty user session.")

            // Sync profile to Realtime DB if this account has no record yet
            syncProfileIfMissing(user)

            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun syncProfileIfMissing(user: FirebaseUser) {
        try {
            val snapshot = realtimeDb.getReference("users").child(user.uid).get().await()
            if (!snapshot.exists()) {
                // Account exists in Auth but not in Realtime DB — write it now
                val displayName = user.displayName ?: ""
                val parts = displayName.split(" ")
                val firstName = if (parts.isNotEmpty()) parts[0] else ""
                val lastName = if (parts.size > 1) parts.subList(1, parts.size).joinToString(" ") else ""
                val profileMap = mapOf(
                    "uid" to user.uid,
                    "firstName" to firstName,
                    "lastName" to lastName,
                    "email" to (user.email ?: ""),
                    "farmName" to "Poultry Farm",
                    "role" to "Lead Farm Specialist",
                    "avatarUrl" to "",
                    "createdAt" to System.currentTimeMillis(),
                    "scansCount" to 0,
                    "activeDays" to 1
                )
                realtimeDb.getReference("users").child(user.uid).setValue(profileMap).await()
            }
        } catch (e: Exception) {
            // Non-critical — don't block login if sync fails
            e.printStackTrace()
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

            val fullName = "$cleanFirstName $cleanLastName"

            // 2. Set display name in Firebase Auth
            val profileUpdates = UserProfileChangeRequest.Builder()
                .setDisplayName(fullName)
                .build()
            try {
                firebaseUser.updateProfile(profileUpdates).await()
            } catch (pError: Exception) {
                pError.printStackTrace()
            }

            // 3. Construct user model
            val userModel = User(
                uid = firebaseUser.uid,
                firstName = cleanFirstName,
                lastName = cleanLastName,
                email = cleanEmail,
                farmName = if (cleanFarmName.isNotEmpty()) cleanFarmName else "Poultry Farm",
                role = "Lead Farm Specialist",
                avatarUrl = "",
                createdAt = System.currentTimeMillis(),
                scansCount = 0,
                activeDays = 1
            )

            // 4. Save to Realtime Database
            try {
                realtimeDb.getReference("users")
                    .child(firebaseUser.uid)
                    .setValue(userModel.toMap())
                    .await()
            } catch (rtError: Exception) {
                rtError.printStackTrace()
            }

            Result.success(userModel)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserProfile(uid: String): Result<User> {
        return try {
            val snapshot = withTimeoutOrNull(4000L) {
                realtimeDb.getReference("users").child(uid).get().await()
            }
            if (snapshot != null && snapshot.exists()) {
                val map = snapshot.value as? Map<*, *>
                val user = User(
                    uid = uid,
                    firstName = map?.get("firstName") as? String ?: "",
                    lastName = map?.get("lastName") as? String ?: "",
                    email = map?.get("email") as? String ?: (auth.currentUser?.email ?: ""),
                    farmName = map?.get("farmName") as? String ?: "Poultry Farm",
                    role = map?.get("role") as? String ?: "Lead Farm Specialist",
                    avatarUrl = map?.get("avatarUrl") as? String ?: "",
                    createdAt = (map?.get("createdAt") as? Long) ?: System.currentTimeMillis(),
                    scansCount = (map?.get("scansCount") as? Long)?.toInt() ?: 0,
                    activeDays = (map?.get("activeDays") as? Long)?.toInt() ?: 1
                )
                Result.success(user)
            } else {
                // Fallback to Firebase Auth display name
                val currentUser = auth.currentUser
                val displayName = currentUser?.displayName ?: ""
                val parts = displayName.split(" ")
                val fName = if (parts.isNotEmpty()) parts[0] else ""
                val lName = if (parts.size > 1) parts.subList(1, parts.size).joinToString(" ") else ""
                Result.success(
                    User(
                        uid = uid,
                        firstName = fName,
                        lastName = lName,
                        email = currentUser?.email ?: "",
                        farmName = "Poultry Farm",
                        role = "Lead Farm Specialist"
                    )
                )
            }
        } catch (e: Exception) {
            // Offline graceful fallback
            val currentUser = auth.currentUser
            val displayName = currentUser?.displayName ?: ""
            val parts = displayName.split(" ")
            val fName = if (parts.isNotEmpty()) parts[0] else ""
            val lName = if (parts.size > 1) parts.subList(1, parts.size).joinToString(" ") else ""
            Result.success(
                User(
                    uid = uid,
                    firstName = fName,
                    lastName = lName,
                    email = currentUser?.email ?: "",
                    farmName = "Poultry Farm",
                    role = "Lead Farm Specialist"
                )
            )
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
}
