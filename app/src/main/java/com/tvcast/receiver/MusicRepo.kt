package com.tvcast.receiver

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Background-music tracks for the "atmospheric" slideshow mode, kept
 * completely separate from the photo/video library (MediaRepo) so a
 * looping music track never shows up as a viewable tile or gets played
 * as if it were a video.
 *
 * Sourcing real royalty-free audio requires reaching sites this build
 * environment's sandbox can't (see commit history) -- so instead of
 * bundling tracks, the user supplies a few of their own (their phone/PC
 * has normal internet access) via the same upload flow, tagged with one
 * of a fixed set of categories. One file on disk per track, at
 * music/<category>/<id>~<original name>.
 */
object MusicRepo {

    val CATEGORIES = listOf("calm", "upbeat", "nature", "festive")

    private lateinit var musicDir: File

    fun init(context: Context) {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        musicDir = File(base, "music").apply { mkdirs() }
        for (c in CATEGORIES) File(musicDir, c).mkdirs()
    }

    private fun dirOf(category: String): File? {
        if (category !in CATEGORIES) return null
        return File(musicDir, category)
    }

    fun list(category: String): List<File> =
        (dirOf(category)?.listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.name }

    fun countsByCategory(): Map<String, Int> = CATEGORIES.associateWith { list(it).size }

    fun save(category: String, originalName: String, input: InputStream) {
        val dir = dirOf(category) ?: return
        val clean = originalName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[~\\r\\n\\t\"?*:<>|]"), "_").trim().ifEmpty { "track" }.take(120)
        val id = "${System.currentTimeMillis()}-${(1000..9999).random()}~$clean"
        FileOutputStream(File(dir, id)).use { out ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
    }

    fun delete(category: String, id: String): Boolean {
        if (id.contains('/') || id.contains("..")) return false
        val dir = dirOf(category) ?: return false
        return File(dir, id).delete()
    }
}
