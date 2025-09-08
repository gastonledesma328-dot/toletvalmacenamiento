package com.toletv.app.ui.movies

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.toletv.app.R
import com.toletv.app.data.model.DetailedMovie
import com.toletv.app.ui.adapters.MovieAdapter
import com.toletv.app.utils.MovieDataLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import com.example.tv67777.Movie
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner

class MoviesActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageView
    private lateinit var spCategory: Spinner
    private lateinit var rvFeatured: RecyclerView
    private lateinit var rvCategories: RecyclerView
    private lateinit var ivBackdrop: ImageView

    // Búsqueda
    private lateinit var btnSearch: ImageView
    private lateinit var searchBar: LinearLayout
    private lateinit var etSearch: EditText
    private lateinit var btnClear: ImageView

    // Botón (opcional) para abrir grilla externa
    private var btnOpenGrid: ImageView? = null

    private lateinit var adapterFeatured: MovieAdapter
    private lateinit var adapterCategories: MovieCategoriesAdapter
    private var adapterGrid: DetailedGridAdapter? = null

    private lateinit var movieDataLoader: MovieDataLoader

    private var allMovies: List<DetailedMovie> = emptyList()
    private var currentCategory: String = "Todas"
    private var currentMapById: Map<Int, DetailedMovie> = emptyMap()
    private var currentQuery: String = ""

    // Spinner dinámico
    private lateinit var spinnerAdapter: ArrayAdapter<String>

    // Estadísticas/permitidos (claves normalizadas)
    private var globalCatStats: Map<String, CatStat> = emptyMap()
    private var allowedCategories: Set<String> = emptySet()

    // Parámetros de unificación
    private val MIN_CAT_OCCURRENCES = 2
    private val RARE_CATEGORIES_CAP = 2

    private var lastGridFocusPos: Int = 0


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movies)

        movieDataLoader = MovieDataLoader(this)
        bindViews()
        setupFeaturedRecycler()
        setupCategoriesRecycler()
        setupSpinner()
        setupSearchUi()
        loadMovies()

        onBackPressedDispatcher.addCallback(this) {
            if (searchBar.isVisible) {
                toggleSearch(false)
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun bindViews() {
        btnBack      = findViewById(R.id.btnBack)
        spCategory   = findViewById(R.id.spCategory)
        rvFeatured   = findViewById(R.id.recyclerViewFeatured)
        rvCategories = findViewById(R.id.recyclerViewCategories)
        ivBackdrop   = findViewById(R.id.ivBackdrop)

        btnSearch = findViewById(R.id.btnSearch)
        searchBar = findViewById(R.id.searchBar)
        etSearch  = findViewById(R.id.etSearch)
        btnClear  = findViewById(R.id.btnClear)

        btnOpenGrid = findViewById(R.id.btnOpenGrid)
        btnOpenGrid?.setOnClickListener { openCategoryGrid("Acción") }

        btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    /** Carrusel horizontal “Destacadas” */
    private fun setupFeaturedRecycler() {
        adapterFeatured = MovieAdapter(
            movies = emptyList(),
            onMovieClick = { selected ->
                val det = selected.id?.let { currentMapById[it] }
                if (det != null) {
                    openDetails(det)
                } else {
                    val intent = Intent(this, MovieDetailsActivity::class.java)
                    intent.putExtra(MovieDetailsActivity.EXTRA_MOVIE, selected)
                    startActivity(intent)
                }
            }
        )
        rvFeatured.apply {
            layoutManager = LinearLayoutManager(
                this@MoviesActivity,
                LinearLayoutManager.HORIZONTAL,
                false
            )
            adapter = adapterFeatured
            setHasFixedSize(true)
        }
    }

    /** Lista vertical de categorías (modo "Todas") */
    private fun setupCategoriesRecycler() {
        adapterCategories = MovieCategoriesAdapter(
            categories = emptyList(),
            onMovieClick = { det -> openDetails(det) },
            onMovieFocused = null
        )
        rvCategories.apply {
            layoutManager = LinearLayoutManager(this@MoviesActivity)
            adapter = adapterCategories
            setHasFixedSize(true)
            isNestedScrollingEnabled = false
        }
    }

    // ===== Spinner dinámico =====
    private fun setupSpinner() {
        spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            mutableListOf("Todas")
        )
        spCategory.adapter = spinnerAdapter

        spCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                currentCategory = spinnerAdapter.getItem(position) ?: "Todas"
                applyFilterAndBind()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun rebuildSpinnerCategories() {
        val previous = currentCategory

        // 1) stats por categoría canon (conteo + mejor rating)
        globalCatStats = computeCategoryStats(allMovies)

        // 2) decidir categorías permitidas
        allowedCategories = pickAllowedCategories(globalCatStats)

        // 3) construir lista visible para el spinner (usar display bonito)
        val dynamicCats = globalCatStats
            .filter { (normKey, _) -> allowedCategories.contains(normKey) }
            .entries
            .sortedBy { it.value.display }
            .map { it.value.display }

        val finalList = mutableListOf("Todas").apply { addAll(dynamicCats) }

        spinnerAdapter.clear()
        spinnerAdapter.addAll(finalList)
        spinnerAdapter.notifyDataSetChanged()

        // Restaurar selección si se puede
        val restore = if (previous != "Todas" && finalList.any { equalsNormalized(it, previous) }) {
            finalList.first { equalsNormalized(it, previous) }
        } else "Todas"

        val idx = finalList.indexOfFirst { it == restore }
        if (idx >= 0) {
            spCategory.setSelection(idx, false)
            currentCategory = restore
        } else {
            spCategory.setSelection(0, false)
            currentCategory = "Todas"
        }
    }

    // ====== Búsqueda ======
    private fun setupSearchUi() {
        btnSearch.setOnClickListener { toggleSearch(true) }
        btnClear.setOnClickListener {
            etSearch.setText("")
            etSearch.requestFocus()
            showKeyboard(etSearch)
        }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString()?.trim().orEmpty()
                applyFilterAndBind()
            }
        })
        etSearch.setOnEditorActionListener { _, _, _ ->
            hideKeyboard(etSearch); true
        }
    }

    private fun toggleSearch(show: Boolean) {
        searchBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            etSearch.setText("")
            currentQuery = ""
            etSearch.requestFocus()
            showKeyboard(etSearch)
        } else {
            hideKeyboard(etSearch)
        }
    }

    private fun showKeyboard(v: View) {
        v.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(v: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    // ====== Carga de películas ======
    private fun loadMovies() {
        CoroutineScope(Dispatchers.IO).launch {
            val url = MovieDataLoader.DEFAULT_MOVIES_URL + "?t=" + System.currentTimeMillis()
            val remote = movieDataLoader.loadMoviesFromUrl(url, cacheLocally = true)
            val result = remote.getOrElse {
                movieDataLoader.loadCachedMovies().getOrElse { emptyList() }
            }
            withContext(Dispatchers.Main) {
                allMovies = result
                rebuildSpinnerCategories()
                applyFilterAndBind()
            }
        }
    }

    /** Aplica filtro y refresca UI */
    private fun applyFilterAndBind() {
        // 1) Filtrado por categoría
        val byCategory = if (currentCategory == "Todas") {
            allMovies
        } else {
            allMovies.filter { m -> hasCategory(m, currentCategory) }
        }

        // 2) Filtro por texto
        val filtered = if (currentQuery.isBlank()) {
            byCategory
        } else {
            val target = normalizeForSearch(currentQuery)
            byCategory.filter { m -> normalizeForSearch(m.title).contains(target) }
        }

        // 3) DESTACADAS
        val pool = filtered.sortedByDescending { it.rating.toFloat() }.take(50)
        val featured = pool.shuffled().take(15)

        // Fondo
        val firstForBg = when {
            featured.isNotEmpty() -> featured.first()
            filtered.isNotEmpty() -> filtered.first()
            allMovies.isNotEmpty() -> allMovies.first()
            else -> null
        }
        setBackdrop(firstForBg?.backdropUrl ?: firstForBg?.posterUrl)

        // 4) Vista principal:
        if (normalizeCategoryKey(currentCategory) == "todas") {
            // Modo LISTA DE CATEGORÍAS (carouseles horizontales)
            val buckets: List<MovieCategory> = buildUnifiedCategories(filtered)
            switchToCategoryList(buckets)
        } else {
            // Modo GRILLA (todas las pelis de la categoría actual, 4 por fila)
            switchToGrid(filtered)
        }

        // Mapa id -> DetailedMovie (para abrir detalles desde Destacadas)
        currentMapById = filtered.associateBy { it.id }

        // Carrusel destacadas
        adapterFeatured.updateMovies(featured.map { it.toSimpleMovieCompat() })
    }

    /** Cambia rvCategories a grilla con 4 columnas y TODAS las pelis */
    private fun switchToGrid(movies: List<DetailedMovie>) {
        val data = movies // mostramos todas

        if (rvCategories.layoutManager !is GridLayoutManager) {
            rvCategories.layoutManager = GridLayoutManager(this, 4) // 4 por fila
            rvCategories.isNestedScrollingEnabled = true
            rvCategories.setHasFixedSize(true)

            // Espaciado prolijo entre pósters (usa LocalSpacingDecoration para evitar conflictos de nombres)
            if (rvCategories.itemDecorationCount == 0) {
                val gap = resources.getDimensionPixelSize(R.dimen.poster_spacing)
                rvCategories.addItemDecoration(LocalSpacingDecoration(gap))
            }
        } else {
            (rvCategories.layoutManager as GridLayoutManager).spanCount = 4
        }

        if (adapterGrid == null) {
            adapterGrid = DetailedGridAdapter(emptyList()) { openDetails(it) }
            rvCategories.adapter = adapterGrid
        } else if (rvCategories.adapter !== adapterGrid) {
            rvCategories.adapter = adapterGrid
        }

        adapterGrid?.update(data)
    }

    /** Cambia rvCategories a lista por secciones (categorías) */
    private fun switchToCategoryList(buckets: List<MovieCategory>) {
        if (rvCategories.layoutManager !is LinearLayoutManager) {
            rvCategories.layoutManager = LinearLayoutManager(this)
            rvCategories.isNestedScrollingEnabled = false // lista “padre” con hijos
        }
        rvCategories.adapter = adapterCategories
        adapterCategories.updateCategories(buckets)
    }

    /** Agrupa usando categoría primaria canónica y PERMITIDA */
    private fun buildUnifiedCategories(movies: List<DetailedMovie>): List<MovieCategory> {
        if (movies.isEmpty()) return emptyList()
        val map = LinkedHashMap<String, MutableList<DetailedMovie>>()
        for (m in movies) {
            val key = unifiedPrimaryCategoryOf(m).ifBlank { "Otros" }
            map.getOrPut(key) { mutableListOf() }.add(m)
        }
        return map.entries.map { (name, list) ->
            MovieCategory(name = name, movies = list.sortedByDescending { it.rating.toFloat() })
        }.sortedBy { normalizeCategoryKey(it.name) }
    }

    private fun openDetails(m: DetailedMovie) {
        val intent = Intent(this, MovieDetailsActivity::class.java).apply {
            putExtra(MovieDetailsActivity.EXTRA_MOVIE, m)
        }
        startActivity(intent)
    }

    /** Abre la grilla externa de una categoría (si querés seguir usando ese botón) */
    private fun openCategoryGrid(category: String) {
        startActivity(
            Intent(this, CategoryGridActivity::class.java)
                .putExtra(CategoryGridActivity.EXTRA_CATEGORY, category)
        )
    }

    // ========= Helpers de categorías (con canonización) =========

    private fun primaryCategoryOf(m: DetailedMovie): String {
        m.genre.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return canonicalizeCategory(it)
        }
        val raw = m.category.orEmpty()
        if (raw.isBlank()) return "Otros"
        val first = raw.split("/", "|", "·", ",", ";")
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
        return canonicalizeCategory(first ?: "Otros")
    }

    private fun categoriesOf(m: DetailedMovie): List<String> {
        val ordered = LinkedHashSet<String>()
        m.genre.forEach { g ->
            val canon = canonicalizeCategory(g)
            if (canon.isNotBlank()) ordered.add(canon)
        }
        val raw = m.category.orElse("")
        if (raw.isNotBlank()) {
            raw.split("/", "|", "·", ",", ";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { c ->
                    val canon = canonicalizeCategory(c)
                    if (canon.isNotBlank()) ordered.add(canon)
                }
        }
        return if (ordered.isEmpty()) listOf("Otros") else ordered.toList()
    }

    private fun hasCategory(m: DetailedMovie, selected: String): Boolean {
        val targetKey = normalizeCategoryKey(selected)
        if (targetKey.isBlank()) return true
        if (!allowedCategories.contains(targetKey)) return false
        return categoriesOf(m).any { c -> normalizeCategoryKey(c) == targetKey }
    }

    private fun unifiedPrimaryCategoryOf(m: DetailedMovie): String {
        val all = categoriesOf(m)
        if (all.isEmpty()) return "Otros"
        val primary = all.first()
        val primaryKey = normalizeCategoryKey(primary)
        if (allowedCategories.contains(primaryKey)) return primary
        val fallback = all.firstOrNull { allowedCategories.contains(normalizeCategoryKey(it)) }
        return fallback ?: primary.ifBlank { "Otros" }
    }

    // ====== Estadísticas de categorías ======
    private data class CatStat(
        var count: Int = 0,
        var display: String = "",
        var bestRating: Float = 0f
    )

    private fun computeCategoryStats(movies: List<DetailedMovie>): Map<String, CatStat> {
        val map = HashMap<String, CatStat>()
        for (m in movies) {
            val r: Float = m.rating.toFloat()
            for (canon in categoriesOf(m)) {
                val key = normalizeCategoryKey(canon)
                if (key.isBlank()) continue
                val stat = map.getOrPut(key) { CatStat(0, canon, r) }
                stat.count += 1
                if (r > stat.bestRating) stat.bestRating = r
            }
        }
        map.values.forEach { it.display = prettifyCategory(it.display) }
        return map
    }

    private fun pickAllowedCategories(stats: Map<String, CatStat>): Set<String> {
        val commons = stats.filter { it.value.count >= MIN_CAT_OCCURRENCES }.keys.toMutableSet()
        val raresSorted: List<String> = stats
            .filter { it.value.count < MIN_CAT_OCCURRENCES }
            .toList()
            .sortedWith(
                compareByDescending<Pair<String, CatStat>> { it.second.bestRating }
                    .thenBy { it.first }
            )
            .map { it.first }
        val raresPicked = raresSorted.take(RARE_CATEGORIES_CAP)
        commons.addAll(raresPicked)
        return commons
    }

    // ========= Normalización / Canonización =========
    private fun canonicalizeCategory(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val key = normalizeCategoryKey(raw)
        return when (key) {
            "anime", "animation", "animacion" -> "Animación"
            "sci fi", "scifi", "science fiction", "ciencia ficcion" -> "Ciencia Ficción"
            "superheroes", "super heroe", "superheroe", "super heroe s", "super hero", "super hero es" -> "Superhéroes"
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
            else -> prettifyCategory(raw.trim())
        }
    }

    private fun normalizeCategoryKey(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val noAccents = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        return noAccents.lowercase(Locale.ROOT).replace("\\s+".toRegex(), " ").trim()
    }

    private fun prettifyCategory(s: String): String {
        val lower = s.lowercase(Locale.ROOT)
        return lower.split(" ").joinToString(" ") { word ->
            if (word.length <= 2) word.uppercase(Locale.ROOT)
            else word.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }.replace("Ficcion", "Ficción")
            .replace("Superheroes", "Superhéroes")
            .replace("Biografia", "Biografía")
            .replace("Fantasia", "Fantasía")
            .replace("Accion", "Acción")
    }

    private fun equalsNormalized(a: String, b: String): Boolean =
        normalizeCategoryKey(a) == normalizeCategoryKey(b)

    private fun normalizeForSearch(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val noAccents = Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        return noAccents.lowercase(Locale.ROOT)
    }

    private fun setBackdrop(url: String?) {
        if (url.isNullOrBlank()) return
        Glide.with(this).load(url).into(ivBackdrop)
    }

    private fun String?.orElse(fallback: String) = this ?: fallback

    // ================== ADAPTER DE GRILLA (INTERNO) ==================
    private class DetailedGridAdapter(
        private var movies: List<DetailedMovie>,
        private val onMovieClick: (DetailedMovie) -> Unit
    ) : RecyclerView.Adapter<DetailedGridAdapter.VH>() {

        companion object { private const val CLICK_DEBOUNCE_MS = 350L }

        init { setHasStableIds(true) }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.ivMoviePoster)
            val focusOverlay: View? = v.findViewById(R.id.focusOverlay)
            var lastClick: Long = 0L
        }

        override fun getItemId(position: Int): Long = movies[position].id.toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater(parent).inflate(R.layout.item_movie, parent, false)
            v.isFocusable = true
            v.isClickable = true
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val m = movies[pos]

            // Respeta tamaño de póster definido en dimens
            val wPx  = h.itemView.resources.getDimensionPixelSize(R.dimen.poster_w)
            val hPx  = h.itemView.resources.getDimensionPixelSize(R.dimen.poster_h)

            Glide.with(h.itemView.context)
                .load(m.posterUrl?.takeIf { it.isNotBlank() } ?: R.drawable.ic_play)
                .override(wPx, hPx)
                .centerCrop()
                .into(h.poster)

            h.itemView.contentDescription = m.title

            h.itemView.setOnClickListener {
                val now = SystemClock.elapsedRealtime()
                if (now - h.lastClick < CLICK_DEBOUNCE_MS) return@setOnClickListener
                h.lastClick = now
                onMovieClick(m)
            }

            h.itemView.setOnKeyListener { v, keyCode, event ->
                if (event.action == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    v.performClick()
                    true
                } else false
            }

            h.itemView.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.05f else 1f
                v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
                h.focusOverlay?.apply {
                    visibility = if (hasFocus) View.VISIBLE else View.GONE
                    alpha = if (hasFocus) 0.15f else 0f
                }
            }
        }

        override fun getItemCount(): Int = movies.size

        fun update(newList: List<DetailedMovie>) {
            movies = newList
            notifyDataSetChanged()
        }

        private fun layoutInflater(parent: ViewGroup) =
            android.view.LayoutInflater.from(parent.context)
    }

    /** ItemDecoration local para espaciado de grilla */
    private class LocalSpacingDecoration(private val space: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, v: View, parent: RecyclerView, state: RecyclerView.State) {
            outRect.set(space, space, space, space)
        }
    }
}

/** Adaptador a Movie simple (carrusel) */
private fun DetailedMovie.toSimpleMovieCompat(): Movie {
    return Movie(
        id = this.id,
        title = this.title,
        description = this.description,
        logoUrl = this.posterUrl,
        streamUrl = this.streamUrl,
        streamType = this.streamType,
        category = this.category
    )
}
