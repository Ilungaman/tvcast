package com.tvcast.receiver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.tvcast.receiver.airplay.AirPlayBridge
import com.tvcast.receiver.airplay.AirPlayVideoRenderer
import com.tvcast.receiver.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var slideshowJob: Job? = null
    private var toastJob: Job? = null
    private var photoJob: Job? = null

    private var airplayRenderer: AirPlayVideoRenderer? = null
    private var mirroring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ServerService.start(this)
        MediaRepo.refresh()

        setupPlayer()
        setupAirPlay()
        showIdle()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { CastState.commands.collect { handle(it) } }
                launch { CastState.serverUrl.collect { renderIdleInfo() } }
                launch { CastState.lastError.collect { renderIdleInfo() } }
                launch { CastState.items.collect { renderIdleInfo() } }
                launch { positionTicker() }
            }
        }
    }

    // ------------------------------------------------------------------ плеер

    private fun setupPlayer() {
        val p = ExoPlayer.Builder(this).build()
        b.playerView.player = p
        b.playerView.useController = false
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                CastState.isPlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    CastState.durationMs.value = p.duration.coerceAtLeast(0L)
                }
                if (state == Player.STATE_ENDED) onPlaybackEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                CastState.lastError.value =
                    "Не удалось воспроизвести файл (${error.errorCodeName}). Возможно, телевизор не поддерживает этот кодек."
                toast("Ошибка воспроизведения: ${error.errorCodeName}")
                showIdle()
            }
        })
        player = p
    }

    // ------------------------------------------------------------ AirPlay

    private fun setupAirPlay() {
        b.airplaySurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                airplayRenderer = AirPlayVideoRenderer(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                airplayRenderer?.stop()
                airplayRenderer = null
            }
        })

        AirPlayBridge.listener = object : AirPlayBridge.Listener {
            // Called on UxPlay's native callback thread -- decode work stays
            // off the UI thread on purpose, AirPlayVideoRenderer is thread-safe.
            override fun onVideoFrame(data: ByteArray, isH265: Boolean, ntpTimeRemote: Long) {
                airplayRenderer?.feed(data, isH265)
            }

            override fun onMirrorStateChanged(running: Boolean) {
                runOnUiThread {
                    mirroring = running
                    if (running) showAirPlay() else showIdle()
                }
            }
        }
    }

    private fun showAirPlay() {
        photoJob?.cancel()
        slideshowJob?.cancel()
        player?.pause()
        b.idleView.visibility = View.GONE
        b.playerView.visibility = View.GONE
        b.photoView.visibility = View.GONE
        b.titleOverlay.visibility = View.GONE
        b.airplaySurface.visibility = View.VISIBLE
    }

    private fun onPlaybackEnded() {
        val p = player ?: return
        if (CastState.repeatOne.value) {
            p.seekTo(0)
            p.play()
            return
        }
        if (!goTo(+1)) showIdle()
    }

    private suspend fun positionTicker() {
        while (true) {
            val p = player
            if (p != null && CastState.currentId.value != null) {
                CastState.positionMs.value = p.currentPosition.coerceAtLeast(0L)
                val d = p.duration
                CastState.durationMs.value = if (d > 0) d else 0L
            }
            delay(500)
        }
    }

    // --------------------------------------------------------------- команды

    private fun handle(cmd: Command) {
        val p = player
        when (cmd) {
            is Command.Show -> show(cmd.id)
            Command.Play -> if (CastState.current()?.isVideo == true) p?.play() else restartSlideshow()
            Command.Pause -> {
                p?.pause()
                slideshowJob?.cancel()
            }
            Command.Toggle -> {
                val cur = CastState.current()
                if (cur != null && cur.isVideo) {
                    if (p?.isPlaying == true) p.pause() else p?.play()
                } else {
                    CastState.slideshowOn.value = !CastState.slideshowOn.value
                    restartSlideshow()
                    toast(if (CastState.slideshowOn.value) "Слайдшоу включено" else "Слайдшоу выключено")
                }
            }
            Command.Stop -> showIdle()
            is Command.Seek -> p?.seekTo(cmd.positionMs.coerceAtLeast(0L))
            is Command.SeekRelative -> p?.let {
                it.seekTo((it.currentPosition + cmd.deltaMs).coerceIn(0L, maxOf(it.duration, 0L)))
            }
            Command.Next -> if (!goTo(+1)) toast("Это последний файл")
            Command.Prev -> if (!goTo(-1)) toast("Это первый файл")
            is Command.Slideshow -> {
                CastState.slideshowOn.value = cmd.on
                CastState.slideshowInterval.value = cmd.intervalSec
                restartSlideshow()
            }
            is Command.Mute -> {
                CastState.muted.value = cmd.on
                p?.volume = if (cmd.on) 0f else 1f
            }
            is Command.RepeatOne -> CastState.repeatOne.value = cmd.on
            is Command.Notice -> toast(cmd.text)
        }
    }

    private fun goTo(step: Int): Boolean {
        val list = CastState.items.value
        if (list.isEmpty()) return false
        val idx = CastState.indexOfCurrent()
        val next = when {
            idx < 0 -> if (step > 0) 0 else list.lastIndex
            else -> idx + step
        }
        if (next !in list.indices) {
            if (CastState.slideshowOn.value) {
                show(list[if (step > 0) 0 else list.lastIndex].id)
                return true
            }
            return false
        }
        show(list[next].id)
        return true
    }

    // ------------------------------------------------------------ отображение

    private fun show(id: String) {
        val entry = MediaRepo.entryOf(id) ?: run {
            MediaRepo.refresh()
            MediaRepo.entryOf(id)
        } ?: return
        val file = MediaRepo.fileOf(id) ?: return

        CastState.currentId.value = id
        CastState.lastError.value = ""
        b.idleView.visibility = View.GONE
        b.airplaySurface.visibility = View.GONE
        showTitle(entry.name)

        if (entry.isVideo) {
            photoJob?.cancel()
            b.photoView.visibility = View.GONE
            b.photoView.setImageDrawable(null)
            b.playerView.visibility = View.VISIBLE
            slideshowJob?.cancel()
            player?.apply {
                setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                volume = if (CastState.muted.value) 0f else 1f
                prepare()
                play()
            }
        } else {
            player?.pause()
            player?.clearMediaItems()
            CastState.isPlaying.value = false
            CastState.durationMs.value = 0
            CastState.positionMs.value = 0
            b.playerView.visibility = View.GONE
            b.photoView.visibility = View.VISIBLE
            photoJob?.cancel()
            photoJob = lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { decodePhoto(file) }
                if (CastState.currentId.value != id) return@launch
                if (bmp == null) {
                    toast("Не удалось открыть изображение")
                } else {
                    b.photoView.setImageBitmap(bmp)
                    b.photoView.alpha = 0f
                    b.photoView.animate().alpha(1f).setDuration(180).start()
                }
            }
            restartSlideshow()
        }
    }

    private fun showIdle() {
        photoJob?.cancel()
        slideshowJob?.cancel()
        player?.pause()
        player?.clearMediaItems()
        CastState.currentId.value = null
        CastState.isPlaying.value = false
        CastState.positionMs.value = 0
        CastState.durationMs.value = 0
        b.playerView.visibility = View.GONE
        b.photoView.visibility = View.GONE
        b.photoView.setImageDrawable(null)
        b.airplaySurface.visibility = View.GONE
        b.titleOverlay.visibility = View.GONE
        b.idleView.visibility = View.VISIBLE
        renderIdleInfo()
    }

    private fun renderIdleInfo() {
        val url = CastState.serverUrl.value
        b.urlText.text = if (url.isBlank()) "нет сети" else url.removePrefix("http://")
        val err = CastState.lastError.value
        b.statusText.text = when {
            err.isNotBlank() -> err
            CastState.items.value.isEmpty() -> "Файлов пока нет — отправьте первые с телефона."
            else -> "Файлов на телевизоре: ${CastState.items.value.size}"
        }
        val qrBmp = if (url.isBlank()) null else QrGen.make(url, 600)
        if (qrBmp != null) b.qrView.setImageBitmap(qrBmp) else b.qrView.setImageDrawable(null)
    }

    private fun showTitle(name: String) {
        b.titleOverlay.text = name
        b.titleOverlay.visibility = View.VISIBLE
        b.titleOverlay.alpha = 1f
        b.titleOverlay.animate().setStartDelay(2500).alpha(0f).setDuration(400)
            .withEndAction { b.titleOverlay.visibility = View.GONE }.start()
    }

    private fun toast(text: String) {
        toastJob?.cancel()
        b.toastOverlay.text = text
        b.toastOverlay.visibility = View.VISIBLE
        toastJob = lifecycleScope.launch {
            delay(2500)
            b.toastOverlay.visibility = View.GONE
        }
    }

    private fun restartSlideshow() {
        slideshowJob?.cancel()
        if (!CastState.slideshowOn.value) return
        val cur = CastState.current() ?: return
        if (cur.isVideo) return
        slideshowJob = lifecycleScope.launch {
            delay(CastState.slideshowInterval.value.coerceIn(2, 120) * 1000L)
            goTo(+1)
        }
    }

    // -------------------------------------------------------- декодирование фото

    private fun decodePhoto(file: File): Bitmap? {
        return try {
            val dm = resources.displayMetrics
            val targetW = maxOf(dm.widthPixels, 1280)
            val targetH = maxOf(dm.heightPixels, 720)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            applyExifRotation(file, bmp)
        } catch (t: OutOfMemoryError) {
            null
        } catch (t: Throwable) {
            null
        }
    }

    private fun applyExifRotation(file: File, bmp: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                else -> return bmp
            }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            rotated
        } catch (t: Throwable) {
            bmp
        }
    }

    // ------------------------------------------------------------ пульт от ТВ

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val idle = b.idleView.visibility == View.VISIBLE
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (!idle) { handle(Command.Toggle); return true }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { handle(Command.Play); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { handle(Command.Pause); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { handle(Command.Stop); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (!idle) {
                    if (CastState.current()?.isVideo == true) handle(Command.SeekRelative(10_000))
                    else handle(Command.Next)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (!idle) {
                    if (CastState.current()?.isVideo == true) handle(Command.SeekRelative(-10_000))
                    else handle(Command.Prev)
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!idle) { handle(Command.Next); return true }
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_DPAD_UP -> {
                if (!idle) { handle(Command.Prev); return true }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (!idle) { handle(Command.Stop); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------- жизненный цикл

    override fun onStart() {
        super.onStart()
        MediaRepo.refresh()
        renderIdleInfo()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        slideshowJob?.cancel()
        photoJob?.cancel()
        player?.release()
        player = null
        b.playerView.player = null
        AirPlayBridge.listener = null
        airplayRenderer?.stop()
        airplayRenderer = null
        if (isFinishing) ServerService.stop(this)
        super.onDestroy()
    }
}
