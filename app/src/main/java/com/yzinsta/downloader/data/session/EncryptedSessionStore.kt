package com.yzinsta.downloader.data.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException

/**
 * [SessionStore] on top of EncryptedSharedPreferences (key in Android Keystore).
 * The file is excluded from backups (see data_extraction_rules.xml), so the
 * keystore key and the data never get separated.
 */
class EncryptedSessionStore(context: Context) : SessionStore {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { open() }

    private fun open(): SharedPreferences {
        val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        fun create() = EncryptedSharedPreferences.create(
            FILE, alias, appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return try {
            create()
        } catch (e: Exception) {
            // Keystore key lost or file corrupted (e.g. restored from another device):
            // the data is unreadable anyway, start over.
            Log.w(TAG, "Encrypted prefs unreadable, resetting", e)
            appContext.deleteSharedPreferences(FILE)
            create()
        }
    }

    override suspend fun load(): StoredSession? = withContext(Dispatchers.IO) {
        val json = prefs.getString(KEY_COOKIES, null) ?: return@withContext null
        try {
            StoredSession(InstagramCookies.fromJson(json), prefs.getString(KEY_USERNAME, null))
        } catch (e: JSONException) {
            Log.w(TAG, "Stored cookies unreadable, dropping them")
            prefs.edit().clear().commit()
            null
        }
    }

    override suspend fun save(session: StoredSession) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_COOKIES, session.cookies.toJson())
            .putString(KEY_USERNAME, session.username)
            .commit()
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().clear().commit()
        Unit
    }

    private companion object {
        const val TAG = "SessionStore"
        const val FILE = "instagram_session"
        const val KEY_COOKIES = "cookies"
        const val KEY_USERNAME = "username"
    }
}
