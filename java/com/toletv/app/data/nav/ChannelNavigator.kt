package com.toletv.app.data.nav

import com.example.tv67777.Movie
import com.toletv.app.data.model.ChannelCategory
import com.toletv.app.data.model.MovieList

/**
 * ChannelNavigator gestiona la navegación tipo "zapping" entre canales.
 * Filtra la lista por categoría y mantiene un índice actual con wrap-around.
 */
object ChannelNavigator {

    private var currentCategory: ChannelCategory = ChannelCategory.TV
    private var currentList: List<Movie> = emptyList()
    private var currentIndex: Int = -1

    /**
     * Inicializa el navegador de canales.
     * @param category La categoría (cine, tv, entretenimiento, deportes).
     * @param seedId Opcional: ID del canal inicial.
     * @param seedIndex Opcional: índice inicial (si preferís usar posición en la lista).
     */
    fun setup(category: ChannelCategory, seedId: Int? = null, seedIndex: Int? = null) {
        currentCategory = category

        currentList = MovieList.list.filter { it.category.equals(category.key, ignoreCase = true) }

        currentIndex = when {
            currentList.isEmpty() -> -1
            seedIndex != null -> seedIndex.coerceIn(0, currentList.lastIndex)
            seedId != null -> currentList.indexOfFirst { it.id == seedId }.takeIf { it >= 0 } ?: 0
            else -> 0
        }
    }

    fun hasData(): Boolean = currentList.isNotEmpty() && currentIndex in currentList.indices

    fun category(): ChannelCategory = currentCategory
    fun size(): Int = currentList.size
    fun index(): Int = currentIndex

    fun current(): Movie? = if (hasData()) currentList[currentIndex] else null

    fun next(): Movie? {
        if (!hasData()) return null
        currentIndex = (currentIndex + 1) % currentList.size
        return current()
    }

    fun prev(): Movie? {
        if (!hasData()) return null
        currentIndex = if (currentIndex - 1 < 0) currentList.lastIndex else currentIndex - 1
        return current()
    }
}
