/**
 * TOLE TV - Movie Data Loading Script Example
 * 
 * This script demonstrates how to load movie data from external sources
 * into the TOLE TV Android app.
 * 
 * Usage Examples:
 * 1. Load from a single URL
 * 2. Load from multiple URLs
 * 3. Use cached data
 * 4. Load sample data for testing
 */

// Example 1: Load movies from a single URL
suspend fun loadMoviesFromSingleSource() {
    val movieLoader = MovieDataLoader(context)
    
    // Your movie API endpoint
    val movieUrl = "https://your-api.com/movies.json"
    
    val result = movieLoader.loadMoviesFromUrl(movieUrl, cacheLocally = true)
    
    result.fold(
        onSuccess = { movies ->
            println("Successfully loaded ${movies.size} movies")
            movies.forEach { movie ->
                println("- ${movie.title} (${movie.year}) - ${movie.getFormattedDuration()}")
            }
        },
        onFailure = { error ->
            println("Error loading movies: ${error.message}")
            // Fallback to sample movies
            val sampleMovies = movieLoader.getSampleMovies()
            println("Using ${sampleMovies.size} sample movies instead")
        }
    )
}

// Example 2: Load from multiple sources
suspend fun loadMoviesFromMultipleSources() {
    val movieLoader = MovieDataLoader(context)
    
    val movieUrls = listOf(
        "https://api.themoviedb.org/3/movie/popular?api_key=YOUR_API_KEY",
        "https://your-server.com/api/movies.json",
        "https://raw.githubusercontent.com/user/repo/main/movies.json"
    )
    
    val result = movieLoader.loadMoviesFromMultipleUrls(movieUrls, cacheLocally = true)
    
    result.fold(
        onSuccess = { movies ->
            println("Loaded ${movies.size} movies from ${movieUrls.size} sources")
            
            // Group by genre
            val moviesByGenre = movies.groupBy { it.genre.firstOrNull() ?: "Unknown" }
            moviesByGenre.forEach { (genre, genreMovies) ->
                println("$genre: ${genreMovies.size} movies")
            }
        },
        onFailure = { error ->
            println("Error loading from multiple sources: ${error.message}")
        }
    )
}

// Example 3: Use cached data
suspend fun loadCachedMovies() {
    val movieLoader = MovieDataLoader(context)
    
    val result = movieLoader.loadCachedMovies()
    
    result.fold(
        onSuccess = { movies ->
            println("Loaded ${movies.size} movies from cache")
        },
        onFailure = { error ->
            println("No cached movies available: ${error.message}")
            // Load fresh data
            loadMoviesFromSingleSource()
        }
    )
}

// Example 4: JSON Structure for your movie API
fun getExpectedJsonStructure(): String {
    return MovieDataLoader.getSampleJsonStructure()
}

/**
 * How to integrate with your existing movie website:
 * 
 * 1. Create an API endpoint on your website that returns movie data in JSON format
 * 2. Use the JSON structure provided by MovieDataLoader.getSampleJsonStructure()
 * 3. Include all movie details: title, description, cast, duration, etc.
 * 4. Provide streaming URLs (m3u8, direct links, or web pages)
 * 5. Use the MovieDataLoader in your Android app to fetch this data
 * 
 * Example API endpoint structure:
 * GET https://your-website.com/api/movies.json
 * 
 * Response should be an array of movie objects with all the required fields.
 */

/**
 * Integration with TOLE TV App:
 * 
 * 1. In MainActivity, you can load movies like this:
 */
class ExampleIntegration {
    
    suspend fun loadMoviesIntoApp(context: Context) {
        val movieLoader = MovieDataLoader(context)
        
        // Try to load from your API first
        val apiResult = movieLoader.loadMoviesFromUrl(
            "https://your-website.com/api/movies.json"
        )
        
        val movies = apiResult.getOrElse {
            // Fallback to cached or sample data
            movieLoader.loadCachedMovies().getOrElse {
                movieLoader.getSampleMovies()
            }
        }
        
        // Update your content repository with the loaded movies
        // This would integrate with your existing ContentRepository
        updateMoviesInRepository(movies)
    }
    
    private fun updateMoviesInRepository(movies: List<DetailedMovie>) {
        // Convert DetailedMovie to your existing Movie model if needed
        val simpleMovies = movies.map { it.toSimpleMovie() }
        
        // Update your repository or adapter
        // movieAdapter.updateMovies(simpleMovies)
    }
}

/**
 * Sample movie data structure for testing:
 */
val sampleMovieJson = """
[
  {
    "id": 1001,
    "title": "Gladiador II",
    "description": "Secuela del épico gladiador de Ridley Scott",
    "synopsis": "Años después de presenciar la muerte del venerado héroe Máximo...",
    "posterUrl": "https://image.tmdb.org/t/p/w500/2cxhvwyEwRlysAmRH4iodkvo0z5.jpg",
    "backdropUrl": "https://image.tmdb.org/t/p/w1280/euYIwmwkmz95mnXvufEmbL6ovhZ.jpg",
    "streamUrl": "https://your-server.com/streams/gladiator2.m3u8",
    "category": "peliculas",
    "duration": 148,
    "year": 2024,
    "rating": 7.8,
    "genre": ["Acción", "Drama", "Aventura"],
    "director": "Ridley Scott",
    "cast": ["Paul Mescal", "Pedro Pascal", "Denzel Washington"],
    "country": "Estados Unidos",
    "language": "Inglés",
    "quality": "4K",
    "ageRating": "R",
    "releaseDate": "2024-11-22",
    "studio": "Paramount Pictures"
  }
]
"""

/**
 * To use this in your TOLE TV app:
 * 
 * 1. Host your movie data as JSON on your website
 * 2. Use MovieDataLoader to fetch the data
 * 3. The app will automatically cache the data locally
 * 4. Movies will appear in the Netflix-style interface
 * 5. Users can browse by categories and play the content
 */
