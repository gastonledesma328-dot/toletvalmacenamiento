package com.toletv.app.ui.movies

import com.toletv.app.data.model.DetailedMovie

data class MovieCategory(
    val name: String,
    val movies: List<DetailedMovie>
)
