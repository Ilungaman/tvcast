package com.tvcast.receiver

import android.content.Context
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Локальный HTTP + WebSocket сервер, который поднимается на телевизоре.
 *
 * iPhone открывает http://<ip телевизора>:8080 в Safari и получает страницу
 * с выбором фото/видео, загрузкой и пультом управления.
 */
class WebServer(private val context: Context, private val port: Int = PORT) {

    private var engine: ApplicationEngine? = null

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0", configure = {
            connectionIdleTimeoutSeconds = 600
        }) {
            install(WebSockets)
            install(PartialContent)
            install(AutoHeadResponse)

            routing {

                get("/") { call.respondAsset("web/index.html", ContentType.Text.Html) }
                get("/app.css") { call.respondAsset("web/app.css", ContentType.Text.CSS) }
                get("/app.js") { call.respondAsset("web/app.js", ContentType.Text.JavaScript) }
                get("/health") { call.respondText("ok") }

                // ---- вход по PIN-коду (PIN показан на экране телевизора) ----
                post("/api/auth") {
                    val body = runCatching { JSONObject(call.receiveText()) }.getOrNull()
                    val pin = body?.optString("pin").orEmpty()
                    if (pin.isNotEmpty() && pin == PinAuth.pin) {
                        call.grantAuth()
                        call.respond(HttpStatusCode.OK)
                    } else {
                        call.respond(HttpStatusCode.Unauthorized)
                    }
                }

                // ---- состояние ----
                get("/api/state") {
                    if (!call.isAuthorized()) { call.respond(HttpStatusCode.Unauthorized); return@get }
                    call.respondText(stateJson().toString(), ContentType.Application.Json)
                }

                // ---- команды пульта ----
                post("/api/cmd") {
                    if (!call.isAuthorized()) { call.respond(HttpStatusCode.Unauthorized); return@post }
                    val body = runCatching { JSONObject(call.receiveText()) }.getOrNull()
                    if (body == null) {
                        call.respond(HttpStatusCode.BadRequest, "bad json")
                        return@post
                    }
                    handleCommand(body)
                    call.respondText(stateJson().toString(), ContentType.Application.Json)
                }

                // ---- загрузка файлов с iPhone ----
                post("/api/upload") {
                    if (!call.isAuthorized()) { call.respond(HttpStatusCode.Unauthorized); return@post }
                    val saved = JSONArray()
                    // The web client appends the "caption" form field before the
                    // "file" field in the same request, so by the time forEachPart
                    // reaches the FileItem, caption already holds this batch's text
                    // (blank if the caption feature is off or left empty).
                    var caption = ""
                    try {
                        val multipart = call.receiveMultipart()
                        multipart.forEachPart { part ->
                            when (part) {
                                is PartData.FormItem -> {
                                    if (part.name == "caption") caption = part.value
                                }
                                is PartData.FileItem -> {
                                    val name = part.originalFileName ?: "file"
                                    val entry = withContext(Dispatchers.IO) {
                                        part.streamProvider().use { MediaRepo.save(name, it, caption) }
                                    }
                                    saved.put(entry.id)
                                }
                                else -> {}
                            }
                            part.dispose()
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "upload failed", t)
                        call.respond(HttpStatusCode.InternalServerError, t.message ?: "upload error")
                        return@post
                    }
                    val autoShow = call.request.queryParameters["show"] != "0"
                    if (autoShow && saved.length() > 0) {
                        CastState.commands.tryEmit(Command.Show(saved.getString(0)))
                    }
                    call.respondText(
                        JSONObject().put("saved", saved).put("state", stateJson()).toString(),
                        ContentType.Application.Json
                    )
                }

                // ---- загрузка фоновой музыки (по категориям) ----
                post("/api/music") {
                    if (!call.isAuthorized()) { call.respond(HttpStatusCode.Unauthorized); return@post }
                    var category = "calm"
                    try {
                        val multipart = call.receiveMultipart()
                        multipart.forEachPart { part ->
                            when (part) {
                                is PartData.FormItem -> {
                                    if (part.name == "category") category = part.value
                                }
                                is PartData.FileItem -> {
                                    val name = part.originalFileName ?: "track"
                                    withContext(Dispatchers.IO) {
                                        part.streamProvider().use { MusicRepo.save(category, name, it) }
                                    }
                                }
                                else -> {}
                            }
                            part.dispose()
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "music upload failed", t)
                        call.respond(HttpStatusCode.InternalServerError, t.message ?: "upload error")
                        return@post
                    }
                    call.respondText(stateJson().toString(), ContentType.Application.Json)
                }

                delete("/api/music/{category}/{id}") {
                    if (!call.isAuthorized()) { call.respond(HttpStatusCode.Unauthorized); return@delete }
                    val category = call.parameters["category"].orEmpty()
                    val id = call.parameters["id"].orEmpty()
                    MusicRepo.delete(category, id)
                    call.respondText(stateJson().toString(), ContentType.Application.Json)
                }

                // ---- отдача файла с поддержкой Range (206 Partial Content) ----
                get("/media/{id}") {
                    if (!call.isAuthorized()) { call.respond(HttpStatusCode.Unauthorized); return@get }
                    val id = call.parameters["id"].orEmpty()
                    val file: File? = MediaRepo.fileOf(id)
                    if (file == null) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        val ct = ContentType.parse(MediaRepo.mimeOf(id))
                        call.respond(LocalFileContent(file, ct))
                    }
                }

                get("/thumb/{id}") {
                    if (!call.isAuthorized()) { call.respond(HttpStatusCode.Unauthorized); return@get }
                    val id = call.parameters["id"].orEmpty()
                    val thumb = withContext(Dispatchers.IO) { MediaRepo.thumbnail(id) }
                    if (thumb == null) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        call.respond(LocalFileContent(thumb, ContentType.Image.JPEG))
                    }
                }

                delete("/api/media/{id}") {
                    if (!call.isAuthorized()) { call.respond(HttpStatusCode.Unauthorized); return@delete }
                    val id = call.parameters["id"].orEmpty()
                    if (CastState.currentId.value == id) CastState.commands.tryEmit(Command.Stop)
                    withContext(Dispatchers.IO) { MediaRepo.delete(id) }
                    call.respondText(stateJson().toString(), ContentType.Application.Json)
                }

                // ---- живое состояние + команды по WebSocket ----
                webSocket("/ws") {
                    if (!call.isAuthorized()) { close(); return@webSocket }
                    val pusher = launch {
                        while (isActive) {
                            runCatching { send(Frame.Text(stateJson().toString())) }
                                .onFailure { return@launch }
                            kotlinx.coroutines.delay(if (CastState.isPlaying.value) 600L else 1400L)
                        }
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val obj = runCatching { JSONObject(frame.readText()) }.getOrNull() ?: continue
                                handleCommand(obj)
                                runCatching { send(Frame.Text(stateJson().toString())) }
                            }
                        }
                    } catch (_: Throwable) {
                    } finally {
                        pusher.cancel()
                    }
                }
            }
        }.also { it.start(wait = false) }
        Log.i(TAG, "HTTP сервер запущен на порту $port")
    }

    fun stop() {
        runCatching { engine?.stop(500L, 1500L) }
        engine = null
    }

    private suspend fun handleCommand(body: JSONObject) {
        when (body.optString("action")) {
            "show" -> CastState.commands.emit(Command.Show(body.optString("id")))
            "play" -> CastState.commands.emit(Command.Play)
            "pause" -> CastState.commands.emit(Command.Pause)
            "toggle" -> CastState.commands.emit(Command.Toggle)
            "stop" -> CastState.commands.emit(Command.Stop)
            "seek" -> CastState.commands.emit(Command.Seek(body.optLong("position")))
            "seekRel" -> CastState.commands.emit(Command.SeekRelative(body.optLong("delta")))
            "next" -> CastState.commands.emit(Command.Next)
            "prev" -> CastState.commands.emit(Command.Prev)
            "slideshow" -> CastState.commands.emit(
                Command.Slideshow(body.optBoolean("on"), body.optInt("interval", 6).coerceIn(2, 120))
            )
            "mute" -> CastState.commands.emit(Command.Mute(body.optBoolean("on")))
            "repeat" -> CastState.commands.emit(Command.RepeatOne(body.optBoolean("on")))
            "transition" -> CastState.commands.emit(Command.Transition(body.optString("effect", "fade")))
            "cleanup" -> {
                // Direct state mutation, not routed through the commands flow like
                // most actions: MediaRepo.refresh() right below needs the new
                // values immediately, and going through MainActivity's async
                // collector first would race it -- refresh() could run against
                // the still-old settings.
                CastState.autoCleanupMode.value = body.optString("mode", "off")
                CastState.autoCleanupValue.value = body.optInt("value", 30).coerceAtLeast(1)
                withContext(Dispatchers.IO) { MediaRepo.refresh() }
            }
            "captions" -> CastState.captionsEnabled.value = body.optBoolean("on")
            "music" -> {
                CastState.musicEnabled.value = body.optBoolean("on")
                CastState.musicCategory.value = body.optString("category", "calm")
            }
            "delete" -> {
                val id = body.optString("id")
                if (CastState.currentId.value == id) CastState.commands.emit(Command.Stop)
                withContext(Dispatchers.IO) { MediaRepo.delete(id) }
            }
            "clear" -> {
                CastState.commands.emit(Command.Stop)
                withContext(Dispatchers.IO) { MediaRepo.deleteAll() }
            }
            "refresh" -> withContext(Dispatchers.IO) { MediaRepo.refresh() }
        }
    }

    private fun stateJson(): JSONObject {
        val musicTracks = JSONObject()
        for (cat in MusicRepo.CATEGORIES) {
            val tracks = JSONArray()
            for (f in MusicRepo.list(cat)) {
                tracks.put(JSONObject().put("id", f.name).put("name", f.name.substringAfter('~')))
            }
            musicTracks.put(cat, tracks)
        }
        val arr = JSONArray()
        for (e in CastState.items.value) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("name", e.name)
                    .put("mime", e.mime)
                    .put("size", e.size)
                    .put("isVideo", e.isVideo)
                    .put("addedAt", e.addedAt)
                    .put("caption", e.caption)
            )
        }
        return JSONObject()
            .put("items", arr)
            .put("currentId", CastState.currentId.value ?: JSONObject.NULL)
            .put("playing", CastState.isPlaying.value)
            .put("position", CastState.positionMs.value)
            .put("duration", CastState.durationMs.value)
            .put("slideshow", CastState.slideshowOn.value)
            .put("interval", CastState.slideshowInterval.value)
            .put("transition", CastState.transitionEffect.value.name.lowercase())
            .put("cleanupMode", CastState.autoCleanupMode.value)
            .put("cleanupValue", CastState.autoCleanupValue.value)
            .put("captionsEnabled", CastState.captionsEnabled.value)
            .put("musicEnabled", CastState.musicEnabled.value)
            .put("musicCategory", CastState.musicCategory.value)
            .put("musicTracks", musicTracks)
            .put("muted", CastState.muted.value)
            .put("repeatOne", CastState.repeatOne.value)
            .put("usedBytes", CastState.items.value.sumOf { it.size })
            .put("freeBytes", MediaRepo.freeBytes())
            .put("serverUrl", CastState.serverUrl.value)
            .put("error", CastState.lastError.value)
    }

    /**
     * Plain Cookie/Set-Cookie header handling instead of Ktor's typed
     * cookie helpers -- deliberately the most version-stable API surface
     * available (ApplicationRequest.headers / ApplicationResponse.header
     * haven't changed shape across Ktor releases), since this is going
     * out without a local build to verify it compiles first.
     */
    private fun io.ktor.server.application.ApplicationCall.isAuthorized(): Boolean {
        val cookieHeader = request.headers["Cookie"] ?: return false
        val wanted = "tvcast_pin=${PinAuth.pin}"
        return cookieHeader.split(";").any { it.trim() == wanted }
    }

    private fun io.ktor.server.application.ApplicationCall.grantAuth() {
        // response.headers.append() (a plain member method, needing no
        // extra import) rather than the header()/cookies typed helpers --
        // same reasoning as isAuthorized(). Max-Age in seconds, ~1 year: a
        // PIN entered once shouldn't need re-entering every visit, only
        // after clearing site data or an actual PIN change.
        response.headers.append("Set-Cookie", "tvcast_pin=${PinAuth.pin}; Path=/; Max-Age=31536000")
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondAsset(
        path: String,
        type: ContentType
    ) {
        val bytes = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(path).use { it.readBytes() } }.getOrNull()
        }
        if (bytes == null) respond(HttpStatusCode.NotFound)
        else respondBytes(bytes, type.withCharset(Charsets.UTF_8))
    }

    companion object {
        const val PORT = 8080
        private const val TAG = "TVCastServer"
    }
}
