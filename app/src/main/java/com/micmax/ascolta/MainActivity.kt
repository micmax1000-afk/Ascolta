package com.micmax.ascolta

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/** Una schermata: la vista, cosa fare quando si chiude e come ridisegnarla quando ci si torna. */
class Scr(val view: View) {
    var onClose: (() -> Unit)? = null
    var refresh: (() -> Unit)? = null
    var back: (() -> Boolean)? = null
    var isPlayer = false
}

class MainActivity : AppCompatActivity() {
    val io = Executors.newSingleThreadExecutor()
    val repo by lazy { AudioRepository(this) }
    private val main = Handler(Looper.getMainLooper())

    private lateinit var host: FrameLayout
    private lateinit var mini: LinearLayout
    private lateinit var tabBar: LinearLayout
    val stack = ArrayList<Scr>()
    private val tabViews = arrayOfNulls<View>(4)
    var tab = 0
    private val tabLabels = listOf("Per te", "Musica", "Podcast", "Radio")
    private val tabIcons = listOf(R.drawable.ic_tab_home, R.drawable.ic_tab_music, R.drawable.ic_tab_mic, R.drawable.ic_tab_radio)

    fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ======================= riproduzione =======================

    var ctl: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()
    /** Chi sta suonando: «exo» (podcast e radio), «web» (YouTube Music) o nessuno. */
    var active: String? = null
    var webState = WebState()
    var musicWeb: WebView? = null
    var sleepAt = 0L
    private val sleepRunner = Runnable { sleepAt = 0L; pauseAll(); toast("😴 Timer del sonno: fermo la riproduzione"); updateMini() }
    var onPlayerTick: (() -> Unit)? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) { active = "exo"; pauseWeb() }; updateMini(); onPlayerTick?.invoke() }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { updateMini(); onPlayerTick?.invoke() }
        override fun onPlaybackStateChanged(playbackState: Int) { updateMini(); onPlayerTick?.invoke() }
    }

    private fun connect() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            ctl = c; c.addListener(playerListener)
            if (active == null && c.mediaItemCount > 0) active = "exo"
            pending.toList().forEach { it(c) }; pending.clear()
            updateMini()
        }, ContextCompat.getMainExecutor(this))
    }

    fun withCtl(f: (MediaController) -> Unit) { val c = ctl; if (c != null) f(c) else pending += f }

    val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) fileCb?.invoke(u) }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            runCatching { notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }

    fun playEpisode(e: Episode) {
        ensureNotificationPermission()
        withCtl { c ->
            val uri = if (repo.isDownloaded(e)) Uri.fromFile(repo.localFile(e)).toString() else e.audioUrl
            c.setMediaItem(episodeItem(this, e, uri), PodcastPrefs.startAt(this, e))
            c.setPlaybackSpeed(PodcastPrefs.speed(this, e.feedUrl))
            c.prepare(); c.play()
            AudioStore.addRecent(this, e); AudioStore.setLast(this, "podcast")
            active = "exo"; pauseWeb(); updateMini()
        }
    }

    fun playStation(s: RadioStation) {
        ensureNotificationPermission()
        withCtl { c ->
            val md = MediaMetadata.Builder().setTitle(s.name).setArtist(listOf("Radio", s.state).filter { it.isNotBlank() }.joinToString(" · ")).setIsPlayable(true)
                .apply { s.favicon?.let { setArtworkUri(Uri.parse(it)) } }.build()
            c.setMediaItem(MediaItem.Builder().setMediaId("radio:" + s.id).setUri(s.url).setMediaMetadata(md).build())
            c.setPlaybackSpeed(1f); c.prepare(); c.play()
            getSharedPreferences("ascolta", MODE_PRIVATE).edit().putString("last_station", com.google.gson.Gson().toJson(s)).apply()
            AudioStore.setLast(this, "radio")
            active = "exo"; pauseWeb(); updateMini()
        }
    }

    /** Fa partire la prossima puntata in coda; false se la coda è vuota. */
    fun playNextInQueue(): Boolean { val e = AudioQueue.pop(this) ?: return false; playEpisode(e); return true }

    fun togglePlay() {
        if (active == "web") { musicWeb?.evaluateJavascript(MusicWeb.command("toggle"), null); return }
        withCtl { c -> if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() } }
    }
    fun pauseWeb() { musicWeb?.evaluateJavascript(MusicWeb.command("pause"), null) }
    fun pauseAll() { pauseWeb(); ctl?.pause() }
    fun isPlayingNow(): Boolean = if (active == "web") webState.playing else ctl?.isPlaying == true

    fun setSleep(minutes: Int) {
        main.removeCallbacks(sleepRunner)
        if (minutes <= 0) { sleepAt = 0L; toast("Timer del sonno spento"); updateMini(); return }
        sleepAt = System.currentTimeMillis() + minutes * 60_000L
        main.postDelayed(sleepRunner, minutes * 60_000L)
        toast("😴 Mi fermo tra $minutes minuti"); updateMini()
    }

    // ======================= schermate =======================

    fun push(s: Scr) {
        stack += s
        host.removeAllViews(); host.addView(s.view, FrameLayout.LayoutParams(-1, -1)); chrome()
    }

    fun pop() {
        if (stack.size <= 1) return
        stack.removeLast().onClose?.invoke()
        host.removeAllViews(); host.addView(stack.last().view, FrameLayout.LayoutParams(-1, -1)); chrome()
        stack.last().refresh?.invoke()
    }

    fun refreshTop() { stack.lastOrNull()?.refresh?.invoke() }

    /** La barra delle schede e il mini lettore si vedono solo dove servono. */
    private fun chrome() {
        tabBar.visibility = if (stack.size == 1) View.VISIBLE else View.GONE
        updateMini()
    }

    fun header(title: String, action: String?, onAction: (() -> Unit)?, back: Boolean = true): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(12), dp(8))
        if (back) addView(TextView(this@MainActivity).apply { text = "‹"; textSize = 32f; gravity = Gravity.CENTER; setTextColor(col(R.color.text)); contentDescription = "Indietro"; setOnClickListener { pop() } }, LinearLayout.LayoutParams(dp(48), dp(44)))
        addView(TextView(this@MainActivity).apply {
            text = title; textSize = 19f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(col(R.color.text)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            if (!back) setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) addView(TextView(this@MainActivity).apply {
            text = action; textSize = 15f; setTextColor(android.graphics.Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundBg(col(R.color.accent), 18); setPadding(dp(16), dp(8), dp(16), dp(8)); setOnClickListener { onAction?.invoke() }
        })
    }

    /** Una pagina con titolo e freccia indietro. */
    fun openScreen(title: String, action: String? = null, onAction: (() -> Unit)? = null, build: (body: LinearLayout, scr: Scr) -> Unit): Scr {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        val scr = Scr(root)
        root.addView(header(title, action, onAction))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        build(body, scr)
        push(scr)
        return scr
    }

    // ======================= mini lettore e schede =======================

    private lateinit var miniArt: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageView

    /** Titolo, sottotitolo e copertina di quello che suona. */
    fun nowInfo(): Triple<String, String, String?>? = when (active) {
        "web" -> if (webState.has || webState.playing) Triple(webState.title.ifBlank { "In riproduzione" }, webState.artist.ifBlank { "YouTube Music" }, webState.art.ifBlank { null }) else null
        "exo" -> ctl?.takeIf { it.mediaItemCount > 0 }?.mediaMetadata?.let { Triple(it.title?.toString().orEmpty(), it.artist?.toString().orEmpty(), it.artworkUri?.toString()) }
        else -> null
    }

    fun updateMini() {
        if (!::mini.isInitialized) return
        val info = nowInfo()
        val onPlayer = stack.lastOrNull()?.isPlayer == true
        mini.visibility = if (info == null || onPlayer) View.GONE else View.VISIBLE
        if (info == null) return
        miniTitle.text = info.first.ifBlank { "In riproduzione" }
        miniSub.text = info.second + if (sleepAt > 0) " · 😴" else ""
        miniPlay.setImageResource(if (isPlayingNow()) R.drawable.ic_pause else R.drawable.ic_play)
        if (info.third != null) Img.load(info.third!!, miniArt) else miniArt.setImageDrawable(null)
    }

    private fun buildChrome(): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        host = FrameLayout(this).apply { setBackgroundColor(col(R.color.bg)) }
        outer.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = roundBg(0xFF1D2A44.toInt(), 16); setPadding(dp(10), dp(8), dp(8), dp(8)); visibility = View.GONE
            setOnClickListener { openPlayer() }
        }
        miniArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = roundBg(col(R.color.accent), 10); clipToOutline = true }
        mini.addView(miniArt, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(10) })
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        miniTitle = TextView(this).apply { textSize = 14.5f; setTextColor(android.graphics.Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        miniSub = TextView(this).apply { textSize = 12f; setTextColor(0xFFB9C3D8.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        txt.addView(miniTitle); txt.addView(miniSub)
        mini.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
        miniPlay = ImageView(this).apply { setColorFilter(0xFF1D2A44.toInt()); setPadding(dp(8), dp(8), dp(8), dp(8)); background = roundBg(android.graphics.Color.WHITE, 22); contentDescription = "Avvia o ferma"; setOnClickListener { togglePlay() } }
        mini.addView(miniPlay, LinearLayout.LayoutParams(dp(44), dp(44)))
        outer.addView(mini, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(4), dp(8), dp(4)) })
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(col(R.color.card)); setPadding(dp(4), dp(2), dp(4), dp(2)) }
        tabLabels.forEachIndexed { i, t ->
            tabBar.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)); tag = i
                addView(ImageView(this@MainActivity).apply { setImageResource(tabIcons[i]) }, LinearLayout.LayoutParams(dp(56), dp(30)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(TextView(this@MainActivity).apply { text = t; textSize = 11.5f; gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) })
                setOnClickListener { showTab(i) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        outer.addView(tabBar, LinearLayout.LayoutParams(-1, -2))
        return outer
    }

    fun showTab(i: Int) {
        tab = i
        for (k in 0 until tabBar.childCount) (tabBar.getChildAt(k) as LinearLayout).apply {
            val on = k == i
            (getChildAt(0) as ImageView).apply { setColorFilter(if (on) col(R.color.accent) else col(R.color.muted)); background = if (on) roundBg(col(R.color.icbg), 16) else null }
            (getChildAt(1) as TextView).apply { setTextColor(if (on) col(R.color.accent) else col(R.color.muted)); setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL) }
        }
        val v = tabViews[i] ?: when (i) { 0 -> buildHomeTab(); 1 -> buildMusicTab(); 2 -> buildPodcastTab(); else -> buildRadioTab() }.also { tabViews[i] = it }
        val base = stack.firstOrNull()?.view as? FrameLayout ?: return
        base.removeAllViews()
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        base.addView(v, FrameLayout.LayoutParams(-1, -1))
        stack.first().refresh?.invoke()
    }

    // ======================= avvio =======================

    fun themeChoice() = getSharedPreferences("ascolta", MODE_PRIVATE).getInt("theme", 0)
    private fun themeMode() = when (themeChoice()) { 1 -> AppCompatDelegate.MODE_NIGHT_NO; 2 -> AppCompatDelegate.MODE_NIGHT_YES; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM }
    fun setThemeChoice(v: Int) {
        getSharedPreferences("ascolta", MODE_PRIVATE).edit().putInt("theme", v).apply()
        AppCompatDelegate.setDefaultNightMode(themeMode())
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(themeMode())
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 29) window.decorView.isForceDarkAllowed = false
        setContentView(buildChrome())
        val base = Scr(FrameLayout(this))
        base.refresh = { when (tab) { 0 -> refreshHome?.invoke(); 2 -> refreshPodcasts?.invoke(); 3 -> refreshRadio?.invoke() } }
        push(base)
        showTab(0)
        connect()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (stack.lastOrNull()?.back?.invoke() == true) return
                if (stack.size > 1) { pop(); return }
                if (tab == 1 && musicWeb?.canGoBack() == true) { musicWeb?.goBack(); return }
                if (tab != 0) { showTab(0); return }
                finish()
            }
        })
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handleIntent(intent) }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        ctl?.removeListener(playerListener)
        ctl?.release()
        musicWeb?.let { runCatching { (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() } }
        super.onDestroy()
    }

    // ---------- la pagina di YouTube Music ----------
    fun musicWebView(): WebView = musicWeb ?: createMusicWebView(this, { s ->
        val was = webState.playing
        webState = s
        if (s.playing && !was) { active = "web"; ctl?.pause(); AudioStore.setLast(this, "music") }
        if (active == null && s.has) active = "web"
        updateMini(); onPlayerTick?.invoke()
    }, { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, { updateMini() }).also { musicWeb = it; fixDayNight() }

    /** Creating a WebView resets the screen to the phone's theme (a known Android bug): this puts back the theme chosen in the app,
     *  otherwise with the light theme on a dark phone the cards drawn afterwards come out dark. */
    fun fixDayNight() {
        val want = when (themeChoice()) { 1 -> android.content.res.Configuration.UI_MODE_NIGHT_NO; 2 -> android.content.res.Configuration.UI_MODE_NIGHT_YES; else -> return }
        val mask = android.content.res.Configuration.UI_MODE_NIGHT_MASK
        for (res in listOf(resources, applicationContext.resources)) {
            val cfg = android.content.res.Configuration(res.configuration)
            if (cfg.uiMode and mask != want) {
                cfg.uiMode = (cfg.uiMode and mask.inv()) or want
                @Suppress("DEPRECATION") res.updateConfiguration(cfg, res.displayMetrics)
            }
        }
    }

    override fun onResume() { super.onResume(); fixDayNight() }

    fun openUrl(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast("Non riesco ad aprire il link") } }

    private fun handleIntent(i: Intent?) {
        i ?: return
        when (i.getStringExtra("open")) { "player" -> { i.removeExtra("open"); if (nowInfo() != null) openPlayer(); return } }
        val action = i.getStringExtra("action") ?: return
        i.removeExtra("action")
        Selftest.run(this, action)
    }

    companion object {
        const val GREEN = 0xFF16A34A.toInt()
    }
}
