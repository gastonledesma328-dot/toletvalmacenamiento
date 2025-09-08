package com.toletv.app.ui.adapters

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.tv67777.Movie
import com.toletv.app.R
import com.toletv.app.data.model.StreamType
import com.toletv.app.ui.player.VideoPlayerActivity
import com.toletv.app.ui.player.WebPlayerActivity2

class MovieAdapter(
    private var movies: List<Movie>,
    private val onMovieClick: (Movie) -> Unit = {},                 // respeta callback del caller
    private val onMovieFocused: ((Movie) -> Unit)? = null,
    private val onMovieClickWithIndex: ((Movie, Int) -> Unit)? = null,
    private val context: Context? = null,                           // opcional para autoRouteOnClick
    private val autoRouteOnClick: Boolean = false                   // si true, abre player directamente
) : RecyclerView.Adapter<MovieAdapter.MovieViewHolder>() {

    companion object {
        private const val MAX_ITEMS = 5
        private const val CLICK_DEBOUNCE_MS = 350L
    }

    init { setHasStableIds(true) }

    class MovieViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivPoster: ImageView = itemView.findViewById(R.id.ivMoviePoster)
        val focusOverlay: View? = itemView.findViewById(R.id.focusOverlay) // opcional en el layout
        var lastClickTime: Long = 0
    }

    override fun getItemId(position: Int): Long = movies[position].id.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MovieViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_featured_movie, parent, false)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.isClickable = true
        return MovieViewHolder(view)
    }

    override fun onBindViewHolder(holder: MovieViewHolder, position: Int) {
        val movie = movies[position]

        // Imagen (simple, sin CustomTarget; el layout define el tamaño, usamos centerCrop)
        val imgUrl = safeUrl(movie.logoUrl)
        val glideOpts = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .dontAnimate()
            .placeholder(R.drawable.ic_play)
            .error(R.drawable.ic_play)

        Glide.with(holder.itemView.context)
            .load(imgUrl ?: R.drawable.ic_play)
            .apply(glideOpts)
            .centerCrop()
            .into(holder.ivPoster)

        // Accesibilidad
        holder.itemView.contentDescription = movie.title

        // Click con debounce
        holder.itemView.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - holder.lastClickTime < CLICK_DEBOUNCE_MS) return@setOnClickListener
            holder.lastClickTime = now

            val idx = holder.bindingAdapterPosition
            if (idx == RecyclerView.NO_POSITION) return@setOnClickListener
            val m = movies[idx]

            // Si querés abrir el player directo (sin callback/Detalles):
            if (autoRouteOnClick && context != null) {
                routeToPlayer(context, m)
                return@setOnClickListener
            }

            // Si hay callback con índice, úsalo; sino el callback simple
            onMovieClickWithIndex?.invoke(m, idx) ?: onMovieClick(m)
        }

        // DPAD/ENTER delegan al click
        holder.itemView.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                v.performClick()
                true
            } else false
        }

        // Efecto de foco + overlay opcional
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.05f else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(160).start()
            holder.focusOverlay?.apply {
                visibility = if (hasFocus) View.VISIBLE else View.GONE
                alpha = if (hasFocus) 0.15f else 0f
            }
            if (hasFocus) onMovieFocused?.invoke(movie)
        }
    }

    override fun getItemCount(): Int = minOf(movies.size, MAX_ITEMS)

    fun updateMovies(newMovies: List<Movie>) {
        movies = newMovies.take(MAX_ITEMS)
        notifyDataSetChanged()
    }

    fun getMovies(): List<Movie> = movies

    // --------- Ruteo opcional ----------
    private fun routeToPlayer(ctx: Context, movie: Movie) {
        val url = movie.streamUrl?.trim().orEmpty()
        if (url.isEmpty()) return
        val title = movie.title

        when (movie.streamType) {
            StreamType.M3U8 -> {
                ctx.startActivity(
                    Intent(ctx, VideoPlayerActivity::class.java).apply {
                        putExtra("VIDEO_URL", url)
                        putExtra("title", title)
                        putExtra("is_live", false)
                    }
                )
            }
            StreamType.WE2 -> {
                ctx.startActivity(
                    Intent(ctx, WebPlayerActivity2::class.java).apply {
                        putExtra("web_url", url)
                        putExtra("title", title)
                        putExtra("STREAM_TYPE", "WE2")
                        putExtra("ALLOW_NATIVE_HANDOFF", true)
                        putExtra("SNIFF_WHITELIST", arrayOf(".m3u8", ".mpd", ".ism/manifest", ".mp4"))
                        putExtra("SNIFF_BLACKLIST", arrayOf("blob:", "data:", ".m3u"))
                    }
                )
            }
            else -> {
                ctx.startActivity(
                    Intent(ctx, WebPlayerActivity2::class.java).apply {
                        putExtra("web_url", url)
                        putExtra("title", title)
                        putExtra("STREAM_TYPE", "WEB")
                        putExtra("ALLOW_NATIVE_HANDOFF", false)
                    }
                )
            }
        }
    }

    // --------- Utils ----------
    private fun safeUrl(url: String?): String? {
        val u = url?.trim().takeUnless { it.isNullOrEmpty() || it.equals("null", ignoreCase = true) }
        return when {
            u == null -> null
            u.startsWith("http://") -> u.replaceFirst("http://", "https://")
            else -> u
        }
    }
}
