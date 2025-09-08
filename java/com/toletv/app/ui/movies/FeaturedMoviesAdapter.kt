// FeaturedMoviesAdapter.kt
package com.toletv.app.ui.movies

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.toletv.app.R
import com.toletv.app.data.model.DetailedMovie

class FeaturedMoviesAdapter(
    private var items: List<DetailedMovie> = emptyList(),
    private val onMovieClick: (DetailedMovie) -> Unit = {},           // <- default vacío
    private val onMovieFocused: ((DetailedMovie) -> Unit)? = null
) : RecyclerView.Adapter<FeaturedMoviesAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iv: ImageView = v.findViewById(R.id.ivMoviePoster)
        val tv: TextView = v.findViewById(R.id.tvMovieTitle)
        val focusOverlay: View? = v.findViewById(R.id.focusOverlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_movie, parent, false)
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = items[pos]
        h.tv.text = m.title

        // Solo posterUrl (si viene null/blank, Glide mostrará el placeholder/error)
        val img = m.posterUrl?.takeIf { it.isNotBlank() }
        Glide.with(h.itemView.context)
            .load(img)
            .placeholder(R.drawable.ic_play)
            .error(R.drawable.ic_play)
            .centerCrop()
            .into(h.iv)

        h.itemView.setOnClickListener { onMovieClick(m) }
        h.itemView.setOnFocusChangeListener { _, hasFocus ->
            val scale = if (hasFocus) 1.05f else 1.0f
            h.itemView.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            h.focusOverlay?.apply {
                visibility = if (hasFocus) View.VISIBLE else View.GONE
                alpha = if (hasFocus) 0.15f else 0f
            }
            if (hasFocus) onMovieFocused?.invoke(m)
        }
    }

    override fun getItemCount() = items.size

    fun updateMovies(newItems: List<DetailedMovie>) {
        items = newItems
        notifyDataSetChanged()
    }
}
