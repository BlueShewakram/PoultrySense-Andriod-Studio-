package com.example.poultrysense.data

import com.google.firebase.database.FirebaseDatabase

/**
 * Single source of truth for the Firebase Realtime Database instance.
 *
 * Every screen/repository must use this instead of repeating the database URL
 * (or calling the no-argument getInstance(), which fails because the database
 * URL is not declared in google-services.json).
 *
 * Profile persistence reads/writes /users/{uid}; the Realtime Database rules
 * must allow the authenticated user to read and write their own uid node.
 */
object FirebaseConfig {

    const val DATABASE_URL =
        "https://poultrysense-app-default-rtdb.asia-southeast1.firebasedatabase.app"

    fun database(): FirebaseDatabase = FirebaseDatabase.getInstance(DATABASE_URL)
}
