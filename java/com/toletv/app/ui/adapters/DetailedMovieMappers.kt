package com.toletv.app.ui.adapters

import com.toletv.app.data.model.DetailedMovie
import com.toletv.app.data.model.StreamType
import com.example.tv67777.Movie

fun DetailedMovie.toSimpleMovie(): Movie {
    val posterOrBackdrop = when {
        !this.posterUrl.isNullOrBlank() -> this.posterUrl
        !this.backdropUrl.isNullOrBlank() -> this.backdropUrl
        else -> ""
    }
    val type = this.streamType ?: deduceStreamType(this.streamUrl)

    return Movie(
        id = this.id, // Movie espera Int
        title = this.title ?: "Sin título",
        logoUrl = posterOrBackdrop,
        streamUrl = this.streamUrl ?: "",
        streamType = type,
        description = this.description?.takeIf { it.isNotBlank() } ?: (this.synopsis ?: ""),
        category = this.genre.firstOrNull() ?: "peliculas"
    )
}

fun List<DetailedMovie>.toSimpleMovies(): List<Movie> = map { it.toSimpleMovie() }

fun deduceStreamType(url: String?): StreamType {
    val u = url?.lowercase() ?: return StreamType.DIRECT_URL
    return when {
        u.endsWith(".m3u8") -> StreamType.M3U8
        u.endsWith(".mpd") -> StreamType.MPD
        u.contains("youtube.com") || u.contains("youtu.be") || u.contains("vimeo.com") || u.contains("/watch") -> StreamType.WEB
        else -> StreamType.DIRECT_URL
    }
}
