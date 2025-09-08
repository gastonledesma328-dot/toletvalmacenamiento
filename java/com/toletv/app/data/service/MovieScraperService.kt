package com.toletv.app.data.service

import com.toletv.app.data.model.DetailedMovie
import com.toletv.app.data.model.StreamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

class MovieScraperService {

    /**
     * Carga películas desde una URL que devuelve JSON.
     * Acepta tanto el esquema "clásico" (genre) como el del ingester (genres).
     * Acepta id numérico o string (slug).
     * Acepta streamType string (HLS/DASH/UNKNOWN) o lo infiere por extensión.
     */
    suspend fun loadMoviesFromUrl(url: String): List<DetailedMovie> {
        return withContext(Dispatchers.IO) {
            try {
                val jsonResponse = fetchJsonFromUrl(url)
                parseMoviesFlexible(jsonResponse)
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    /**
     * Carga una película específica desde una URL.
     * Soporta respuesta como objeto o arreglo de un solo elemento.
     */
    suspend fun loadMovieFromUrl(url: String): DetailedMovie? {
        return withContext(Dispatchers.IO) {
            try {
                val jsonResponse = fetchJsonFromUrl(url)
                parseSingleMovieFlexible(jsonResponse)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /* ------------------------ Networking ------------------------ */

    private fun fetchJsonFromUrl(urlString: String): String {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "TOLE TV Android App")
            setRequestProperty("Accept-Charset", "utf-8")
            connectTimeout = 15000
            readTimeout = 15000
        }

        return conn.use {
            val code = it.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(it.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line)
                    }
                    sb.toString()
                }
            } else {
                throw Exception("HTTP Error: $code")
            }
        }
    }

    // Cierra correctamente la conexión
    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T {
        return try { block(this) } finally { try { disconnect() } catch (_: Exception) {} }
    }

    /* ------------------------ Parsing flexible ------------------------ */

    private fun parseMoviesFlexible(jsonString: String): List<DetailedMovie> {
        // Intentar como array primero
        try {
            val arr = JSONArray(jsonString)
            val out = mutableListOf<DetailedMovie>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                out += parseMovieObjectFlexible(obj)
            }
            return out
        } catch (_: JSONException) {
            // no era array, probar objeto con "results" o uno solo
        }

        // Objeto único o con "results"
        val obj = JSONObject(jsonString)
        return when {
            obj.has("results") && !obj.isNull("results") -> {
                val arr = obj.getJSONArray("results")
                val out = mutableListOf<DetailedMovie>()
                for (i in 0 until arr.length()) {
                    out += parseMovieObjectFlexible(arr.getJSONObject(i))
                }
                out
            }
            else -> listOf(parseMovieObjectFlexible(obj))
        }
    }

    private fun parseSingleMovieFlexible(jsonString: String): DetailedMovie {
        // Si viene como array, tomar el primero
        return try {
            val arr = JSONArray(jsonString)
            parseMovieObjectFlexible(arr.getJSONObject(0))
        } catch (_: JSONException) {
            parseMovieObjectFlexible(JSONObject(jsonString))
        }
    }

    private fun parseMovieObjectFlexible(json: JSONObject): DetailedMovie {
        // --- id: puede ser Int o String (slug) ---
        val id: Int = when {
            json.has("id") && !json.isNull("id") -> {
                when (val any = json.get("id")) {
                    is Number -> any.toInt()
                    is String -> any.hashCode() // mapeo estable a Int
                    else -> 0
                }
            }
            else -> 0
        }

        // --- listas ---
        val genreList = optStringList(json, prefer = "genres", fallback = "genre")
        val castList = optStringList(json, prefer = "cast")
        val awardsList = optStringList(json, prefer = "awards")

        // --- rating: número o string ---
        val rating: Float = when {
            json.has("rating") && !json.isNull("rating") -> {
                val any = json.get("rating")
                when (any) {
                    is Number -> any.toFloat()
                    is String -> any.toFloatOrNull() ?: 0f
                    else -> 0f
                }
            }
            else -> 0f
        }

        // --- streamUrl + streamType flexible ---
        val streamUrl = json.optString("streamUrl", "")
        val streamTypeStr = json.optString("streamType", "")
        val streamType = when {
            streamTypeStr.equals("HLS", true) -> StreamType.M3U8
            streamTypeStr.equals("DASH", true) -> StreamType.MPD
            streamTypeStr.equals("UNKNOWN", true) || streamTypeStr.isEmpty() -> determineStreamType(streamUrl)
            else -> determineStreamType(streamUrl)
        }

        return DetailedMovie(
            id = id,
            title = json.optString("title", ""),
            description = json.optString("description", ""),
            synopsis = json.optString("synopsis", ""),
            posterUrl = json.optString("posterUrl", ""),
            backdropUrl = json.optString("backdropUrl", ""),
            streamUrl = streamUrl,
            streamType = streamType,
            category = json.optString("category", "peliculas"),
            duration = json.optInt("duration", 0),
            year = json.optInt("year", 0),
            rating = rating,
            genre = genreList,
            director = json.optString("director", ""),
            cast = castList,
            quality = json.optString("quality", "HD"),
            ageRating = json.optString("ageRating", ""),
            awards = awardsList,
            isFavorite = json.optBoolean("isFavorite", false),
            isWatched = json.optBoolean("isWatched", false),
            watchProgress = json.optInt("watchProgress", 0)
        )
    }

    private fun optStringList(json: JSONObject, prefer: String, fallback: String? = null): List<String> {
        // Intenta prefer; si no está y hay fallback, intenta fallback.
        val list = json.optJSONArray(prefer)?.toStringList()
        if (list != null) return list
        if (fallback != null) {
            json.optJSONArray(fallback)?.toStringList()?.let { return it }
        }
        return emptyList()
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val v = opt(i)
            when (v) {
                is String -> if (v.isNotBlank()) out += v
                is JSONObject -> {
                    // por si vienen objetos {name:"..."} (TMDb/JSON-LD)
                    val name = v.optString("name", "")
                    if (name.isNotBlank()) out += name
                }
            }
        }
        return out
    }

    /* ------------------------ Stream type ------------------------ */

    private fun determineStreamType(streamUrl: String): StreamType {
        val u = streamUrl.lowercase(Locale.ROOT)
        return when {
            u.endsWith(".m3u8") -> StreamType.M3U8
            u.endsWith(".mpd") -> StreamType.MPD
            u.contains(".html") || u.contains("cvatt") -> StreamType.WEB
            u.startsWith("http") -> StreamType.DIRECT_URL
            else -> StreamType.M3U8
        }
    }

    /* ------------------------ Samples ------------------------ */

    fun getSampleMovies(): List<DetailedMovie> {
        return listOf(
            DetailedMovie(
                id = 1001,
                title = "Gladiador II",
                description = "Secuela del épico gladiador de Ridley Scott",
                synopsis = "Años después de presenciar la muerte del venerado héroe Máximo a manos de su tío, Lucio se ve forzado a entrar en el Coliseo tras ser testigo de la conquista de su hogar por parte de los tiranos emperadores que ahora dirigen Roma con puño de hierro. Con la rabia en el corazón y el futuro del Imperio en juego, Lucio debe mirar hacia su pasado para encontrar la fuerza y el honor necesarios para devolver la gloria de Roma a su pueblo.",
                posterUrl = "https://image.tmdb.org/t/p/w500/2cxhvwyEwRlysAmRH4iodkvo0z5.jpg",
                backdropUrl = "https://image.tmdb.org/t/p/w1280/euYIwmwkmz95mnXvufEmbL6ovhZ.jpg",
                streamUrl = "https://example.com/movies/gladiator2.m3u8",
                streamType = StreamType.M3U8,
                category = "peliculas",
                duration = 148,
                year = 2024,
                rating = 7.8f,
                genre = listOf("Acción", "Drama", "Aventura"),
                director = "Ridley Scott",
                cast = listOf("Paul Mescal", "Pedro Pascal", "Denzel Washington", "Connie Nielsen"),


                quality = "4K",
                ageRating = "R",

            ),
            DetailedMovie(
                id = 1002,
                title = "MUFASA: El Rey León",
                description = "La historia del origen de Mufasa, el legendario rey de las Tierras del Reino",
                synopsis = "Mufasa, un cachorro de león huérfano, perdido y solo, conoce a un simpático león llamado Taka, heredero de un linaje real...",
                posterUrl = "https://image.tmdb.org/t/p/w500/lurEK87kukWNaHd0zYnsi3yzJrs.jpg",
                backdropUrl = "https://image.tmdb.org/t/p/w1280/3ovFaFeojLFIl5ClqhtgYMDS8sE.jpg",
                streamUrl = "https://example.com/movies/mufasa.m3u8",
                streamType = StreamType.M3U8,
                category = "peliculas",
                duration = 118,
                year = 2024,
                rating = 7.2f,
                genre = listOf("Animación", "Familia", "Aventura", "Drama"),
                director = "Barry Jenkins",
                cast = listOf("Aaron Pierre", "Kelvin Harrison Jr.", "Tiffany Boone", "Kagiso Lediga"),

                quality = "4K",
                ageRating = "PG",

            )
            // ... (dejé 2 para abreviar)
        )
    }
}
