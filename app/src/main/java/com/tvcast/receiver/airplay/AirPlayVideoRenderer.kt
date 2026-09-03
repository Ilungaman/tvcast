package com.tvcast.receiver.airplay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/**
 * Decodes the Annex-B H.264/H.265 elementary stream UxPlay hands us (see
 * AirPlayBridge.onVideoFrame) with a hardware MediaCodec straight onto a
 * Surface. No audio yet.
 *
 * Frames arrive serially on one native callback thread (UxPlay's mirror RTP
 * receiver thread) -- every method here is written to be driven from that
 * single thread, with [stop] additionally allowed from the UI thread, so
 * codec lifecycle access is synchronized against that one race.
 */
class AirPlayVideoRenderer(private val surface: Surface) {

    private var codec: MediaCodec? = null
    private var configured = false
    private val pendingParamSets = ArrayList<ByteArray>()

    @Synchronized
    fun feed(data: ByteArray, isH265: Boolean) {
        try {
            if (!configured) {
                tryConfigure(data, isH265)
                if (!configured) return
            }
            val mc = codec ?: return
            val inIndex = mc.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buf = mc.getInputBuffer(inIndex) ?: return
                buf.clear()
                buf.put(data)
                mc.queueInputBuffer(inIndex, 0, data.size, System.nanoTime() / 1000, 0)
            }
            drainOutput(mc)
        } catch (t: Throwable) {
            Log.e(TAG, "feed() failed, resetting decoder", t)
            releaseCodec()
        }
    }

    @Synchronized
    fun stop() {
        releaseCodec()
        pendingParamSets.clear()
    }

    private fun drainOutput(mc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = mc.dequeueOutputBuffer(info, 0)
            if (outIndex < 0) break
            mc.releaseOutputBuffer(outIndex, true)
        }
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
    }
}
