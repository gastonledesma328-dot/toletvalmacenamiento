package com.toletv.app.ui.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.LoadControl
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DefaultAllocator
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.util.Util
import com.toletv.app.R
import com.toletv.app.data.model.StreamSource
import com.toletv.app.data.model.StreamType
import com.toletv.app.data.model.ZappingChannel
import com.toletv.app.utils.StreamUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        private const val STATE_ZAP_INDEX = "state_zap_index"
        private const val STATE_OVERLAY_VISIBLE = "state_overlay_visible"
    }

    private lateinit var playerView: StyledPlayerView
    private var progressBar: ProgressBar? = null
    private var exoPlayer: ExoPlayer? = null

    /** Mirrors del ítem actual */
    private var sources: ArrayList<StreamSource> = arrayListOf()
    private var currentMirrorIndex = 0

    /** Modo fila */
    private var rowUrls: ArrayList<String>? = null
    private var rowTypes: ArrayList<String>? = null
    private var rowTitles: ArrayList<String>? = null
    private var currentRowIndex: Int = -1
    private var inRowMode: Boolean = false

    private var contentTitle: String? = null
    private var isLive: Boolean = false
    private var initialized = false

    // === OVERLAY ZAPPING ===
    private lateinit var zappingOverlay: View
    private var overlayVisible = false

    // Lista horizontal de canales (overlay)
    private lateinit var rvZapping: RecyclerView
    private lateinit var zappingAdapter: ZappingAdapter
    private var channels: MutableList<ZappingChannel> = mutableListOf()
    private var currentZapIndex = 0

    // Habilitación dinámica de overlay
    private var zappingEnabled: Boolean = true

    // Restore (rotación)
    private var restoredZapIndex: Int? = null
    private var restoredOverlayVisible: Boolean = false

    // Autocierre del overlay
    private val overlayAutoHideMs = 4000L
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val overlayHideRunnable = Runnable { showOverlay(false) }

    private val http by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_video_player)

        // Restaurar estado si aplica (guardamos y aplicamos luego de setear adapter)
        savedInstanceState?.let { state ->
            restoredZapIndex = state.getInt(STATE_ZAP_INDEX, -1).takeIf { it >= 0 }
            restoredOverlayVisible = state.getBoolean(STATE_OVERLAY_VISIBLE, false)
        }

        enableImmersiveMode()

        // ---------- Habilitación/Deshabilitación automática del overlay ----------
        // Señales para Películas / VOD
        val catExtra = (intent.getStringExtra("CATEGORY_KEY")
            ?: intent.getStringExtra("category")
            ?: "")
        val isMovieSection = intent.getBooleanExtra("IS_MOVIE", false) ||
                catExtra.contains("pelicul", ignoreCase = true)

        // Si no especifican, asumimos VOD cuando is_live=false
        val preIsLive = intent.getBooleanExtra("is_live", false)

        // Si viene ZAPPING_ENABLED explícito, respeta eso. Si no, deshabilita en Películas o VOD.
        zappingEnabled = if (intent.hasExtra("ZAPPING_ENABLED")) {
            intent.getBooleanExtra("ZAPPING_ENABLED", true)
        } else {
            !(isMovieSection || !preIsLive)
        }

        initViews()
        readIntent()

        onBackPressedDispatcher.addCallback(this) {
            if (overlayVisible) { showOverlay(false) } else { releasePlayer(); finish() }
        }

        setupPlayer()
    }

    private fun initViews() {
        playerView = findViewById(R.id.playerView)
        progressBar = findViewById(R.id.progressBar)
        playerView.useController = true
        playerView.setShowBuffering(StyledPlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        playerView.controllerShowTimeoutMs = 2500
        playerView.keepScreenOn = true

        // Overlay
        zappingOverlay = findViewById(R.id.zappingOverlay)

        // RecyclerView dentro del overlay
        rvZapping = findViewById(R.id.rvZapping)
        rvZapping.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        rvZapping.addItemDecoration(SpaceItemDecoration(dp(8)))

        // 1) Cargar canales (Intent -> fallback)
        channels = (intent.getParcelableArrayListExtra<ZappingChannel>("CHANNELS")?.toMutableList())
            ?: provideZappingChannels().toMutableList()

        // 2) Selección inicial: prioridad CHANNEL_INDEX -> URL actual -> 0
        val idxFromIntent = intent.getIntExtra("CHANNEL_INDEX", -1)
        currentZapIndex = when {
            idxFromIntent in channels.indices -> idxFromIntent
            else -> channels.indexOfFirst { it.url == sources.getOrNull(currentMirrorIndex)?.url }
        }.let { if (it < 0) 0 else it }

        // Pisa la selección si hay estado
        restoredZapIndex?.let { currentZapIndex = it }

        // 3) Adapter
        zappingAdapter = ZappingAdapter(channels) { pos, item -> zapTo(pos, item) }
        rvZapping.adapter = zappingAdapter
        zappingAdapter.select(currentZapIndex)
        rvZapping.scrollToPosition(currentZapIndex)

        // Si overlay está deshabilitado (Películas/VOD), ocultarlo del todo
        if (!zappingEnabled) {
            zappingOverlay.visibility = View.GONE
            overlayVisible = false
        } else if (restoredOverlayVisible) {
            overlayVisible = true
            zappingOverlay.visibility = View.VISIBLE
            zappingAdapter.select(currentZapIndex)
            rvZapping.scrollToPosition(currentZapIndex)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_ZAP_INDEX, currentZapIndex)
        outState.putBoolean(STATE_OVERLAY_VISIBLE, overlayVisible)
    }

    @Suppress("DEPRECATION")
    private fun readIntent() {
        contentTitle = intent.getStringExtra("title")
        isLive = intent.getBooleanExtra("is_live", false)

        // 0) Handoff directo
        val handoffUrl = intent.getStringExtra("VIDEO_URL")
        @Suppress("UNCHECKED_CAST")
        val handoffHeaders = intent.getSerializableExtra("VIDEO_HEADERS") as? HashMap<String, String>
        if (!handoffUrl.isNullOrBlank()) {
            val deduced = deduceTypeByExtensionOrGuess(handoffUrl)
            sources.clear()
            sources.add(StreamSource(handoffUrl, deduced, handoffHeaders ?: emptyMap()))
            inRowMode = false
            currentMirrorIndex = 0
            return
        }

        // 1) Mirrors completos
        val incoming = intent.getParcelableArrayListExtra<StreamSource>("SOURCES")
        if (!incoming.isNullOrEmpty()) {
            sources = incoming
            inRowMode = false
            return
        }

        // 2) Modo fila
        rowUrls = intent.getStringArrayListExtra("ROW_URLS")
        rowTypes = intent.getStringArrayListExtra("ROW_TYPES")
        rowTitles = intent.getStringArrayListExtra("ROW_TITLES")
        val idxFromIntent = intent.getIntExtra("ROW_INDEX", -1)

        inRowMode = !rowUrls.isNullOrEmpty() && rowTypes?.size == rowUrls?.size
        if (inRowMode && idxFromIntent in 0 until (rowUrls?.size ?: 0)) {
            currentRowIndex = idxFromIntent
            val url = rowUrls!![currentRowIndex]
            val type = parseStreamType(rowTypes!![currentRowIndex])
            contentTitle = (rowTitles?.getOrNull(currentRowIndex)) ?: (contentTitle ?: "")
            sources.clear()
            sources.add(StreamSource(url, type, emptyMap()))
            currentMirrorIndex = 0
            return
        }

        // 3) Compat antiguo
        val legacyUrl = intent.getStringExtra("stream_url")
        val legacyTypeStr = intent.getStringExtra("stream_type")
        val legacyType = runCatching { StreamType.valueOf(legacyTypeStr ?: "") }.getOrNull()
            ?: deduceTypeByExtensionOrGuess(legacyUrl ?: "")
        if (!legacyUrl.isNullOrBlank()) {
            sources.add(StreamSource(legacyUrl, legacyType, emptyMap()))
        }
    }

    private fun parseStreamType(raw: String?): StreamType {
        val r = (raw ?: "").uppercase(Locale.getDefault())
        if (r == "WE2") return StreamType.WE2
        return runCatching { StreamType.valueOf(r) }.getOrNull()
            ?: StreamUtils.deduceStreamType(null)
    }

    private fun deduceTypeByExtensionOrGuess(url: String): StreamType {
        val u = url.lowercase(Locale.getDefault())
        return when {
            u.endsWith(".m3u8") || u.contains(".m3u8") -> StreamType.M3U8
            u.endsWith(".mpd")  || u.contains(".mpd")  -> StreamType.MPD
            u.endsWith(".mp4")                         -> StreamType.DIRECT_URL
            else                                       -> StreamType.DIRECT_URL
        }
    }

    private suspend fun sniffContentType(url: String, headers: Map<String,String>): Pair<Int, String?> =
        withContext(Dispatchers.IO) {
            val reqBuilder = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0 (Android TV) ExoPlayer")
            headers.forEach { (k,v) -> reqBuilder.header(k, v) }
            http.newCall(reqBuilder.build()).execute().use { resp ->
                resp.code to resp.header("Content-Type")
            }
        }

    private fun buildVodLoadControl(): LoadControl =
        DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 16 * 1024))
            .setBufferDurationsMs(35_000, 120_000, 1_000, 4_000)
            .setTargetBufferBytes(-1)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

    private fun buildLiveLoadControl(): LoadControl =
        DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 16 * 1024))
            .setBufferDurationsMs(25_000, 90_000, 1_000, 3_500)
            .setTargetBufferBytes(-1)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

    private fun openInWebView(currentUrl: String) {
        val intent = Intent(this, WebPlayerActivity::class.java)
            .putExtra("title", contentTitle ?: "")
            .putExtra("web_url", currentUrl)
            .putParcelableArrayListExtra("SOURCES", sources)
            .putExtra("MIRROR_INDEX", currentMirrorIndex)
            .putParcelableArrayListExtra("CHANNELS", ArrayList(channels))
            .putExtra("CHANNEL_INDEX", currentZapIndex)
            // Propagar estado del overlay (si está deshabilitado, mantenerlo así en Web)
            .putExtra("ZAPPING_ENABLED", zappingEnabled)

        startActivity(intent); finish()
    }

    private fun openInWebView2(currentUrl: String) {
        val intent = Intent(this, WebPlayerActivity2::class.java)
            .putExtra("title", contentTitle ?: "")
            .putExtra("web_url", currentUrl)
            .putParcelableArrayListExtra("SOURCES", sources)
            .putExtra("MIRROR_INDEX", currentMirrorIndex)
            .putParcelableArrayListExtra("CHANNELS", ArrayList(channels))
            .putExtra("CHANNEL_INDEX", currentZapIndex)
            // Propagar estado del overlay (si está deshabilitado, mantenerlo así en Web)
            .putExtra("ZAPPING_ENABLED", zappingEnabled)

        startActivity(intent); finish()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> progressBar?.visibility = View.VISIBLE
                Player.STATE_READY     -> progressBar?.visibility = View.GONE
                Player.STATE_ENDED     -> if (inRowMode) playNextInRow() else finish()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val unsupported = when (error.errorCode) {
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
                else -> false
            }
            if (unsupported) {
                val curUrl = sources.getOrNull(currentMirrorIndex)?.url.orEmpty()
                Toast.makeText(this@VideoPlayerActivity, "Formato no soportado. Abriendo en WebView…", Toast.LENGTH_SHORT).show()
                releasePlayer(false)
                openInWebView(curUrl)
                return
            }
            Toast.makeText(this@VideoPlayerActivity, "Error: ${error.errorCodeName}. Probando otra fuente…", Toast.LENGTH_SHORT).show()
            tryNextMirrorOrRow()
        }
    }

    private fun setupPlayer() {
        if (initialized) return
        initialized = true
        if (sources.isEmpty()) {
            Toast.makeText(this, "No hay fuentes para reproducir", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        playCurrent()
    }

    private fun playCurrent() {
        val current = sources[currentMirrorIndex]
        val url = current.url

        if (current.type == StreamType.WEB) { openInWebView(url); return }
        if (current.type == StreamType.WE2) { openInWebView2(url); return }

        lifecycleScope.launch {
            var effectiveType = current.type
            try {
                val (code, ct) = sniffContentType(url, current.headers ?: emptyMap())
                val mime = (ct ?: "").lowercase(Locale.getDefault())
                if (mime.startsWith("text/html")) {
                    releasePlayer(false); openInWebView(url); return@launch
                }
                effectiveType = when {
                    mime.contains("m3u8") || url.endsWith(".m3u8", true) -> StreamType.M3U8
                    mime.contains("mpd")  || url.endsWith(".mpd",  true) -> StreamType.MPD
                    mime.startsWith("video/") || url.endsWith(".mp4", true) -> StreamType.DIRECT_URL
                    code in 200..399 -> deduceTypeByExtensionOrGuess(url)
                    else -> current.type
                }
            } catch (_: Exception) { /* seguimos con el tipo deducido */ }

            releasePlayer(keepInitialized = true)

            val renderersFactory = DefaultRenderersFactory(this@VideoPlayerActivity)
                .setEnableDecoderFallback(true)

            exoPlayer = ExoPlayer.Builder(this@VideoPlayerActivity, renderersFactory)
                .setLoadControl(if (isLive) buildLiveLoadControl() else buildVodLoadControl())
                .build()

            val audioAttrs = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
            exoPlayer?.setAudioAttributes(audioAttrs, true)

            playerView.player = exoPlayer
            exoPlayer?.addListener(playerListener)

            loadStream(current, effectiveType)
        }
    }

    private fun loadStream(source: StreamSource, effectiveType: StreamType) {
        try {
            val url = source.url
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(source.headers?.get("User-Agent") ?: "Mozilla/5.0 (Android TV) ExoPlayer")
                .setAllowCrossProtocolRedirects(true)
                .apply {
                    val headers = (source.headers ?: emptyMap())
                    if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
                }

            val mediaItem = MediaItem.Builder().setUri(url).build()

            val mediaSource: MediaSource = when (effectiveType) {
                StreamType.M3U8       -> HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
                StreamType.MPD        -> DashMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
                StreamType.DIRECT_URL -> ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
                StreamType.WEB -> { releasePlayer(false); openInWebView(url); return }
                StreamType.WE2 -> { releasePlayer(false); openInWebView2(url); return }
            }

            exoPlayer?.apply {
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
        } catch (_: Exception) {
            tryNextMirrorOrRow()
        }
    }

    /** Picker de variantes (mirrors) */
    private fun showVariantPicker() {
        if (sources.isEmpty()) return
        val items = sources.mapIndexed { idx, s ->
            val host = runCatching { Uri.parse(s.url).host ?: "" }.getOrNull().orEmpty()
            val type = s.type.name
            val label = try {
                val f = s.javaClass.getDeclaredField("label").apply { isAccessible = true }
                (f.get(s) as? String)?.takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }
            "${label ?: "Servidor ${idx + 1}"}  ·  $type${if (host.isNotEmpty()) "  –  $host" else ""}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(contentTitle ?: "Elegir variante")
            .setSingleChoiceItems(items, currentMirrorIndex) { dlg, which ->
                dlg.dismiss(); jumpToMirror(which)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun jumpToMirror(index: Int) {
        if (index !in sources.indices) return
        currentMirrorIndex = index
        releasePlayer(keepInitialized = true)
        playCurrent()
    }

    private fun tryNextMirrorOrRow() {
        if (currentMirrorIndex + 1 < sources.size) {
            currentMirrorIndex++
            releasePlayer(keepInitialized = true)
            playCurrent()
            return
        }
        if (inRowMode) { playNextInRow(); return }
        Toast.makeText(this, "No se pudieron reproducir las fuentes", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun playNextInRow() {
        val total = rowUrls?.size ?: 0
        if (!inRowMode || total == 0) { finish(); return }
        val next = currentRowIndex + 1
        if (next >= total) { finish(); return }

        val url = rowUrls!![next]
        val type = parseStreamType(rowTypes!![next])
        contentTitle = rowTitles?.getOrNull(next) ?: contentTitle

        sources.clear()
        sources.add(StreamSource(url, type, emptyMap()))
        currentMirrorIndex = 0
        currentRowIndex = next

        releasePlayer(keepInitialized = true)
        playCurrent()
    }

    private fun playPrevInRow() {
        val total = rowUrls?.size ?: 0
        if (!inRowMode || total == 0) return
        val prev = currentRowIndex - 1
        if (prev < 0) return

        val url = rowUrls!![prev]
        val type = parseStreamType(rowTypes!![prev])
        contentTitle = rowTitles?.getOrNull(prev) ?: contentTitle

        sources.clear()
        sources.add(StreamSource(url, type, emptyMap()))
        currentMirrorIndex = 0
        currentRowIndex = prev

        releasePlayer(keepInitialized = true)
        playCurrent()
    }

    /** DPAD/teclas: overlay + navegación + variantes */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {

            // ---------- Overlay (solo si está habilitado) ----------
            if (zappingEnabled) {
                when (event.keyCode) {
                    // Overlay: abrir/cerrar
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!overlayVisible) { showOverlay(true); return true }
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BACK -> {
                        if (overlayVisible) { showOverlay(false); return true }
                    }

                    // Navegación dentro del overlay
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (overlayVisible && channels.isNotEmpty()) {
                            currentZapIndex = if (currentZapIndex - 1 >= 0) currentZapIndex - 1 else channels.lastIndex
                            zappingAdapter.select(currentZapIndex)
                            rvZapping.smoothScrollToPosition(currentZapIndex)
                            scheduleOverlayAutohide()
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (overlayVisible && channels.isNotEmpty()) {
                            currentZapIndex = (currentZapIndex + 1) % channels.size
                            zappingAdapter.select(currentZapIndex)
                            rvZapping.smoothScrollToPosition(currentZapIndex)
                            scheduleOverlayAutohide()
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (overlayVisible && channels.isNotEmpty()) {
                            zapTo(currentZapIndex, channels[currentZapIndex])
                            return true
                        }
                    }
                }
            }

            // ---------- Variantes ----------
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> { showVariantPicker(); return true }
                KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK, KeyEvent.KEYCODE_BUTTON_Y -> {
                    if (sources.size > 1) {
                        val next = (currentMirrorIndex + 1) % sources.size
                        jumpToMirror(next)
                        Toast.makeText(this, "Variante: ${next + 1}/${sources.size}", Toast.LENGTH_SHORT).show()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // === Animación + autocierre del overlay ===
    private fun showOverlay(show: Boolean) {
        if (!zappingEnabled) return
        if (show == overlayVisible) {
            if (show) scheduleOverlayAutohide()
            return
        }
        overlayVisible = show

        if (show) {
            zappingOverlay.visibility = View.VISIBLE
            zappingOverlay.post {
                zappingOverlay.translationY = zappingOverlay.height.toFloat()
                zappingOverlay.animate()
                    .translationY(0f)
                    .setDuration(180)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        zappingAdapter.select(currentZapIndex)
                        rvZapping.scrollToPosition(currentZapIndex)
                        zappingOverlay.requestFocus()
                        scheduleOverlayAutohide()
                    }
                    .start()
            }
        } else {
            cancelOverlayAutohide()
            zappingOverlay.animate()
                .translationY(zappingOverlay.height.toFloat())
                .setDuration(160)
                .withEndAction { zappingOverlay.visibility = View.GONE }
                .start()
        }
    }

    private fun scheduleOverlayAutohide() {
        cancelOverlayAutohide()
        overlayHandler.postDelayed(overlayHideRunnable, overlayAutoHideMs)
    }

    private fun cancelOverlayAutohide() {
        overlayHandler.removeCallbacks(overlayHideRunnable)
    }

    private fun dp(value: Int): Int =
        (resources.displayMetrics.density * value).toInt()

    /** Espaciado horizontal entre ítems del carrusel */
    private class SpaceItemDecoration(private val spacePx: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            outRect.right = spacePx
            if (parent.getChildAdapterPosition(view) == 0) outRect.left = spacePx
        }
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onStart() { super.onStart(); if (Util.SDK_INT > 23 && exoPlayer == null) setupPlayer() }
    override fun onResume() { super.onResume(); enableImmersiveMode(); if (Util.SDK_INT <= 23 && exoPlayer == null) setupPlayer() }
    override fun onPause() { super.onPause(); if (Util.SDK_INT <= 23) releasePlayer(); cancelOverlayAutohide() }
    override fun onStop() { super.onStop(); if (Util.SDK_INT > 23) releasePlayer(); cancelOverlayAutohide() }
    override fun onDestroy() { super.onDestroy(); releasePlayer(); cancelOverlayAutohide() }

    private fun releasePlayer(keepInitialized: Boolean = false) {
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
        if (!keepInitialized) initialized = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ======= Helpers de zapping =======

    private fun provideZappingChannels(): List<ZappingChannel> {
        // TODO: mapeá desde tu fuente real
        return listOf(
            ZappingChannel(
                1, "Telefe",
                "https://upload.wikimedia.org/wikipedia/commons/a/ac/Telefe.png",
                "https://mitelefe.com/Api/Videos/GetSourceUrl/694564/0/HLS",
                StreamType.WEB
            ),
            ZappingChannel(
                2, "Telefe Cba",
                "https://upload.wikimedia.org/wikipedia/commons/a/ac/Telefe.png",
                "https://www.parsatv.com/embed.php?name=Telefe-Cordoba",
                StreamType.WE2
            ),
            ZappingChannel(
                3, "El Trece",
                "https://upload.wikimedia.org/wikipedia/commons/e/ec/Logo_Canal_13_200_8.png",
                "https://router.cdn.rcs.net.ar/mnp/el13_hls/playlist.m3u8",
                StreamType.M3U8
            ),
            ZappingChannel(
                4, "A24",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/3/36/A24-logo.svg/258px-A24-logo.svg.png",
                "https://canalesonline.netlify.app/servidores/anbalancer.html?id=A24",
                StreamType.WE2
            ),
            ZappingChannel(
                5, "TV Pública",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a5/TVP_-_Televisi%C3%B3n_P%C3%BAblica_%282021%29.svg/1200px-TVP_-_Televisi%C3%B3n_P%C3%BAblica_%282021%29.svg.png",
                "https://cointv.online/cvatt.html?get=Q2FuYWw3",
                StreamType.WE2
            ),
            ZappingChannel(
                6, "LN",
                "https://upload.wikimedia.org/wikipedia/commons/8/81/LN%2B.png",
                "https://canalesonline.netlify.app/servidores/sensa.html?id=LaNacionMas",
                StreamType.WE2
            ),
            ZappingChannel(
                7, "C5N",
                "https://upload.wikimedia.org/wikipedia/commons/5/5a/C5N_%282017%29.png",
                "https://is-frontera.cdn.rcs.net.ar/mnp/c5n_hls/playlist.m3u8",
                StreamType.M3U8
            ),
            ZappingChannel(
                8, "El Doce TV",
                "https://upload.wikimedia.org/wikipedia/commons/9/94/El_doce_tv_cba_logo.png",
                "https://sixdayslater.com/cvatt.html?get=Q2FuYWxfMTJfQ0JB&lang=1",
                StreamType.WEB
            ),
            ZappingChannel(
                9, "Canal 26",
                "https://upload.wikimedia.org/wikipedia/commons/8/8c/LogoCanal26.png",
                "https://canalesonline.netlify.app/servidores/sensa.html?id=Canal26",
                StreamType.WE2
            ),
            ZappingChannel(
                10, "Crónica",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/0/07/Cr%C3%B3nica-logo.svg/1280px-Cr%C3%B3nica-logo.svg.png",
                "https://canalesonline.netlify.app/servidores/sensa.html?id=Cronic",
                StreamType.WE2
            ),
            ZappingChannel(
                11, "Canal 4",
                "https://directostv.teleame.com/wp-content/uploads/2020/09/Canal-4-San-Francisco-Cordoba-en-vivo-Online.png",
                "http://204.199.3.2/.m3u8",
                StreamType.M3U8
            ),
            ZappingChannel(
                12, "America",
                "https://upload.wikimedia.org/wikipedia/commons/c/c8/Am%C3%A9rica_TV_%28Nuevo_logo_Junio_2020%29.png",
                "https://canalesonline.netlify.app/servidores/anbalancer.html?id=AMERICA_TV",
                StreamType.WE2
            ),
            ZappingChannel(
                13, "Garage TV",
                "https://static.wikia.nocookie.net/logopedia/images/d/d1/Large.logo_garageTV_HD.png.9288a09ab4d6cbef56c691cd4890aa24.png/revision/latest?cb=20210525001851&path-prefix=es",
                "https://stream1.sersat.com/hls/garagetv.m3u8",
                StreamType.M3U8
            ),
            ZappingChannel(
                14, "Cartoon Network",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/4/40/Cartoon_network_modified_logo.PNG/960px-Cartoon_network_modified_logo.PNG",
                "https://cointv.online/cvatt.html?get=Q2FydG9vbk5ldHdvcms=",
                StreamType.WE2
            ),
            ZappingChannel(
                15, "Cartoonito",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a5/Cartoonito_-_Logo_2021.svg/2560px-Cartoonito_-_Logo_2021.svg.png",
                "https://cointv.online/cvatt.html?get=Qm9vbWVyYW5n",
                StreamType.WE2
            ),
            ZappingChannel(
                16, "Disney Channel",
                "https://upload.wikimedia.org/wikipedia/commons/7/78/Disney_Channel_Germany_Logo_2014.png",
                "https://cointv.online/cvatt.html?get=RGlzbmV5Q2hhbm5lbEhE",
                StreamType.WE2
            ),
            ZappingChannel(
                17, "Disney Jr",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e3/2024_Disney_Jr._Logo.svg/1200px-2024_Disney_Jr._Logo.svg.png",
                "https://cointv.online/cvatt.html?get=RGlzbmV5SnI=",
                StreamType.WE2
            ),
            ZappingChannel(
                18, "Discovery Kids",
                "https://upload.wikimedia.org/wikipedia/commons/6/62/Discovery_kids_logo.png",
                "https://cointv.online/cvatt.html?get=RGlzY292ZXJ5X0tpZHM=",
                StreamType.WE2
            ),
            ZappingChannel(
                19, "NICKELODEON",
                "https://upload.wikimedia.org/wikipedia/commons/c/cb/Nickelodeon_2023_logo.png",
                "https://cointv.online/cvatt.html?get=Tmlja2Vsb2Rlb24=",
                StreamType.WE2
            ),
            ZappingChannel(
                20, "NICK JR",
                "https://upload.wikimedia.org/wikipedia/commons/0/05/Nick_Jr.logo.png",
                "https://cointv.online/cvatt.html?get=Tmlja19Kcg==",
                StreamType.WE2
            ),
            ZappingChannel(
                21, "Animal Planet",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/2/20/2018_Animal_Planet_logo.svg/960px-2018_Animal_Planet_logo.svg.png",
                "https://cointv.online/cvatt.html?get=QW5pbWFsUGxhbmV0",
                StreamType.WE2
            ),
            ZappingChannel(
                22, "Love Nature",
                "https://lovenature.com/wp-content/uploads/2020/08/love-nature-logo_peacock.png",
                "https://canalesonline.netlify.app/servidores/anbalancer.html?id=LOVE_NATURE",
                StreamType.WE2
            ),
            ZappingChannel(
                23, "Discovery Channel",
                "https://static.cdnlogo.com/logos/d/60/discovery-channel.png",
                "https://cointv.online/cvatt.html?get=RGlzY292ZXJ5SEQ=",
                StreamType.WE2
            ),
            ZappingChannel(
                24, "History",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f5/History_Logo.svg/1200px-History_Logo.svg.png",
                "https://cointv.online/cvatt.html?get=SGlzdG9yeUhE",
                StreamType.WE2
            ),
            ZappingChannel(
                25, "History 2",
                "https://upload.wikimedia.org/wikipedia/commons/a/a3/History2Logo2019.png",
                "https://cointv.online/cvatt.html?get=SGlzdG9yeV8y",
                StreamType.WE2
            ),
            ZappingChannel(
                26, "National Geographic",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6a/National-Geographic-Logo.svg/1200px-National-Geographic-Logo.svg.png",
                "https://cointv.online/cvatt.html?get=TmF0R2VvSEQ=",
                StreamType.WE2
            ),
            ZappingChannel(
                27, "ESPN",
                "https://upload.wikimedia.org/wikipedia/commons/6/60/ESPN_logos.png",
                "https://cointv.online/html/cvattde.html?get=RVNQTjJIRA",
                StreamType.WE2
            ),
            ZappingChannel(
                28, "ESPN 2",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/b/bf/ESPN2_logo.svg/2560px-ESPN2_logo.svg.png",
                "https://cointv.online/cvattde.html?get=RVNQTjJfQXJn",
                StreamType.WE2
            ),
            ZappingChannel(
                29, "ESPN 3",
                "https://televvd.com/wp-content/uploads/2024/03/TODOS_2024_ESPN-3-1024x357.png",
                "https://cointv.online/cvattde.html?get=RVNQTjM",
                StreamType.WE2
            ),
            ZappingChannel(
                30, "ESPN 4",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/7/78/ESPN_4_logo.svg/1200px-ESPN_4_logo.svg.png",
                "https://streamtp11.com/global1.php?stream=espn4",
                StreamType.WE2
            ),
            ZappingChannel(
                31, "ESPN Premium",
                "https://upload.wikimedia.org/wikipedia/commons/d/db/ESPN_Premium_logo.png",
                "https://canalesonline.netlify.app/servidores/anbalancer.html?id=ESPN_PREMIUM",
                StreamType.WE2
            ),
            ZappingChannel(
                32, "TyC Sports",
                "https://r2.thesportsdb.com/images/media/channel/logo/zmpjy41624030194.png",
                "https://streamtp11.com/global1.php?stream=tycsports",
                StreamType.WE2
            ),
            ZappingChannel(
                33, "TNT Sports",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/d/d3/TNT_Sports_2021_logo.svg/1280px-TNT_Sports_2021_logo.svg.png",
                "https://cointv.online/cvattde.html?get=VE5UX1Nwb3J0c19IRA",
                StreamType.WE2
            ),
            ZappingChannel(
                34, "Fox Sports",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/0/0c/FOX_Sports_logo.svg/2560px-FOX_Sports_logo.svg.png",
                "https://cointv.online/cvattde.html?get=Rm94U3BvcnRz",
                StreamType.WE2
            ),
            ZappingChannel(
                35, "Fox Sports 2",
                "https://cdn.storage.foromedios.com/monthly_2023_02/large.1344882745_FOXSports2ARG(2023-).png.dc87e7653323f06426dd778a825e3514.png",
                "https://cointv.online/cvattde.html?get=Rm94U3BvcnRzMkhE",
                StreamType.WE2
            ),
            ZappingChannel(
                36, "Fox Sports 3",
                "https://cdn.storage.foromedios.com/monthly_2023_02/large.551251533_FOXSports3ARG(2023-).png.9facfaf5bfc0dd66a8d9535ea8d7cb05.png",
                "https://cointv.online/cvattde.html?get=Rm94U3BvcnRzM0hE",
                StreamType.WE2
            ),
            ZappingChannel(
                37, "Dsport",
                "https://upload.wikimedia.org/wikipedia/commons/5/5a/DSports.png",
                "https://streamtp11.com/global1.php?stream=dsports",
                StreamType.WE2
            ),
            ZappingChannel(
                38, "Dreamworks",
                "https://logos-world.net/wp-content/uploads/2020/12/DreamWorks-Animation-Logo.png",
                "https://cointv.online/cvatt.html?get=RHJlYW13b3Jrcw==",
                StreamType.WE2
            ),
            ZappingChannel(
                39, "Warner",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/6/64/Warner_Bros_logo.svg/355px-Warner_Bros_logo.svg.png",
                "https://cointv.online/html/cvatt.html?get=V2FybmVySEQ=",
                StreamType.WE2
            ),
            ZappingChannel(
                40, "TNT Series",
                "https://upload.wikimedia.org/wikipedia/commons/7/75/TNT_Series_Logo_2016.png",
                "https://tele-libre.org/html/cvatt.html?get=VE5UU2VyaWVz",
                StreamType.WE2
            ),
            ZappingChannel(
                41, "TNT",
                "https://upload.wikimedia.org/wikipedia/commons/6/68/Logo_TNT_Series.png",
                "https://cointv.online/cvatt.html?get=VE5UX0hEX0FyZw",
                StreamType.WE2
            ),
            ZappingChannel(
                42, "TNT Novelas",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3a/Logo_TNT_Novelas.png/1200px-Logo_TNT_Novelas.png",
                "https://cointv.online/cvatt.html?get=VEJT",
                StreamType.WE2
            ),
            ZappingChannel(
                43, "Cine Ar",
                "https://upload.wikimedia.org/wikipedia/commons/a/a4/CINEARLogo.png",
                "https://cointv.online/html/cvatt.html?get=SU5DQUFfVHY=",
                StreamType.WE2
            ),
            ZappingChannel(
                44, "Star Channel",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/8/89/Star_Channel_2020.svg/2560px-Star_Channel_2020.svg.png",
                "https://cointv.online/html/cvatt.html?get=Rk9YSEQ=",
                StreamType.WE2
            ),
            ZappingChannel(
                45, "Cine Canal",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e6/CinecanalLA.png/1200px-CinecanalLA.png",
                "https://cointv.online/html/cvatt.html?get=Q2luZWNhbmFsSEQ=",
                StreamType.WE2
            ),
            ZappingChannel(
                46, "Cinemax",
                "https://upload.wikimedia.org/wikipedia/commons/6/6a/Cinemax_LA.png",
                "https://cointv.online/cvatt.html?get=Q2luZW1heA==",
                StreamType.WE2
            ),
            ZappingChannel(
                47, "Space",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1a/SpaceLogo.svg/1200px-SpaceLogo.svg.png",
                "https://cointv.online/cvatt.html?get=U3BhY2U=",
                StreamType.WE2
            ),
            ZappingChannel(
                48, "Paramount Network",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/9/9f/Paramount_Network.svg/1028px-Paramount_Network.svg.png",
                "https://cointv.online/html/cvatt.html?get=UGFyYW1vdW50",
                StreamType.WE2
            ),
            ZappingChannel(
                49, "HBO",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/d/de/HBO_logo.svg/512px-HBO_logo.svg.png",
                "https://cointv.online/html/cvatt.html?get=SEJPSEQ=",
                StreamType.WE2
            ),
            ZappingChannel(
                50, "HBO Family",
                "https://upload.wikimedia.org/wikipedia/commons/3/3a/HBO_Family_logo.png",
                "https://cointv.online/html/cvatt.html?get=SEJPX0ZhbWlseQ==",
                StreamType.WE2
            ),
            ZappingChannel(
                51, "Ciudad Magazine",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/d/d5/Ciudad_magazine_logo.png/640px-Ciudad_magazine_logo.png",
                "https://live-01-07-ciudad.vodgc.net/live-01-07-ciudad.vodgc.net/tracks-v1a1/mono.m3u8",
                StreamType.M3U8
            )
        )
    }

    private fun zapTo(position: Int, item: ZappingChannel) {
        currentZapIndex = position
        zappingAdapter.select(position)
        showOverlay(false)

        when (item.type) {
            StreamType.M3U8, StreamType.MPD, StreamType.DIRECT_URL -> {
                sources.clear()
                sources.add(StreamSource(item.url, item.type, emptyMap()))
                currentMirrorIndex = 0
                contentTitle = item.name
                releasePlayer(keepInitialized = true)
                playCurrent()
            }
            StreamType.WEB, StreamType.WE2 -> {
                val target = if (item.type == StreamType.WEB) WebPlayerActivity::class.java else WebPlayerActivity2::class.java
                startActivity(
                    Intent(this, target).apply {
                        putExtra("title", item.name)
                        putExtra("VIDEO_URL", item.url)
                        putParcelableArrayListExtra("CHANNELS", ArrayList(channels))
                        putExtra("CHANNEL_INDEX", position)
                        putExtra("ZAPPING_ENABLED", zappingEnabled) // mantener criterio
                    }
                )
                finish()
            }
        }
    }
}
