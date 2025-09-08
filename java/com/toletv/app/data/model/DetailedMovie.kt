package com.toletv.app.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class DetailedMovie(
    val id: Int,
    val title: String,
    val description: String,
    val synopsis: String = "",
    val posterUrl: String,
    val backdropUrl: String = "",

    val streamUrl: String,
    val streamType: StreamType,

    val category: String,
    val duration: Int = 0,             // minutos
    val year: Int = 0,
    val rating: Float = 0f,            // IMDb / tu escala
    val genre: List<String> = emptyList(),
    val director: String = "",
    val cast: List<String> = emptyList(),

    val country: String = "",          // ✅ agregado (lo usa MovieDataLoader)
    val language: String = "",         // ✅ agregado
    val quality: String = "HD",
    val ageRating: String = "",

    val studio: String = "",           // ✅ agregado (lo usa MovieDetailsActivity)
    val awards: List<String> = emptyList(),

    val isFavorite: Boolean = false,
    val isWatched: Boolean = false,
    val watchProgress: Int = 0         // porcentaje visto
) : Parcelable {

    fun getFormattedDuration(): String {
        if (duration <= 0) return "--"
        val h = duration / 60
        val m = duration % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun getGenresString(): String = genre.joinToString(", ")

    fun getCastString(): String = cast.take(3).joinToString(", ")

    fun getFormattedRating(): String = if (rating > 0) String.format("%.1f", rating) else "--"

    /** Compat → tu modelo simple usado en otros adapters */
    fun toSimpleMovie(): com.example.tv67777.Movie {
        return com.example.tv67777.Movie(
            id = id,
            title = title,
            description = description,
            logoUrl = posterUrl,
            streamUrl = streamUrl,
            streamType = streamType,
            category = category,
            duration = duration
        )
    }

    companion object {
        /** Helper inverso por si sólo tienes `Movie` y necesitas `DetailedMovie` */
        fun fromSimpleMovie(m: com.example.tv67777.Movie): DetailedMovie {
            return DetailedMovie(
                id = m.id ?: 0,
                title = m.title ?: "",
                description = m.description ?: "",
                synopsis = "",
                posterUrl = m.logoUrl ?: "",
                backdropUrl = "",
                streamUrl = m.streamUrl ?: "",
                streamType = m.streamType ?: StreamType.WEB,
                category = m.category ?: "",
                duration = m.duration ?: 0,
                year = 0,
                rating = 0f,
                genre = emptyList(),
                director = "",
                cast = emptyList(),
                country = "",
                language = "",
                quality = "HD",
                ageRating = "",
                studio = "",
                awards = emptyList(),
                isFavorite = false,
                isWatched = false,
                watchProgress = 0
            )
        }
    }
}
