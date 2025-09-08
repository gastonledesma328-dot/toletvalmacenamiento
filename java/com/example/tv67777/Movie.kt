package com.example.tv67777

import android.os.Parcelable
import com.toletv.app.data.model.StreamType
import kotlinx.parcelize.Parcelize
import java.io.Serializable
import android.content.Intent
import com.toletv.app.ui.movies.MovieDetailsActivity
import com.example.tv67777.Movie

@Parcelize
data class Movie(
    val id: Int,
    val title: String,
    val description: String,
    val logoUrl: String,
    val streamUrl: String,
    val streamType: StreamType,
    val category: String,
    val duration: Int = 0, // en minutos

) : Parcelable {

    /**
     * Devuelve la duración en formato legible.
     * Ejemplo: 95 -> "1h 35m", 45 -> "45m"
     */
    fun getFormattedDuration(): String {
        if (duration <= 0) return "--"
        val hours = duration / 60
        val minutes = duration % 60
        return if (hours > 0) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes}m"
        }
    }
}