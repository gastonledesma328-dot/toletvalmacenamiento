package com.toletv.app.data.model

enum class ChannelCategory(val key: String) {
    CINE("cine"),
    TV("tv"),
    ENTRETENIMIENTO("entretenimiento"),
    DEPORTES("deportes");

    companion object {
        fun fromRaw(raw: String?): ChannelCategory {
            val k = raw?.trim()?.lowercase() ?: return TV
            return when (k) {
                // 🎬 cine
                "cine", "peliculas", "pelis", "movie", "movies" -> CINE

                // 📺 tv
                "tv", "tele", "television", "canales" -> TV

                // 🎭 entretenimiento
                "entretenimiento", "variedades", "variety", "shows" -> ENTRETENIMIENTO

                // 🏟️ deportes
                "deportes", "deporte", "sports", "sport" -> DEPORTES

                else -> TV
            }
        }
    }
}

/** Convierte cualquier string a una key estandarizada (“cine”, “tv”, “entretenimiento”, “deportes”). */
fun String?.toCategoryKey(): String = ChannelCategory.fromRaw(this).key
