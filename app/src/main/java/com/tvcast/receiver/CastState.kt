package com.tvcast.receiver

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Один элемент библиотеки на телевизоре. */
data class MediaEntry(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val addedAt: Long
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
    data class Notice(val text: String) : Command()
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
    val muted = MutableStateFlow(false)
    val repeatOne = MutableStateFlow(false)
    val serverUrl = MutableStateFlow("")
    val lastError = MutableStateFlow("")

    /** Сервер -> экран. */
    val commands = MutableSharedFlow<Command>(extraBufferCapacity = 64)

    fun current(): MediaEntry? = items.value.firstOrNull { it.id == currentId.value }

    fun indexOfCurrent(): Int = items.value.indexOfFirst { it.id == currentId.value }
}
