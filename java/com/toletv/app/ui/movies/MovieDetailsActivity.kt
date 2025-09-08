package com.toletv.app.ui.movies

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.tv67777.Movie
import com.toletv.app.R
import com.toletv.app.data.model.DetailedMovie
import com.toletv.app.ui.player.WebPlayerActivity   // usamos tu WebPlayerActivity

class MovieDetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MOVIE = "extra_movie" // DetailedMovie o Movie simple

        // Extras opcionales si llega Movie simple:
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_DESCRIPTION = "extra_description"
        const val EXTRA_DURATION_MIN = "extra_duration_min" // Int
        const val EXTRA_RATING = "extra_rating"             // Double
        const val EXTRA_YEAR = "extra_year"                 // Inta
        const val EXTRA_STREAM_URL = "extra_stream_url"     // String

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_POSTER = "extra_poster"


    }

    // Views del layout original (compatibilidad)
    private lateinit var ivPoster: ImageView
    private lateinit var tvCategory: TextView
    private lateinit var tvYear: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvRating: TextView
    private lateinit var tvDescription: TextView
    private lateinit var dot1: View
    private lateinit var dot2: View

    // Views del nuevo layout Netflix
    private lateinit var tvTitle: TextView           // Header title
    private lateinit var tvMovieTitle: TextView      // Main title
    private lateinit var ivPosterMain: ImageView     // Main poster (right side)
    private lateinit var btnBack: ImageView
    private lateinit var btnPlay: Button

    // Inline meta arriba del botón (opcionales: solo existen si los agregaste en el XML)
    private var tvCategoryInline: TextView? = null
    private var tvYearInline: TextView? = null
    private var tvRatingInline: TextView? = null
    private var dotInline1: View? = null
    private var dotInline2: View? = null

    // Preferimos usar la meta inline (si existe) y ocultar la inferior
    private val preferInlineMeta: Boolean = true
    private fun useInlineMeta(): Boolean {
        return preferInlineMeta &&
                (tvCategoryInline != null || tvYearInline != null || tvRatingInline != null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movie_details)

        // Initialize views - Original layout (hidden/compatibility)
        ivPoster      = findViewById(R.id.ivPoster)
        tvCategory    = findViewById(R.id.tvCategory)
        tvYear        = findViewById(R.id.tvYear)
        tvDuration    = findViewById(R.id.tvDuration)
        tvRating      = findViewById(R.id.tvRating)
        tvDescription = findViewById(R.id.tvDescription)
        dot1          = findViewById(R.id.dot1)
        dot2          = findViewById(R.id.dot2)

        // Initialize views - New Netflix layout
        tvTitle       = findViewById(R.id.tvTitle)
        tvMovieTitle  = findViewById(R.id.tvMovieTitle)
        ivPosterMain  = findViewById(R.id.ivPosterMain)
        btnBack       = findViewById(R.id.btnBack)
        btnPlay       = findViewById(R.id.btnPlay)

        // Inline meta (si existen en el layout)
        tvCategoryInline = findViewByIdOrNull(R.id.tvCategoryInline)
        tvYearInline     = findViewByIdOrNull(R.id.tvYearInline)
        tvRatingInline   = findViewByIdOrNull(R.id.tvRatingInline)
        dotInline1       = findViewByIdOrNull(R.id.dotInline1)
        dotInline2       = findViewByIdOrNull(R.id.dotInline2)

        // Aseguramos que el botón esté visible/clickable y al frente
        btnPlay.visibility = View.VISIBLE
        btnPlay.isClickable = true
        btnPlay.isFocusable = true
        btnPlay.bringToFront()

        // 1) DetailedMovie primero
        val detailed: DetailedMovie? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_MOVIE, DetailedMovie::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_MOVIE)
        }

        // 2) Si no llegó, probamos con Movie simple
        val simple: Movie? = if (detailed == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_MOVIE, Movie::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_MOVIE)
            }
        } else null

        if (detailed != null) {
            // ===== DetailedMovie =====
            setMovieTitle(detailed.title)
            loadPosterImage(safeUrl(detailed.posterUrl))
            loadImage(safeUrl(detailed.posterUrl), ivPoster) // Compatibility

            setTextOrHideDual(detailed.category)
            setMetaYearDual(detailed.year)
            setMetaDuration(detailed.duration)
            setMetaRatingDual(detailed.rating?.toDouble())
            setDescription(detailed.description.ifBlank { detailed.synopsis })

            setPlayButton(detailed.streamUrl)

        } else if (simple != null) {
            // ===== Movie simple =====
            setMovieTitle(simple.title.orEmpty())
            loadPosterImage(safeUrl(simple.logoUrl))
            loadImage(safeUrl(simple.logoUrl), ivPoster) // Compatibility

            val category    = intent.getStringExtra(EXTRA_CATEGORY) ?: simple.category
            val description = intent.getStringExtra(EXTRA_DESCRIPTION) ?: simple.description
            val durationMin = intent.getIntExtra(EXTRA_DURATION_MIN, simple.duration ?: 0)
            // Tu Movie NO tiene year/rating → usamos SOLO los extras (o 0/0.0 si no llegan)
            val rating      = if (intent.hasExtra(EXTRA_RATING)) intent.getDoubleExtra(EXTRA_RATING, 0.0) else 0.0
            val year        = intent.getIntExtra(EXTRA_YEAR, 0)
            val streamUrl   = intent.getStringExtra(EXTRA_STREAM_URL) ?: simple.streamUrl

            setTextOrHideDual(category)
            setMetaYearDual(year)
            setMetaDuration(durationMin)
            setMetaRatingDual(rating)
            setDescription(description)

            setPlayButton(streamUrl)
        }
        else {
            // Fallback: llegaron extras sueltos
            val title       = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
            val posterUrl   = safeUrl(intent.getStringExtra(EXTRA_POSTER))
            val category    = intent.getStringExtra(EXTRA_CATEGORY).orEmpty()
            val durationMin = intent.getIntExtra(EXTRA_DURATION_MIN, 0)
            val rating      = if (intent.hasExtra(EXTRA_RATING)) intent.getDoubleExtra(EXTRA_RATING, 0.0) else 0.0
            val year        = intent.getIntExtra(EXTRA_YEAR, 0)
            val streamUrl   = intent.getStringExtra(EXTRA_STREAM_URL)

            setMovieTitle(title)
            loadPosterImage(posterUrl)
            loadImage(posterUrl, ivPoster) // compat

            setTextOrHideDual(category)
            setMetaYearDual(year)
            setMetaDuration(durationMin)
            setMetaRatingDual(rating)
            setDescription(description)

            setPlayButton(streamUrl) // ¡activa el botón Reproducir!
        }

        btnBack.setOnClickListener { finish() }
    }

    // -------- New Netflix Layout helpers --------

    private fun setMovieTitle(title: String) {
        // Set title en header y main title
        tvTitle.text = title
        tvMovieTitle.text = title
    }

    private fun loadPosterImage(url: String?) {
        val opts = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .dontAnimate()
            .placeholder(R.drawable.ic_play)
            .error(R.drawable.ic_play)

        Glide.with(this).load(url ?: R.drawable.ic_play).apply(opts).into(ivPosterMain)
    }

    private fun setDescription(description: String?) {
        val desc = description?.trim().orEmpty()
        if (desc.isEmpty()) {
            tvDescription.visibility = View.GONE
        } else {
            tvDescription.visibility = View.VISIBLE
            tvDescription.text = desc
        }
    }

    // -------- UI helpers (dual: inline + sección inferior) --------

    /** Categoría: muestra inline y oculta abajo si hay inline. */
    private fun setTextOrHideDual(category: String?) {
        val t = category?.trim().orEmpty()

        // INLINE
        tvCategoryInline?.let { v ->
            if (t.isEmpty()) v.visibility = View.GONE
            else { v.visibility = View.VISIBLE; v.text = t }
        }

        // INFERIOR
        if (useInlineMeta()) {
            tvCategory.visibility = View.GONE
        } else {
            if (t.isEmpty()) tvCategory.visibility = View.GONE
            else { tvCategory.visibility = View.VISIBLE; tvCategory.text = t }
        }

        // recalcular separadores inline
        updateInlineDots()
    }

    /** Año: muestra inline y oculta abajo si hay inline. */
    private fun setMetaYearDual(year: Int) {
        val hasYear = year > 0

        // INLINE
        tvYearInline?.apply {
            visibility = if (hasYear) View.VISIBLE else View.GONE
            if (hasYear) text = year.toString()
        }

        // INFERIOR
        if (useInlineMeta()) {
            tvYear.visibility = View.GONE
        } else {
            if (hasYear) {
                tvYear.visibility = View.VISIBLE
                tvYear.text = year.toString()
            } else {
                tvYear.visibility = View.GONE
            }
        }

        updateInlineDots()
        updateBottomDots()
    }

    /** Duración: se mantiene abajo; no se muestra inline. */
    private fun setMetaDuration(min: Int) {
        if (min > 0) {
            tvDuration.visibility = View.VISIBLE
            val h = min / 60
            val m = min % 60
            tvDuration.text = if (h > 0) "${h}h ${m}m" else "${m}m"
        } else {
            tvDuration.visibility = View.GONE
        }
        updateBottomDots()
    }

    /** Rating: muestra inline y oculta abajo si hay inline. */
    private fun setMetaRatingDual(r: Double?) {
        val hasRating = r != null && r > 0.0

        // INLINE
        tvRatingInline?.apply {
            visibility = if (hasRating) View.VISIBLE else View.GONE
            if (hasRating) text = "⭐ %.1f".format(r)
        }

        // INFERIOR
        if (useInlineMeta()) {
            tvRating.visibility = View.GONE
        } else {
            if (hasRating) {
                tvRating.visibility = View.VISIBLE
                tvRating.text = "⭐ %.1f".format(r)
            } else {
                tvRating.visibility = View.GONE
            }
        }

        updateInlineDots()
        updateBottomDots()
    }

    /** Dots/separadores de la fila inline (arriba del botón). */
    private fun updateInlineDots() {
        val catVisible  = tvCategoryInline?.visibility == View.VISIBLE
        val yearVisible = tvYearInline?.visibility == View.VISIBLE
        val rateVisible = tvRatingInline?.visibility == View.VISIBLE

        dotInline1?.visibility = if (catVisible && (yearVisible || rateVisible)) View.VISIBLE else View.GONE
        dotInline2?.visibility = if (yearVisible && rateVisible) View.VISIBLE else View.GONE
    }

    /** Dots/separadores de la fila inferior de meta. */
    private fun updateBottomDots() {
        // Si usamos inline, ocultamos los puntos inferiores para que no quede nada colgando.
        if (useInlineMeta()) {
            dot1.visibility = View.GONE
            dot2.visibility = View.GONE
            return
        }
        dot1.visibility = if (tvYear.visibility == View.VISIBLE && tvDuration.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        dot2.visibility = if (tvDuration.visibility == View.VISIBLE && tvRating.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    // -------- Reproducción --------

    private fun setPlayButton(url: String?) {
        btnPlay.visibility = if (!url.isNullOrBlank()) {
            btnPlay.setOnClickListener {
                // Películas: WebPlayerActivity con handoff habilitado
                startActivity(Intent(this, WebPlayerActivity::class.java).apply {
                    putExtra("url", url)                                       // WebPlayerActivity acepta "url"/"web_url"/"streamUrl"
                    putExtra("title", tvMovieTitle.text?.toString() ?: "Video") // Use main title
                    putExtra("ALLOW_NATIVE_HANDOFF", true)                      // 👈 habilita salto a ExoPlayer para películas
                })
            }
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    // (Opcional) Fallback a players externos
    private fun tryStartExternalPlayer(rawUrl: String) {
        val url = safeUrl(rawUrl) ?: return
        val uri = Uri.parse(url)

        // 0) Intent pelado (sin MIME)
        val i0 = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        if (launchIfResolvable(i0)) return

        // 1) MIME por extensión
        val mime = guessMime(url)
        val i1 = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addCategory(Intent.CATEGORY_BROWSABLE)
        if (launchIfResolvable(i1)) return

        // 2) Genérico video/*
        val i2 = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*").addCategory(Intent.CATEGORY_BROWSABLE)
        if (launchIfResolvable(i2)) return

        // 3) Players conocidos
        val known = listOf("org.videolan.vlc","com.mxtech.videoplayer.ad","com.mxtech.videoplayer.pro","com.brouken.player")
        for (pkg in known) {
            val ix = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).setPackage(pkg).addCategory(Intent.CATEGORY_BROWSABLE)
            if (launchIfResolvable(ix)) return
        }

        // 4) Chrome explícito
        val chrome = Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.chrome").addCategory(Intent.CATEGORY_BROWSABLE)
        if (launchIfResolvable(chrome)) return

        // 5) Invitar a instalar VLC
        promptInstallVLC()
    }

    private fun launchIfResolvable(intent: Intent): Boolean {
        return try {
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(Intent.createChooser(intent, "Abrir con…"))
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun promptInstallVLC() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=org.videolan.vlc")))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=org.videolan.vlc")))
        }
        Toast.makeText(this, "Instalá VLC para reproducir enlaces HLS/DASH (.m3u8/.mpd).", Toast.LENGTH_LONG).show()
    }

    private fun guessMime(url: String): String {
        val u = url.lowercase()
        return when {
            u.endsWith(".mpd")  -> "application/dash+xml"
            u.endsWith(".mp4")  -> "video/mp4"
            u.endsWith(".webm") -> "video/webm"
            u.endsWith(".mkv")  -> "video/x-matroska"
            else -> "video/*"
        }
    }

    // -------- Utilitarios --------

    private fun <T : View> findViewByIdOrNull(id: Int): T? = try {
        findViewById(id)
    } catch (_: Exception) {
        null
    }

    private fun safeUrl(u: String?): String? {
        val s = u?.trim().takeUnless { it.isNullOrEmpty() || it.equals("null", true) }
        return when {
            s == null -> null
            s.startsWith("http://") -> s.replaceFirst("http://","https://")
            else -> s
        }
    }

    private fun loadImage(url: String?, target: ImageView) {
        val opts = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .dontAnimate()
            .placeholder(R.drawable.ic_play)
            .error(R.drawable.ic_play)

        Glide.with(this).load(url ?: R.drawable.ic_play).apply(opts).into(target)
    }
}
