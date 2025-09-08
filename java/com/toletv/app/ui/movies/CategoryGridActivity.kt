// app/src/main/java/com/toletv/app/ui/movies/CategoryGridActivity.kt
package com.toletv.app.ui.movies

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.toletv.app.R
import com.toletv.app.data.model.DetailedMovie
import com.toletv.app.utils.MovieDataLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.LinkedHashSet
import java.util.Locale
class CategoryGridActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CATEGORY = "extra_category_display" // p.ej. "Acción"
        private const val GRID_SPAN_COUNT = 5               // 5 columnas (5 por fila)
    }

    private lateinit var rvGrid: RecyclerView
    private lateinit var btnBack: ImageView

    private lateinit var movieDataLoader: MovieDataLoader
    private var allMovies: List<DetailedMovie> = emptyList()
    private var categoryDisplay: String = "Acción"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category_grid)

        categoryDisplay = intent.getStringExtra(EXTRA_CATEGORY) ?: "Acción"

        movieDataLoader = MovieDataLoader(this)
        rvGrid = findViewById(R.id.rvGrid)
        btnBack = findViewById(R.id.btnBack)

        btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this) { isEnabled = false; onBackPressedDispatcher.onBackPressed() }

        rvGrid.apply {
            layoutManager = GridLayoutManager(this@CategoryGridActivity, GRID_SPAN_COUNT)
            setHasFixedSize(true)
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        loadMovies()
    }

    private fun loadMovies() {
        CoroutineScope(Dispatchers.IO).launch {
            val url = MovieDataLoader.DEFAULT_MOVIES_URL + "?t=" + System.currentTimeMillis()
            val remote = movieDataLoader.loadMoviesFromUrl(url, cacheLocally = true)
            val result = remote.getOrElse {
                movieDataLoader.loadCachedMovies().getOrElse { emptyList() }
            }
            withContext(Dispatchers.Main) {
                allMovies = result
                bindGrid()
            }
        }
    }

    private fun bindGrid() {
        // Filtrar por la categoría (canonizando sinónimos, ej. "Action"/"Accion" -> "Acción")
        val filtered = allMovies.filter { hasCategory(it, categoryDisplay) }

        rvGrid.adapter = MovieGridAdapter(
            movies = filtered,
            onMovieClick = { det ->
                // Abrimos detalles (puedes cambiar a ruteo directo al player si querés)
                val i = Intent(this, MovieDetailsActivity::class.java).apply {
                    putExtra(MovieDetailsActivity.EXTRA_MOVIE, det)
                }
                startActivity(i)
            }
        )
    }

    // ====== Helpers de categorías (mini-canon, consistente con tu MoviesActivity) ======
    private fun hasCategory(m: DetailedMovie, selectedDisplay: String): Boolean {
        val targetKey = normalizeKey(canonicalize(selectedDisplay))
        return categoriesOf(m).any { normalizeKey(it) == targetKey }
    }

    private fun categoriesOf(m: DetailedMovie): List<String> {
        val ordered = LinkedHashSet<String>()
        m.genre.forEach { g ->
            val c = canonicalize(g)
            if (c.isNotBlank()) ordered.add(c)
        }
        val raw = m.category ?: ""
        if (raw.isNotBlank()) {
            raw.split("/", "|", "·", ",", ";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { c ->
                    val can = canonicalize(c)
                    if (can.isNotBlank()) ordered.add(can)
                }
        }
        return if (ordered.isEmpty()) listOf("Otros") else ordered.toList()
    }

    private fun canonicalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return when (normalizeKey(raw)) {
            "anime", "animation", "animacion" -> "Animación"
            "sci fi", "scifi", "science fiction", "ciencia ficcion" -> "Ciencia Ficción"
            "thriller" -> "Suspenso"
            "horror" -> "Terror"
            "crime", "policial" -> "Crimen"
            "documentary" -> "Documental"
            "biography", "biografia" -> "Biografía"
            "history", "historico", "historia" -> "Historia"
            "family" -> "Familia"
            "fantasy", "fantasia" -> "Fantasía"
            "adventure", "aventura" -> "Aventura"
            "action", "accion" -> "Acción"
            "comedy", "comedia" -> "Comedia"
            "drama" -> "Drama"
            "mystery", "misterio" -> "Misterio"
            "war", "guerra" -> "Guerra"
            "western" -> "Western"
            else -> prettify(raw)
        }
    }

    private fun normalizeKey(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val noAccents = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        return noAccents.lowercase(Locale.ROOT).replace("\\s+".toRegex(), " ").trim()
    }

    private fun prettify(s: String): String {
        val lower = s.lowercase(Locale.ROOT)
        return lower.split(" ").joinToString(" ") { w ->
            if (w.length <= 2) w.uppercase(Locale.ROOT)
            else w.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }.replace("Ficcion", "Ficción")
            .replace("Superheroes", "Superhéroes")
            .replace("Biografia", "Biografía")
            .replace("Fantasia", "Fantasía")
            .replace("Accion", "Acción")
    }
}
