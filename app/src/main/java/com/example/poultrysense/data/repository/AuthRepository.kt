package com.example.poultrysense.data.repository

import com.example.poultrysense.data.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

class AuthRepository {

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val realtimeDb: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }

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

            // 4. Save to Cloud Firestore & Realtime Database asynchronously (non-blocking)
            try {
                firestore.collection("users")
                    .document(firebaseUser.uid)
                    .set(userModel.toMap(), SetOptions.merge())
            } catch (fsError: Exception) {
                fsError.printStackTrace()
            }

            try {
                realtimeDb.getReference("users")
                    .child(firebaseUser.uid)
                    .setValue(userModel.toMap())
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
            val doc = withTimeoutOrNull(3000L) {
                firestore.collection("users").document(uid).get().await()
            }
            if (doc != null && doc.exists()) {
                val firstName = doc.getString("firstName") ?: ""
                val lastName = doc.getString("lastName") ?: ""
                val email = doc.getString("email") ?: (auth.currentUser?.email ?: "")
                val farmName = doc.getString("farmName") ?: "Poultry Farm"
                val role = doc.getString("role") ?: "Lead Farm Specialist"
                val avatarUrl = doc.getString("avatarUrl") ?: ""
                val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                val scansCount = doc.getLong("scansCount")?.toInt() ?: 0
                val activeDays = doc.getLong("activeDays")?.toInt() ?: 1

                val user = User(
                    uid = uid,
                    firstName = firstName,
                    lastName = lastName,
                    email = email,
                    farmName = farmName,
                    role = role,
                    avatarUrl = avatarUrl,
                    createdAt = createdAt,
                    scansCount = scansCount,
                    activeDays = activeDays
                )
                Result.success(user)
            } else {
                // Fallback to Firebase Auth user info
                val currentUser = auth.currentUser
                val displayName = currentUser?.displayName ?: ""
                val parts = displayName.split(" ")
                val fName = if (parts.isNotEmpty()) parts[0] else ""
                val lName = if (parts.size > 1) parts.subList(1, parts.size).joinToString(" ") else ""

                val fallbackUser = User(
                    uid = uid,
                    firstName = fName,
                    lastName = lName,
                    email = currentUser?.email ?: "",
                    farmName = "Poultry Farm",
                    role = "Lead Farm Specialist"
                )
                Result.success(fallbackUser)
            }
        } catch (e: Exception) {
            // Offline/graceful fallback
            val currentUser = auth.currentUser
            val displayName = currentUser?.displayName ?: ""
            val parts = displayName.split(" ")
            val fName = if (parts.isNotEmpty()) parts[0] else ""
            val lName = if (parts.size > 1) parts.subList(1, parts.size).joinToString(" ") else ""

            val fallbackUser = User(
                uid = uid,
                firstName = fName,
                lastName = lName,
                email = currentUser?.email ?: "",
                farmName = "Poultry Farm",
                role = "Lead Farm Specialist"
            )
            Result.success(fallbackUser)
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
