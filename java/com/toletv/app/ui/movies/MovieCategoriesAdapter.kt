// MovieCategoriesAdapter.kt
package com.toletv.app.ui.movies

import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.toletv.app.R
import com.toletv.app.data.model.DetailedMovie

class MovieCategoriesAdapter(
    private var categories: List<MovieCategory> = emptyList(),
    private val onMovieClick: (DetailedMovie) -> Unit = {},
    private val onMovieFocused: ((DetailedMovie) -> Unit)? = null
) : RecyclerView.Adapter<MovieCategoriesAdapter.CatVH>() {

    init { setHasStableIds(true) }

    class CatVH(v: View) : RecyclerView.ViewHolder(v) {
        val title = v.findViewById<android.widget.TextView>(R.id.tvCategoryTitle)
        val list  = v.findViewById<RecyclerView>(R.id.rvCategoryList)
    }

    override fun getItemId(position: Int): Long =
        categories[position].name.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CatVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_movie_category, parent, false)
        return CatVH(v)
    }

    override fun onBindViewHolder(h: CatVH, pos: Int) {
        val cat = categories[pos]
        h.title.text = cat.name

        val rowAdapter = object : RecyclerView.Adapter<RowVH>() {
            init { setHasStableIds(true) }

            override fun getItemId(position: Int): Long {
                val m = cat.movies[position]
                // m.id es no nulo (Int), así que no hace falta safe call ni Elvis
                return m.id.toLong()
            }

            override fun onCreateViewHolder(p: ViewGroup, vt: Int): RowVH {
                val v = LayoutInflater.from(p.context)
                    .inflate(R.layout.item_movie, p, false)
                v.isFocusable = true
                v.isClickable = true
                return RowVH(v)
            }

            override fun onBindViewHolder(rh: RowVH, p2: Int) {
                val m = cat.movies[p2]

                // posterUrl es no nulo (String), quitamos safe call
                Glide.with(rh.itemView.context)
                    .load(m.posterUrl.takeIf { it.isNotBlank() })
                    .placeholder(R.drawable.ic_play)
                    .error(R.drawable.ic_play)
                    .centerCrop()
                    .into(rh.thumb)

                rh.itemView.contentDescription = m.title

                rh.itemView.setSafeClickListener { onMovieClick(m) }

                rh.itemView.setOnKeyListener { v, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_UP &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    ) {
                        v.performClick()
                        true
                    } else false
                }

                rh.itemView.setOnFocusChangeListener { v, hasFocus ->
                    val scale = if (hasFocus) 1.05f else 1.0f
                    v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
                    rh.focusOverlay?.apply {
                        visibility = if (hasFocus) View.VISIBLE else View.GONE
                        alpha = if (hasFocus) 0.15f else 0f
                    }
                    if (hasFocus) onMovieFocused?.invoke(m)
                }
            }

            override fun getItemCount() = cat.movies.size
        }

        h.list.apply {
            layoutManager = LinearLayoutManager(
                h.itemView.context,
                LinearLayoutManager.HORIZONTAL,
                false
            )
            adapter = rowAdapter
            setHasFixedSize(true)
            isFocusable = false
            isClickable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    override fun getItemCount() = categories.size

    fun updateCategories(newCats: List<MovieCategory>) {
        categories = newCats
        notifyDataSetChanged()
    }

    class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.ivMoviePoster)
        val focusOverlay: View? = v.findViewById(R.id.focusOverlay) // opcional en el layout
    }
}

// Extensión anti doble click
private fun View.setSafeClickListener(interval: Long = 350L, onSafeClick: (View) -> Unit) {
    var last = 0L
    setOnClickListener {
        val now = SystemClock.elapsedRealtime()
        if (now - last > interval) {
            last = now
            onSafeClick(it)
        }
    }
}
