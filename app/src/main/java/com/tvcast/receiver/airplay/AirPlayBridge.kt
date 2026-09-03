package com.tvcast.receiver.airplay

/**
 * Native (airplay_jni.cpp) -> Kotlin callback surface for the AirPlay
 * receiver. A plain object with a swappable listener, mirroring how the
 * rest of the app decouples the server from the UI via CastState: whoever
 * owns the on-screen Surface (MainActivity) sets [listener], independently
 * of whoever started the native receiver (ServerService).
 *
 * Called from UxPlay's own native worker threads, not the main thread --
 * implementations must hop to the main/UI thread themselves before
 * touching views.
 */
object AirPlayBridge {

    interface Listener {
        fun onVideoFrame(data: ByteArray, isH265: Boolean, ntpTimeRemote: Long)
        fun onMirrorStateChanged(running: Boolean)
    }

    @Volatile
    var listener: Listener? = null

    @JvmStatic
    fun onVideoFrame(data: ByteArray, isH265: Boolean, ntpTimeRemote: Long) {
        listener?.onVideoFrame(data, isH265, ntpTimeRemote)
    }

    @JvmStatic
    fun onMirrorStateChanged(running: Boolean) {
        listener?.onMirrorStateChanged(running)
    }
}
