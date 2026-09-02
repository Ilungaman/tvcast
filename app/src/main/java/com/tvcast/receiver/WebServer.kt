package com.tvcast.receiver

import android.content.Context
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
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

                // ---- состояние ----
                get("/api/state") {
                    call.respondText(stateJson().toString(), ContentType.Application.Json)
                }

                // ---- команды пульта ----
                post("/api/cmd") {
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
                    val saved = JSONArray()
                    try {
                        val multipart = call.receiveMultipart()
                        multipart.forEachPart { part ->
                            if (part is PartData.FileItem) {
                                val name = part.originalFileName ?: "file"
                                val entry = withContext(Dispatchers.IO) {
                                    part.streamProvider().use { MediaRepo.save(name, it) }
                                }
                                saved.put(entry.id)
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

                // ---- отдача файла с поддержкой Range (206 Partial Content) ----
                get("/media/{id}") {
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
                    val id = call.parameters["id"].orEmpty()
                    val thumb = withContext(Dispatchers.IO) { MediaRepo.thumbnail(id) }
                    if (thumb == null) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        call.respond(LocalFileContent(thumb, ContentType.Image.JPEG))
                    }
                }

                delete("/api/media/{id}") {
                    val id = call.parameters["id"].orEmpty()
                    if (CastState.currentId.value == id) CastState.commands.tryEmit(Command.Stop)
                    withContext(Dispatchers.IO) { MediaRepo.delete(id) }
                    call.respondText(stateJson().toString(), ContentType.Application.Json)
                }

                // ---- живое состояние + команды по WebSocket ----
                webSocket("/ws") {
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
            .put("muted", CastState.muted.value)
            .put("repeatOne", CastState.repeatOne.value)
            .put("usedBytes", CastState.items.value.sumOf { it.size })
            .put("freeBytes", MediaRepo.freeBytes())
            .put("serverUrl", CastState.serverUrl.value)
            .put("error", CastState.lastError.value)
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
