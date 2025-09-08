package com.toletv.app.ui.player

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import com.toletv.app.R
import com.toletv.app.data.model.StreamSource
import com.toletv.app.data.model.StreamType
import com.toletv.app.data.model.ZappingChannel

class WebPlayerActivity : AppCompatActivity() {

    // Web
    private lateinit var webView: WebView
    private lateinit var webFullscreenContainer: ViewGroup
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var webUrl: String? = null
    private var contentTitle: String? = null

    // Mirrors y puntero
    private var sources: ArrayList<StreamSource> = arrayListOf()
    private var currentIndex = 0

    // Señales
    @Volatile private var userInteracted: Boolean = false
    @Volatile private var fallingBack: Boolean = false

    // Para WEB lo dejamos desactivado por defecto (lo habilitás con el extra)
    private var allowNativeHandoff: Boolean = false

    companion object {
        private val denyHandoffHosts: MutableSet<String> = mutableSetOf()
    }

    // === OVERLAY ZAPPING ===
    private lateinit var zappingOverlay: View
    private lateinit var rvZapping: RecyclerView
    private lateinit var zappingAdapter: ZappingAdapter
    private var channels: MutableList<ZappingChannel> = mutableListOf()
    private var currentZapIndex = 0
    private var overlayVisible = false
    private val overlayAutoHideMs = 4000L
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val overlayHideRunnable = Runnable { showOverlay(false) }
    private var zappingEnabled = true

    // -------- helpers detección --------
    private fun hostOf(u: String?): String? =
        try { Uri.parse(u).host?.lowercase() } catch (_: Exception) { null }

    private fun isAdUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase()
        return listOf(
            "doubleclick.net","googlesyndication.com","adnxs.com","exoclick","taboola","outbrain",
            "adservice","adsystem","popads","onclick","adsterra","imasdk","/vast","/preroll"
        ).any { u.contains(it) }
    }

    private fun isWe2Url(u: String?): Boolean {
        if (u.isNullOrBlank()) return false
        val ul = u.lowercase()
        return ul.contains("stream.we2") || ul.contains("/we2/") || ul.contains("we2=")
                || ul.endsWith(".we2") || ul.contains(".we2?")
    }

    private fun isDirectStreamUrl(u: String?): Boolean {
        if (u.isNullOrBlank()) return false
        if (isWe2Url(u)) return false
        val ul = u.lowercase()
        return ul.contains(".m3u8") || ul.contains(".mpd") || ul.contains(".ism/manifest") || ul.endsWith(".mp4")
    }

    // -------- handoff opcional a ExoPlayer --------
    private fun handoffToNativePlayer(streamUrl: String) {
        val h = hostOf(streamUrl)
        if (!allowNativeHandoff || isWe2Url(streamUrl) || (h != null && denyHandoffHosts.contains(h))) {
            android.util.Log.d("WEBFLOW", "Handoff bloqueado (allow=$allowNativeHandoff, we2=${isWe2Url(streamUrl)}, hostDeny=${h in denyHandoffHosts}): $streamUrl")
            return
        }
        runOnUiThread {
            try {
                Toast.makeText(this, "Abriendo stream nativo…", Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(this, VideoPlayerActivity::class.java).apply {
                        putExtra("VIDEO_URL", streamUrl)
                        putExtra("title", contentTitle ?: "Video")
                        putExtra("is_live", false)
                        putParcelableArrayListExtra("CHANNELS", ArrayList(channels))
                        putExtra("CHANNEL_INDEX", currentZapIndex)
                    }
                )
                finish()
            } catch (_: Exception) {
                Toast.makeText(this, "No se pudo abrir el reproductor nativo", Toast.LENGTH_LONG).show()
            }
        }
    }

    // =================== Ciclo de vida ===================
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_web_player)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        enableImmersiveMode()

        // ----- Intent base -----
        contentTitle = intent.getStringExtra("title")
        webUrl = intent.getStringExtra("web_url")
            ?: intent.getStringExtra("url")
                    ?: intent.getStringExtra("EXTRA_URL")
                    ?: intent.getStringExtra("streamUrl")
                    ?: intent.getStringExtra("VIDEO_URL")

        // **Desactivar zapping en Películas** y activar handoff nativo por defecto si no viene explícito
        val catExtra = (intent.getStringExtra("CATEGORY_KEY") ?: intent.getStringExtra("category") ?: "")
        val isMovieSection = intent.getBooleanExtra("IS_MOVIE", false)
                || catExtra.equals("peliculas", true)
                || catExtra.equals("películas", true)
                || catExtra.contains("pelicul", true)

        zappingEnabled = intent.getBooleanExtra("ZAPPING_ENABLED", true) && !isMovieSection

        allowNativeHandoff = if (intent.hasExtra("ALLOW_NATIVE_HANDOFF")) {
            intent.getBooleanExtra("ALLOW_NATIVE_HANDOFF", false)
        } else {
            // Por defecto, en Películas habilitamos handoff nativo
            isMovieSection
        }

        // Mirrors
        intent.getParcelableArrayListExtra<StreamSource>("SOURCES")?.let { sources = it }
        if (sources.isEmpty() && !webUrl.isNullOrBlank()) {
            sources.add(StreamSource(webUrl!!, StreamType.WEB))
        }

        // ----- Views -----
        webView = findViewById(R.id.webView)
        webFullscreenContainer = findViewById(R.id.webFullscreenContainer)

        zappingOverlay = findViewById(R.id.zappingOverlay)
        rvZapping = findViewById(R.id.rvZapping)
        rvZapping.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        rvZapping.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val s = dp(8); outRect.right = s; if (parent.getChildAdapterPosition(view) == 0) outRect.left = s
            }
        })

        // Canales (zapping): Intent o fallback
        channels = (intent.getParcelableArrayListExtra<ZappingChannel>("CHANNELS")?.toMutableList())
            ?: provideZappingChannels().toMutableList()
        if (channels.isEmpty()) channels = provideZappingChannels().toMutableList()

        val idxFromIntent = intent.getIntExtra("CHANNEL_INDEX", -1)
        currentZapIndex = if (idxFromIntent in channels.indices) idxFromIntent else 0

        zappingAdapter = ZappingAdapter(channels) { pos, item -> onZap(pos, item) }
        rvZapping.adapter = zappingAdapter
        zappingAdapter.select(currentZapIndex)
        rvZapping.scrollToPosition(currentZapIndex)
        zappingAdapter.notifyDataSetChanged()
        zappingOverlay.bringToFront()

        // Si zapping está deshabilitado (Películas), no mostrar overlay
        if (!zappingEnabled) {
            zappingOverlay.visibility = View.GONE
            overlayVisible = false
        }

        // Hosts para denegar handoff si hay WE2
        val allHosts = buildList {
            hostOf(webUrl)?.let { add(it) }
            sources.forEach { hostOf(it.url)?.let { h -> add(h) } }
        }
        if (sources.any { it.type == StreamType.WE2 || isWe2Url(it.url) } || isWe2Url(webUrl)) {
            allowNativeHandoff = false
            denyHandoffHosts.addAll(allHosts)
        }
        if (allHosts.any { it in denyHandoffHosts }) allowNativeHandoff = false

        if (sources.isEmpty()) {
            Toast.makeText(this, "No se recibió una URL de reproducción", Toast.LENGTH_LONG).show()
            finish(); return
        }

        // ----- WebView setup -----
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            allowContentAccess = true
            allowFileAccess = true
            setGeolocationEnabled(true)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setNeedInitialFocus(false)
            @Suppress("DEPRECATION")
            setOffscreenPreRaster(true)
            userAgentString = "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36 WebView"
        }
        webView.setBackgroundColor(Color.BLACK)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.keepScreenOn = true
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        WebView.setWebContentsDebuggingEnabled(true)

        webView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                userInteracted = true
                injectAutoUnmute()
                ensurePlayerFocus()
                injectStabilityAgent()
            }
            false
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface fun onUserPlay() {
                userInteracted = true
                runOnUiThread {
                    injectAutoUnmute()
                    ensurePlayerFocus()
                    injectStabilityAgent()
                }
            }
            @JavascriptInterface fun playStream(url: String?) {
                if (allowNativeHandoff && !isWe2Url(url) && isDirectStreamUrl(url)) {
                    url?.let { handoffToNativePlayer(it) }
                }
            }
        }, "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                if (isAdUrl(u)) return true

                if (isDirectStreamUrl(u)) {
                    val h = hostOf(u)
                    if (allowNativeHandoff && !isWe2Url(u) && (h == null || h !in denyHandoffHosts)) {
                        handoffToNativePlayer(u)
                        return true // NO navegar; ya hicimos handoff
                    }
                    return false
                }

                val scheme = request?.url?.scheme
                val schemeOk = scheme == "http" || scheme == "https"
                return !schemeOk
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                if (isAdUrl(u) && !userInteracted) return WebResourceResponse("text/plain", "utf-8", null)

                if (isDirectStreamUrl(u)) {
                    val h = hostOf(u)
                    if (allowNativeHandoff && !isWe2Url(u) && (h == null || h !in denyHandoffHosts) &&
                        (request?.isForMainFrame == true || userInteracted)) {
                        runOnUiThread { handoffToNativePlayer(u) }
                        return null
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                android.util.Log.d("WEBFLOW", "onPageStarted ${url ?: "(null)"}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                android.util.Log.d("WEBFLOW", "onPageFinished ${url ?: "(null)"}")
                injectMinimalCss()
                injectPlayDetection()
                injectAutoPlaySoft()
                injectHardAutoplay()
                ensurePlayerFocus()
                injectAutoUnmute()
                injectVideoHealthWatchdog()

                if (userInteracted) injectStabilityAgent()

                // NUEVO: evita _blank y detecta links/requests directos para abrir ExoPlayer
                injectForceSelfTargets()
                injectDirectLinkSniffer()

                // NUEVO: hacerlo navegable con DPAD (focus + navegación espacial)
                injectFocusAndSpatialNavLite()

                webView.post {
                    webView.isFocusable = true
                    webView.isFocusableInTouchMode = true
                    webView.requestFocus()
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    android.util.Log.e("WEBFLOW", "onReceivedError main: ${error?.description}")
                    fallToNext("Error de carga: ${error?.description}")
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) {
                    val code = errorResponse?.statusCode ?: 200
                    android.util.Log.e("WEBFLOW", "HTTP $code ${errorResponse?.reasonPhrase} @ ${request.url}")
                    if (code >= 400) fallToNext("HTTP $code")
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                android.util.Log.e("WEBFLOW", "SSL error: $error")
                handler?.cancel()
                Toast.makeText(this@WebPlayerActivity, "Problema SSL. Probá nuevamente.", Toast.LENGTH_SHORT).show()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                android.util.Log.e("WEBFLOW", "RenderProcessGone, didCrash=${detail.didCrash()}")
                try { view.destroy() } catch (_: Exception) {}
                fallToNext("Crash del render process")
                return true
            }
        }

        // WebChromeClient para FULLSCREEN HTML5
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                webFullscreenContainer.visibility = View.VISIBLE
                webFullscreenContainer.addView(
                    view,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                // Overlay por encima del fullscreen
                zappingOverlay.bringToFront()
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }

        installServiceWorkerAdPolicy()

        // arranque
        currentIndex = 0
        playCurrent()

        // Back: cierra overlay, luego fullscreen, luego historial, luego actividad
        onBackPressedDispatcher.addCallback(this) {
            when {
                overlayVisible -> showOverlay(false)
                customView != null -> hideCustomView()
                this@WebPlayerActivity::webView.isInitialized && webView.canGoBack() -> webView.goBack()
                else -> finish()
            }
        }
    }

    private fun hideCustomView() {
        customView?.let { v -> webFullscreenContainer.removeView(v) }
        webFullscreenContainer.visibility = View.GONE
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
    }

    // ===== Lógica de mirrors =====
    private fun playCurrent() {
        if (currentIndex !in 0 until sources.size) {
            Toast.makeText(this, "No hay más mirrors", Toast.LENGTH_SHORT).show()
            return
        }
        val cur = sources[currentIndex]
        webUrl = cur.url
        android.util.Log.d("WEBFLOW", "PLAY idx=$currentIndex → ${cur.url}")
        try {
            val headers = (cur.headers ?: emptyMap())
            if (headers.isEmpty()) webView.loadUrl(cur.url) else webView.loadUrl(cur.url, headers)
        } catch (_: Exception) {
            fallToNext("Excepción al cargar URL")
        }
    }

    private fun fallToNext(reason: String) {
        if (fallingBack) return
        fallingBack = true
        android.util.Log.w("WEBFLOW", "Fallback ($reason). Siguiente mirror…")

        currentIndex++
        if (currentIndex < sources.size) {
            Toast.makeText(this, "Probando mirror ${currentIndex + 1}/${sources.size}", Toast.LENGTH_SHORT).show()
            webView.postDelayed({
                fallingBack = false
                playCurrent()
            }, 350)
        } else {
            fallingBack = false
            Toast.makeText(this, "Error: $reason. Sin más mirrors. Atrás para salir.", Toast.LENGTH_LONG).show()
        }
    }

    // ===== Inyecciones útiles =====
    private fun injectMinimalCss() {
        val js = """
            (function(){
              try{
                var css=`html,body{margin:0!important;padding:0!important;background:#000!important}
                video{image-rendering:auto}`;
                var s=document.getElementById('__MIN_CSS__');
                if(!s){ s=document.createElement('style'); s.id='__MIN_CSS__'; s.textContent=css; document.head.appendChild(s); }
                var v=document.querySelector('video');
                if(v){ try{ v.style.willChange='transform'; v.style.transform='translateZ(0)'; }catch(e){} }
              }catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectPlayDetection() {
        val js = """
            (function(){
              try{
                ['.vjs-big-play-button','.plyr__control--overlaid','button[class*="play"]','.jw-display','button[aria-label="Play"]']
                  .forEach(function(sel){
                    document.querySelectorAll(sel).forEach(function(btn){
                      btn.addEventListener('click', function(){ AndroidBridge.onUserPlay(); }, {capture:true});
                      btn.addEventListener('touchend', function(){ AndroidBridge.onUserPlay(); }, {capture:true});
                    });
                  });
                var v = document.querySelector('video');
                if (v){ v.addEventListener('play', function(){ AndroidBridge.onUserPlay(); }, {capture:true}); }
              }catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectAutoPlaySoft() {
        val js = """
            (function(){
              try{
                function go(){
                  try{ var v=document.querySelector('video'); if(v){ v.muted=false; v.removeAttribute('muted'); v.play().catch(()=>{});} }catch(e){}
                  try{ if(typeof jwplayer==='function'){ var jw=jwplayer(); if(jw&&jw.play) jw.play(); } }catch(e){}
                  try{ if(typeof videojs==='function'){ var p = videojs.getPlayers && Object.values(videojs.getPlayers())[0]; if(p&&p.play) p.play(); } }catch(e){}
                  try{ if(window.Plyr){ var el=document.querySelector('.plyr, video'); if(el&&el.plyr) el.plyr.play(); } }catch(e){}
                }
                setTimeout(go, 400); setTimeout(go, 1500); setTimeout(go, 3000);
              }catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectAutoUnmute() {
        val js = """
            (function(){
              try{
                function apply(v){
                  try{ v.muted=false; v.removeAttribute('muted'); if(typeof v.volume==='number') v.volume=1.0; if(typeof v.defaultMuted!=='undefined') v.defaultMuted=false; }catch(e){}
                }
                var i=0;(function loop(){ document.querySelectorAll('video').forEach(apply); if(++i<12) setTimeout(loop,600); })();
              }catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectVideoHealthWatchdog() {
        val js = """
          (function(){
            try{
              if (window.__VID_WATCHDOG__) return;
              window.__VID_WATCHDOG__ = true;
              var stuckCount = 0, lastT = 0, recoverTries = 0;

              function softRecover(v){
                try{
                  if (typeof v.play === 'function') v.play().catch(()=>{});
                  if (v.readyState < 2) { try{ v.load(); }catch(e){} }
                }catch(e){}
                try{ if (typeof jwplayer==='function'){ var jw=jwplayer(); if(jw && jw.play){ jw.pause && jw.pause(true); setTimeout(()=>{jw.play();}, 300); } } }catch(e){}
                try{ if (typeof videojs==='function'){ var p = videojs.getPlayers && Object.values(videojs.getPlayers())[0]; if(p&&p.play){ p.pause && p.pause(); setTimeout(()=>{p.play();}, 300);} } }catch(e){}
                try{ if (window.hls && hls.recoverMediaError) hls.recoverMediaError(); }catch(e){}
              }

              function hardRecover(){
                try{
                  var v=document.querySelector('video');
                  if (v){
                    v.pause && v.pause();
                    setTimeout(function(){
                      v.load && v.load();
                      v.play && v.play();
                    }, 400);
                  }
                  try{ if (window.hls && hls.recoverMediaError) hls.recoverMediaError(); }catch(e){}
                }catch(e){}
              }

              function loop(){
                var v=document.querySelector('video');
                if(!v){ setTimeout(loop,1200); return; }
                var ct=v.currentTime||0;
                var paused=v.paused, ended=v.ended, rs=v.readyState||0;

                if (!paused && !ended) {
                  if (rs < 2 || Math.abs(ct - lastT) < 0.01) {
                    stuckCount++;
                  } else {
                    stuckCount = 0;
                  }
                } else {
                  stuckCount = 0;
                }
                lastT = ct;

                if (stuckCount >= 5){
                  if (recoverTries < 3) {
                    softRecover(v);
                    recoverTries++;
                    stuckCount = 0;
                  } else {
                    hardRecover();
                  }
                }
                setTimeout(loop, 2000);
              }
              setTimeout(loop, 1800);
            }catch(e){}
          })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectHardAutoplay() {
        val js = """
      (function(){
        try{
          function forceVideo(v){
            try{
              v.setAttribute('autoplay','');
              v.setAttribute('playsinline','');
              v.playsInline = true;
              v.muted = true;
              v.removeAttribute('muted');
              if (typeof v.play === 'function') v.play().catch(function(){});
            }catch(e){}
          }
          document.querySelectorAll('video').forEach(forceVideo);

          try{ if (typeof jwplayer==='function'){ var jw=jwplayer(); if(jw && jw.play) jw.play(); } }catch(e){}
          try{ if (typeof videojs==='function'){ 
            var p = (videojs.getPlayers && Object.values(videojs.getPlayers())[0]) || null; 
            if(p && p.play) p.play(); 
          } }catch(e){}
          try{ if (window.Plyr){ 
            var el = document.querySelector('.plyr, video'); 
            if (el && el.plyr && el.plyr.play) el.plyr.play(); 
          } }catch(e){}
          try{
            var ev1 = new MouseEvent('click', {bubbles:true, cancelable:true, view:window});
            var ev2 = new KeyboardEvent('keydown', {key:' ', code:'Space', bubbles:true});
            document.body && document.body.dispatchEvent(ev1);
            document.body && document.body.dispatchEvent(ev2);
          }catch(e){}
          setTimeout(function(){ document.querySelectorAll('video').forEach(forceVideo); }, 1200);
          setTimeout(function(){ document.querySelectorAll('video').forEach(forceVideo); }, 3000);
        }catch(e){}
      })();
    """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectStabilityAgent() {
        val js = """
            (function(){
              try{
                if (window.__STAB_AGENT__) return; 
                window.__STAB_AGENT__ = true;

                function dropped(v){
                    try{
                      if (v.getVideoPlaybackQuality) {
                        var q=v.getVideoPlaybackQuality();
                        return (q && typeof q.droppedVideoFrames==='number') ? q.droppedVideoFrames : 0;
                      }
                      if (typeof v.webkitDroppedFrameCount==='number') return v.webkitDroppedFrameCount;
                    }catch(e){}
                    return 0;
                }

                function clamp720_30(){
                  try{
                    var h = window.hls || (window.player && window.player.hls);
                    if (h && h.levels){
                      var pick=-1, best=0;
                      for (var i=0;i<h.levels.length;i++){
                        var L=h.levels[i]; var hh=L.height||0; var br=L.bitrate||0; var fr=L.frameRate||0;
                        if (hh<=720 && (fr===0 || fr<=30)){ if (br>best){best=br;pick=i;} }
                      }
                      if (pick>=0){ h.autoLevelEnabled=false; h.currentLevel=pick; return true; }
                    }
                  }catch(e){}
                  return false;
                }
                function restoreAuto(){
                  try{ var h=window.hls; if (h){ h.autoLevelEnabled=true; h.currentLevel=-1; } }catch(e){}
                }

                function gpuHints(){
                  var v=document.querySelector('video'); if (!v) return;
                  try{ v.style.willChange='transform'; v.style.transform='translateZ(0)'; }catch(e){}
                }

                function loop(){
                  var v=document.querySelector('video'); 
                  if(!v){ setTimeout(loop,1200); return; }
                  gpuHints();
                  var d=dropped(v);
                  if (typeof window.__lastDropped==='undefined') window.__lastDropped=d;
                  var inc=d-window.__lastDropped; window.__lastDropped=d;

                  if (inc>=20){
                    if (!window.__capActive__){ window.__capActive__ = clamp720_30(); }
                    if (typeof window.__capRelax!=='undefined') clearTimeout(window.__capRelax);
                    window.__capRelax = setTimeout(function(){ window.__capActive__=false; restoreAuto(); }, 15000);
                  }
                  setTimeout(loop, 2000);
                }
                setTimeout(loop, 1500);
              }catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /** Fuerza target=_blank -> _self y window.open a navegar en la misma vista. */
    private fun injectForceSelfTargets() {
        val js = """
          (function(){
            try{
              document.querySelectorAll('a[target="_blank"]').forEach(function(a){ a.setAttribute('target','_self'); });
              try{
                var oldOpen = window.open;
                window.open = function(url){
                  if (!url) return null;
                  try{ location.href = url; }catch(e){}
                  return oldOpen ? oldOpen.apply(window, arguments) : null;
                };
              }catch(e){}
            }catch(e){}
          })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /** Sniffer: clics y requests hacia .m3u8/.mpd/.mp4/.ism/manifest -> AndroidBridge.playStream(url) */
    private fun injectDirectLinkSniffer() {
        val js = """
          (function(){
            try{
              if (window.__NATIVE_SNIFFER__) return;
              window.__NATIVE_SNIFFER__ = true;

              function isDirect(u){
                if(!u) return false;
                u = (""+u).toLowerCase();
                return /\.m3u8(\?|#|$)/.test(u) || /\.mpd(\?|#|$)/.test(u) || /\.mp4(\?|#|$)/.test(u) || u.indexOf(".ism/manifest")>=0;
              }

              // Clics en <a>
              document.addEventListener('click', function(e){
                var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
                if(!a) return;
                var href = a.href;
                if (isDirect(href)){
                  try{ AndroidBridge.playStream(href); }catch(_){}
                  e.preventDefault(); e.stopPropagation();
                  return false;
                }
              }, true);

              // Interceptar window.open
              try{
                var _open = window.open;
                window.open = function(url){
                  try{ if (isDirect(url)){ AndroidBridge.playStream(url); return null; } }catch(_){}
                  return _open ? _open.apply(this, arguments) : null;
                };
              }catch(_){}

              // Interceptar fetch
              try{
                var _fetch = window.fetch;
                window.fetch = function(input, init){
                  try{
                    var u = (typeof input === 'string') ? input : (input && input.url);
                    if (isDirect(u)) { AndroidBridge.playStream(u); }
                  }catch(_){}
                  return _fetch.apply(this, arguments);
                };
              }catch(_){}

              // Interceptar XHR
              try{
                var _xhrOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(m,u){
                  try{ if (isDirect(u)) { AndroidBridge.playStream(u); } }catch(_){}
                  return _xhrOpen.apply(this, arguments);
                };
              }catch(_){}
            }catch(e){}
          })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /** NUEVO: hace focusable lo clicable y añade navegación espacial con DPAD en la página */
    /** NAV TV "lite": promueve algunos CTAs/boxes al focus sin degradar el resto; DPAD sólo si la página no lo usa */
    private fun injectFocusAndSpatialNavLite() {
        val js = """
(function(){
  try{
    var ua=(navigator.userAgent||"").toLowerCase();
    if(!(ua.includes("android")&&(ua.includes("tv")||ua.includes("aosp")||ua.includes("bravia")||ua.includes("smart-tv")))){
      return;
    }

    // ====== Hosts conocidos ======
    var SERVER_WORDS = [
      "vidhide","streamwish","filemoon","mixdrop","streamtape","dood","okru","ok.ru","uqload","vidoza",
      "yourupload","sbplay","streamsb","gcloud","upcloud","wolfstream","vidcloud","streamlare","sendvid",
      "megacloud","pixeldrain","hydrax","fastream","drop","mcloud","voe","mp4upload","streamwish.to","vidhidepro",
      "download"
    ];

    // ========= Utils =========
    function visible(el){
      if(!el) return false;
      var cs=getComputedStyle(el);
      if(cs.display==='none'||cs.visibility==='hidden'||el.disabled) return false;
      if(cs.opacity && parseFloat(cs.opacity)<0.12) return false;
      if(cs.pointerEvents==='none') return false;
      var r=el.getBoundingClientRect();
      return (r.width>=20 && r.height>=20 && r.bottom>=0 && r.right>=0 &&
              r.top<=(innerHeight||document.documentElement.clientHeight) &&
              r.left<=(innerWidth||document.documentElement.clientWidth));
    }
    function rect(el){ var r=el.getBoundingClientRect(); return {l:r.left,t:r.top,r:r.right,b:r.bottom,w:r.width,h:r.height,cx:(r.left+r.right)/2,cy:(r.top+r.bottom)/2}; }
    function textOf(el){
      try{
        var t=(el.getAttribute('aria-label')||el.getAttribute('title')||el.innerText||el.textContent||'');
        return t.trim().replace(/\s+/g,' ').toLowerCase();
      }catch(_){ return ''; }
    }
    function hasImgOrSvg(el){ return !!el.querySelector('img,svg'); }
    function hasReadableText(el){
      var t = textOf(el);
      if (t && t.length>=2) return true;
      var spans = el.querySelectorAll('span, p, b, strong, small');
      for (var i=0;i<spans.length;i++){
        var ts = (spans[i].innerText||spans[i].textContent||'').trim();
        if (ts.length>=2) return true;
      }
      return false;
    }

    var RX_MAIN_TXT = /(ver|play|reproducir|watch|continuar|ver ahora|watch now|play now|server|mirror|source|fuente|opci[oó]n|calidad|quality|hd|720p|1080p|open|start|video|player)/i;
    var RX_MAIN_CLS = /(primary|cta|play|watch|hero|main|principal|featured|server|mirror|source|opci[oó]n|quality|selector|tab|pill|link|btn|host)/i;
    var RX_EXCLUDE  = /(close|cerrar|\bx\b|ad[s]?|advert|cookie|gdpr|policy|login|sign[- ]?in|register|sign[- ]?up|age[- ]?gate|report|share|like|promo|banner|omitir|skip)/i;

    function isServerName(el){
      var t = textOf(el);
      var href = (el.getAttribute && (el.getAttribute('href')||'')) || '';
      var dataHost = (el.getAttribute('data-server')||el.getAttribute('data-host')||el.getAttribute('data-mirror')||'').toLowerCase();
      var cls = (el.className||'').toString().toLowerCase();
      var id  = (el.id||'').toString().toLowerCase();
      for (var i=0;i<SERVER_WORDS.length;i++){
        var w = SERVER_WORDS[i].toLowerCase();
        if (t.includes(w) || href.toLowerCase().includes(w) || dataHost.includes(w) || cls.includes(w) || id.includes(w)) return true;
      }
      if (/(server|mirror|source)\s*[\-_:]?\s*([0-9]+|[a-z])\b/i.test(t)) return true;
      return false;
    }
    function hasMainKW(el){
      var cls=(el.className||'').toString().toLowerCase();
      var id =(el.id||'').toString().toLowerCase();
      var aria=(el.getAttribute('aria-label')||'').toLowerCase();
      var title=(el.getAttribute('title')||'').toLowerCase();
      var data=(el.getAttribute('data-testid')||el.getAttribute('data-qa')||el.getAttribute('data-action')||'').toLowerCase();
      var t=textOf(el);
      if (RX_EXCLUDE.test(cls) || RX_EXCLUDE.test(id) || RX_EXCLUDE.test(aria) || RX_EXCLUDE.test(title) || RX_EXCLUDE.test(t)) return false;
      return RX_MAIN_TXT.test(t) || RX_MAIN_CLS.test(cls) || RX_MAIN_CLS.test(id) || RX_MAIN_CLS.test(aria) || RX_MAIN_CLS.test(title) || RX_MAIN_CLS.test(data);
    }

    // ---- Detecta contenedor "global" de servidores (NO lo marcamos)
    function isWrapper(el){
      if(!el) return false;
      var tag = (el.tagName||'').toUpperCase();
      if(/^(DIV|LI|UL|SECTION|NAV|ASIDE)$/i.test(tag)){
        var btns = el.querySelectorAll('a[href],button,[role="button"],[role="link"]');
        if(btns.length >= 2) return true;
      }
      // muchos hijos + algún link -> típico grid/lista
      if(el.children && el.children.length >= 3 && el.querySelector('a,button,[role="button"],[role="link"]')) return true;
      return false;
    }
    function isGlobalServerBox(el){
      if(!el) return false;
      var cls=(el.className||'').toString().toLowerCase();
      var id =(el.id||'').toString().toLowerCase();
      var t  = textOf(el);
      var hasServerWord = SERVER_WORDS.some(function(w){
        w=w.toLowerCase();
        return cls.includes(w)||id.includes(w)||t.includes(w);
      });
      // cajas como "server-list", "mirrors", etc.
      var looksList = /\b(server|mirror|source|host|links?|options?|selector|providers?)\b/.test(cls+ " " +id);
      return (isWrapper(el) && (hasServerWord || looksList));
    }

    // ======== Normalización: NO subir hasta la caja global ========
    function normalizeClickable(el){
      if (!el) return null;
      var start = el;
      var best = el;
      var baseRect = rect(el);
      for (var i=0;i<5 && el && el !== document.body && el !== document.documentElement; i++){
        var r = rect(el);
        if (isGlobalServerBox(el)) break; // <-- evita capturar el contenedor global
        var areaOk = (r.w*r.h) <= (baseRect.w*baseRect.h*8);
        var looksButton = el.tagName==='A' || el.tagName==='BUTTON' || el.hasAttribute('onclick') ||
                          el.getAttribute('role')==='button' || el.getAttribute('role')==='link' ||
                          getComputedStyle(el).cursor==='pointer' ||
                          hasMainKW(el) || isServerName(el);
        var hasBoth = hasImgOrSvg(el) && hasReadableText(el);
        if (visible(el) && areaOk && (looksButton || hasBoth)) {
          best = el;
          if (hasBoth) break;
        }
        el = el.parentElement;
      }
      return best;
    }

    // ======== Candidatos: SOLO botones/enlaces (no div/li de server) ========
    function candidates(){
      var q = [
        'a[href]','button','[role="button"]','[role="link"]','[onclick]'
      ].join(',');
      var raw = Array.from(document.querySelectorAll(q)).filter(function(el){
        if(!visible(el)) return false;
        // si este propio elemento ya es un contenedor grande, afuera
        if(isGlobalServerBox(el)) return false;
        // si su padre cercano es una caja global y este no es un botón real, afuera
        var p = el.closest('div,li,ul,section,nav,aside');
        if(p && isGlobalServerBox(p)){
          // Permitimos SOLO si es un enlace/botón individual visible (sí)
          return true;
        }
        return true;
      });

      // Normalizamos sin subir al global
      var out=[];
      raw.forEach(function(el){
        var norm = normalizeClickable(el) || el;
        if (visible(norm) && !isGlobalServerBox(norm)) out.push(norm);
      });
      // únicos
      return Array.from(new Set(out));
    }

    // ======== Ponderación ========
    function score(el){
      var r=rect(el), a=r.w*r.h;
      var t=textOf(el);
      var cls=(el.className||'').toString().toLowerCase();
      var id =(el.id||'').toString().toLowerCase();

      if (RX_EXCLUDE.test(t)||RX_EXCLUDE.test(cls)||RX_EXCLUDE.test(id)) return -1e6;

      var s=0;
      if (isServerName(el)) s += 520;
      if (hasMainKW(el))    s += 340;
      if (hasImgOrSvg(el) && hasReadableText(el)) s += 220;

      // tamaños: acepta chicos pero evita micro
      if (r.w>=160 && r.h>=40) s += 130;
      if (a>=9000) s += 90;
      if (r.w>=120 && r.h>=34) s += 110;
      if (a>=5000) s += 80;
      if (r.w>=30 && r.h>=22)  s += 70;  // capta botones chicos
      if (a>=900)              s += 40;

      var cx=(window.innerWidth||document.documentElement.clientWidth)/2;
      var cy=(window.innerHeight||document.documentElement.clientHeight)/2;
      var dx=Math.abs(r.cx-cx), dy=Math.abs(r.cy-cy);
      var dist=Math.sqrt(dx*dx+dy*dy);
      s += Math.max(0, 110 - Math.min(110, dist/3));
      s += Math.max(0, 70 - Math.min(70, r.t/6));

      // No bonificar por padre "server-list" para evitar subir al global
      var p=el.closest('[class*="options"],[class*="selector"]');
      if (p && !isGlobalServerBox(p)) s += 40;

      if (/(inicio|home|explorar|buscar|search|categor[ií]a|atr[aá]s|back|profile|perfil)/i.test(t)) s -= 60;

      return s;
    }

    function promoteSome(){
      var all=candidates().filter(visible);
      if(!all.length) return;

      var ranked=all.map(function(el){return {el:el,s:score(el)};})
                    .filter(function(x){return x.s>=60;});

      ranked.sort(function(a,b){
        if(b.s!==a.s) return b.s-a.s;
        var ar=rect(a.el), br=rect(b.el);
        return (ar.t-br.t)||(ar.l-br.l);
      });

      var picks = ranked.slice(0, 40).map(function(x){return x.el;});
      // extras “server” solo si NO son contenedores globales
      var serverExtras = all.filter(function(el){
        return isServerName(el) && (hasImgOrSvg(el)||hasReadableText(el)) && !isGlobalServerBox(el);
      }).slice(0, 20);

      var finalSet = Array.from(new Set([].concat(picks, serverExtras)));

      finalSet.forEach(function(el){
        if (el.tabIndex < 0) el.setAttribute('tabindex','0');
        el.setAttribute('data-tv-focus','1');
      });

      var first = null;
      for (var i=0;i<ranked.length;i++){
        var e = ranked[i].el;
        if (isServerName(e) && (hasImgOrSvg(e)||hasReadableText(e)) && !isGlobalServerBox(e)){ first = e; break; }
      }
      if (!first && ranked[0]) first = ranked[0].el;
      if(first){ try{ first.focus({preventScroll:true}); first.scrollIntoView({block:'nearest',inline:'nearest'});}catch(_){} }
    }

    // ====== Estilos de foco ======
    (function ensureFocusStyles(){
      if (document.getElementById('__TV_MILD_FOCUS__')) return;
      var s=document.createElement('style'); s.id='__TV_MILD_FOCUS__';
      s.textContent=[
        '[data-tv-focus]:focus, [data-tv-focus]:focus-within{',
        '  outline:2px solid rgba(0,150,255,.9);',
        '  outline-offset:3px;',
        '  border-radius:6px;',
        '}',
        '[data-tv-focus] *:focus{',
        '  outline:none !important;',
        '}'
      ].join('');
      document.head.appendChild(s);
    })();

    function focusables(){
      return Array.from(document.querySelectorAll(
        '[tabindex]:not([tabindex="-1"]),a[href],button,input,select,textarea,summary,[role="button"],[role="link"]'
      )).filter(function(el){ return visible(el) && !isGlobalServerBox(el); });
    }

    function spatial(dir){
      var cur=(document.activeElement && document.activeElement!==document.body)?document.activeElement:null;
      var list=focusables();
      if(!list.length) return false;

      if(!cur){
        list.sort(function(a,b){ return rect(a).t-rect(b).t || rect(a).l-rect(b).l; });
        var f=list[0]; if(f){ try{ f.focus({preventScroll:true}); f.scrollIntoView({block:'nearest',inline:'nearest'});}catch(_){} }
        return !!f;
      }
      var cr=rect(cur), cand=[];
      list.forEach(function(el){
        if(el===cur) return;
        var rr=rect(el), inDir=false, primary=1e9, secondary=1e9;
        if (dir==='left'  && rr.r<=cr.l){ inDir=true; primary=cr.l-rr.r; secondary=Math.abs(rr.cy-cr.cy); }
        if (dir==='right' && rr.l>=cr.r){ inDir=true; primary=rr.l-cr.r; secondary=Math.abs(rr.cy-cr.cy); }
        if (dir==='up'    && rr.b<=cr.t){ inDir=true; primary=cr.t-rr.b; secondary=Math.abs(rr.cx-cr.cx); }
        if (dir==='down'  && rr.t>=cr.b){ inDir=true; primary=rr.t-cr.b; secondary=Math.abs(rr.cx-cr.cx); }
        if (inDir){
          var bonus = (isServerName(el) ? -350 : (hasMainKW(el) ? -220 : 0));
          cand.push({el:el, score: primary*1000 + secondary + bonus});
        }
      });
      if(!cand.length) return false;
      cand.sort(function(a,b){ return a.score-b.score; });
      var next=cand[0].el;
      if(next){ try{ next.focus({preventScroll:true}); next.scrollIntoView({block:'nearest',inline:'nearest'});}catch(_){ } return true; }
      return false;
    }

    if(!window.__TV_DPAD_LITE__){
      window.__TV_DPAD_LITE__ = true;

      document.addEventListener('keydown', function(e){
        var c=e.keyCode||e.which, dir=null;
        if(e.defaultPrevented) return;
        if(e.key==='ArrowLeft'||c===21) dir='left';
        else if(e.key==='ArrowRight'||c===22) dir='right';
        else if(e.key==='ArrowUp'||c===19) dir='up';
        else if(e.key==='ArrowDown'||c===20) dir='down';
        if(dir){
          if(spatial(dir)){ e.preventDefault(); e.stopPropagation(); }
        }
      }, true);

      document.addEventListener('keydown', function(e){
        var c=e.keyCode||e.which;
        if(e.defaultPrevented) return;
        if(e.key==='Enter' || e.key===' ' || c===23 || c===66){
          var el=document.activeElement;
          if(el && visible(el) && !isGlobalServerBox(el)){
            try{
              var clicked=false;
              if(typeof el.click==='function'){ el.click(); clicked=true; }
              if(!clicked){
                var target = el.querySelector('a[href], button, [role="button"], [role="link"]');
                if(target && visible(target)){
                  if(typeof target.click==='function'){ target.click(); clicked=true; }
                  else target.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true}));
                }
              }
              if(!clicked){
                el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true}));
              }
              e.preventDefault(); e.stopPropagation();
            }catch(_){}
          }
        }
      }, true);
    }

    // Re-escaneos por DOM perezoso
    function tick(){ try{ promoteSome(); }catch(_){} }
    tick(); setTimeout(tick,700); setTimeout(tick,1500); setTimeout(tick,2600);
  }catch(e){}
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
    }






    private fun installServiceWorkerAdPolicy() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) return
        ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                val u = request.url?.toString() ?: return null
                return if (isAdUrl(u) && !userInteracted) WebResourceResponse("text/plain","utf-8", null) else null
            }
        })
    }

    // ===== UI util =====
    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() { super.onResume(); enableImmersiveMode(); if (this::webView.isInitialized) webView.onResume() }
    override fun onPause()  { if (this::webView.isInitialized) webView.onPause(); super.onPause() }
    override fun onDestroy(){
        if (this::webView.isInitialized) webView.destroy()
        cancelOverlayAutohide()
        super.onDestroy()
    }

    // ===== Controles con control remoto =====
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // 1) Overlay primero (solo si está habilitado)
            if (zappingEnabled) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!overlayVisible) { showOverlay(true); return true }
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BACK -> {
                        if (overlayVisible) { showOverlay(false); return true }
                    }
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
                            onZap(currentZapIndex, channels[currentZapIndex]); return true
                        }
                    }
                }
            }

            // 2) Si overlay no está activo (o deshabilitado), reenviamos a la página
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { ensurePlayerFocus(); sendTab(true);  return true }
                KeyEvent.KEYCODE_DPAD_LEFT  -> { ensurePlayerFocus(); sendTab(false); return true }
                KeyEvent.KEYCODE_DPAD_DOWN  -> { ensurePlayerFocus(); sendTab(true);  return true }
                KeyEvent.KEYCODE_DPAD_UP    -> { ensurePlayerFocus(); sendTab(false); return true }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    ensurePlayerFocus()
                    sendKeyToWeb(KeyEvent.KEYCODE_ENTER)
                    sendKeyToWeb(KeyEvent.KEYCODE_SPACE)
                    simulateCenterTap()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    ensurePlayerFocus()
                    sendKeyToWeb(KeyEvent.KEYCODE_SPACE)
                    sendKeyToWeb(KeyEvent.KEYCODE_K)
                    sendKeyToWeb(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    return true
                }

                KeyEvent.KEYCODE_F -> { ensurePlayerFocus(); sendKeyToWeb(KeyEvent.KEYCODE_F); return true }
                KeyEvent.KEYCODE_VOLUME_MUTE -> { ensurePlayerFocus(); sendKeyToWeb(KeyEvent.KEYCODE_M); return true }

                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { ensurePlayerFocus(); sendKeyToWeb(KeyEvent.KEYCODE_DPAD_RIGHT); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND       -> { ensurePlayerFocus(); sendKeyToWeb(KeyEvent.KEYCODE_DPAD_LEFT);  return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ===== Helpers input =====
    private fun sendKeyToWeb(code: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta)
        val up   = KeyEvent(now, now, KeyEvent.ACTION_UP,   code, 0, meta)
        webView.dispatchKeyEvent(down)
        webView.dispatchKeyEvent(up)
    }

    private fun sendTab(forward: Boolean) {
        val meta = if (forward) 0 else KeyEvent.META_SHIFT_ON
        sendKeyToWeb(KeyEvent.KEYCODE_TAB, meta)
    }

    private fun simulateCenterTap() {
        val x = webView.width / 2f
        val y = webView.height / 2f
        val down = SystemClock.uptimeMillis()
        val evDown = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
        val evUp   = MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, x, y, 0)
        webView.dispatchTouchEvent(evDown)
        webView.dispatchTouchEvent(evUp)
        evDown.recycle(); evUp.recycle()
    }

    /** Foco al video/iframe para que reciba teclas */
    private fun ensurePlayerFocus() {
        val js = """
            (function(){
              try{
                var sel = ['video','.vjs-control-bar','.plyr__controls','.jw-controls','.jw-display'];
                for (var i=0;i<sel.length;i++){
                  var n=document.querySelector(sel[i]);
                  if(n){
                    var r=n.getBoundingClientRect(), st=getComputedStyle(n);
                    if(r.width>2 && r.height>2 && st.display!=='none' && st.visibility!=='hidden'){
                      try{ n.setAttribute('tabindex','0'); n.focus({preventScroll:true}); }catch(_){}
                      return true;
                    }
                  }
                }
                var ifr = Array.from(document.querySelectorAll('iframe')).find(function(f){
                  var r=f.getBoundingClientRect(), st=getComputedStyle(f);
                  return r.width>2 && r.height>2 && st.display!=='none' && st.visibility!=='hidden';
                });
                if (ifr){
                  try{ ifr.setAttribute('tabindex','0'); ifr.focus({preventScroll:true}); }catch(_){}
                  return true;
                }
              }catch(e){}
              return false;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // === Overlay ===
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

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()

    // === Fallback de canales (si no vienen por Intent) ===
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
            // ... (recorta o completa tus canales como ya los tenías)
            ZappingChannel(
                51, "Ciudad Magazine",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/d/d5/Ciudad_magazine_logo.png/640px-Ciudad_magazine_logo.png",
                "https://live-01-07-ciudad.vodgc.net/live-01-07-ciudad.vodgc.net/tracks-v1a1/mono.m3u8",
                StreamType.M3U8
            )
        )
    }

    // === Zapping desde Web ===
    private fun onZap(position: Int, item: ZappingChannel) {
        showOverlay(false)
        currentZapIndex = position
        zappingAdapter.select(position)

        when (item.type) {
            StreamType.M3U8, StreamType.MPD, StreamType.DIRECT_URL -> {
                startActivity(
                    Intent(this, VideoPlayerActivity::class.java).apply {
                        putExtra("title", item.name)
                        putParcelableArrayListExtra(
                            "SOURCES",
                            arrayListOf(StreamSource(item.url, item.type, emptyMap()))
                        )
                        putParcelableArrayListExtra("CHANNELS", ArrayList(channels))
                        putExtra("CHANNEL_INDEX", position)
                        putExtra("is_live", true)
                    }
                )
                finish()
            }
            StreamType.WEB, StreamType.WE2 -> {
                val target = if (item.type == StreamType.WEB) WebPlayerActivity::class.java
                else WebPlayerActivity2::class.java

                startActivity(
                    Intent(this, target).apply {
                        putExtra("title", item.name)
                        putExtra("VIDEO_URL", item.url)
                        putParcelableArrayListExtra("CHANNELS", ArrayList(channels))
                        putExtra("CHANNEL_INDEX", position)
                        putExtra("ZAPPING_ENABLED", zappingEnabled)
                        putExtra("CATEGORY_KEY", if (zappingEnabled) "" else "Peliculas")
                    }
                )
                finish()
            }
        }
    }
}
