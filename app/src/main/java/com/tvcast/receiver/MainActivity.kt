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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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

    // Hybrid screensaver: after IDLE_TIMEOUT_MS with nothing happening on
    // the idle screen, either loop the photo library ambiently (if it has
    // any photos) or dim the idle screen to a blank black background --
    // both avoid burning the static QR/URL/PIN text into this TV's panel.
    private var idleTimeoutJob: Job? = null
    private var screensaverJob: Job? = null
    private var screensaverActive = false
    private var clockJob: Job? = null
    private var weatherJob: Job? = null
    private var clockMoveJob: Job? = null

    private var lastWeatherInfo: WeatherInfo? = null
    private var resolvedTimeZone: TimeZone? = null

    // Continuous slow movement across the screen so the clock/weather
    // overlay never sits still long enough to burn into the panel --
    // several selectable styles (CastState.clockMotionStyle), each a pure
    // function of elapsed time except "bounce" and "wander", which need
    // their own persistent velocity/target state.
    private var clockMotionStarted = false
    private var clockMotionStartNs = 0L

    private var bounceInited = false
    private var bouncePosX = 0f
    private var bouncePosY = 0f
    private var bounceVelX = 0f
    private var bounceVelY = 0f

    private var wanderInited = false
    private var wanderPosX = 0f
    private var wanderPosY = 0f
    private var wanderTargetX = 0f
    private var wanderTargetY = 0f
    private var wanderPaused = false
    private var wanderPauseUntilNs = 0L

    // Background "atmospheric" music: a second, independent ExoPlayer so it
    // never fights the main one over MediaItem/prepare() state, looping a
    // shuffled queue of the user's own tracks (see MusicRepo) at low volume
    // for as long as photos are on screen (manual slideshow or screensaver).
    private var musicPlayer: ExoPlayer? = null
    private var musicQueue: List<File> = emptyList()
    private var musicIdx = 0

    private var airplayScrimJob: Job? = null

    // Written from the UI thread (surface lifecycle / mirror state), read
    // from UxPlay's native callback threads (video and audio each get
    // their own) -- @Volatile so a freshly assigned renderer is visible
    // across threads without a full lock for what's just a reference swap.
    @Volatile private var airplayRenderer: AirPlayVideoRenderer? = null
    @Volatile private var airplayAudioRenderer: AirPlayAudioRenderer? = null
    private var mirroring = false

    companion object {
        private const val IDLE_TIMEOUT_MS = 3 * 60_000L
        private const val WEATHER_REFRESH_MS = 30 * 60_000L
        private const val AIRPLAY_WARMUP_MS = 1800L
        private const val WEATHER_RETRY_MS = 60_000L
        private const val WEATHER_POLL_MS = 1000L
        private const val CLOCK_TICK_MS = 33L
        private const val CLOCK_SPEED_DP_PER_SEC = 14f
        private const val CLOCK_EDGE_INSET_DP = 24f
        private const val CLOCK_ORBIT_PERIOD_SEC = 180f
        private const val CLOCK_LISSAJOUS_PERIOD_SEC = 240f
        private const val CLOCK_DRIFT_PERIOD_SEC = 45f
        private const val CLOCK_DRIFT_AMPLITUDE_DP = 28f
        private const val WANDER_PAUSE_MIN_SEC = 60f
        private const val WANDER_PAUSE_MAX_SEC = 180f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ServerService.start(this)
        MediaRepo.refresh()

        setupPlayer()
        setupAirPlay()
        showIdle()
        startAmbientUpdaters()

        b.tvHomeBtn.setOnClickListener {
            handle(Command.Stop)
            hideTvMenu()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { CastState.commands.collect { handle(it) } }
                launch { CastState.serverUrl.collect { renderIdleInfo() } }
                launch { CastState.lastError.collect { renderIdleInfo() } }
                launch { CastState.items.collect { renderIdleInfo() } }
                launch { CastState.connectedClients.collect { renderIdleInfo() } }
                launch { CastState.musicEnabled.collect { onMusicSettingChanged() } }
                launch { CastState.musicCategory.collect { onMusicSettingChanged() } }
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
        disarmIdleTimeout()
        stopBackgroundMusic()
        stopClockMotion()
        screensaverJob?.cancel()
        screensaverJob = null
        screensaverActive = false
        photoJob?.cancel()
        slideshowJob?.cancel()
        player?.pause()
        b.idleView.visibility = View.GONE
        b.ambientInfo.visibility = View.GONE
        b.screensaverAmbient.visibility = View.GONE
        b.playerView.visibility = View.GONE
        resetPhotoViews()
        b.titleOverlay.visibility = View.GONE
        // Full screen until the first onVideoSize call letterboxes it to the
        // real aspect ratio -- avoids briefly showing a stale box sized from
        // a previous mirroring session.
        val lp = b.airplaySurface.layoutParams as FrameLayout.LayoutParams
        lp.width = FrameLayout.LayoutParams.MATCH_PARENT
        lp.height = FrameLayout.LayoutParams.MATCH_PARENT
        b.airplaySurface.layoutParams = lp
        b.airplaySurface.visibility = View.VISIBLE

        // iOS's mirroring encoder can take a moment to ramp up when a
        // session starts, and whatever it sends before then can decode into
        // visible colour-block/noise corruption for the first frame or two
        // -- confirmed on the real TV: it clears up on its own after a
        // couple of seconds. Rather than try to detect "the first clean
        // frame" (MediaCodec has no notion of visual quality, so even a
        // successfully decoded frame could still be one of the bad ones),
        // just keep the surface covered for a fixed warm-up window.
        b.airplayScrim.alpha = 1f
        b.airplayScrim.visibility = View.VISIBLE
        airplayScrimJob?.cancel()
        airplayScrimJob = lifecycleScope.launch {
            delay(AIRPLAY_WARMUP_MS)
            b.airplayScrim.animate().alpha(0f).setDuration(300)
                .withEndAction { b.airplayScrim.visibility = View.GONE }.start()
        }
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
        if (screensaverActive) exitScreensaver()
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
        disarmIdleTimeout()
        val entry = MediaRepo.entryOf(id) ?: run {
            MediaRepo.refresh()
            MediaRepo.entryOf(id)
        } ?: return
        val file = MediaRepo.fileOf(id) ?: return

        CastState.currentId.value = id
        CastState.lastError.value = ""
        b.idleView.visibility = View.GONE
        b.ambientInfo.visibility = View.GONE
        b.airplaySurface.visibility = View.GONE
        val caption = entry.caption
        val hasCaption = CastState.captionsEnabled.value && caption.isNotBlank()
        showTitle(if (hasCaption) caption else entry.name, persistent = hasCaption)

        if (entry.isVideo) {
            stopBackgroundMusic()
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
            startBackgroundMusic()
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
        stopBackgroundMusic()
        screensaverJob?.cancel()
        screensaverJob = null
        screensaverActive = false
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
        airplayScrimJob?.cancel()
        airplayScrimJob = null
        b.airplayScrim.animate().cancel()
        b.airplayScrim.visibility = View.GONE
        b.titleOverlay.visibility = View.GONE
        b.idleView.animate().cancel()
        b.idleView.alpha = 1f
        b.idleView.visibility = View.VISIBLE
        b.ambientInfo.animate().cancel()
        b.ambientInfo.alpha = 1f
        b.ambientInfo.visibility = View.VISIBLE
        b.screensaverAmbient.animate().cancel()
        b.screensaverAmbient.alpha = 1f
        b.screensaverAmbient.visibility = View.GONE
        stopClockMotion()
        renderIdleInfo()
        armIdleTimeout()
    }

    // ------------------------------------------------------------ скринсейвер

    private fun armIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = lifecycleScope.launch {
            delay(IDLE_TIMEOUT_MS)
            startScreensaver()
        }
    }

    private fun disarmIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null
    }

    private fun startScreensaver() {
        if (screensaverActive || mirroring || CastState.currentId.value != null) return
        screensaverActive = true
        // Заставка по бездействию -- всегда просто чёрный экран с крупными
        // часами/датой/погодой (screensaverAmbient), независимо от того,
        // пуста библиотека или нет. Раньше при непустой библиотеке этот же
        // путь вместо этого молча запускал бесконечную ротацию фото на весь
        // экран -- пользователь это не включал и не просил; показ фото по
        // расписанию — отдельная, явно включаемая функция ("Слайдшоу" в
        // настройках, см. restartSlideshow()), к простою она не привязана.
        b.idleView.animate().alpha(0f).setDuration(1500).start()
        b.ambientInfo.animate().alpha(0f).setDuration(1500).start()
        renderWeatherViews(lastWeatherInfo)
        b.screensaverAmbient.alpha = 0f
        b.screensaverAmbient.translationX = 0f
        b.screensaverAmbient.translationY = 0f
        b.screensaverAmbient.visibility = View.VISIBLE
        b.screensaverAmbient.animate().alpha(1f).setDuration(1500).start()
        startClockMotion(b.screensaverAmbient)
        startBackgroundMusic()
    }

    private fun exitScreensaver() {
        if (!screensaverActive) return
        screensaverActive = false
        screensaverJob?.cancel()
        screensaverJob = null
        showIdle()
    }

    // ------------------------------------------------------- меню на ТВ

    /**
     * Полноэкранное меню, открываемое прямо с пульта (кнопка Menu, или ОК на
     * экране ожидания) -- позволяет выбрать любое конкретное фото/видео и
     * остановить показ/повтор экрана без необходимости брать телефон.
     */
    private fun showTvMenu() {
        if (screensaverActive) exitScreensaver()
        disarmIdleTimeout()
        populateTvMenuGrid()
        val active = CastState.currentId.value != null || mirroring
        b.tvHomeBtn.visibility = if (active) View.VISIBLE else View.GONE
        b.tvMenu.alpha = 0f
        b.tvMenu.visibility = View.VISIBLE
        b.tvMenu.animate().alpha(1f).setDuration(200).start()
        b.tvMenu.post {
            val target = if (active) b.tvHomeBtn else b.tvMenuGrid.getChildAt(0)
            (target ?: b.tvMenu).requestFocus()
        }
    }

    private fun hideTvMenu() {
        b.tvMenu.animate().alpha(0f).setDuration(150)
            .withEndAction { b.tvMenu.visibility = View.GONE }
            .start()
        if (b.idleView.visibility == View.VISIBLE) armIdleTimeout()
    }

    /** Newest-first thumbnail strip, same order as the phone's own gallery grid. */
    private fun populateTvMenuGrid() {
        val grid = b.tvMenuGrid
        grid.removeAllViews()
        val items = CastState.items.value.asReversed()
        b.tvMenuEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val density = resources.displayMetrics.density
        val tileSize = (160 * density).toInt()
        val margin = (8 * density).toInt()
        for (entry in items) {
            val img = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(tileSize, tileSize)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF1E242C.toInt())
            }
            val badge = android.widget.TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.BOTTOM or Gravity.START; setMargins(margin, 0, 0, margin) }
                text = if (entry.isVideo) "▶ видео" else "фото"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 11f
                setBackgroundColor(0xA6000000.toInt())
                setPadding(margin / 2, margin / 4, margin / 2, margin / 4)
            }
            val tile = FrameLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(tileSize, tileSize).apply {
                    marginEnd = margin * 2
                }
                isFocusable = true
                isFocusableInTouchMode = false
                foreground = androidx.core.content.ContextCompat.getDrawable(
                    this@MainActivity, R.drawable.tv_tile_focus_bg
                )
                addView(img)
                addView(badge)
                setOnClickListener {
                    handle(Command.Show(entry.id))
                    hideTvMenu()
                }
                // A second focusable delete button next to each tile would
                // make D-pad LEFT/RIGHT hop confusingly between a tile's
                // photo and its neighbor's trash icon (Android's focus
                // finder works purely on geometry, not intended grouping).
                // A long-press on the same OK button that already shows the
                // tile keeps one focusable target per tile and reuses the
                // exact gesture TV remotes already do for "more options".
                setOnLongClickListener {
                    confirmDeleteFromTvMenu(entry)
                    true
                }
            }
            grid.addView(tile)
            lifecycleScope.launch(Dispatchers.IO) {
                val bmp = MediaRepo.thumbnail(entry.id)?.let { BitmapFactory.decodeFile(it.path) }
                if (bmp != null) withContext(Dispatchers.Main) { img.setImageBitmap(bmp) }
            }
        }
    }

    private fun confirmDeleteFromTvMenu(entry: MediaEntry) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Удалить файл?")
            .setMessage(entry.name)
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    if (CastState.currentId.value == entry.id) handle(Command.Stop)
                    withContext(Dispatchers.IO) { MediaRepo.delete(entry.id) }
                    populateTvMenuGrid()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // -------------------------------------------------------- фоновая музыка

    private fun startBackgroundMusic() {
        if (!CastState.musicEnabled.value) return
        val tracks = MusicRepo.list(CastState.musicCategory.value)
        if (tracks.isEmpty()) return
        if (musicPlayer == null) {
            musicPlayer = ExoPlayer.Builder(this).build().apply {
                volume = 0.35f
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) playNextMusicTrack()
                    }
                })
            }
        }
        musicQueue = tracks.shuffled()
        musicIdx = 0
        playCurrentMusicTrack()
    }

    private fun playCurrentMusicTrack() {
        val f = musicQueue.getOrNull(musicIdx) ?: return
        musicPlayer?.apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(f)))
            prepare()
            play()
        }
    }

    private fun playNextMusicTrack() {
        if (musicQueue.isEmpty()) return
        musicIdx = (musicIdx + 1) % musicQueue.size
        playCurrentMusicTrack()
    }

    private fun stopBackgroundMusic() {
        musicPlayer?.pause()
        musicPlayer?.clearMediaItems()
    }

    /** Reacts to the on/off toggle or category change arriving live from the phone. */
    private fun onMusicSettingChanged() {
        if (!CastState.musicEnabled.value) { stopBackgroundMusic(); return }
        val photoActive = (CastState.currentId.value != null && CastState.current()?.isVideo == false) ||
            screensaverActive
        if (photoActive) startBackgroundMusic()
    }

    // ------------------------------------------------------- часы и погода

    private fun startAmbientUpdaters() {
        clockJob?.cancel()
        clockJob = lifecycleScope.launch {
            // TextView.setTextSize() unconditionally calls requestLayout()
            // internally, even when the new value equals the old one --
            // calling it every second regardless of whether the setting
            // actually changed was forcing a full relayout of the
            // screensaver's centered clock/date/weather column every tick.
            // That's what was making the date (and, once weather started
            // working, the forecast row too) visibly twitch: floating-point
            // text measurement isn't perfectly bit-identical across repeated
            // layout passes, so gravity="center_horizontal" kept
            // re-centering everything under it by a sub-pixel amount each
            // second. Tracking what was last actually applied and skipping
            // the call when nothing changed removes the forced relayout
            // entirely for the (overwhelmingly common) case where the user
            // hasn't touched the setting since the previous tick.
            var lastAppliedSize = -1
            var lastAppliedColor = ""
            while (true) {
                // The device's absolute clock (System.currentTimeMillis()) is
                // trustworthy -- Android syncs it over the network regardless
                // of the *timezone* the TV was set up with. It's the
                // timezone that can be wrong (installer picked the wrong
                // one, or left a default), which is what caused the earlier
                // "2 hours off" report. So: real device time, but rendered in
                // the timezone resolved from the TV's own IP address
                // (resolvedTimeZone, refreshed by weatherJob below), falling
                // back to the device's own timezone only if that lookup
                // hasn't succeeded yet.
                val nowMs = System.currentTimeMillis()
                val tz = resolvedTimeZone ?: TimeZone.getDefault()
                val clockText = formatClock(nowMs, CastState.clockStyle.value, tz)
                setTextIfChanged(b.clockText, clockText)
                // The idle-corner clock's size is fixed (matches the XML
                // default) and no longer user-configurable, so there's
                // nothing to re-apply here every tick any more, only the
                // anchor.
                if (clockMoveJob == null) parkAmbientInfo()
                // Color, like size, is meant to style the big screensaver
                // clock the user actually asked to customize -- the small
                // idle-corner clock stays its fixed XML white instead of
                // also reacting to this setting.
                setTextIfChanged(b.ssClockText, clockText)
                val fontSize = CastState.clockFontSize.value
                if (fontSize != lastAppliedSize) {
                    b.ssClockText.textSize = fontSize.toFloat()
                    lastAppliedSize = fontSize
                }
                val color = CastState.clockColor.value
                if (color != lastAppliedColor) {
                    b.ssClockText.setTextColor(parseClockColor(color))
                    lastAppliedColor = color
                }
                // Re-setting a TextView's text to an equal-content but new
                // String instance every second (formatDate() always returns
                // a fresh String) was making a specific letter pair visibly
                // jitter -- some system fonts' shaping/kerning isn't fully
                // deterministic across repeated layout passes for certain
                // pairs. Skipping the redundant set (the date column, unlike
                // the clock, only actually changes once a day) fixes that
                // and avoids 86400 pointless re-layouts a day besides.
                setTextIfChanged(b.ssDateText, formatDate(nowMs, tz))
                delay(1000L)
            }
        }
        weatherJob?.cancel()
        weatherJob = lifecycleScope.launch {
            while (true) {
                var targetDelay: Long
                if (!CastState.weatherEnabled.value) {
                    lastWeatherInfo = null
                    renderWeatherViews(null)
                    targetDelay = WEATHER_RETRY_MS
                } else {
                    val info = WeatherProvider.fetch(CastState.weatherForecastDays.value)
                    lastWeatherInfo = info
                    info?.timeZoneId?.let { id ->
                        try { resolvedTimeZone = TimeZone.getTimeZone(id) } catch (t: Throwable) { /* keep previous */ }
                    }
                    renderWeatherViews(info)
                    // A transient failure right at boot (Wi-Fi/DNS not fully
                    // up yet) would otherwise mean no weather for a full 30
                    // minutes -- retry much sooner instead.
                    targetDelay = if (info != null) WEATHER_REFRESH_MS else WEATHER_RETRY_MS
                }
                // Waiting out the full delay with a single delay() call
                // meant flipping the weather toggle or switching "today" to
                // "week" could silently take up to 30 minutes to actually
                // take effect -- the setting was saved immediately, but
                // nothing re-fetched until the current wait finished, which
                // read as "the week forecast only shows one day". Polling in
                // short slices and breaking out the moment either setting
                // changes makes it react within about a second instead.
                val enabledAtStart = CastState.weatherEnabled.value
                val daysAtStart = CastState.weatherForecastDays.value
                var waited = 0L
                while (waited < targetDelay) {
                    delay(WEATHER_POLL_MS)
                    waited += WEATHER_POLL_MS
                    if (CastState.weatherEnabled.value != enabledAtStart ||
                        CastState.weatherForecastDays.value != daysAtStart
                    ) break
                }
            }
        }
    }

    private fun renderWeatherViews(info: WeatherInfo?) {
        if (info == null || !CastState.weatherEnabled.value) {
            b.weatherText.visibility = View.GONE
            b.ssWeatherRow.visibility = View.GONE
            return
        }
        val temp = "${info.tempC.roundToInt()}°"
        b.weatherText.text = "${info.emoji} $temp  ${info.city}"
        b.weatherText.visibility = View.VISIBLE
        b.ssWeatherEmoji.text = info.emoji
        b.ssWeatherTemp.text = temp
        b.ssWeatherCity.text = info.city
        renderForecastColumns(info.forecast.drop(1))
        b.ssWeatherRow.visibility = View.VISIBLE
    }

    /** One column per day: day-of-week on top, icon, day (max) temp, then night (min) temp at the bottom. */
    private fun renderForecastColumns(days: List<DayForecast>) {
        val containers = listOf(b.fc1Col, b.fc2Col, b.fc3Col, b.fc4Col, b.fc5Col, b.fc6Col)
        val dayViews = listOf(b.fc1Day, b.fc2Day, b.fc3Day, b.fc4Day, b.fc5Day, b.fc6Day)
        val iconViews = listOf(b.fc1Icon, b.fc2Icon, b.fc3Icon, b.fc4Icon, b.fc5Icon, b.fc6Icon)
        val maxViews = listOf(b.fc1Max, b.fc2Max, b.fc3Max, b.fc4Max, b.fc5Max, b.fc6Max)
        val minViews = listOf(b.fc1Min, b.fc2Min, b.fc3Min, b.fc4Min, b.fc5Min, b.fc6Min)
        b.ssForecastRow.visibility = if (days.isEmpty()) View.GONE else View.VISIBLE
        for (i in containers.indices) {
            val day = days.getOrNull(i)
            containers[i].visibility = if (day == null) View.GONE else View.VISIBLE
            if (day != null) {
                dayViews[i].text = day.label
                iconViews[i].text = day.emoji
                maxViews[i].text = "${day.maxC}°"
                minViews[i].text = "${day.minC}°"
            }
        }
    }

    private fun formatDate(epochMs: Long, tz: TimeZone): String {
        val fmt = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
        fmt.timeZone = tz
        val text = fmt.format(Date(epochMs))
        return text.replaceFirstChar { it.titlecase(Locale("ru")) }
    }

    /** Skips the redundant setText() call entirely when the value hasn't actually changed. */
    private fun setTextIfChanged(view: android.widget.TextView, text: String) {
        if (view.text.toString() != text) view.text = text
    }

    /**
     * The overlay only travels around the screen while the photo-loop
     * screensaver is showing it over long stretches of rotating photos --
     * on the plain idle screen it stays parked (see [stopClockMotion]) so
     * it never drifts on top of the QR/URL/PIN text there.
     */
    private fun startClockMotion(target: View) {
        clockMoveJob?.cancel()
        clockMotionStarted = false
        bounceInited = false
        wanderInited = false
        clockMoveJob = lifecycleScope.launch {
            val density = resources.displayMetrics.density
            val speedPx = CLOCK_SPEED_DP_PER_SEC * density
            val insetPx = CLOCK_EDGE_INSET_DP * density
            val driftAmpPx = CLOCK_DRIFT_AMPLITUDE_DP * density
            var lastTickNs = System.nanoTime()
            while (true) {
                delay(CLOCK_TICK_MS)
                val nowNs = System.nanoTime()
                val dtSec = (nowNs - lastTickNs) / 1_000_000_000f
                lastTickNs = nowNs

                val rootW = b.root.width
                val rootH = b.root.height
                val viewW = target.width
                val viewH = target.height
                // Not laid out yet (e.g. right at startup) -- try again next tick.
                if (rootW == 0 || rootH == 0 || viewW == 0 || viewH == 0) continue

                val minX = insetPx
                val minY = insetPx
                val maxX = (rootW - viewW - insetPx).coerceAtLeast(minX)
                val maxY = (rootH - viewH - insetPx).coerceAtLeast(minY)

                if (!clockMotionStarted) {
                    clockMotionStarted = true
                    clockMotionStartNs = nowNs
                }
                val tSec = (nowNs - clockMotionStartNs) / 1_000_000_000f

                val (x, y) = when (CastState.clockMotionStyle.value) {
                    // A constant, however slow, creep across the screen was
                    // reported as visible trembling once the screensaver
                    // block grew large (weather + the 6-day forecast row) --
                    // giving a genuinely static option settles that instead
                    // of continuing to guess at motion speeds/styles.
                    "none" -> maxX to minY
                    "orbit" -> orbitPosition(tSec, minX, maxX, minY, maxY)
                    "lissajous" -> lissajousPosition(tSec, minX, maxX, minY, maxY)
                    "drift" -> driftPosition(tSec, minX, maxX, minY, maxY, driftAmpPx)
                    "wander" -> wanderPosition(dtSec, nowNs, speedPx, minX, maxX, minY, maxY)
                    else -> bouncePosition(dtSec, speedPx, minX, maxX, minY, maxY)
                }
                target.translationX = x
                target.translationY = y
            }
        }
    }

    /** Stops the travel and returns the overlay to its fixed top-right idle-screen spot. */
    private fun stopClockMotion() {
        clockMoveJob?.cancel()
        clockMoveJob = null
        bounceInited = false
        wanderInited = false
        clockMotionStarted = false
        parkAmbientInfo()
    }

    /**
     * Anchors ambientInfo's top-right corner to the screen's top-right corner
     * (with CLOCK_EDGE_INSET_DP of padding). Not a one-shot: called again on
     * every clock tick while parked (see startAmbientUpdaters()) because the
     * view's own width can change under it -- a translationX computed for an
     * old, narrower width would leave the right edge hanging past the screen
     * edge once the view grows, which is exactly what a stale single call
     * looked like.
     */
    private fun parkAmbientInfo() {
        b.ambientInfo.post {
            val density = resources.displayMetrics.density
            val insetPx = CLOCK_EDGE_INSET_DP * density
            val rootW = b.root.width
            val viewW = b.ambientInfo.width
            b.ambientInfo.translationX = if (rootW > 0 && viewW > 0) {
                (rootW - viewW - insetPx).coerceAtLeast(insetPx)
            } else {
                0f
            }
            b.ambientInfo.translationY = insetPx
        }
    }

    private fun bouncePosition(
        dtSec: Float,
        speedPx: Float,
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float
    ): Pair<Float, Float> {
        if (!bounceInited) {
            bounceInited = true
            bouncePosX = maxX // starts top-right, matching the original fixed position
            bouncePosY = minY
            bounceVelX = -speedPx
            bounceVelY = speedPx * 0.6f
        }
        bouncePosX += bounceVelX * dtSec
        bouncePosY += bounceVelY * dtSec
        if (bouncePosX < minX) { bouncePosX = minX; bounceVelX = -bounceVelX }
        if (bouncePosX > maxX) { bouncePosX = maxX; bounceVelX = -bounceVelX }
        if (bouncePosY < minY) { bouncePosY = minY; bounceVelY = -bounceVelY }
        if (bouncePosY > maxY) { bouncePosY = maxY; bounceVelY = -bounceVelY }
        return bouncePosX to bouncePosY
    }

    private fun orbitPosition(tSec: Float, minX: Float, maxX: Float, minY: Float, maxY: Float): Pair<Float, Float> {
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f
        val radiusX = (maxX - minX) / 2f
        val radiusY = (maxY - minY) / 2f
        val theta = (tSec / CLOCK_ORBIT_PERIOD_SEC) * (2f * Math.PI).toFloat()
        return (centerX + radiusX * cos(theta)) to (centerY + radiusY * sin(theta))
    }

    /** Sin(theta)/sin(2*theta) traces a classic figure-8 (infinity symbol). */
    private fun lissajousPosition(tSec: Float, minX: Float, maxX: Float, minY: Float, maxY: Float): Pair<Float, Float> {
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f
        val radiusX = (maxX - minX) / 2f
        val radiusY = (maxY - minY) / 2f
        val theta = (tSec / CLOCK_LISSAJOUS_PERIOD_SEC) * (2f * Math.PI).toFloat()
        return (centerX + radiusX * sin(theta)) to (centerY + radiusY * sin(2f * theta))
    }

    /** Small, slow wobble around the original top-right resting spot -- barely noticeable. */
    private fun driftPosition(
        tSec: Float,
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float,
        ampPx: Float
    ): Pair<Float, Float> {
        val anchorX = maxX
        val anchorY = minY
        val theta = (tSec / CLOCK_DRIFT_PERIOD_SEC) * (2f * Math.PI).toFloat()
        val x = (anchorX + ampPx * cos(theta)).coerceIn(minX, maxX)
        val y = (anchorY + ampPx * sin(theta) * 0.6f).coerceIn(minY, maxY)
        return x to y
    }

    private fun wanderPosition(
        dtSec: Float,
        nowNs: Long,
        speedPx: Float,
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float
    ): Pair<Float, Float> {
        if (!wanderInited) {
            wanderInited = true
            wanderPosX = maxX
            wanderPosY = minY
            pickNewWanderTarget(minX, maxX, minY, maxY)
        }
        if (wanderPaused) {
            if (nowNs >= wanderPauseUntilNs) {
                wanderPaused = false
                pickNewWanderTarget(minX, maxX, minY, maxY)
            }
            return wanderPosX to wanderPosY
        }
        val dx = wanderTargetX - wanderPosX
        val dy = wanderTargetY - wanderPosY
        val dist = sqrt(dx * dx + dy * dy)
        val step = speedPx * dtSec
        if (dist <= step || dist < 1f) {
            wanderPosX = wanderTargetX
            wanderPosY = wanderTargetY
            wanderPaused = true
            val pauseSec = WANDER_PAUSE_MIN_SEC + Math.random().toFloat() * (WANDER_PAUSE_MAX_SEC - WANDER_PAUSE_MIN_SEC)
            wanderPauseUntilNs = nowNs + (pauseSec * 1_000_000_000L).toLong()
        } else {
            wanderPosX += dx / dist * step
            wanderPosY += dy / dist * step
        }
        return wanderPosX to wanderPosY
    }

    private fun pickNewWanderTarget(minX: Float, maxX: Float, minY: Float, maxY: Float) {
        wanderTargetX = minX + Math.random().toFloat() * (maxX - minX)
        wanderTargetY = minY + Math.random().toFloat() * (maxY - minY)
    }

    private fun formatClock(epochMs: Long, style: String, tz: TimeZone): String {
        val pattern = when (style) {
            "digital12" -> "hh:mm a"
            "seconds" -> "HH:mm:ss"
            else -> "HH:mm"
        }
        val fmt = SimpleDateFormat(pattern, Locale.getDefault())
        fmt.timeZone = tz
        return fmt.format(Date(epochMs))
    }

    private fun parseClockColor(hex: String): Int = try {
        android.graphics.Color.parseColor(hex)
    } catch (t: Throwable) {
        android.graphics.Color.WHITE
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
        b.pinText.text = PinAuth.pin
        val err = CastState.lastError.value
        // The idle screen used to look identical whether a phone was
        // connected or not -- same QR/PIN prompt either way -- which is
        // exactly what read as "unclear whether the connection actually
        // went through". Showing the live client count here makes a
        // successful connection visible without touching the phone again.
        val connected = CastState.connectedClients.value > 0
        b.statusText.text = when {
            err.isNotBlank() -> err
            connected && CastState.items.value.isEmpty() ->
                "📱 Телефон подключён — отправьте первое фото или видео."
            connected ->
                "📱 Телефон подключён · файлов на телевизоре: ${CastState.items.value.size}"
            CastState.items.value.isEmpty() -> "Файлов пока нет — отправьте первые с телефона."
            else -> "Файлов на телевизоре: ${CastState.items.value.size}"
        }
        val qrBmp = if (url.isBlank()) null else QrGen.make(url, 600)
        if (qrBmp != null) b.qrView.setImageBitmap(qrBmp) else b.qrView.setImageDrawable(null)
    }

    /**
     * @param persistent Filenames flash briefly, same as always -- but a
     * caption is content someone deliberately wrote to go with this photo,
     * not incidental metadata, and a 2.9-second flash was too easy to miss
     * entirely (looking at the phone while uploading, not the TV) for
     * anyone to reliably confirm it actually applied. Kept on screen for as
     * long as this photo is, instead.
     */
    private fun showTitle(name: String, persistent: Boolean = false) {
        b.titleOverlay.animate().cancel()
        b.titleOverlay.text = name
        b.titleOverlay.visibility = View.VISIBLE
        b.titleOverlay.alpha = 1f
        if (!persistent) {
            b.titleOverlay.animate().setStartDelay(2500).alpha(0f).setDuration(400)
                .withEndAction { b.titleOverlay.visibility = View.GONE }.start()
        }
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
        // Меню на ТВ открыто -- отдаём стрелки/ОК стандартной навигации
        // фокуса Android (меню собрано из обычных View, этого достаточно),
        // и сами обрабатываем только "Назад", чтобы закрыть его.
        if (b.tvMenu.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { hideTvMenu(); return true }
            return super.onKeyDown(keyCode, event)
        }
        if (screensaverActive) {
            exitScreensaver()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showTvMenu()
            return true
        }
        val idle = b.idleView.visibility == View.VISIBLE
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // На экране ожидания ОК раньше не делал вообще ничего --
                // теперь открывает галерею, чтобы выбрать конкретное фото
                // с пульта без телефона.
                if (!idle) handle(Command.Toggle) else showTvMenu()
                return true
            }
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
        idleTimeoutJob?.cancel()
        screensaverJob?.cancel()
        clockJob?.cancel()
        weatherJob?.cancel()
        clockMoveJob?.cancel()
        airplayScrimJob?.cancel()
        player?.release()
        player = null
        musicPlayer?.release()
        musicPlayer = null
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
