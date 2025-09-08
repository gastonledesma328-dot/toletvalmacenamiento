// app/src/main/java/com/toletv/app/ui/movies/MovieGridAdapter.kt
package com.toletv.app.ui.movies

import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.toletv.app.R
import com.toletv.app.data.model.DetailedMovie

class MovieGridAdapter(
    private var movies: List<DetailedMovie>,
    private val onMovieClick: (DetailedMovie) -> Unit = {},
    private val onMovieFocused: ((DetailedMovie) -> Unit)? = null
) : RecyclerView.Adapter<MovieGridAdapter.VH>() {   // <-- genérico correcto

    companion object { private const val CLICK_DEBOUNCE_MS = 350L }

    init { setHasStableIds(true) }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.ivMoviePoster)
        val focusOverlay: View? = v.findViewById(R.id.focusOverlay) // opcional en el layout
        var lastClick: Long = 0L
    }

    override fun getItemId(position: Int): Long = movies[position].id.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        // Usa tu item de tarjeta 2:3 (debe tener @id/ivMoviePoster)
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_movie, parent, false)
        v.isFocusable = true
        v.isClickable  = true
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = movies[pos]

        val opts = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .dontAnimate()
            .placeholder(R.drawable.ic_play)
            .error(R.drawable.ic_play)

        Glide.with(h.itemView.context)
            .load(m.posterUrl?.takeIf { it.isNotBlank() } ?: R.drawable.ic_play)
            .apply(opts)
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
            if (hasFocus) onMovieFocused?.invoke(m)
        }
    }

    override fun getItemCount(): Int = movies.size

    fun update(newList: List<DetailedMovie>) {
        movies = newList
        notifyDataSetChanged()
    }
}
