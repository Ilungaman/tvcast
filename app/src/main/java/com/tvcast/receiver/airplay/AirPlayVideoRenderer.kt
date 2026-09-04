package com.tvcast.receiver.airplay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.util.concurrent.ArrayBlockingQueue

/**
 * Decodes the Annex-B H.264/H.265 elementary stream UxPlay hands us (see
 * AirPlayBridge.onVideoFrame) with a hardware MediaCodec straight onto a
 * Surface.
 *
 * [feed] is called directly from UxPlay's native mirror-receiver thread
 * (airplay_jni.cpp's cb_video_process) -- the SAME thread that reads the
 * mirror TCP stream off the socket (raop_rtp_mirror.c's
 * raop_rtp_mirror_thread does recv() and depacketizing there too, there is
 * no separate network I/O thread). Doing MediaCodec work on that thread
 * would block it on Java/JNI calls, stalling the TCP read loop -- and
 * therefore network delivery -- for as long as decode of the current frame
 * takes. Confirmed against the real TV: the same footage that judders over
 * AirPlay during fast motion played back perfectly smoothly from a local
 * flash drive (a path with no network thread in the loop at all), pointing
 * at this self-inflicted stall rather than the decoder being too slow for
 * the content. So [feed] only ever hands the frame to a queue and returns;
 * all actual MediaCodec work happens on a dedicated decode thread that
 * owns [codec]/[configured]/[pendingParamSets] exclusively -- [stop] is
 * the only other public entry point and only ever signals that thread,
 * never touches codec state directly.
 */
class AirPlayVideoRenderer(
    private val surface: Surface,
    private val onVideoSize: ((width: Int, height: Int) -> Unit)? = null
) {

    private var codec: MediaCodec? = null
    private var configured = false
    private val pendingParamSets = ArrayList<ByteArray>()

    // Anchors a playback clock to the first frame of the current decode
    // session (see scheduleRenderTime()), reset whenever the codec is torn
    // down and recreated.
    private var firstFramePtsNs = -1L
    private var firstRenderAtNs = -1L

    private data class Frame(val data: ByteArray, val isH265: Boolean)

    // Bounded so a real decode backlog can't grow memory or latency
    // without limit -- dropping the oldest queued frame is cheap and, once
    // the render clock is behind by more than MAX_LAG_NS anyway, that
    // frame was headed for a resync discard on the output side regardless.
    private val queue = ArrayBlockingQueue<Frame>(QUEUE_CAPACITY)

    @Volatile
    private var running = true

    // Fields above must all be initialized before this line: Thread.start()
    // happens-before everything the new thread observes, but only for
    // state written before start() is called, so decodeThread must be the
    // last property in the class.
    private val decodeThread = Thread(::decodeLoop, "AirPlayVideoDecode").apply { start() }

    fun feed(data: ByteArray, isH265: Boolean) {
        val frame = Frame(data, isH265)
        if (!queue.offer(frame)) {
            queue.poll()
            queue.offer(frame)
        }
    }

    fun stop() {
        running = false
        decodeThread.interrupt()
        try {
            decodeThread.join(500)
        } catch (_: InterruptedException) {
        }
    }

    private fun decodeLoop() {
        try {
            while (running) {
                val frame = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    break
                }
                decodeFrame(frame.data, frame.isH265)
            }
        } finally {
            releaseCodec()
            pendingParamSets.clear()
        }
    }

    private fun decodeFrame(data: ByteArray, isH265: Boolean) {
        try {
            if (!configured) {
                tryConfigure(data, isH265)
                if (!configured) return
            }
            feedToCodec(data)
        } catch (t: Throwable) {
            Log.e(TAG, "decodeFrame() failed, reconfiguring from this frame", t)
            releaseCodec()
            pendingParamSets.clear()
            try {
                tryConfigure(data, isH265)
                if (configured) feedToCodec(data)
            } catch (t2: Throwable) {
                Log.e(TAG, "reconfigure retry also failed", t2)
                releaseCodec()
            }
        }
    }

    private fun feedToCodec(data: ByteArray) {
        val mc = codec ?: return
        val inIndex = mc.dequeueInputBuffer(10_000)
        if (inIndex >= 0) {
            val buf = mc.getInputBuffer(inIndex) ?: return
            buf.clear()
            buf.put(data)
            mc.queueInputBuffer(inIndex, 0, data.size, System.nanoTime() / 1000, 0)
        }
        drainOutput(mc)
    }

    private fun drainOutput(mc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = mc.dequeueOutputBuffer(info, 0)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val (w, h) = decodedSize(mc.outputFormat)
                Log.i(TAG, "output format changed: ${w}x$h")
                onVideoSize?.invoke(w, h)
                continue
            }
            if (outIndex < 0) break
            mc.releaseOutputBuffer(outIndex, scheduleRenderTime(info.presentationTimeUs))
        }
    }

    /**
     * Displaying a frame the instant our thread happens to reach
     * releaseOutputBuffer() (as releaseOutputBuffer(index, true) does)
     * couples on-screen timing to whatever jitter exists in our own
     * pipeline -- decode time variance, GC pauses, and previously (before
     * decode moved to its own thread) network arrival spacing too.
     *
     * feedToCodec() stamps each input buffer's presentationTimeUs with its
     * arrival time (System.nanoTime()/1000); MediaCodec carries that
     * timestamp through to the matching output buffer unchanged. Anchoring
     * the first frame's arrival time to "now plus a small buffer" and then
     * scheduling every later frame at the same offset from that anchor
     * reproduces the original relative spacing between frames instead of
     * however our own processing happened to be spaced -- letting
     * SurfaceFlinger's timed presentation absorb small timing variance
     * instead of showing it as judder.
     *
     * That alone isn't enough during a sustained burst of complex frames:
     * if decode genuinely can't keep up for a stretch, frames' scheduled
     * times drift further into the past every frame, and a past timestamp
     * renders immediately, so the backlog dumps onto the screen in one
     * catch-up burst the moment decode gets a chance to run. Once the
     * drift passes MAX_LAG_NS, resync the anchor to now instead of
     * dutifully replaying the growing backlog at its original spacing --
     * trading exact frame timing (briefly discarding the lag) for staying
     * visually smooth.
     */
    private fun scheduleRenderTime(presentationTimeUs: Long): Long {
        val ptsNs = presentationTimeUs * 1000L
        val nowNs = System.nanoTime()
        val scheduledNs = firstRenderAtNs + (ptsNs - firstFramePtsNs)
        if (firstFramePtsNs < 0 || scheduledNs < nowNs - MAX_LAG_NS) {
            firstFramePtsNs = ptsNs
            firstRenderAtNs = nowNs + RENDER_BUFFER_NS
            return firstRenderAtNs
        }
        return scheduledNs
    }

    /**
     * H.264/H.265 pad the coded frame up to a macroblock-aligned size (a
     * multiple of 16), then declare the real visible area via a crop
     * rectangle -- KEY_WIDTH/KEY_HEIGHT alone report the padded size on
     * some devices. Real screen content (arbitrary point dimensions, not
     * 16-aligned) hits this; a still photo's canvas apparently happened to
     * already be aligned, which is why only mirroring looked wrong.
     */
    private fun decodedSize(fmt: MediaFormat): Pair<Int, Int> {
        val hasCrop = fmt.containsKey("crop-left") && fmt.containsKey("crop-right") &&
            fmt.containsKey("crop-top") && fmt.containsKey("crop-bottom")
        if (hasCrop) {
            val left = fmt.getInteger("crop-left")
            val right = fmt.getInteger("crop-right")
            val top = fmt.getInteger("crop-top")
            val bottom = fmt.getInteger("crop-bottom")
            return (right - left + 1) to (bottom - top + 1)
        }
        return fmt.getInteger(MediaFormat.KEY_WIDTH) to fmt.getInteger(MediaFormat.KEY_HEIGHT)
    }

    private fun tryConfigure(data: ByteArray, isH265: Boolean) {
        val nals = splitAnnexB(data)
        for (nal in nals) {
            val type = if (isH265) (nal[0].toInt() shr 1) and 0x3F else nal[0].toInt() and 0x1F
            val isParamSet = if (isH265) type in 32..34 else type == 7 || type == 8
            if (isParamSet) pendingParamSets.add(withStartCode(nal))
        }
        val haveAll = if (isH265) {
            pendingParamSets.any { isNalType(it, true, 32) } &&
                pendingParamSets.any { isNalType(it, true, 33) } &&
                pendingParamSets.any { isNalType(it, true, 34) }
        } else {
            pendingParamSets.any { isNalType(it, false, 7) } &&
                pendingParamSets.any { isNalType(it, false, 8) }
        }
        if (!haveAll) return

        val mime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        // Placeholder size: the decoder renegotiates the real geometry from
        // the SPS itself once configured; this is just a hint some vendor
        // decoders want up front.
        val format = MediaFormat.createVideoFormat(mime, 1920, 1080)
        if (isH265) {
            val all = concat(pendingParamSets)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(all))
        } else {
            val sps = pendingParamSets.first { isNalType(it, false, 7) }
            val pps = pendingParamSets.first { isNalType(it, false, 8) }
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps))
            format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps))
        }
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

        val mc = MediaCodec.createDecoderByType(mime)
        mc.configure(format, surface, null, 0)
        mc.start()
        codec = mc
        configured = true
        pendingParamSets.clear()
        Log.i(TAG, "decoder configured: $mime")
    }

    private fun releaseCodec() {
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
        configured = false
        firstFramePtsNs = -1L
        firstRenderAtNs = -1L
    }

    private fun isNalType(nalWithStartCode: ByteArray, isH265: Boolean, wantType: Int): Boolean {
        val offset = startCodeLength(nalWithStartCode)
        if (offset >= nalWithStartCode.size) return false
        val type = if (isH265) {
            (nalWithStartCode[offset].toInt() shr 1) and 0x3F
        } else {
            nalWithStartCode[offset].toInt() and 0x1F
        }
        return type == wantType
    }

    private fun startCodeLength(nal: ByteArray) = 4 // withStartCode() always writes a 4-byte start code

    private fun withStartCode(nal: ByteArray): ByteArray {
        val out = ByteArray(4 + nal.size)
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1
        System.arraycopy(nal, 0, out, 4, nal.size)
        return out
    }

    private fun concat(chunks: List<ByteArray>): ByteArray {
        val total = chunks.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, offset, c.size)
            offset += c.size
        }
        return out
    }

    /** Splits an Annex-B buffer into NAL units (payload only, start codes stripped). */
    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val scStarts = ArrayList<Int>()  // where each start code begins
        val nalStarts = ArrayList<Int>() // first payload byte after that start code
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                scStarts.add(i); nalStarts.add(i + 3); i += 3; continue
            }
            if (i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                scStarts.add(i); nalStarts.add(i + 4); i += 4; continue
            }
            i++
        }
        val result = ArrayList<ByteArray>(nalStarts.size)
        for (idx in nalStarts.indices) {
            val from = nalStarts[idx]
            val to = if (idx + 1 < scStarts.size) scStarts[idx + 1] else data.size
            if (to > from) result.add(data.copyOfRange(from, to))
        }
        return result
    }

    companion object {
        private const val TAG = "AirPlayVideoRenderer"

        // How many compressed frames can queue up between the native
        // callback thread and the decode thread before feed() starts
        // dropping the oldest one. Roughly matches RENDER_BUFFER_NS at a
        // typical mirroring frame rate -- enough to absorb a short burst
        // without letting a real backlog grow unbounded.
        private const val QUEUE_CAPACITY = 8

        // How far into the future the first frame of a decode session is
        // scheduled, giving later frames room to absorb arrival/decode
        // timing variance without visibly slipping. Adds the same amount
        // of end-to-end latency. Raised from 100ms to 300ms after
        // real-device testing showed 100ms wasn't enough headroom to
        // fully absorb this device's mirroring jitter -- traded for more
        // lag since this is mirroring (no interaction to feel delayed),
        // not a playback buffer.
        private const val RENDER_BUFFER_NS = 300_000_000L // 300ms

        // How far a scheduled frame is allowed to drift into the past
        // before scheduleRenderTime() gives up on the original spacing and
        // resyncs the clock to now. Bigger than RENDER_BUFFER_NS so normal
        // jitter never triggers it -- only a real, sustained decode
        // backlog does.
        private const val MAX_LAG_NS = 600_000_000L // 600ms
    }
}
