package com.toletv.app.utils

import com.toletv.app.data.model.StreamType

object StreamUtils {
    @JvmStatic
    fun deduceStreamType(url: String?): StreamType {
        if (url.isNullOrBlank()) return StreamType.WEB
        val u = url.trim().lowercase()

        // Extensiones claras de media
        if (u.endsWith(".m3u8")) return StreamType.M3U8
        if (u.endsWith(".mpd"))  return StreamType.MPD
        if (u.endsWith(".mp4") || u.endsWith(".mkv") || u.endsWith(".mov")) return StreamType.DIRECT_URL

        // Si parece página o host de embed → WEB
        val looksLikePage = u.contains(".html") ||
                u.contains("?") || u.contains("&") || u.contains("watch") ||
                listOf(
                    "filemoon", "streamwish", "streamtape", "ok.ru", "uqload",
                    "dood.", "streamsb", "sbfull", "wolfstream", "vidoza", "voe.sx",
                    "zplayer", "gcloud", "pelispedia", "pelisplus", "cuevana",
                    "cuevanahd"
                ).any { u.contains(it) }

        return if (looksLikePage) StreamType.WEB else StreamType.WEB // conservador
    }
}
