package com.toletv.app.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.toletv.app.R
import com.toletv.app.data.model.Channel
import com.toletv.app.data.model.StreamSource
import com.toletv.app.data.model.StreamType
import com.toletv.app.data.repository.ContentRepository
import com.toletv.app.databinding.ActivityMainBinding
import com.toletv.app.ui.adapters.ChannelAdapter
import com.toletv.app.ui.adapters.MovieAdapter
import com.toletv.app.ui.adapters.toSimpleMovies
import com.toletv.app.ui.movies.MoviesActivity
import com.toletv.app.ui.player.VideoPlayerActivity
import com.toletv.app.ui.player.WebPlayerActivity
import com.toletv.app.utils.MovieDataLoader
import com.toletv.app.utils.NetProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var contentRepository: ContentRepository
    private lateinit var movieDataLoader: MovieDataLoader

    // Adapters
    private lateinit var canalesAdapter: ChannelAdapter
    private lateinit var favoritosAdapter: ChannelAdapter
    private lateinit var movieAdapter: MovieAdapter

    private var sportsAdapter: ChannelAdapter? = null
    private var entertainmentAdapter: ChannelAdapter? = null
    private var cinemaChannelsAdapter: ChannelAdapter? = null

    // Status bar time
    private lateinit var timeHandler: Handler
    private lateinit var timeRunnable: Runnable

    // Sidebar nav
    private lateinit var navCine: ImageView
    private lateinit var navCanales: ImageView
    private lateinit var navEntretenimiento: ImageView
    private lateinit var navDeportes: ImageView
    private lateinit var navFavoritos: ImageView
    private lateinit var navPeliculas: ImageView
    private var selectedNavItem: ImageView? = null

    // Scroll anchors
    private lateinit var contentScroll: NestedScrollView
    private lateinit var tvCineTitle: View
    private lateinit var tvCanalesTitle: View
    private lateinit var tvEntretenimientoTitle: View
    private lateinit var tvDeportesTitle: View
    private lateinit var tvFavoritosTitle: View
    private lateinit var tvPeliculasTitle: View
    private lateinit var tvRadiosTitle: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
        setupRepository()
        setupRecyclerViews()
        setupNavigation()
        setupStatusBar()
        loadContent()
        fetchAndShowMovies()
    }

    private fun initViews() {
        contentScroll = binding.contentScroll

        tvCineTitle = binding.tvCineTitle
        tvCanalesTitle = binding.tvCanalesTitle
        tvEntretenimientoTitle = binding.tvEntretenimientoTitle
        tvDeportesTitle = binding.tvDeportesTitle
        tvFavoritosTitle = binding.tvFavoritosTitle
        tvPeliculasTitle = binding.tvPeliculasTitle
        // placeholder si no existe radios:
        tvRadiosTitle = binding.root

        navCine = binding.navCine
        navCanales = binding.navCanales
        navEntretenimiento = binding.navEntretenimiento
        navDeportes = binding.navDeportes
        navFavoritos = binding.navFavoritos
        navPeliculas = binding.navPeliculas

        binding.btnSeeMoreMovies?.setOnClickListener { openMoviesActivity() }
    }

    private fun setupRepository() {
        contentRepository = ContentRepository()
        movieDataLoader = MovieDataLoader(this)
    }

    // ---------- Helpers para armar filas ----------
    private data class RowArrays(
        val urls: ArrayList<String>,
        val types: ArrayList<String>,
        val titles: ArrayList<String>
    )

    private fun buildRowArrays(rowList: List<Channel>): RowArrays {
        val rowPrimary: List<StreamSource> =
            rowList.mapNotNull { c -> buildSourcesForChannel(c).firstOrNull() }

        val urls = arrayListOf<String>().apply { addAll(rowPrimary.map { it.url }) }
        val types = arrayListOf<String>().apply { addAll(rowPrimary.map { it.type.name }) }
        val titles = arrayListOf<String>().apply { addAll(rowList.map { it.name }) }

        return RowArrays(urls, types, titles)
    }

    private fun setupRecyclerViews() {
        // CANALES
        binding.rvCanales?.let { rv ->
            canalesAdapter = ChannelAdapter(
                emptyList(),
                onChannelClick = { /* compat, no usado */ },
                onChannelClickWithIndex = { ch, pos ->
                    routeChannelClick(ch, pos) { canalesAdapter.getChannels() }
                },
                onChannelFocused = null,
                onChannelLongClick = { ch, pos ->
                    showChannelVariants(ch, pos, canalesAdapter.getChannels())
                }
            )
            rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            rv.adapter = canalesAdapter
        }

        // FAVORITOS
        binding.rvFavoritos?.let { rv ->
            favoritosAdapter = ChannelAdapter(
                emptyList(),
                onChannelClick = { /* compat, no usado */ },
                onChannelClickWithIndex = { ch, pos ->
                    routeChannelClick(ch, pos) { favoritosAdapter.getChannels() }
                },
                onChannelFocused = null,
                onChannelLongClick = { ch, pos ->
                    showChannelVariants(ch, pos, favoritosAdapter.getChannels())
                }
            )
            rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            rv.adapter = favoritosAdapter
        }

        // PELÍCULAS
        binding.rvPeliculas?.let { rv ->
            movieAdapter = MovieAdapter(
                emptyList(),
                onMovieClick = { movie ->
                    playContent(movie.streamUrl, movie.streamType, movie.title ?: "", isLive = false)
                },
                onMovieFocused = null,
                onMovieClickWithIndex = { movie: com.example.tv67777.Movie, pos: Int ->
                    val rowList = movieAdapter.getMovies()

                    val rowUrls = ArrayList(rowList.mapNotNull { it.streamUrl })
                    val rowTypes = ArrayList(rowList.map { it.streamType.name })
                    val rowTitles = ArrayList(rowList.map { it.title ?: "" })
                    val startIndex = pos.coerceIn(0, (rowUrls.size - 1).coerceAtLeast(0))

                    val url = movie.streamUrl ?: ""
                    val type = movie.streamType

                    if (isWebLike(type)) {
                        openWeb(
                            url = url,
                            title = movie.title ?: "",
                            rowUrls = rowUrls,
                            rowTypes = rowTypes,
                            rowTitles = rowTitles,
                            startIndex = startIndex
                        )
                    } else {
                        openNative(
                            url = url,
                            type = type,
                            title = movie.title ?: "",
                            isLive = false,
                            rowUrls = rowUrls,
                            rowTypes = rowTypes,
                            rowTitles = rowTitles,
                            startIndex = startIndex
                        )
                    }
                }
            )
            rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            rv.adapter = movieAdapter
        }

        // CINE
        binding.rvCine?.let { rv ->
            cinemaChannelsAdapter = ChannelAdapter(
                emptyList(),
                onChannelClick = { /* compat, no usado */ },
                onChannelClickWithIndex = { ch, pos ->
                    routeChannelClick(ch, pos) { cinemaChannelsAdapter?.getChannels().orEmpty() }
                },
                onChannelFocused = null,
                onChannelLongClick = { ch, pos ->
                    showChannelVariants(ch, pos, cinemaChannelsAdapter?.getChannels().orEmpty())
                }
            )
            rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            rv.adapter = cinemaChannelsAdapter
        }

        // DEPORTES
        binding.rvDeportes?.let { rv ->
            sportsAdapter = ChannelAdapter(
                emptyList(),
                onChannelClick = { /* compat, no usado */ },
                onChannelClickWithIndex = { ch, pos ->
                    routeChannelClick(ch, pos) { sportsAdapter?.getChannels().orEmpty() }
                },
                onChannelFocused = null,
                onChannelLongClick = { ch, pos ->
                    showChannelVariants(ch, pos, sportsAdapter?.getChannels().orEmpty())
                }
            )
            rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            rv.adapter = sportsAdapter
        }

        // ENTRETENIMIENTO
        binding.rvEntretenimiento?.let { rv ->
            entertainmentAdapter = ChannelAdapter(
                emptyList(),
                onChannelClick = { /* compat, no usado */ },
                onChannelClickWithIndex = { ch, pos ->
                    routeChannelClick(ch, pos) { entertainmentAdapter?.getChannels().orEmpty() }
                },
                onChannelFocused = null,
                onChannelLongClick = { ch, pos ->
                    showChannelVariants(ch, pos, entertainmentAdapter?.getChannels().orEmpty())
                }
            )
            rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            rv.adapter = entertainmentAdapter
        }
    } // <- CIERRE setupRecyclerViews()

    /** Diálogo de variantes (primary + mirrors) y lanza el player en la elegida. */
    private fun showChannelVariants(ch: Channel, pos: Int, rowList: List<Channel>) {
        val sourcesAll: List<StreamSource> = buildSourcesForChannel(ch)
        if (sourcesAll.isEmpty()) {
            Toast.makeText(this, "Sin variantes para ${ch.name}", Toast.LENGTH_SHORT).show()
            return
        }

        val items: Array<String> = sourcesAll.mapIndexed { idx, s ->
            val host = runCatching { Uri.parse(s.url).host ?: "" }.getOrNull().orEmpty()
            val label = try {
                val f = s.javaClass.getDeclaredField("label").apply { isAccessible = true }
                (f.get(s) as? String)?.takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }

            val base = label ?: if (idx == 0) "Principal" else "Variante $idx"
            val type = s.type.name
            "$base  ·  $type${if (host.isNotEmpty()) "  –  $host" else ""}"
        }.toTypedArray()

        val arrays = buildRowArrays(rowList)
        val startIndex = pos.coerceIn(0, (arrays.urls.size - 1).coerceAtLeast(0))

        AlertDialog.Builder(this)
            .setTitle("${ch.name} – Variantes")
            .setItems(items) { _, which ->
                val chosen = sourcesAll[which]
                if (isWebLike(chosen.type)) {
                    openWeb(
                        url = chosen.url,
                        title = ch.name,
                        rowUrls = arrays.urls,
                        rowTypes = arrays.types,
                        rowTitles = arrays.titles,
                        startIndex = startIndex
                    )
                } else {
                    openNative(
                        url = chosen.url,
                        type = chosen.type,
                        title = ch.name,
                        isLive = true,
                        rowUrls = arrays.urls,
                        rowTypes = arrays.types,
                        rowTitles = arrays.titles,
                        startIndex = startIndex
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // -------- Navegación lateral --------
    private fun setupNavigation() {
        val navItems = listOf(navCine, navCanales, navEntretenimiento, navDeportes, navFavoritos, navPeliculas)

        navItems.forEach { item ->
            item.isFocusable = true
            item.isClickable = true

            item.setOnClickListener { v ->
                v.requestFocus()
                onNavSelected(v as ImageView)
            }
            item.setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundColor(
                    ContextCompat.getColor(
                        this,
                        if (hasFocus) R.color.focus_background else android.R.color.transparent
                    )
                )
            }
        }
        onNavSelected(navCine)
    }

    private fun onNavSelected(navItem: ImageView) {
        selectedNavItem?.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
        selectedNavItem = navItem
        navItem.setBackgroundColor(ContextCompat.getColor(this, R.color.nav_selected))

        when (navItem.id) {
            R.id.navCine -> scrollToSection(binding.tvCineTitle, binding.rvCine)
            R.id.navCanales -> scrollToSection(binding.tvCanalesTitle, binding.rvCanales)
            R.id.navEntretenimiento -> scrollToSection(binding.tvEntretenimientoTitle, binding.rvEntretenimiento)
            R.id.navDeportes -> scrollToSection(binding.tvDeportesTitle, binding.rvDeportes)
            R.id.navFavoritos -> scrollToSection(binding.tvFavoritosTitle, binding.rvFavoritos)
            R.id.navPeliculas -> scrollToSection(binding.tvPeliculasTitle, binding.rvPeliculas)
        }
    }

    private fun scrollToSection(titleView: View?, listView: View?) {
        val target = titleView ?: return
        contentScroll.post {
            contentScroll.smoothScrollTo(0, target.top)
            (listView ?: target).requestFocus()
        }
    }

    // -------- Status bar --------
    private fun setupStatusBar() {
        timeHandler = Handler(Looper.getMainLooper())
        timeRunnable = object : Runnable {
            override fun run() {
                updateTime()
                timeHandler.postDelayed(this, 1000)
            }
        }
        timeHandler.post(timeRunnable)
    }

    private fun updateTime() {
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        binding.tvTime.text = currentTime
    }

    // -------- Carga de datos --------
    private fun loadContent() {
        runCatching { contentRepository.getFavoriteChannels() }
            .onSuccess {
                if (::favoritosAdapter.isInitialized) favoritosAdapter.updateChannels(it)
                if (::canalesAdapter.isInitialized && it.isNotEmpty()) canalesAdapter.updateChannels(it)
            }
            .onFailure { android.util.Log.e("MainActivity", "Fav channels error", it) }

        sportsAdapter?.let { adapter ->
            runCatching { contentRepository.getSportsChannels() }
                .onSuccess { adapter.updateChannels(it) }
                .onFailure { android.util.Log.w("MainActivity", "Sports error", it) }
        }

        entertainmentAdapter?.let { adapter ->
            runCatching { contentRepository.getEntertainmentChannels() }
                .onSuccess { adapter.updateChannels(it) }
                .onFailure { android.util.Log.w("MainActivity", "Entertainment error", it) }
        }

        cinemaChannelsAdapter?.let { adapter ->
            runCatching { contentRepository.getCinemaChannels() }
                .onSuccess { adapter.updateChannels(it) }
                .onFailure { android.util.Log.w("MainActivity", "Cinema channels error", it) }
        }
    }

    private fun fetchAndShowMovies() {
        lifecycleScope.launch(Dispatchers.IO) {
            val url = MovieDataLoader.DEFAULT_MOVIES_URL + "?t=" + System.currentTimeMillis()
            val remote = movieDataLoader.loadMoviesFromUrl(url, cacheLocally = true)
            val fromRemoteOrCache = remote.getOrElse {
                movieDataLoader.loadCachedMovies().getOrElse { emptyList() }
            }
            val finalList = fromRemoteOrCache
            val uiMovies = finalList.toSimpleMovies()
            withContext(Dispatchers.Main) {
                movieAdapter.updateMovies(uiMovies)
            }
        }
    }

    // -------- Reproducción --------
    private fun deduceStreamType(url: String?): StreamType {
        val u = url?.lowercase(Locale.ROOT) ?: return StreamType.DIRECT_URL
        return when {
            u.endsWith(".m3u8") -> StreamType.M3U8
            u.endsWith(".mpd")  -> StreamType.MPD
            u.startsWith("http") && (u.contains("youtube.com") || u.contains("vimeo.com") || u.contains("/watch")) ->
                StreamType.WEB
            else -> StreamType.DIRECT_URL
        }
    }

    /** WEB/WE2 => WebView directo; nativo => probamos y abrimos Exo */
    private fun playContent(streamUrl: String, streamType: StreamType, title: String, isLive: Boolean = true) {
        if (isWebLike(streamType)) {
            openWeb(streamUrl, title)
            return
        }

        // Sólo probamos cuando vamos a usar ExoPlayer
        lifecycleScope.launch {
            val probe = NetProbe.probe(streamUrl)
            android.util.Log.d("Preflight", "ok=${probe.ok} code=${probe.code} type=${probe.contentType} url=${probe.finalUrl}")

            if (probe.ok) {
                openNative(streamUrl, streamType, title, isLive)
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "La URL no es un video directo (code=${probe.code}, type=${probe.contentType})",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startExoPlayer(url: String, streamType: StreamType, title: String, isLive: Boolean) {
        if (isWebLike(streamType)) {
            openWeb(url, title)
        } else {
            openNative(url, streamType, title, isLive)
        }
    }

    // -------- Utilitarios de ruteo --------
    private fun isWebLike(t: StreamType): Boolean =
        (t == StreamType.WEB || t == StreamType.WE2)

    private fun openWeb(
        url: String,
        title: String,
        rowUrls: ArrayList<String>? = null,
        rowTypes: ArrayList<String>? = null,
        rowTitles: ArrayList<String>? = null,
        startIndex: Int? = null
    ) {
        startActivity(Intent(this, WebPlayerActivity::class.java).apply {
            putExtra("streamUrl", url)
            putExtra("title", title)
            rowUrls?.let { putStringArrayListExtra("ROW_URLS", it) }
            rowTypes?.let { putStringArrayListExtra("ROW_TYPES", it) }
            rowTitles?.let { putStringArrayListExtra("ROW_TITLES", it) }
            startIndex?.let { putExtra("ROW_INDEX", it) }
        })
    }

    private fun openNative(
        url: String,
        type: StreamType,
        title: String,
        isLive: Boolean,
        rowUrls: ArrayList<String>? = null,
        rowTypes: ArrayList<String>? = null,
        rowTitles: ArrayList<String>? = null,
        startIndex: Int? = null
    ) {
        startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra("streamUrl", url)
            putExtra("title", title)
            putExtra("stream_type", type.name)
            putExtra("is_live", isLive)
            rowUrls?.let { putStringArrayListExtra("ROW_URLS", it) }
            rowTypes?.let { putStringArrayListExtra("ROW_TYPES", it) }
            rowTitles?.let { putStringArrayListExtra("ROW_TITLES", it) }
            startIndex?.let { putExtra("ROW_INDEX", it) }
        })
    }

    /** Click en una tarjeta de canal con ruteo correcto + navegación por fila */
    private fun routeChannelClick(ch: Channel, pos: Int, rowProvider: () -> List<Channel>) {
        val sources = buildSourcesForChannel(ch)
        if (sources.isEmpty()) {
            Toast.makeText(this, "Sin fuentes para ${ch.name}", Toast.LENGTH_SHORT).show()
            return
        }

        val rowList = rowProvider()
        val arrays = buildRowArrays(rowList)
        val startIndex = pos.coerceIn(0, (arrays.urls.size - 1).coerceAtLeast(0))

        val primary = sources.first()
        if (isWebLike(primary.type)) {
            openWeb(
                url = primary.url,
                title = ch.name,
                rowUrls = arrays.urls,
                rowTypes = arrays.types,
                rowTitles = arrays.titles,
                startIndex = startIndex
            )
        } else {
            openNative(
                url = primary.url,
                type = primary.type,
                title = ch.name,
                isLive = true,
                rowUrls = arrays.urls,
                rowTypes = arrays.types,
                rowTitles = arrays.titles,
                startIndex = startIndex
            )
        }
    }

    // -------- Fuentes / mirrors por canal --------
    // -------- Fuentes / mirrors por canal (IDs actualizados) --------
    private val channelMirrors: Map<String, List<StreamSource>> = mapOf(

        // 📺 TV
        "1"  to listOf( // Telefe
            StreamSource("https://mitelefe.com/Api/Videos/GetSourceUrl/694564/0/HLS", StreamType.WEB)
        ),
        "2"  to listOf( // Telefe Cba
            StreamSource("https://www.parsatv.com/embed.php?name=Telefe-Cordoba", StreamType.WE2)
        ),
        "3"  to listOf( // El Trece
            StreamSource("https://router.cdn.rcs.net.ar/mnp/el13_hls/playlist.m3u8", StreamType.M3U8),
            StreamSource("https://cointv.online/cvatt.html?get=QXJ0ZWFySEQ", StreamType.WE2)
        ),
        "4"  to listOf( // A24
            StreamSource("https://tele-libre.org/html/cvatt.html?get=QW1lcmljYTI0", StreamType.WE2)
        ),
        "5"  to listOf( // TV Pública
            StreamSource("https://cointv.online/cvatt.html?get=Q2FuYWw3", StreamType.WE2),
            StreamSource("https://canalesonline.netlify.app/servidores/sensa.html?id=TVPublica", StreamType.WE2),
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=TV_PUBLICA_HD", StreamType.WE2),
            StreamSource("https://streamtp11.com/global1.php?stream=tv_publica", StreamType.WE2)
        ),
        "6"  to listOf( // LN
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=LN_Plus", StreamType.WE2),
            StreamSource("https://canalesonline.netlify.app/servidores/sensa.html?id=LaNacionMas", StreamType.WE2)
        ),
        "7"  to listOf( // C5N
            StreamSource("https://cointv.online/html/cvatt.html?get=QzVO8", StreamType.WE2),
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=C5N", StreamType.WE2),
            StreamSource("https://canalesonline.netlify.app/servidores/sensa.html?id=C5N", StreamType.WE2)
        ),
        "8"  to listOf( // El Doce TV
            StreamSource("https://sixdayslater.com/cvatt.html?get=Q2FuYWxfMTJfQ0JB&lang=1", StreamType.WE2)
        ),
        "9"  to listOf( // Canal 26
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=Canal_26", StreamType.WE2),
            StreamSource("https://cointv.online/html/cvatt.html?get=MjZfVFZfSEQ", StreamType.WE2)
        ),
        "10" to listOf( // Crónica
            StreamSource("https://cointv.online/html/cvatt.html?get=Q3JvbmljYVRW", StreamType.WE2),
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=Cronica_TV", StreamType.WE2)
        ),
        "11" to listOf( // Canal 4
            StreamSource("http://204.199.3.2/.m3u8", StreamType.M3U8)
        ),
        "12" to listOf( // América
            StreamSource("https://canalesonline.netlify.app/servidores/sensa.html?id=America", StreamType.M3U8)
        ),

        // 🎭 Entretenimiento
        "13" to listOf( // Garage TV
            StreamSource("https://stream1.sersat.com/hls/garagetv.m3u8", StreamType.M3U8)
        ),
        "14" to listOf( // Cartoon Network
            StreamSource("https://cointv.online/cvatt.html?get=Q2FydG9vbk5ldHdvcms=", StreamType.WE2)
        ),
        "15" to listOf( // Cartoonito
            StreamSource("https://cointv.online/cvatt.html?get=Qm9vbWVyYW5n", StreamType.WE2)
        ),
        "16" to listOf( // Disney Channel
            StreamSource("https://cointv.online/cvatt.html?get=RGlzbmV5Q2hhbm5lbEhE", StreamType.WE2)
        ),
        "17" to listOf( // Disney Jr
            StreamSource("https://cointv.online/cvatt.html?get=RGlzbmV5SnI=", StreamType.WE2)
        ),
        "18" to listOf( // Discovery Kids
            StreamSource("https://cointv.online/cvatt.html?get=RGlzY292ZXJ5X0tpZHM=", StreamType.WE2)
        ),
        "19" to listOf( // Nickelodeon
            StreamSource("https://cointv.online/cvatt.html?get=Tmlja2Vsb2Rlb24=", StreamType.WE2)
        ),
        "20" to listOf( // Nick Jr
            StreamSource("https://cointv.online/cvatt.html?get=Tmlja19Kcg==", StreamType.WE2)
        ),
        "21" to listOf( // Animal Planet
            StreamSource("https://cointv.online/cvatt.html?get=QW5pbWFsUGxhbmV0", StreamType.WE2)
        ),
        "22" to listOf( // Love Nature
            StreamSource("https://canalesonline.netlify.app/servidores/m3u8.html?stream=Love_Nature1", StreamType.WE2)
        ),
        "23" to listOf( // Discovery Channel
            StreamSource("https://cointv.online/cvatt.html?get=RGlzY292ZXJ5SEQ=", StreamType.WE2)
        ),
        "24" to listOf( // History
            StreamSource("https://cointv.online/cvatt.html?get=SGlzdG9yeUhE", StreamType.WE2)
        ),
        "25" to listOf( // History 2
            StreamSource("https://cointv.online/cvatt.html?get=SGlzdG9yeV8y", StreamType.WE2)
        ),
        "26" to listOf( // National Geographic
            StreamSource("https://cointv.online/cvatt.html?get=TmF0R2VvSEQ=", StreamType.WE2)
        ),

        // ⚽ Deportes
        "27" to listOf( // ESPN
            StreamSource("https://streamtp11.com/global1.php?stream=espn", StreamType.WEB),
            StreamSource("https://la14hd.com/vivo/canales.php?stream=espn", StreamType.WE2),
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=ESPN_HD", StreamType.WE2)
        ),
        "28" to listOf( // ESPN 2
            StreamSource("https://streamtp11.com/global1.php?stream=espn2", StreamType.WEB),
            StreamSource("https://la14hd.com/vivo/canales.php?stream=espn2", StreamType.WE2),
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=ESPN_2_HD", StreamType.WE2)
        ),
        "29" to listOf( // ESPN 3
            StreamSource("https://streamtp11.com/global1.php?stream=espn3", StreamType.WEB),
            StreamSource("https://la14hd.com/vivo/canales.php?stream=espn3", StreamType.WE2),
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=ESPN_3_HD", StreamType.WE2)
        ),
        "30" to listOf( // ESPN 4

            StreamSource("https://la14hd.com/vivo/canales.php?stream=espn4", StreamType.WE2),
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=ESPN_4_HD", StreamType.WE2)
        ),
        "31" to listOf( // ESPN Premium
            StreamSource("https://streamtp11.com/global1.php?stream=espnpremium", StreamType.WE2),
            StreamSource("https://la14hd.com/vivo/canales.php?stream=espnpremium", StreamType.WE2)
        ),
        "32" to listOf( // TyC Sports
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=TyC_HD", StreamType.WEB),
            StreamSource("https://la14hd.com/vivo/canales.php?stream=tycsports", StreamType.WE2)
        ),
        "33" to listOf( // TNT Sports
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=TNT_Sports_HD", StreamType.WEB),
            StreamSource("https://la14hd.com/vivo/canales.php?stream=tntsports", StreamType.WE2)

        ),
        "34" to listOf( // Fox Sports
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=FOX_SPORTS_HD", StreamType.WE2)
        ),
        "35" to listOf( // Fox Sports 2
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=Fox_Sports_2_HD", StreamType.WE2),
            StreamSource("https://la14hd.com/vivo/canales.php?stream=foxsports2", StreamType.WE2)
        ),
        "36" to listOf( // Fox Sports 3
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=Fox_Sports_3_HD", StreamType.WE2)
        ),
        "37" to listOf( // Dsport
            StreamSource("https://la14hd.com/vivo/canales.php?stream=dsports", StreamType.WE2)
        ),

        // 🎬 Cine
        "38" to listOf( // Dreamworks
            StreamSource("https://cointv.online/cvatt.html?get=RHJlYW13b3Jrcw==", StreamType.WE2)
        ),
        "39" to listOf( // Warner
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=Warner_Channel_HD", StreamType.MPD)
        ),
        "40" to listOf( // TNT Series
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=TNT_SERIES_HD", StreamType.WE2)
        ),
        "41" to listOf( // TNT
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=TNT_HD", StreamType.WE2)
        ),
        "42" to listOf( // TNT Novelas
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=TNT_NOVELAS", StreamType.WE2)
        ),
        "43" to listOf( // Cine Ar
            // (sin mirrors declarados)
        ),
        "44" to listOf( // Star Channel
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=FOX_HD", StreamType.WE2)
        ),
        "45" to listOf( // Cine Canal
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=CINECANAL_HD", StreamType.WE2)
        ),
        "46" to listOf( // Cinemax
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=CINEMAX", StreamType.WE2)
        ),
        "47" to listOf( // Space
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=Space_HD", StreamType.WE2)
        ),
        "48" to listOf( // Paramount Network
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=Paramount_HD", StreamType.WE2)
        ),
        "49" to listOf( // HBO
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=HBO", StreamType.WE2)
        ),
        "50" to listOf( // HBO Family
            StreamSource("https://canalesonline.netlify.app/servidores/anbalancer.html?id=HBO_Family", StreamType.WE2)
        ),
        "51" to listOf( // Ciudad Magazine
            StreamSource("https://live-01-07-ciudad.vodgc.net/live-01-07-ciudad.vodgc.net/tracks-v1a1/mono.m3u8", StreamType.M3U8)
        )
    )


    private fun deduceTypeByExtensionOrGuess(url: String): StreamType {
        val u = url.lowercase(Locale.getDefault())
        return when {
            u.endsWith(".m3u8") || u.contains(".m3u8") -> StreamType.M3U8
            u.endsWith(".mpd")  || u.contains(".mpd")  -> StreamType.MPD
            u.endsWith(".mp4")                         -> StreamType.DIRECT_URL
            else                                       -> StreamType.DIRECT_URL
        }
    }

    /**
     * Unifica las fuentes de un canal:
     * - Principal (streamUrl/streamType del Channel)
     * - Variantes declaradas en el Channel (pueden ser List<StreamSource> o List<String>)
     * - Variantes inyectadas por el mapa channelMirrors
     */
    private fun buildSourcesForChannel(ch: Channel): List<StreamSource> {
        val list = mutableListOf<StreamSource>()

        // 1) principal
        list.add(StreamSource(ch.streamUrl, ch.streamType, emptyMap()))

        // 2) variantes propias del Channel (acepta StreamSource o String)
        val rawVariants: List<*> = try {
            @Suppress("UNCHECKED_CAST")
            (ch.variants as? List<*>) ?: emptyList<Any>()
        } catch (_: Throwable) {
            emptyList<Any>()
        }

        rawVariants.forEach { v ->
            when (v) {
                is StreamSource -> {
                    if (list.none { it.url == v.url }) {
                        list.add(v)
                    }
                }
                is String -> {
                    val url = v
                    val type = deduceTypeByExtensionOrGuess(url)
                    if (list.none { it.url == url }) {
                        list.add(StreamSource(url, type, emptyMap()))
                    }
                }
                else -> Unit
            }
        }

        // 3) variantes inyectadas por id/nombre (opcional)
        val key = ch.id ?: ch.name
        channelMirrors[key]?.forEach { mirror ->
            if (list.none { it.url == mirror.url }) {
                list.add(mirror)
            }
        }

        return list
    }

    private fun openMoviesActivity() {
        startActivity(Intent(this, MoviesActivity::class.java))
    }

    // -------- Ciclo de vida --------
    override fun onResume() {
        super.onResume()
        if (::timeHandler.isInitialized) timeHandler.post(timeRunnable)
    }

    override fun onPause() {
        super.onPause()
        if (::timeHandler.isInitialized) timeHandler.removeCallbacks(timeRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::timeHandler.isInitialized) timeHandler.removeCallbacks(timeRunnable)
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finishAffinity()   // cierra todas las activities
        exitProcess(0)     // fuerza la salida de la app
    }
}
