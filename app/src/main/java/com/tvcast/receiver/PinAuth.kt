package com.tvcast.receiver

import android.content.Context
import java.security.SecureRandom

/**
 * A 4-digit PIN, generated once per install and persisted so it survives
 * app/TV restarts (regenerating it every launch would lock the family out
 * constantly). Shown on the TV's own idle screen -- entering it once on a
 * phone is what proves "this phone's owner can see the TV", which is the
 * whole point: anyone else on the same Wi-Fi can still reach the upload
 * page, but not use it, without watching the screen it's displayed on.
 */
object PinAuth {
    private lateinit var appContext: Context
    private var cached: String? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val pin: String
        get() {
            cached?.let { return it }
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(PREF_PIN, null)?.let { cached = it; return it }
            val generated = (1000 + SecureRandom().nextInt(9000)).toString()
            prefs.edit().putString(PREF_PIN, generated).apply()
            cached = generated
            return generated
        }

    private const val PREFS_NAME = "tvcast_auth"
    private const val PREF_PIN = "pin"
}
