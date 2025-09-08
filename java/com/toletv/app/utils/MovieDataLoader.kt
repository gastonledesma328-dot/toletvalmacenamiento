package com.toletv.app.utils

import android.content.Context
import android.util.Log
import com.toletv.app.data.model.DetailedMovie
import com.toletv.app.data.model.StreamType
import com.toletv.app.data.service.MovieScraperService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Carga y cacheo de películas desde múltiples fuentes (sin withContext:
 * el caller decide el dispatcher, usar Dispatchers.IO).
 */
class MovieDataLoader(private val context: Context) {

    private val movieScraperService = MovieScraperService()
    private val tag = "MovieDataLoader"

    suspend fun loadMoviesFromUrl(
        url: String,
        cacheLocally: Boolean = true
    ): Result<List<DetailedMovie>> {
        return try {
            Log.d(tag, "Loading movies from URL: $url")

            val movies = movieScraperService.loadMoviesFromUrl(url)
            if (movies.isEmpty()) {
                return Result.failure(IllegalStateException("Remote movies is empty for $url"))
            }

            if (cacheLocally) {
                runCatching { cacheMoviesLocally(movies) }
                    .onFailure { Log.e(tag, "Error caching movies locally", it) }
            }

            Log.d(tag, "Successfully loaded ${movies.size} movies")
            Result.success(movies)
        } catch (e: Exception) {
            Log.e(tag, "Error loading movies from URL: $url", e)
            Result.failure(e)
        }
    }

    /**
     * Combina múltiples fuentes con de-dup por id (Int).
     */
    suspend fun loadMoviesFromMultipleUrls(
        urls: List<String>,
        cacheLocally: Boolean = true
    ): Result<List<DetailedMovie>> {
        return try {
            val byId = LinkedHashMap<Int, DetailedMovie>() // conserva orden

            for (src in urls) {
                try {
                    val movies = movieScraperService.loadMoviesFromUrl(src)
                    Log.d(tag, "Loaded ${movies.size} movies from $src")
                    for (m in movies) {
                        if (m.id != 0 && !byId.containsKey(m.id)) {
                            byId[m.id] = m
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Failed to load from $src: ${e.message}")
                }
            }

            val allMovies = byId.values.toList()

            // Solo cacheamos si hay algo (y opcionalmente si mejora lo ya cacheado)
            if (cacheLocally && allMovies.isNotEmpty()) {
                val cacheFile = File(context.cacheDir, "movies_cache.json")
                val currentCount = runCatching {
                    if (cacheFile.exists()) JSONArray(cacheFile.readText()).length() else 0
                }.getOrDefault(0)

                if (allMovies.size >= currentCount) {
                    runCatching { cacheMoviesLocally(allMovies) }
                        .onFailure { Log.e(tag, "Error caching movies locally", it) }
                } else {
                    Log.w(tag, "Skipping cache: new size ${allMovies.size} < cached size $currentCount")
                }
            }

            return if (allMovies.isEmpty()) {
                Result.failure(IllegalStateException("No se pudieron cargar películas de las fuentes"))
            } else {
                Result.success(allMovies)
            }

        } catch (e: Exception) {
            Log.e(tag, "Error loading movies from multiple URLs", e)
            Result.failure(e)
        }
    }

    suspend fun loadCachedMovies(): Result<List<DetailedMovie>> {
        return try {
            val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
            if (!cacheFile.exists()) {
                Log.w(tag, "No cached movies found")
                return Result.failure(Exception("No cached movies found"))
            }

            val jsonContent = cacheFile.readText()
            val movies = parseMoviesFromJson(jsonContent)
            Log.d(tag, "Loaded ${movies.size} movies from cache")
            Result.success(movies)
        } catch (e: Exception) {
            Log.e(tag, "Error loading cached movies", e)
            Result.failure(e)
        }
    }

    fun getSampleMovies(): List<DetailedMovie> {
        return movieScraperService.getSampleMovies()
    }

    // ---------------- Cache/JSON ----------------

    private fun cacheMoviesLocally(movies: List<DetailedMovie>) {
        val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
        val jsonContent = convertMoviesToJson(movies)
        cacheFile.writeText(jsonContent)
        Log.d(tag, "Cached ${movies.size} movies at ${cacheFile.absolutePath}")
    }

    private fun convertMoviesToJson(movies: List<DetailedMovie>): String {
        val sb = StringBuilder()
        sb.append("[\n")
        movies.forEachIndexed { index, movie ->
            sb.append("  {\n")
            sb.append("    \"id\": ${movie.id},\n")
            sb.append("    \"title\": ${q(movie.title)},\n")
            sb.append("    \"description\": ${q(movie.description)},\n")
            sb.append("    \"synopsis\": ${q(movie.synopsis)},\n")
            sb.append("    \"posterUrl\": ${q(movie.posterUrl)},\n")
            sb.append("    \"backdropUrl\": ${q(movie.backdropUrl)},\n")
            sb.append("    \"streamUrl\": ${q(movie.streamUrl)},\n")
            sb.append("    \"category\": ${q(movie.category)},\n")
            sb.append("    \"duration\": ${n(movie.duration)},\n")
            sb.append("    \"year\": ${n(movie.year)},\n")
            sb.append("    \"rating\": ${num(movie.rating)},\n")
            sb.append("    \"genre\": ${arr(movie.genre)},\n")
            sb.append("    \"director\": ${q(movie.director)},\n")
            sb.append("    \"cast\": ${arr(movie.cast)},\n")
            sb.append("    \"quality\": ${q(movie.quality)},\n")
            sb.append("    \"ageRating\": ${q(movie.ageRating)},\n")
            sb.append("    \"streamType\": ${q(movie.streamType.name)}\n")
            sb.append("  }")
            if (index < movies.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]")
        return sb.toString()
    }

    /**
     * Parser robusto:
     * - id Int o String (slug -> hash)
     * - "genres" (lista) o "genre" (lista)
     * - streamType String o inferido por URL (mejor heurística)
     * - duration acepta "135m" o número
     */
    private fun parseMoviesFromJson(jsonContent: String): List<DetailedMovie> {
        val out = ArrayList<DetailedMovie>()
        val arr = JSONArray(jsonContent)

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)

            val id: Int = when {
                o.has("id") && !o.isNull("id") -> {
                    when (val any = o.get("id")) {
                        is Number -> any.toInt()
                        is String -> any.hashCode()
                        else -> 0
                    }
                }
                else -> 0
            }

            val title = o.optString("title", "")
            val description = o.optString("description", "")
            val synopsis = o.optString("synopsis", if (description.isNotEmpty()) description else "")
            val posterUrl = o.optString("posterUrl", "")
            val backdropUrl = o.optString("backdropUrl", "")
            val streamUrl = o.optString("streamUrl", "")
            val category = o.optString("category", "")

            val duration: Int = when {
                o.has("duration") && !o.isNull("duration") -> {
                    val any = o.get("duration")
                    when (any) {
                        is Number -> any.toInt()
                        is String -> any.trim().removeSuffix("m").toIntOrNull() ?: 0
                        else -> 0
                    }
                }
                else -> 0
            }

            val year = o.optInt("year", 0)

            val rating = if (o.has("rating") && !o.isNull("rating")) {
                try { o.getDouble("rating").toFloat() } catch (_: Exception) { 0f }
            } else 0f

            val genresList =
                if (o.has("genres") && !o.isNull("genres")) o.optStringListNonNull("genres")
                else o.optStringListNonNull("genre")

            val director = o.optString("director", "")
            val cast = if (o.has("cast") && !o.isNull("cast")) o.optStringListNonNull("cast") else emptyList()
            val country = o.optString("country", "")
            val language = o.optString("language", "")
            val quality = o.optString("quality", "")
            val ageRating = o.optString("ageRating", "")
            val releaseDate = o.optString("releaseDate", "")
            val studio = o.optString("studio", "")

            // Normalizamos el streamType: si viene vacío o dudoso, deducimos por URL (heurística nueva)
            val streamTypeStr = normalizeEnumString(o.optString("streamType", ""))
            var streamType = when (streamTypeStr) {
                "WEB", "WEBPAGE" -> StreamType.WEB
                "DIRECT_URL", "DIRECT", "MP4", "MKV", "MOV" -> StreamType.DIRECT_URL
                "M3U8", "HLS" -> StreamType.M3U8
                "MPD", "DASH" -> StreamType.MPD
                else -> inferStreamType(streamUrl) // <-- nueva heurística robusta
            }

            // Si la URL parece página con player, forzamos WEB aunque no tenga extensión
            if (looksLikeWebPage(streamUrl)) {
                streamType = StreamType.WEB
            }

            Log.d(tag, "Movie '$title' -> type=$streamType url=$streamUrl")

            out += DetailedMovie(
                id = id,
                title = title,
                description = description,
                synopsis = synopsis,
                posterUrl = posterUrl,
                backdropUrl = backdropUrl,
                streamUrl = streamUrl,
                category = category,
                duration = duration,
                year = year,
                rating = rating,

                director = director,
                cast = cast,
                quality = quality,
                ageRating = ageRating,
                streamType = streamType
            )
        }

        return out
    }

    // ---------- Heurística de streamType (actualizada) ----------

    private fun inferStreamType(url: String): StreamType {
        val u = url.lowercase(Locale.getDefault()).trim()

        // 1) Señales de HLS (aunque no termine en .m3u8)
        if (isProbablyHls(u)) return StreamType.M3U8

        // 2) Señales de DASH
        if (isProbablyDash(u)) return StreamType.MPD

        // 3) Si parece página (embed/hosts conocidos) => WEB
        if (looksLikeWebPage(u)) return StreamType.WEB

        // 4) Progresivo solo si hay EXTENSIÓN o patrón claro de media directa
        if (isProbablyProgressive(u)) return StreamType.DIRECT_URL

        // 5) Fallback conservador: mejor WEB que DIRECT_URL (evita "container unsupportable")
        return StreamType.WEB
    }

    private fun isProbablyHls(u: String): Boolean {
        // Subcadenas y parámetros típicos de HLS aunque no haya .m3u8
        return u.contains(".m3u8") ||
                u.contains("hls/") ||
                u.contains("playlist-type=hls") ||
                u.contains("ext=m3u8") ||
                u.contains("format=m3u8") ||
                u.contains("m3u8?") ||
                u.contains("master.m3u") ||
                // algunas CDNs usan "token/expire" pero sirven HLS:
                (u.contains("token=") && (u.contains("m3u") || u.contains("hls")))
    }

    private fun isProbablyDash(u: String): Boolean {
        return u.contains(".mpd") || u.contains("dash/") || u.contains("format=mpd")
    }

    private fun isProbablyProgressive(u: String): Boolean {
        // extensiones "de archivo"
        if (u.endsWith(".mp4") || u.endsWith(".mkv") || u.endsWith(".mov") ||
            u.endsWith(".webm") || u.endsWith(".ts")) return true

        // patrones de media directa (p. ej. Google/CDN)
        if (u.contains("/videoplayback")) return true

        // si hay un parámetro de "mime=video/*" también
        if (u.contains("mime=video")) return true

        return false
    }

    private fun normalizeEnumString(raw: String?): String =
        raw?.trim()?.uppercase(Locale.getDefault()) ?: ""

    private fun looksLikeWebPage(url: String): Boolean {
        val u = url.lowercase(Locale.getDefault())

        // Si es un embed explícito
        if (u.contains("/embed")) return true

        // Hosts comunes de páginas con player embebido (no streams directos)
        val hosts = listOf(
            "filemoon", "streamwish", "streamtape", "ok.ru", "uqload",
            "dood.", "streamsb", "sbfull", "wolfstream", "vidoza", "voe.sx",
            "zplayer", "gcloud.live", "pelispedia", "pelisplus", "cuevana",
            "yuguaab.com", "lamovie.link", "embed", "embed69.org", "mega.nz",
            "netu.", "sibnet", "mixdrop", "streamlare", "streamvid"
        )
        val isHostEmbed = hosts.any { u.contains(it) }

        // ¿Parece ruta HTML (sin extensión de media)?
        val looksHtmlish = !(u.endsWith(".m3u8") || u.endsWith(".mpd") ||
                u.endsWith(".mp4")  || u.endsWith(".mkv") ||
                u.endsWith(".mov")  || u.endsWith(".webm") ||
                u.endsWith(".ts"))

        return (isHostEmbed && looksHtmlish)
    }

    // --------------- Helpers de serialización ---------------

    private fun escapeJson(text: String): String =
        text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private fun q(value: String): String = "\"${escapeJson(value)}\""
    private fun n(value: Int): String = value.toString()
    private fun num(value: Number?): String = value?.toString() ?: "null"
    private fun arr(list: List<String>): String =
        if (list.isEmpty()) "[]" else list.joinToString(prefix = "[", postfix = "]") { q(it) }

    // ----------------- Cache control -----------------

    suspend fun clearCache() {
        try {
            val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
            if (cacheFile.exists()) {
                cacheFile.delete()
                Log.d(tag, "Cache cleared successfully")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error clearing cache", e)
        }
    }

    companion object {
        private const val CACHE_FILE_NAME = "movies_cache.json"

        // ✅ URL RAW canónica de tu repo (branch main)
        const val DEFAULT_MOVIES_URL =
            "https://raw.githubusercontent.com/gastonledesma328-dot/toletvalmacenamiento/main/movies.json"

        val EXAMPLE_MOVIE_URLS = listOf(
            "https://api.themoviedb.org/3/movie/popular",
            "https://your-server.com/api/movies.json",
            "https://raw.githubusercontent.com/user/repo/main/movies.json"
        )

        fun getSampleJsonStructure(): String {
            return """
            [
              {
                "id": 1,
                "title": "Movie Title",
                "description": "Short description",
                "synopsis": "Detailed plot synopsis",
                "posterUrl": "https://image.url/poster.jpg",
                "backdropUrl": "https://image.url/backdrop.jpg",
                "trailerUrl": "https://video.url/trailer.mp4",
                "streamUrl": "https://stream.url/movie.m3u8",
                "category": "peliculas",
                "duration": 120,
                "year": 2024,
                "rating": 8.5,
                "genre": ["Action", "Drama"],
                "director": "Director Name",
                "cast": ["Actor 1", "Actor 2", "Actor 3"],
                "country": "USA",
                "language": "English",
                "quality": "4K",
                "ageRating": "PG-13",
                "releaseDate": "2024-01-01",
                "studio": "Studio Name",
                "streamType": "M3U8"
              }
            ]
            """.trimIndent()
        }
    }
}

// ---------------- Extensiones org.json ----------------

private fun JSONObject.optStringListNonNull(key: String): List<String> {
    val list = ArrayList<String>()
    if (has(key) && !isNull(key)) {
        val arr = optJSONArray(key)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotEmpty()) list += v
            }
        }
    }
    return list
}
