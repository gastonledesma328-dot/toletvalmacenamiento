package com.toletv.app.ui.player

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
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
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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

class WebPlayerActivity2 : AppCompatActivity() {

    private lateinit var webView: WebView

    // Overlay zapping
    private lateinit var zappingOverlay: View
    private lateinit var rvZapping: RecyclerView
    private lateinit var zappingAdapter: ZappingAdapter
    private var overlayVisible = false
    private val overlayAutoHideMs = 4000L
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val overlayHideRunnable = Runnable { showOverlay(false) }

    private var channels: MutableList<ZappingChannel> = mutableListOf()
    private var currentZapIndex = 0

    private var webUrl: String? = null
    private var contentTitle: String? = null

    // Mirrors y puntero
    private var sources: ArrayList<StreamSource> = arrayListOf()
    private var currentIndex = 0

    // Señales
    @Volatile private var userInteracted = false
    @Volatile private var fallingBack   = false

    // Política WE2 / flags
    private var isWE2: Boolean = false
    private var softenOverlayKiller = true

    // Detección
    private var sniffWhitelist: Array<String> = arrayOf(".mpd", ".ism/manifest", ".mp4")
    private var sniffBlacklist: Array<String> = arrayOf("blob:", "data:", ".m3u8")

    companion object {
        private val denyHandoffHosts: MutableSet<String> = mutableSetOf()
    }

    // ---------- Fullscreen WebChrome ----------
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var originalSystemUi: Int = 0
    private val decor: ViewGroup by lazy { window.decorView as ViewGroup }

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
        return ul.contains("we2") || ul.endsWith(".we2")
    }

    private fun isDirectStreamUrl(u: String?): Boolean {
        if (u.isNullOrBlank()) return false
        if (isWe2Url(u)) return false
        val ul = u.lowercase()
        return sniffWhitelist.any { ul.contains(it) } && sniffBlacklist.none { ul.contains(it) }
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

        contentTitle = intent.getStringExtra("title")

        // URL principal (admite varias keys)
        webUrl = intent.getStringExtra("web_url")
            ?: intent.getStringExtra("url")
                    ?: intent.getStringExtra("EXTRA_URL")
                    ?: intent.getStringExtra("streamUrl")
                    ?: intent.getStringExtra("VIDEO_URL")

        val streamTypeExtra = (intent.getStringExtra("STREAM_TYPE") ?: "").uppercase()
        isWE2 = streamTypeExtra == "WE2" || isWe2Url(webUrl)

        // Mirrors (opcional)
        intent.getParcelableArrayListExtra<StreamSource>("SOURCES")?.let { sources = it }
        if (sources.isEmpty() && !webUrl.isNullOrBlank()) {
            val t = if (isWE2) StreamType.WE2 else StreamType.WEB
            sources.add(StreamSource(webUrl!!, t))
        }

        intent.getStringArrayExtra("SNIFF_WHITELIST")?.let { sniffWhitelist = it }
        intent.getStringArrayExtra("SNIFF_BLACKLIST")?.let { sniffBlacklist = it }

        val allHosts = buildList {
            hostOf(webUrl)?.let { add(it) }
            sources.forEach { hostOf(it.url)?.let { h -> add(h) } }
        }
        if (isWE2) denyHandoffHosts.addAll(allHosts)

        // WebView setup
        webView = findViewById(R.id.webView)
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
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            setNeedInitialFocus(false)
            @Suppress("DEPRECATION")
            setOffscreenPreRaster(true)
            userAgentString = buildUA(userAgentString, desktop = isWE2)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = false
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
        webView.setOnLongClickListener { true }
        webView.isHapticFeedbackEnabled = false

        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }
        WebView.setWebContentsDebuggingEnabled(true)

        webView.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
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
        }, "AndroidBridge")

        // WebChrome: target=_blank en el mismo WebView + fullscreen real
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                return try {
                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                    transport?.webView = webView
                    resultMsg?.sendToTarget()
                    true
                } catch (_: Throwable) {
                    injectForceSelfTargets()
                    false
                }
            }
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) { callback?.onCustomViewHidden(); return }
                customView = view
                customCallback = callback
                originalSystemUi = window.decorView.systemUiVisibility
                decor.addView(
                    view,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                webView.visibility = View.GONE
                enableImmersiveMode()
            }
            override fun onHideCustomView() {
                customView?.let { decor.removeView(it) }
                customView = null
                customCallback?.onCustomViewHidden()
                webView.visibility = View.VISIBLE
                window.decorView.systemUiVisibility = originalSystemUi
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                enableImmersiveMode()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                if (isAdUrl(u)) return true
                val scheme = request?.url?.scheme
                val schemeOk = scheme == "http" || scheme == "https"
                return !schemeOk
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                if (isAdUrl(u) && !userInteracted) return WebResourceResponse("text/plain", "utf-8", null)
                return super.shouldInterceptRequest(view, request)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectMinimalCss()
                if (softenOverlayKiller) injectOverlayKillerSoft()
                injectPlayDetection()
                injectAutoPlaySoft()
                injectForceSelfTargets()
                injectFullscreenBridge()
                ensurePlayerFocus()
                injectAutoUnmute()
                injectVideoHealthWatchdog()
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) fallToNext("Error de carga: ${error?.description}")
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) {
                    val code = errorResponse?.statusCode ?: 200
                    if (code >= 400) fallToNext("HTTP $code")
                }
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                Toast.makeText(this@WebPlayerActivity2, "Problema SSL. Probá nuevamente.", Toast.LENGTH_SHORT).show()
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                try { view.destroy() } catch (_: Exception) {}
                fallToNext("Crash render process")
                return true
            }
        }

        installServiceWorkerAdPolicy()

        // ========= ZAPPING OVERLAY =========
        zappingOverlay = findViewById(R.id.zappingOverlay)
        rvZapping = findViewById(R.id.rvZapping)
        rvZapping.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        rvZapping.addItemDecoration(SpaceItemDecoration(dp(8)))

        // 1) Cargar lista (Intent -> fallback)
        channels = (intent.getParcelableArrayListExtra<ZappingChannel>("CHANNELS")?.toMutableList())
            ?: provideZappingChannels().toMutableList()

        // 2) Selección inicial basada en la URL actual
        currentZapIndex = channels.indexOfFirst { it.url == sources.getOrNull(currentIndex)?.url }
        if (currentZapIndex < 0) currentZapIndex = 0

        // 3) Adapter
        zappingAdapter = ZappingAdapter(channels) { pos, item -> zapTo(pos, item) }
        rvZapping.adapter = zappingAdapter
        zappingAdapter.select(currentZapIndex)
        rvZapping.scrollToPosition(currentZapIndex)

        // Arranque
        currentIndex = 0
        playCurrent()

        onBackPressedDispatcher.addCallback(this) {
            when {
                overlayVisible -> showOverlay(false)
                customView != null ->
                    webView.post { (webView.webChromeClient as? WebChromeClient)?.onHideCustomView() }
                this@WebPlayerActivity2::webView.isInitialized && webView.canGoBack() -> webView.goBack()
                else -> finish()
            }
        }
    }

    // ================= Lógica de mirrors =================
    private fun playCurrent() {
        if (currentIndex !in 0 until sources.size) {
            Toast.makeText(this, "No hay más mirrors", Toast.LENGTH_SHORT).show()
            return
        }
        val cur = sources[currentIndex]
        webUrl = cur.url
        try {
            val headers = (cur.headers ?: emptyMap())
            if (headers.isEmpty()) webView.loadUrl(cur.url) else webView.loadUrl(cur.url, headers)
        } catch (_: Exception) { fallToNext("Excepción al cargar URL") }
    }

    private fun fallToNext(reason: String) {
        if (fallingBack) return
        fallingBack = true
        currentIndex++
        if (currentIndex < sources.size) {
            Toast.makeText(this, "Probando mirror ${currentIndex + 1}/${sources.size}", Toast.LENGTH_SHORT).show()
            webView.postDelayed({ fallingBack = false; playCurrent() }, 350)
        } else {
            fallingBack = false
            Toast.makeText(this, "Error: $reason. Sin más mirrors. Atrás para salir.", Toast.LENGTH_LONG).show()
        }
    }

    // ==================== JS helpers ====================
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

    private fun injectOverlayKillerSoft() {
        val js = """
          (function(){
            try{
              var bad = [
                '[class*="overlay"]','[id*="overlay"]','[class*="modal"]','[id*="modal"]',
                '.ad','.ads','.advert','.jw-rightclick','.video-ads','.ytp-paid-content-overlay',
                '[class*="popup"]','[id*="popup"]'
              ];
              function looksLikePlayer(n){
                try{
                  if (n.querySelector && n.querySelector('video')) return true;
                  var t = (n.textContent||'').toLowerCase();
                  if (t.includes('reproductor')||t.includes('player')) return true;
                }catch(e){}
                return false;
              }
              function hideAll(){
                bad.forEach(function(sel){
                  document.querySelectorAll(sel).forEach(function(n){
                    try{
                      var r=n.getBoundingClientRect(), st=getComputedStyle(n);
                      if(r.width>160 && r.height>120 && st.position!=='static' && !looksLikePlayer(n)){
                        n.style.setProperty('display','none','important');
                        n.style.setProperty('visibility','hidden','important');
                        n.style.setProperty('pointer-events','none','important');
                      }
                    }catch(e){}
                  });
                });
              }
              hideAll(); setTimeout(hideAll,600); setInterval(hideAll,2500);
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
              var stuckCount = 0, lastT = 0;

              function softRecover(v){
                try{
                  if (typeof v.play === 'function') v.play().catch(()=>{});
                  if (v.readyState < 2) { try{ v.load(); }catch(e){} }
                }catch(e){}
                try{ if (typeof jwplayer==='function'){ var jw=jwplayer(); if(jw && jw.play){ jw.pause && jw.pause(true); setTimeout(()=>{jw.play();}, 300); } } }catch(e){}
                try{ if (typeof videojs==='function'){ var p = videojs.getPlayers && Object.values(videojs.getPlayers())[0]; if(p&&p.play){ p.pause && p.pause(); setTimeout(()=>{p.play();}, 300);} } }catch(e){}
                try{ if (window.hls && hls.recoverMediaError) hls.recoverMediaError(); }catch(e){}
              }

              function loop(){
                var v=document.querySelector('video');
                if(!v){ setTimeout(loop,1200); return; }
                var ct=v.currentTime||0;
                var paused=v.paused, ended=v.ended, rs=v.readyState||0;

                if (!paused && !ended) {
                  if (rs < 2 || Math.abs(ct - lastT) < 0.01) stuckCount++;
                  else stuckCount = 0;
                } else stuckCount = 0;

                lastT = ct;

                if (stuckCount >= 5) { softRecover(v); stuckCount = 0; }
                setTimeout(loop, 2000);
              }
              setTimeout(loop, 1800);
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

    private fun injectForceSelfTargets() {
        val js = """
          (function(){
            try{
              document.querySelectorAll('a[target="_blank"]').forEach(function(a){ a.setAttribute('target','_self'); });
              try{ window.open = function(url){ try{ location.href = url; }catch(e){} return null; }; }catch(e){}
            }catch(e){}
          })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectFullscreenBridge() {
        val js = """
          (function(){
            try{
              if (window.__FS_BRIDGE__) return;
              window.__FS_BRIDGE__ = true;
              function emit(){
                try {
                  var fs = !!(document.fullscreenElement||document.webkitFullscreenElement);
                  AndroidBridge.onUserPlay();
                } catch(e){}
              }
              document.addEventListener('fullscreenchange', emit);
              document.addEventListener('webkitfullscreenchange', emit);
            }catch(e){}
          })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ================== Service Worker Ads ==================
    private fun installServiceWorkerAdPolicy() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) return
        ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                val u = request.url?.toString() ?: return null
                return if (isAdUrl(u) && !userInteracted) WebResourceResponse("text/plain","utf-8", null) else null
            }
        })
    }

    // ================== UI / ciclo ==================
    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        if (this::webView.isInitialized) {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    override fun onPause()  {
        if (this::webView.isInitialized) {
            webView.onPause()
            webView.pauseTimers()
        }
        cancelOverlayAutohide()
        super.onPause()
    }

    override fun onDestroy() {
        try {
            if (isWE2) {
                val allHosts = buildList {
                    hostOf(webUrl)?.let { add(it) }
                    sources.forEach { hostOf(it.url)?.let { h -> add(h) } }
                }
                denyHandoffHosts.removeAll(allHosts)
            }
            if (customView != null) {
                (webView.webChromeClient as? WebChromeClient)?.onHideCustomView()
            }
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.webChromeClient = WebChromeClient()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.clearHistory()
            webView.clearCache(true)
            webView.removeAllViews()
            webView.destroy()
            CookieManager.getInstance().flush()
        } catch (_: Exception) { }
        super.onDestroy()
    }

    // ================== Controles (remoto) ==================
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
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

                // Playback keys al WebView (cuando overlay no visible)
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (!overlayVisible) {
                        ensurePlayerFocus()
                        sendKeyToWeb(KeyEvent.KEYCODE_SPACE)
                        sendKeyToWeb(KeyEvent.KEYCODE_K)
                        sendKeyToWeb(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                        return true
                    }
                }

                KeyEvent.KEYCODE_F -> { if (!overlayVisible) { toggleDomFullscreen(); return true } }
                KeyEvent.KEYCODE_VOLUME_MUTE -> { if (!overlayVisible) { ensurePlayerFocus(); sendKeyToWeb(KeyEvent.KEYCODE_M); return true } }

                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (!overlayVisible) { ensurePlayerFocus(); sendKeyToWeb(KeyEvent.KEYCODE_DPAD_RIGHT); return true } }
                KeyEvent.KEYCODE_MEDIA_REWIND       -> { if (!overlayVisible) { ensurePlayerFocus(); sendKeyToWeb(KeyEvent.KEYCODE_DPAD_LEFT);  return true } }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ================== Helpers de input ==================
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
        evDown.recycle()
        evUp.recycle()
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

    private fun toggleDomFullscreen() {
        val js = """
          (function(){
            try{
              var el = document.querySelector('video') || document.documentElement;
              if (document.fullscreenElement || document.webkitFullscreenElement) {
                (document.exitFullscreen || document.webkitExitFullscreen || function(){})().call(document);
                return "exit";
              } else {
                (el.requestFullscreen || el.webkitRequestFullscreen || el.webkitEnterFullscreen || function(){})().call(el);
                return "enter";
              }
            }catch(e){ return "error"; }
          })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ================== Overlay helpers ==================
    private fun showOverlay(show: Boolean) {
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
                    .withEndAction {
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

    // ======= Zapping actions =======
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
            StreamType.WEB, StreamType.WE2 -> {
                // Reproducir en este mismo Web (cierra el player actual y carga el nuevo)
                sources.clear()
                sources.add(StreamSource(item.url, item.type, emptyMap()))
                currentIndex = 0
                contentTitle = item.name
                playCurrent()
            }
            StreamType.M3U8, StreamType.MPD, StreamType.DIRECT_URL -> {
                // Ir al player nativo y cerrar este Web
                startActivity(
                    Intent(this, VideoPlayerActivity::class.java).apply {
                        putExtra("VIDEO_URL", item.url)
                        putExtra("title", item.name)
                        putParcelableArrayListExtra("CHANNELS", ArrayList(channels))
                        putExtra("CHANNEL_INDEX", position)
                        putExtra("is_live", true)
                    }
                )
                finish()
            }
        }
    }

    // ================== Aux ==================
    private fun buildUA(base: String?, desktop: Boolean): String {
        val tv = "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36 WebView"
        val desk = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
        val uaBase = base ?: tv
        return if (desktop) desk else uaBase
    }
}
