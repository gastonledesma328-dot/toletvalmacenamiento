package com.toletv.app.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ProbeResult(
    val ok: Boolean,
    val code: Int,
    val contentType: String,
    val finalUrl: String,
    val samplePrefix: String // <- primeros bytes del body (para detectar HTML vs m3u8)
)

object NetProbe {
    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun isLikelyMedia(contentType: String, url: String, sample: String): Boolean {
        val ct = contentType.lowercase()
        val u  = url.lowercase()
        val s  = sample.trimStart()
        // HTML claro
        if (ct.startsWith("text/html") || s.startsWith("<!doctype") || s.startsWith("<html")) return false
        // HLS / DASH / Video
        if ("application/vnd.apple.mpegurl" in ct || "application/x-mpegurl" in ct) return true
        if ("application/dash+xml" in ct) return true
        if (ct.startsWith("video/")) return true
        // Por extensión + firma del contenido
        if (u.endsWith(".m3u8") || s.startsWith("#EXTM3U")) return true
        if (u.endsWith(".mpd")) return true
        if (u.endsWith(".mp4") || u.endsWith(".mov")) return true
        return false
    }

    /** Descarga sólo lo mínimo: headers y ~2KB para identificar el tipo real */
    suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "TOLETV/1.0 (ExoPlayer)")
                .header("Range", "bytes=0-2047")
                .build()

            http.newCall(req).execute().use { resp ->
                val code = resp.code
                val ct   = resp.header("Content-Type") ?: ""
                val finalUrl = resp.request.url.toString()
                val sample = try {
                    // lee un pedacito (no siempre hay soporte para Range)
                    val peek = resp.peekBody(2048)
                    peek.string().take(200) // guardamos los primeros 200 chars para logs
                } catch (_: Exception) { "" }

                val ok = code in 200..206 && isLikelyMedia(ct, finalUrl, sample)
                Log.d("NetProbe", "code=$code, ct=$ct, final=$finalUrl, sample=${sample.take(40)}...")
                ProbeResult(ok, code, ct, finalUrl, sample)
            }
        } catch (e: Exception) {
            Log.e("NetProbe", "probe error: ${e.message}", e)
            ProbeResult(false, -1, "", url, "")
        }
    }
}
