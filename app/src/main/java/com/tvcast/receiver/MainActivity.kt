package com.tvcast.receiver

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.tvcast.receiver.airplay.AirPlayAudioRenderer
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

    // Two-layer photo crossfade: frontIsA tracks which ImageView currently
    // shows the active photo, so a new one can animate in on the other
    // layer instead of replacing the bitmap in place (which would just be
    // a fade-in from black, not an actual transition between photos).
    private var frontIsA = true
    private var photoAnimator: Animator? = null

    // Written from the UI thread (surface lifecycle / mirror state), read
    // from UxPlay's native callback threads (video and audio each get
    // their own) -- @Volatile so a freshly assigned renderer is visible
    // across threads without a full lock for what's just a reference swap.
    @Volatile private var airplayRenderer: AirPlayVideoRenderer? = null
    @Volatile private var airplayAudioRenderer: AirPlayAudioRenderer? = null
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
                airplayRenderer = AirPlayVideoRenderer(holder.surface) { w, h ->
                    runOnUiThread { resizeAirPlaySurface(w, h) }
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                airplayRenderer?.stop()
                airplayRenderer = null
            }
        })

        AirPlayBridge.listener = object : AirPlayBridge.Listener {
            // Called on UxPlay's native callback thread -- decode work stays
            // off the UI thread on purpose, both renderers are thread-safe.
            override fun onVideoFrame(data: ByteArray, isH265: Boolean, ntpTimeRemote: Long) {
                airplayRenderer?.feed(data, isH265)
            }

            override fun onAudioFrame(data: ByteArray, ct: Int, ntpTimeRemote: Long) {
                airplayAudioRenderer?.feed(data, ct)
            }

            override fun onMirrorStateChanged(running: Boolean) {
                if (running) {
                    airplayAudioRenderer = AirPlayAudioRenderer()
                } else {
                    airplayAudioRenderer?.stop()
                    airplayAudioRenderer = null
                }
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
        // Full screen until the first onVideoSize call letterboxes it to the
        // real aspect ratio -- avoids briefly showing a stale box sized from
        // a previous mirroring session.
        val lp = b.airplaySurface.layoutParams as FrameLayout.LayoutParams
        lp.width = FrameLayout.LayoutParams.MATCH_PARENT
        lp.height = FrameLayout.LayoutParams.MATCH_PARENT
        b.airplaySurface.layoutParams = lp
        b.airplaySurface.visibility = View.VISIBLE
    }

    /** Letterboxes the mirrored picture instead of stretching it to fill the (landscape) TV screen. */
    private fun resizeAirPlaySurface(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val containerW = b.root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val containerH = b.root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val videoAspect = videoWidth.toFloat() / videoHeight
        val containerAspect = containerW.toFloat() / containerH
        val targetW: Int
        val targetH: Int
        if (videoAspect > containerAspect) {
            targetW = containerW
            targetH = (containerW / videoAspect).toInt()
        } else {
            targetW = (containerH * videoAspect).toInt()
            targetH = containerH
        }
        val lp = b.airplaySurface.layoutParams as FrameLayout.LayoutParams
        lp.width = targetW
        lp.height = targetH
        lp.gravity = Gravity.CENTER
        b.airplaySurface.layoutParams = lp
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
            is Command.Transition -> CastState.transitionEffect.value = TransitionEffect.fromWire(cmd.effect)
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
            resetPhotoViews()
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
            photoJob?.cancel()
            photoJob = lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { decodePhoto(file) }
                if (CastState.currentId.value != id) return@launch
                if (bmp == null) {
                    toast("Не удалось открыть изображение")
                } else {
                    showPhotoWithTransition(bmp)
                }
            }
            restartSlideshow()
        }
    }

    /**
     * Crosses from whichever ImageView is currently the "front" layer to
     * the other one, which gets the new bitmap -- an actual transition
     * between the old and new photo, not just a fade-in from black (which
     * is all a single ImageView could ever show, since setImageBitmap()
     * replaces its content immediately).
     */
    private fun showPhotoWithTransition(bmp: Bitmap) {
        val front = if (frontIsA) b.photoView else b.photoViewB
        val back = if (frontIsA) b.photoViewB else b.photoView
        val oldBitmap = (front.drawable as? BitmapDrawable)?.bitmap

        photoAnimator?.cancel()
        photoAnimator = null
        front.animate().cancel()
        back.animate().cancel()
        back.alpha = 1f
        back.translationX = 0f
        back.scaleX = 1f
        back.scaleY = 1f
        back.setImageBitmap(bmp)
        back.visibility = View.VISIBLE

        val configured = CastState.transitionEffect.value
        val effect = if (configured == TransitionEffect.RANDOM) {
            listOf(TransitionEffect.FADE, TransitionEffect.KENBURNS, TransitionEffect.SLIDE).random()
        } else configured

        when (effect) {
            TransitionEffect.SLIDE -> {
                val w = b.root.width.takeIf { it > 0 }?.toFloat() ?: resources.displayMetrics.widthPixels.toFloat()
                back.translationX = w
                val interp = AccelerateDecelerateInterpolator()
                front.animate().translationX(-w).setDuration(500).setInterpolator(interp).start()
                back.animate().translationX(0f).setDuration(500).setInterpolator(interp)
                    .withEndAction { finishPhotoTransition(front, oldBitmap) }.start()
            }
            TransitionEffect.KENBURNS -> {
                back.alpha = 0f
                back.animate().alpha(1f).setDuration(600)
                    .withEndAction { finishPhotoTransition(front, oldBitmap) }.start()
                val durationMs = CastState.slideshowInterval.value.coerceIn(2, 120) * 1000L + 2000L
                val sx = ObjectAnimator.ofFloat(back, View.SCALE_X, 1f, 1.15f)
                val sy = ObjectAnimator.ofFloat(back, View.SCALE_Y, 1f, 1.15f)
                val set = AnimatorSet()
                set.playTogether(sx, sy)
                set.duration = durationMs
                set.interpolator = LinearInterpolator()
                set.start()
                photoAnimator = set
            }
            else -> { // FADE
                back.alpha = 0f
                back.animate().alpha(1f).setDuration(500)
                    .withEndAction { finishPhotoTransition(front, oldBitmap) }.start()
                front.animate().alpha(0f).setDuration(500).start()
            }
        }
        frontIsA = !frontIsA
    }

    private fun finishPhotoTransition(outgoing: ImageView, oldBitmap: Bitmap?) {
        outgoing.visibility = View.GONE
        outgoing.setImageDrawable(null)
        outgoing.alpha = 1f
        outgoing.translationX = 0f
        outgoing.scaleX = 1f
        outgoing.scaleY = 1f
        if (oldBitmap != null && !oldBitmap.isRecycled) oldBitmap.recycle()
    }

    /** Cancels any running transition and clears both photo layers, recycling their bitmaps. */
    private fun resetPhotoViews() {
        photoAnimator?.cancel()
        photoAnimator = null
        for (v in listOf(b.photoView, b.photoViewB)) {
            v.animate().cancel()
            val bmp = (v.drawable as? BitmapDrawable)?.bitmap
            v.setImageDrawable(null)
            v.visibility = View.GONE
            v.alpha = 1f
            v.translationX = 0f
            v.scaleX = 1f
            v.scaleY = 1f
            if (bmp != null && !bmp.isRecycled) bmp.recycle()
        }
        frontIsA = true
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
        resetPhotoViews()
        b.airplaySurface.visibility = View.GONE
        b.titleOverlay.visibility = View.GONE
        b.idleView.visibility = View.VISIBLE
        renderIdleInfo()
    }

    private fun renderIdleInfo() {
        val url = CastState.serverUrl.value
        // "192.168.1.27:8080" has no spaces or hyphens for the layout to break
        // on, so when it doesn't fit on one line it was wrapping at an
        // arbitrary character (mid-digit-group) instead of somewhere
        // readable. A zero-width space after the colon is the only break
        // point TextView will ever choose, so an unavoidable wrap lands
        // between host and port instead.
        b.urlText.text = if (url.isBlank()) {
            "нет сети"
        } else {
            val zeroWidthSpace = '\u200B'
            url.removePrefix("http://").replaceFirst(":", ":$zeroWidthSpace")
        }
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
        airplayAudioRenderer?.stop()
        airplayAudioRenderer = null
        if (isFinishing) ServerService.stop(this)
        super.onDestroy()
    }
}
