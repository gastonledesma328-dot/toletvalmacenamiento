package com.toletv.app.data.model

/** Tipo de stream que usa cada canal/película. */
enum class StreamType {
    /** HLS (.m3u8) reproducible con ExoPlayer */
    M3U8,

    /** MPEG-DASH (.mpd) reproducible con ExoPlayer */
    MPD,

    /** Link directo a media (mp4, ts, etc) reproducible con ExoPlayer */
    DIRECT_URL,

    /** Fuentes web que abrís en WebView (embeds, players en página, etc.) */
    WEB,

    WE2

}