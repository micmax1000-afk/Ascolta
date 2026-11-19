package com.micmax.ascolta

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

const val YT_MUSIC_HOME = "https://music.youtube.com/"

/** Quello che la pagina di YouTube Music sta suonando (lo riferisce lo script ogni secondo). */
data class WebState(val playing: Boolean = false, val title: String = "", val artist: String = "", val art: String = "", val posMs: Long = 0, val durMs: Long = 0) {
    val has get() = title.isNotBlank()
}

object MusicWeb {
    val ORIGINS = setOf("https://music.youtube.com", "https://www.youtube.com", "https://m.youtube.com", "https://youtube.com")
    const val BRIDGE = "AsBridge"

    /** Dentro la pagina restano YouTube Music e l'accesso a Google; qualsiasi altro indirizzo si apre nel browser. */
    fun isMusicPageHost(url: String?): Boolean {
        val u = runCatching { java.net.URI(url ?: return false) }.getOrNull() ?: return false
        val host = u.host?.lowercase() ?: return false
        val path = u.path.orEmpty()
        return host == "music.youtube.com" || host == "accounts.google.com" || host == "accounts.youtube.com" ||
            host == "consent.youtube.com" || host == "consent.google.com" || host == "www.youtube.com" && path.startsWith("/signin")
    }

    /** Da «{"t":"state",…}» a [WebState]; null per qualsiasi altro messaggio. */
    fun parseState(message: String?): WebState? {
        val m = runCatching { JSONObject(message ?: return null) }.getOrNull() ?: return null
        if (m.optString("t") != "state") return null
        return WebState(m.optBoolean("playing"), m.optString("title"), m.optString("artist"), m.optString("art"), m.optLong("pos"), m.optLong("dur"))
    }

    /** I comandi che il lettore manda alla pagina: play, pause, toggle, next, prev, seek (millisecondi). */
    fun command(cmd: String, value: Long = 0) = "window.__asCmd && window.__asCmd('$cmd', $value);"

    /** Lo script che gira dentro YouTube Music: audio in background, salto delle pubblicità, stato per il lettore. */
    val SCRIPT = """
(function () {
  if (window.__asYT) return; window.__asYT = true;
  var bridge = window.$BRIDGE;
  function post(o) { try { if (bridge) bridge.postMessage(JSON.stringify(o)); } catch (e) {} }

  // la pagina non si vede mai «nascosta»: così l'audio non si ferma con lo schermo spento
  try {
    Object.defineProperty(document, 'hidden', { get: function () { return false; } });
    Object.defineProperty(document, 'visibilityState', { get: function () { return 'visible'; } });
    Object.defineProperty(document, 'webkitHidden', { get: function () { return false; } });
  } catch (e) {}
  ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'blur'].forEach(function (ev) {
    window.addEventListener(ev, function (e) { e.stopImmediatePropagation(); }, true);
    document.addEventListener(ev, function (e) { e.stopImmediatePropagation(); }, true);
  });

  var css = '.ytp-ad-overlay-container,.ytp-ad-image-overlay,ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,' +
    'ytm-promoted-video-renderer,ad-slot-renderer,ytd-ad-slot-renderer,#player-ads,#masthead-ad{display:none!important}';
  function addCss() {
    if (document.getElementById('as-css')) return;
    var s = document.createElement('style'); s.id = 'as-css'; s.textContent = css;
    (document.head || document.documentElement).appendChild(s);
  }
  addCss(); document.addEventListener('DOMContentLoaded', addCss);

  function player() { return document.getElementById('movie_player') || document.querySelector('.html5-video-player'); }
  function video() { var p = player(); return (p && p.querySelector('video')) || document.querySelector('video'); }
  function click(sel) { var b = document.querySelector(sel); if (b) { b.click(); return true; } return false; }

  // pubblicità: muto, avanti veloce, «Salta»
  var inAd = false, savedMuted = false, savedRate = 1;
  function adCheck() {
    var p = player(), v = video();
    var ad = !!(p && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting')));
    if (ad && v) {
      if (!inAd) { inAd = true; savedMuted = v.muted; savedRate = v.playbackRate || 1; }
      v.muted = true;
      try { v.playbackRate = 16; } catch (e) {}
      if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.3) { try { v.currentTime = v.duration - 0.1; } catch (e) {} }
      click('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button,button[class*="ad-skip"],button[class*="skip-ad"],ytm-skip-ad-button button');
    } else if (inAd) {
      inAd = false;
      if (v) { v.muted = savedMuted; try { v.playbackRate = savedRate; } catch (e) {} }
    }
    return ad;
  }
  // appena il lettore segna una pubblicità (o parte un nuovo video) si salta subito, senza aspettare il controllo periodico
  var watched = null;
  function watchPlayer() {
    var p = player(); if (!p || p === watched || !window.MutationObserver) return;
    watched = p;
    try { new MutationObserver(function () { try { adCheck(); } catch (e) {} }).observe(p, { attributes: true, attributeFilter: ['class'] }); } catch (e) {}
    var v = video();
    if (v) ['loadedmetadata', 'playing', 'durationchange'].forEach(function (ev) { v.addEventListener(ev, function () { try { adCheck(); } catch (e) {} }); });
  }

  // «Sei ancora lì?» e inviti a Premium: si chiudono da soli
  var lastNag = 0;
  function nagCheck() {
    var now = Date.now(); if (now - lastNag < 1500) return;
    var still = document.querySelector('ytmusic-you-there-renderer button, ytmusic-you-there-renderer [role="button"],' +
      'yt-confirm-dialog-renderer #confirm-button button, yt-confirm-dialog-renderer #confirm-button, ytm-confirm-dialog-renderer .confirm-button button');
    if (still && still.offsetParent !== null) { lastNag = now; still.click(); var v = video(); if (v && v.paused) { try { v.play(); } catch (e) {} } return; }
    var promo = document.querySelector('ytmusic-mealbar-promo-renderer #dismiss-button button, ytm-mealbar-promo-renderer .mealbar-promo-dismiss-button button,' +
      'ytmusic-upsell-dialog-renderer #dismiss-button button, ytm-upsell-dialog-renderer .upsell-dialog-dismiss-button button');
    if (promo && promo.offsetParent !== null) { lastNag = now; promo.click(); }
  }

  function txt(sel, root) { try { var e = (root || document).querySelector(sel); return e ? (e.textContent || '').replace(/\s+/g, ' ').trim() : ''; } catch (e) { return ''; } }
  function cleanTitle(s) { return (s || '').replace(/\s*[|\-–]\s*YouTube Music\s*$/i, '').replace(/^YouTube Music$/i, '').trim(); }
  function videoId() { var m = location.search.match(/[?&]v=([\w-]{11})/); return m ? m[1] : ''; }
  function meta() {
    var t = '', a = '', art = '';
    try {
      var m = navigator.mediaSession && navigator.mediaSession.metadata;
      if (m) { t = m.title || ''; a = m.artist || ''; var im = m.artwork || []; if (im.length) art = im[im.length - 1].src || ''; }
    } catch (e) {}
    var bar = document.querySelector('ytmusic-player-bar');
    if (!t) t = txt('.title', bar) || txt('.song-title') || txt('.title.ytmusic-player-bar');
    if (!a) { var by = txt('.byline', bar) || txt('.subtitle .byline', bar) || txt('.byline.ytmusic-player-bar') || txt('.subtitle'); if (by) a = by.split(/\s[•·]\s/)[0].trim(); }
    if (!art) { try { var img = document.querySelector('ytmusic-player-bar img.image, ytmusic-player-bar #thumbnail img, ytmusic-player-bar img'); if (img && img.src && img.src.indexOf('http') === 0) art = img.src; } catch (e) {} }
    if (!t) t = cleanTitle(document.title);
    var id = videoId();
    if (!art && id) art = 'https://i.ytimg.com/vi/' + id + '/hqdefault.jpg';
    return { t: cleanTitle(t), a: a, art: art };
  }
  var last = '';
  function report() {
    var v = video(); if (!v) return;
    var m = meta();
    var playing = !v.paused && !v.ended && v.readyState > 2;
    var o = { t: 'state', playing: playing, title: m.t, artist: m.a, art: m.art, pos: Math.round((v.currentTime || 0) * 1000), dur: isFinite(v.duration) ? Math.round(v.duration * 1000) : 0 };
    var s = JSON.stringify(o);
    if (s !== last) { last = s; post(o); }
  }

  window.__asCmd = function (c, val) {
    var v = video();
    if (c === 'play') { if (v) try { v.play(); } catch (e) {} }
    else if (c === 'pause') { if (v) v.pause(); }
    else if (c === 'toggle') { if (v) { if (v.paused) { try { v.play(); } catch (e) {} } else v.pause(); } }
    else if (c === 'seek') { if (v) v.currentTime = val / 1000; }
    else if (c === 'next') { click('ytmusic-player-bar .next-button, .next-button, button[aria-label*="Successiv"], button[aria-label*="Next"]'); }
    else if (c === 'prev') { click('ytmusic-player-bar .previous-button, .previous-button, button[aria-label*="Precedent"], button[aria-label*="Previous"]'); }
  };

  // durante la pubblicità non si riferisce lo stato: nel lettore restano titolo e copertina della canzone
  setInterval(function () { try { watchPlayer(); var ad = adCheck(); nagCheck(); if (!ad) report(); } catch (e) {} }, 500);
})();
""".trimIndent()
}

/** La pagina di YouTube Music: una sola, tenuta viva (così la musica non si ferma quando cambi scheda). */
@SuppressLint("SetJavaScriptEnabled")
fun createMusicWebView(c: Context, onState: (WebState) -> Unit, openExternal: (String) -> Unit, onPage: () -> Unit): WebView {
    val w = WebView(c)
    w.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        userAgentString = android.webkit.WebSettings.getDefaultUserAgent(c)
        loadWithOverviewMode = true; useWideViewPort = true
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
    w.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url.toString()
            if (!u.startsWith("http") || MusicWeb.isMusicPageHost(u)) return false
            if (!request.isForMainFrame) return false
            openExternal(u); return true
        }
        override fun onPageFinished(view: WebView, url: String?) {
            // senza lo script all'avvio della pagina (vecchi Android) lo si inietta qui
            if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(MusicWeb.SCRIPT, null)
            onPage()
        }
    }
    w.webChromeClient = WebChromeClient()
    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
        runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, MusicWeb.SCRIPT, MusicWeb.ORIGINS) }
    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER)) runCatching {
        androidx.webkit.WebViewCompat.addWebMessageListener(w, MusicWeb.BRIDGE, MusicWeb.ORIGINS) { _, message, _, isMainFrame, _ ->
            if (!isMainFrame) return@addWebMessageListener
            MusicWeb.parseState(message.data)?.let(onState)
        }
    }
    w.loadUrl(YT_MUSIC_HOME)
    return w
}
