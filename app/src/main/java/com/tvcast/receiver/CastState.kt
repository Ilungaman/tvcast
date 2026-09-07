package com.tvcast.receiver

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Один элемент библиотеки на телевизоре. */
data class MediaEntry(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val addedAt: Long,
    val caption: String = ""
) {
    /** Всё, что не картинка, отдаём в ExoPlayer — он сам определит контейнер. */
    val isVideo: Boolean get() = !mime.startsWith("image/")
}

/** Команды, которые приходят с iPhone и исполняются на экране телевизора. */
sealed class Command {
    data class Show(val id: String) : Command()
    data object Play : Command()
    data object Pause : Command()
    data object Toggle : Command()
    data object Stop : Command()
    data class Seek(val positionMs: Long) : Command()
    data class SeekRelative(val deltaMs: Long) : Command()
    data object Next : Command()
    data object Prev : Command()
    data class Slideshow(val on: Boolean, val intervalSec: Int) : Command()
    data class Mute(val on: Boolean) : Command()
    data class RepeatOne(val on: Boolean) : Command()
    data class Transition(val effect: String) : Command()
    data class Notice(val text: String) : Command()
}

/** Эффект смены фото. RANDOM выбирает один из остальных заново на каждом показе. */
enum class TransitionEffect {
    FADE, KENBURNS, SLIDE, RANDOM;

    companion object {
        fun fromWire(s: String): TransitionEffect = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: FADE
    }
}

/**
 * Общее состояние между Ktor-сервером (фоновый сервис) и экраном воспроизведения.
 * Простой синглтон на StateFlow — сервису и Activity этого достаточно.
 */
object CastState {
    val items = MutableStateFlow<List<MediaEntry>>(emptyList())
    val currentId = MutableStateFlow<String?>(null)
    val isPlaying = MutableStateFlow(false)
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val slideshowOn = MutableStateFlow(false)
    val slideshowInterval = MutableStateFlow(6)
    val transitionEffect = MutableStateFlow(TransitionEffect.FADE)
    /** "off" | "days" | "count" -- see MediaRepo.applyAutoCleanup(). */
    val autoCleanupMode = MutableStateFlow("off")
    val autoCleanupValue = MutableStateFlow(30)
    /** Off by default -- when on, the upload page offers a "from whom" caption field. */
    val captionsEnabled = MutableStateFlow(false)
    /** Off by default -- when on, a category is picked and background music loops during slideshow. */
    val musicEnabled = MutableStateFlow(false)
    val musicCategory = MutableStateFlow("calm")
    /** "digital24" | "digital12" | "seconds" -- see MainActivity.formatClock(). */
    val clockStyle = MutableStateFlow("digital24")
    val clockFontSize = MutableStateFlow(28)
    val clockColor = MutableStateFlow("#FFFFFF")
    /** "bounce" | "orbit" | "wander" | "drift" | "lissajous" -- see MainActivity's clock motion functions. */
    val clockMotionStyle = MutableStateFlow("bounce")
    /** On by default -- shows city/temperature on the idle and screensaver screens. */
    val weatherEnabled = MutableStateFlow(true)
    /** "1" | "7" -- how many days ahead the forecast row on screen covers. */
    val weatherForecastDays = MutableStateFlow(1)
    val muted = MutableStateFlow(false)
    val repeatOne = MutableStateFlow(false)
    val serverUrl = MutableStateFlow("")
    val lastError = MutableStateFlow("")

    /** Сервер -> экран. */
    val commands = MutableSharedFlow<Command>(extraBufferCapacity = 64)

    fun current(): MediaEntry? = items.value.firstOrNull { it.id == currentId.value }

    fun indexOfCurrent(): Int = items.value.indexOfFirst { it.id == currentId.value }
}
