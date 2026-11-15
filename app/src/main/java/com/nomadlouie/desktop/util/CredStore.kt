package com.nomadlouie.desktop.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Stores the last connection and (optionally) credentials, encrypted at rest. */
class CredStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "nomadlouie_creds",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        context.getSharedPreferences("nomadlouie_creds_fallback", Context.MODE_PRIVATE)
    }

    var url: String
        get() = prefs.getString("url", "https://desktop.nomadlouie.com") ?: "https://desktop.nomadlouie.com"
        set(value) = prefs.edit().putString("url", value).apply()

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(value) = prefs.edit().putString("username", value).apply()

    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(value) = prefs.edit().putString("password", value).apply()

    var remember: Boolean
        get() = prefs.getBoolean("remember", true)
        set(value) = prefs.edit().putBoolean("remember", value).apply()
}
