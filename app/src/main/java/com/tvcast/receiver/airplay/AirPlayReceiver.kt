package com.tvcast.receiver.airplay

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.File
import java.security.SecureRandom

/**
 * Kotlin side of the native AirPlay2 receiver (see app/src/main/cpp).
 *
 * Phase 1: pairing + RTSP/RTP session handling only, against UxPlay's
 * vendored protocol core. Received video/audio frames are currently just
 * logged natively (android.log tag "TVCastAirPlay") -- MediaCodec
 * decoding/rendering is a separate follow-up once pairing itself is
 * confirmed working against a real iPhone.
 */
class AirPlayReceiver(private val context: Context) {

    private var running = false
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start(serverName: String): Boolean {
        if (running) return true

        // The native mdnsd advertises over a raw multicast socket, bypassing
        // Android's NsdManager -- some Wi-Fi drivers silently drop inbound
        // multicast without an explicit lock held.
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock(TAG)?.also {
            it.setReferenceCounted(true)
            it.acquire()
        }

        val hwAddr = devicePseudoMac()
        val keyfile = File(context.filesDir, "uxplay_pairing_keys.plist").absolutePath
        running = nativeStart(serverName, hwAddr, keyfile)
        if (!running) {
            Log.e(TAG, "nativeStart failed")
            multicastLock?.release()
            multicastLock = null
        }
        return running
    }

    fun stop() {
        if (!running) return
        nativeStop()
        running = false
        multicastLock?.release()
        multicastLock = null
    }

    /** A random, per-install MAC-format identifier, persisted so paired iPhones don't re-pair every launch. */
    private fun devicePseudoMac(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(PREF_HW_ADDR, null)?.let { return it }

        val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
        bytes[0] = (bytes[0].toInt() and 0xFE or 0x02).toByte() // locally administered, unicast
        val mac = bytes.joinToString(":") { "%02x".format(it) }
        prefs.edit().putString(PREF_HW_ADDR, mac).apply()
        return mac
    }

    private external fun nativeStart(serverName: String, hwAddr: String, keyfilePath: String): Boolean
    private external fun nativeStop()

    companion object {
        private const val TAG = "AirPlayReceiver"
        private const val PREFS_NAME = "tvcast_airplay"
        private const val PREF_HW_ADDR = "hw_addr"

        init {
            System.loadLibrary("tvcast_airplay")
        }
    }
}
