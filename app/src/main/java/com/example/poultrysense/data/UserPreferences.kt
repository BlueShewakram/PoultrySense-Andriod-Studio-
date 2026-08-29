package com.example.poultrysense.data

import android.content.Context
import android.content.SharedPreferences
import com.example.poultrysense.data.models.User
import com.google.gson.Gson

object UserPreferences {
    private const val PREFS_NAME = "poultrysense_user_prefs"
    private const val KEY_CACHED_USER = "cached_user_json"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveUser(context: Context, user: User) {
        val json = Gson().toJson(user)
        getPrefs(context).edit().putString(KEY_CACHED_USER, json).apply()
    }

    fun getCachedUser(context: Context): User? {
        val json = getPrefs(context).getString(KEY_CACHED_USER, null) ?: return null
        return try {
            Gson().fromJson(json, User::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun clearUser(context: Context) {
        getPrefs(context).edit().remove(KEY_CACHED_USER).apply()
    }
}
