package com.tvcast.receiver.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * Decodes the AAC-ELD audio AirPlay mirroring sends (ct == 8 in
 * audio_decode_struct) with a software MediaCodec and plays it through an
 * AudioTrack. UxPlay's own reference renderer (renderers/audio_renderer.c)
 * documents the fixed format screen mirroring always uses -- 44100Hz
 * stereo, AudioSpecificConfig f8e85000 -- so unlike video there is no
 * per-stream negotiation to parse out of the bitstream.
 *
 * Frames arrive serially on UxPlay's native callback thread; [feed] and
 * [stop] are synchronized against each other, same pattern as
 * AirPlayVideoRenderer.
 */
class AirPlayAudioRenderer {

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var configured = false

    @Synchronized
    fun feed(data: ByteArray, ct: Int) {
        if (ct != 8) return // only the AirPlay-mirroring AAC-ELD path is wired up
        try {
            if (!configured) configure()
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
            release()
        }
    }

    @Synchronized
    fun stop() {
        release()
    }

    private fun configure() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(AAC_ELD_ASC))
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
        val mc = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        mc.configure(format, null, null, 0)
        mc.start()
        codec = mc
        configured = true
        Log.i(TAG, "audio decoder configured: AAC-ELD $SAMPLE_RATE/$CHANNELS")
    }

    private fun drainOutput(mc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = mc.dequeueOutputBuffer(info, 0)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                ensureTrack(mc.outputFormat)
                continue
            }
            if (outIndex < 0) break
            val buf = mc.getOutputBuffer(outIndex)
            if (buf != null && info.size > 0) {
                val pcm = ByteArray(info.size)
                buf.get(pcm)
                track?.write(pcm, 0, pcm.size)
            }
            mc.releaseOutputBuffer(outIndex, false)
        }
    }

    private fun ensureTrack(fmt: MediaFormat) {
        if (track != null) return
        val rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else SAMPLE_RATE
        val channelCount = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else CHANNELS
        val channelMask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        Log.i(TAG, "AudioTrack started: ${rate}Hz channels=$channelCount")
    }

    private fun release() {
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
        try {
            track?.stop()
        } catch (_: Throwable) {
        }
        try {
            track?.release()
        } catch (_: Throwable) {
        }
        track = null
        configured = false
    }

    companion object {
        private const val TAG = "AirPlayAudioRenderer"
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 2

        // AudioSpecificConfig for AAC-ELD 44100Hz/stereo, taken from
        // UxPlay's own reference renderer (renderers/audio_renderer.c:
        // "ct = 8; codec_data from MPEG v4 ISO 14996-3 Section 1.6.2.1:
        // AAC_ELD 44100/2 spf = 480" -- codec_data=(buffer)f8e85000).
        private val AAC_ELD_ASC = byteArrayOf(0xf8.toByte(), 0xe8.toByte(), 0x50.toByte(), 0x00.toByte())
    }
}
