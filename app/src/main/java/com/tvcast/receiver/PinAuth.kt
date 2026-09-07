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
    // Volatile + computed once, synchronously, in init() -- not lazily on
    // first access. The previous version generated the PIN lazily into a
    // plain (non-volatile) var, read from two different threads: the TV
    // UI thread (rendering the idle screen) and Ktor's server engine
    // thread(s) (checking /api/auth). Without @Volatile, the JVM memory
    // model gives no guarantee that one thread's write to a plain field
    // ever becomes visible to another thread's reads -- so the UI and the
    // server could each end up with their own cached copy of a value that
    // was only ever supposed to be generated once, permanently
    // disagreeing with each other. That would explain exactly what was
    // seen: a PIN typed correctly, matching the screen exactly, that
    // still never authenticates. Eager init() removes the lazy first-
    // access race entirely, and @Volatile guarantees any later read on
    // any thread sees the same value.
    @Volatile
    private var pinValue: String = ""

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(PREF_PIN, null)
        pinValue = if (existing != null) {
            existing
        } else {
            val generated = (1000 + SecureRandom().nextInt(9000)).toString()
            prefs.edit().putString(PREF_PIN, generated).apply()
            generated
        }
    }

    val pin: String get() = pinValue

    private const val PREFS_NAME = "tvcast_auth"
    private const val PREF_PIN = "pin"
}
