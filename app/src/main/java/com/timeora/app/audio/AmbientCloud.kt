package com.timevora.app.audio

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ambient sounds uploaded in the admin panel (MP3 on Cloudinary). A sound is downloaded the first time it plays and kept in the
 * app cache, so every later play works offline. Call [file] from a background thread: it does blocking network I/O.
 */
object AmbientCloud {
    private const val DIR = "ambient"
    private const val MAX_BYTES = 20L * 1024 * 1024 // the server only allows 15 MB; this is a safety net

    fun file(c: Context, s: AmbientSound): File {
        val dir = File(c.cacheDir, DIR).apply { mkdirs() }
        val f = File(dir, "${s.id}-${Integer.toHexString(s.url.hashCode())}.mp3")
        if (f.exists() && f.length() > 0) return f
        // A new upload under the same id means the old copy is stale: remove it so the cache does not grow.
        dir.listFiles()?.filter { it.name.startsWith(s.id + "-") && it != f }?.forEach { it.delete() }
        val tmp = File(dir, f.name + ".part")
        val conn = URL(s.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            var total = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("File too large")
                        out.write(buf, 0, n)
                    }
                }
            }
            if (total == 0L) throw IOException("Empty file")
            if (!tmp.renameTo(f)) throw IOException("Could not save the sound")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        return f
    }
}
