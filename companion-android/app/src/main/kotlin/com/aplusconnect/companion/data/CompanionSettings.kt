package com.aplusconnect.companion.data

import android.content.Context
import android.content.SharedPreferences
import com.aplusconnect.companion.protocol.Pairing

/**
 * What the phone remembers about the computer it is paired with.
 *
 * The pairing is the computer's stable id and the fingerprint of its
 * certificate. After the first successful handshake both are stored, and
 * every later connection must present the same certificate.
 */
class CompanionSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("companion", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, 24443)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /** The one time code the computer showed when pairing. */
    var pairingCode: String
        get() = prefs.getString(KEY_CODE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CODE, value).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    /** The paired computer, or null when this phone is not paired yet. */
    var pairing: Pairing?
        get() {
            val pcId = prefs.getString(KEY_PC_ID, null) ?: return null
            val fingerprint = prefs.getString(KEY_FINGERPRINT, null) ?: return null
            return Pairing(pcId, fingerprint)
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(KEY_PC_ID).remove(KEY_FINGERPRINT)
            } else {
                editor.putString(KEY_PC_ID, value.pcId)
                editor.putString(KEY_FINGERPRINT, value.fingerprint)
            }
            editor.apply()
        }

    /** Remembers the computer after the first handshake. */
    fun remember(pcId: String, fingerprint: String) {
        pairing = Pairing(pcId, fingerprint)
    }

    fun forget() {
        pairing = null
    }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_CODE = "code"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PC_ID = "pc_id"
        private const val KEY_FINGERPRINT = "fingerprint"
    }
}
