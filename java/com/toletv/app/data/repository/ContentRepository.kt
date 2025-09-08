package com.toletv.app.data.repository

import com.toletv.app.data.model.Channel
import com.example.tv67777.Movie
import com.toletv.app.data.model.MovieList
import com.toletv.app.data.model.StreamType

class ContentRepository {

    // ---------- Canales (TV) ----------

    fun getFavoriteChannels(): List<Channel> =
        getChannelsByCategories("favoritos", "favorite", "tv_favoritos")
            .ifEmpty { getChannelsByCategories("tv") }

    fun getAllChannels(): List<Channel> =
        getChannelsByCategories("tv")

    fun getSportsChannels(): List<Channel> =
        getChannelsByCategories("deportes", "sports")


    fun getEntertainmentChannels(): List<Channel> =
        getChannelsByCategories("entretenimiento", "entertainment")

    // ⬇️ ESTA ES LA CORRECTA PARA CANALES DE CINE (List<Channel>)
    fun getCinemaChannels(): List<Channel> =
        getChannelsByCategories("cine", "movies-tv", "peliculas")

    // ---------- Búsqueda simple en TV ----------
    fun searchChannels(query: String): List<Channel> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return getAllChannels().filter {
            it.name.contains(q, ignoreCase = true) ||
                    (it.description?.contains(q, ignoreCase = true) == true)
        }
    }

    // ---------- Películas (List<Movie>) ----------

    fun getMovies(): List<Movie> {
        val items = MovieList.list.filter { hasCategory(it, "peliculas") }
        return if (items.isNotEmpty()) {
            items.map { normalizeMovie(it) }.sortedBy { it.title.lowercase() }
        } else {
            getDefaultMovies()
        }
    }

    // ⬇️ ESTA ES LA CORRECTA PARA “Cine” como películas (List<Movie>)
    fun getCineMovies(): List<Movie> {
        val items = MovieList.list.filter { hasCategory(it, "cine", "peliculas") }
        return items.map { normalizeMovie(it) }.sortedBy { it.title.lowercase() }
    }

    private fun getDefaultMovies(): List<Movie> = listOf(
        Movie(
            id = 1001,
            title = "jurassic-world-el-renacer-2025---pelispedia",
            description = "Cinco años después de los eventos de .",
            logoUrl = "https://pelispedia.mov/wp-content/uploads/jurassic-world-el-renacer-2025.jpg",
            streamUrl = "https://filemoon.link/e/zy85yq3z154r",
            streamType = StreamType.WEB,   // HLS → va a ExoPlayer
            category = "peliculas"
        ),
    ).sortedBy { it.title.lowercase() }

    // ---------- Radios (hardcoded) ----------

    // ---------- Helpers internos ----------

    private fun getChannelsByCategories(vararg categories: String): List<Channel> {
        if (categories.isEmpty()) return emptyList()
        val set = categories.map { it.trim().lowercase() }.toSet()

        return MovieList.list
            .asSequence()
            .filter { hasCategory(it, *set.toTypedArray()) }
            .map { mapMovieToChannel(it) }
            .distinctBy { it.id } // evita duplicados
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    // tolerante a mayúsculas/espacios/nulos
    private fun hasCategory(item: Movie, vararg names: String): Boolean {
        val cat = item.category?.trim()?.lowercase() ?: return false
        return names.any { cat == it.trim().lowercase() }
    }

    private fun mapMovieToChannel(movieItem: Movie): Channel =
        Channel(
            id = movieItem.id.toString(),
            name = movieItem.title,
            logoUrl = movieItem.logoUrl,
            streamUrl = movieItem.streamUrl,
            streamType = determineStreamType(movieItem.streamUrl),
            description = movieItem.description
        )

    private fun normalizeMovie(item: Movie): Movie {
        val normalizedType = item.streamType ?: determineStreamType(item.streamUrl)
        return Movie(
            id = item.id,
            title = item.title,
            description = item.description,
            logoUrl = item.logoUrl,
            streamUrl = item.streamUrl,
            streamType = normalizedType,
            category = item.category
        )
    }

    private fun determineStreamType(streamUrl: String): StreamType {
        val url = streamUrl.trim()
        return when {
            url.contains(".m3u8", ignoreCase = true) -> StreamType.M3U8
            url.contains(".mpd", ignoreCase = true) -> StreamType.MPD
            url.contains("youtube.com", ignoreCase = true) ||
                    url.contains("youtu.be", ignoreCase = true) ||
                    url.contains("twitch.tv", ignoreCase = true) ||
                    url.contains("vimeo.com", ignoreCase = true) ||
                    url.contains(".html", ignoreCase = true) ||
                    url.contains("cvatt", ignoreCase = true) -> StreamType.WEB
            url.startsWith("http", ignoreCase = true) -> StreamType.DIRECT_URL
            else -> StreamType.M3U8
        }
    }
}
