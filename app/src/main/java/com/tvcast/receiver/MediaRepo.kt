package com.tvcast.receiver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * Хранилище медиафайлов на телевизоре.
 *
 * Файлы лежат в getExternalFilesDir("media") — это приватная папка приложения:
 * не нужны разрешения на доступ к памяти, и всё удаляется вместе с приложением.
 *
 * Имя файла на диске: "<время>-<случайное>~<исходное имя>" — так мы храним
 * оригинальное имя и дату без отдельной базы данных.
 */
object MediaRepo {

    private lateinit var appContext: Context
    lateinit var mediaDir: File
        private set
    private lateinit var thumbDir: File

    fun init(context: Context) {
        appContext = context.applicationContext
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        mediaDir = File(base, "media").apply { mkdirs() }
        thumbDir = File(base, "thumbs").apply { mkdirs() }
        refresh()
    }

    fun refresh() {
        val list = (mediaDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.contains('~') }
            .map { f ->
                MediaEntry(
                    id = f.name,
                    name = f.name.substringAfter('~'),
                    mime = mimeOf(f.name),
                    size = f.length(),
                    addedAt = f.name.substringBefore('-').toLongOrNull() ?: f.lastModified()
                )
            }
            .sortedBy { it.addedAt }
        CastState.items.value = list
    }

    fun fileOf(id: String): File? {
        // защита от выхода за пределы каталога
        if (id.contains('/') || id.contains("..")) return null
        val f = File(mediaDir, id)
        return if (f.isFile) f else null
    }

    fun entryOf(id: String): MediaEntry? = CastState.items.value.firstOrNull { it.id == id }

    /** Потоковая запись загружаемого файла — без буферизации целиком в памяти. */
    fun save(originalName: String, input: InputStream): MediaEntry {
        val clean = sanitize(originalName)
        val id = "${System.currentTimeMillis()}-${(1000..9999).random()}~$clean"
        val target = File(mediaDir, id)
        FileOutputStream(target).use { out ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            out.flush()
        }
        refresh()
        return entryOf(id) ?: MediaEntry(id, clean, mimeOf(clean), target.length(), System.currentTimeMillis())
    }

    fun delete(id: String): Boolean {
        val f = fileOf(id) ?: return false
        val ok = f.delete()
        File(thumbDir, "$id.jpg").delete()
        refresh()
        return ok
    }

    fun deleteAll() {
        mediaDir.listFiles()?.forEach { it.delete() }
        thumbDir.listFiles()?.forEach { it.delete() }
        refresh()
    }

    fun freeBytes(): Long = mediaDir.usableSpace

    fun usedBytes(): Long = (mediaDir.listFiles() ?: emptyArray()).sumOf { it.length() }

    /** Миниатюра для веб-галереи на телефоне. Кэшируется на диске. */
    fun thumbnail(id: String): File? {
        val src = fileOf(id) ?: return null
        val out = File(thumbDir, "$id.jpg")
        if (out.isFile && out.length() > 0) return out
        val bmp: Bitmap? = try {
            if (mimeOf(id).startsWith("video/")) videoFrame(src) else scaledImage(src)
        } catch (t: Throwable) {
            null
        }
        if (bmp == null) return null
        return try {
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 78, it) }
            bmp.recycle()
            out
        } catch (t: Throwable) {
            null
        }
    }

    private fun scaledImage(f: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        val target = 320
        while (bounds.outWidth / sample > target * 2 && bounds.outHeight / sample > target * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(f.absolutePath, opts)
    }

    private fun videoFrame(f: File): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(f.absolutePath)
            mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            try {
                mmr.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[~\\r\\n\\t\"?*:<>|]"), "_").trim()
        return if (cleaned.isEmpty()) "file" else cleaned.take(120)
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heic"
        "avif" -> "image/avif"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "3gp" -> "video/3gpp"
        "ts" -> "video/mp2t"
        "m3u8" -> "application/vnd.apple.mpegurl"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }
}
