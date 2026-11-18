package com.micmax.ascolta

import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// refresh delle schede (si impostano quando la scheda viene costruita)
var refreshHome: (() -> Unit)? = null
var refreshPodcasts: (() -> Unit)? = null
var refreshRadio: (() -> Unit)? = null

private fun MainActivity.tabScroll(build: (LinearLayout) -> Unit): Pair<View, LinearLayout> {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(16)) }
    root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
    build(body)
    return root to body
}

private fun MainActivity.tabHeader(title: String, vararg actions: Pair<String, () -> Unit>): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(8), dp(4))
    addView(TextView(this@tabHeader).apply { text = title; textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(col(R.color.text)) }, LinearLayout.LayoutParams(0, -2, 1f))
    actions.forEach { (t, f) -> addView(TextView(this@tabHeader).apply { text = t; textSize = 22f; gravity = Gravity.CENTER; setOnClickListener { f() } }, LinearLayout.LayoutParams(dp(46), dp(44))) }
}

private fun minutesLeft(c: android.content.Context, e: Episode): String {
    val pos = AudioStore.position(c, e.id); val dur = e.durationSec * 1000
    return if (dur > 0 && pos > 0 && !AudioStore.isFinished(c, e.id)) "${((dur - pos) / 60000).coerceAtLeast(1)} min rimasti" else if (dur > 0) "${(dur / 60000).coerceAtLeast(1)} min" else ""
}

private val DAY = SimpleDateFormat("d MMM", Locale.ITALIAN)

fun episodeSub(c: android.content.Context, e: Episode) = listOf(e.podcastTitle, if (e.time > 0) DAY.format(Date(e.time)) else "", minutesLeft(c, e)).filter { it.isNotBlank() }.joinToString(" · ")

// ======================= Per te =======================

fun MainActivity.buildHomeTab(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
    root.addView(tabHeader("Ascolta", "⏰" to { openRadioAlarm() }, "⚙️" to { openSettings() }))
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(16)) }
    root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
    fun render() {
        body.removeAllViews()
        val recent = AudioStore.recent(this).take(4)
        sectionLabel(body, "CONTINUA")
        if (recent.isEmpty() && lastStation() == null) hintLabel(body, "Qui trovi quello che stavi ascoltando. Cerca un podcast, una radio o apri la musica.")
        recent.forEach { e -> listRow(body, "🎙", e.title, episodeSub(this, e)) { playEpisode(e) } }
        lastStation()?.let { s -> listRow(body, "📻", s.name, "Ultima radio ascoltata") { playStation(s) } }
        val q = AudioQueue.list(this)
        if (q.isNotEmpty()) listRow(body, "📋", "Coda · ${q.size}", "Si ascoltano una dopo l'altra") { openQueue() }
        val eps = repo.cachedEpisodes().filter { !AudioStore.isFinished(this, it.id) }.take(8)
        sectionLabel(body, "NUOVE PUNTATE")
        if (eps.isEmpty()) hintLabel(body, if (repo.subscriptions().isEmpty()) "Segui un podcast nella scheda Podcast: le puntate nuove compaiono qui." else "Nessuna puntata nuova. Tira giù «Aggiorna» qui sotto.")
        eps.forEach { e -> listRow(body, "🎙", e.title, episodeSub(this, e), { episodeMenu(e) }) { playEpisode(e) } }
        if (repo.subscriptions().isNotEmpty()) listRow(body, "🔄", "Aggiorna le puntate", "Cerca le novità dei podcast che segui") {
            toast("Cerco le novità…"); io.execute { repo.refreshEpisodes(); runOnUiThread { render() } }
        }
        val favs = repo.favoriteStations()
        if (favs.isNotEmpty()) {
            sectionLabel(body, "RADIO PREFERITE")
            body.addView(chipBar(favs.take(12).map { s -> "📻 ${s.name.take(22)}" to { playStation(s) } }))
        }
    }
    refreshHome = { render() }
    render()
    if (repo.subscriptions().isNotEmpty() && repo.cachedEpisodes().isEmpty()) io.execute { repo.refreshEpisodes(); runOnUiThread { render() } }
    return root
}

private fun MainActivity.lastStation(): RadioStation? = runCatching {
    com.google.gson.Gson().fromJson(getSharedPreferences("ascolta", android.content.Context.MODE_PRIVATE).getString("last_station", null), RadioStation::class.java)
}.getOrNull()

/** Menu di una puntata: ascolta dopo, scarica, segna come ascoltata. */
fun MainActivity.episodeMenu(e: Episode) {
    val downloaded = repo.isDownloaded(e)
    val opts = arrayOf("▶ Ascolta ora", "📋 Ascolta dopo", if (downloaded) "🗑 Togli il file scaricato" else "⬇ Scarica", "✓ Segna come ascoltata")
    MaterialAlertDialogBuilder(this).setTitle(e.title.take(60)).setItems(opts) { _, w ->
        when (w) {
            0 -> playEpisode(e)
            1 -> { AudioQueue.add(this, e); toast("Aggiunta alla coda"); refreshTop() }
            2 -> if (downloaded) { repo.localFile(e).delete(); toast("File tolto"); refreshTop() } else { toast(if (PodcastAuto.download(this, e)) "Scarico…" else "Non riesco a scaricare") }
            3 -> { AudioStore.markFinished(this, e.id); refreshTop(); refreshHome?.invoke() }
        }
    }.show()
}

// ======================= Musica =======================

fun MainActivity.buildMusicTab(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
    val w = musicWebView()
    (w.parent as? android.view.ViewGroup)?.removeView(w)
    root.addView(chipBar(listOf(
        "‹" to { if (w.canGoBack()) w.goBack() }, "⟳" to { w.reload() }, "🏠" to { w.loadUrl(YT_MUSIC_HOME) },
        "⛶ Lettore" to { if (nowInfo() != null) openPlayer() else toast("Fai partire prima una canzone") }
    )))
    root.addView(w, LinearLayout.LayoutParams(-1, 0, 1f))
    return root
}

// ======================= Podcast =======================

fun MainActivity.buildPodcastTab(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
    root.addView(tabHeader("Podcast", "🔍" to { openPodcastSearch() }))
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(16)) }
    root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
    fun render() {
        body.removeAllViews()
        val q = AudioQueue.list(this)
        if (q.isNotEmpty()) listRow(body, "📋", "Coda · ${q.size}", "Si ascoltano una dopo l'altra") { openQueue() }
        val subs = repo.subscriptions()
        if (subs.isEmpty()) {
            hintLabel(body, "Non segui ancora nessun podcast.")
            listRow(body, "🔍", "Cerca un podcast", "Per nome o argomento") { openPodcastSearch() }
            listRow(body, "📥", "Importa da un file OPML", "Il file con l'elenco dei tuoi podcast (anche da Web Launcher)") { importOpml() }
            return
        }
        sectionLabel(body, "CHE SEGUI · ${subs.size}")
        subs.sortedBy { it.title.lowercase() }.forEach { p ->
            listRow(body, "🎙", p.title, p.author.ifBlank { null }, { MaterialAlertDialogBuilder(this).setTitle(p.title).setItems(arrayOf("Non seguire più")) { _, _ -> repo.unsubscribe(p.feedUrl); render() }.show() }) { openPodcast(p) }
        }
    }
    refreshPodcasts = { render() }
    render()
    return root
}

fun MainActivity.openPodcast(p: Podcast) {
    openScreen(p.title.take(28)) { body, scr ->
        fun render(eps: List<Episode>?) {
            body.removeAllViews()
            val subscribed = repo.isSubscribed(p.feedUrl)
            body.addView(MaterialButton(this, null, if (subscribed) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle).apply {
                text = if (subscribed) "✓ Lo segui" else "＋ Segui"; isAllCaps = false
                setOnClickListener { if (subscribed) repo.unsubscribe(p.feedUrl) else repo.subscribe(p); toast(if (subscribed) "Non lo segui più" else "Lo segui"); render(eps); refreshPodcasts?.invoke() }
            })
            if (p.author.isNotBlank()) hintLabel(body, p.author)
            val o = PodcastPrefs.get(this, p.feedUrl)
            listRow(body, "⚡", "Velocità di questo podcast", if (o.speed > 0) "${o.speed}×" else "Come la velocità generale") {
                val speeds = floatArrayOf(0f, 0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)
                MaterialAlertDialogBuilder(this).setTitle("Velocità").setItems(speeds.map { if (it == 0f) "Come la generale" else "$it×" }.toTypedArray()) { _, w -> PodcastPrefs.set(this, p.feedUrl, o.copy(speed = speeds[w])); render(eps) }.show()
            }
            if (eps == null) { hintLabel(body, "Cerco le puntate…"); return }
            if (eps.isEmpty()) { hintLabel(body, "Non riesco a leggere le puntate di questo podcast."); return }
            sectionLabel(body, "PUNTATE")
            eps.take(40).forEach { e ->
                val d = if (repo.isDownloaded(e)) "⬇ " else ""
                listRow(body, if (AudioStore.isFinished(this, e.id)) "✓" else "🎙", e.title, d + episodeSub(this, e).removePrefix(e.podcastTitle + " · "), { episodeMenu(e) }) { playEpisode(e) }
            }
            hintLabel(body, "Tieni premuta una puntata per ascoltarla dopo, scaricarla o segnarla come ascoltata.")
        }
        scr.refresh = { render(repo.cachedEpisodes().filter { it.feedUrl == p.feedUrl }.ifEmpty { null }) }
        render(null)
        io.execute { val eps = runCatching { repo.episodes(p.feedUrl) }.getOrDefault(emptyList()); runOnUiThread { render(eps) } }
    }
}

fun MainActivity.openPodcastSearch() {
    openScreen("Cerca un podcast") { body, _ ->
        val et = field("Nome o argomento", null).apply { setSingleLine(); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(et); body.addView(results)
        fun search() {
            val q = et.text.toString().trim(); if (q.length < 2) return
            results.removeAllViews(); hintLabel(results, "Cerco…")
            io.execute {
                val list = runCatching { repo.search(q) }.getOrNull()
                runOnUiThread {
                    results.removeAllViews()
                    when {
                        list == null -> hintLabel(results, "Non riesco a cercare: controlla la connessione.")
                        list.isEmpty() -> hintLabel(results, "Nessun podcast trovato per «$q».")
                        else -> list.forEach { p -> listRow(results, "🎙", p.title, p.author.ifBlank { null }) { openPodcast(p) } }
                    }
                }
            }
        }
        et.setOnEditorActionListener { _, _, _ -> search(); true }
        body.addView(MaterialButton(this).apply { text = "Cerca"; isAllCaps = false; setOnClickListener { search() } }, 1 + 0)
    }
}

fun MainActivity.openQueue() {
    openScreen("Coda", "Svuota", { AudioQueue.clear(this); refreshTop() }) { body, scr ->
        fun render() {
            body.removeAllViews()
            val q = AudioQueue.list(this)
            if (q.isEmpty()) { hintLabel(body, "La coda è vuota. Tieni premuta una puntata e scegli «Ascolta dopo»."); return }
            q.forEachIndexed { i, e ->
                listRow(body, "${i + 1}", e.title, e.podcastTitle, { MaterialAlertDialogBuilder(this).setItems(arrayOf("⬆ Sposta su", "⬇ Sposta giù")) { _, w -> AudioQueue.move(this, e.id, w == 0); render() }.show() },
                    { AudioQueue.remove(this, e.id); render() }) { AudioQueue.remove(this, e.id); playEpisode(e); render() }
            }
        }
        scr.refresh = { render() }
        render()
    }
}

// ======================= Radio =======================

fun MainActivity.buildRadioTab(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
    root.addView(tabHeader("Radio", "🔍" to { openStationSearch() }))
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(16)) }
    root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
    fun render() {
        body.removeAllViews()
        val favs = repo.favoriteStations()
        if (favs.isNotEmpty()) {
            sectionLabel(body, "PREFERITE")
            favs.forEach { s -> listRow(body, "⭐", s.name, listOf(s.state, s.tags.split(',').firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }, { stationMenu(s) }) { playStation(s) } }
        }
        sectionLabel(body, "SCOPRI")
        listRow(body, "🇮🇹", "Le più ascoltate in Italia", null) { openStations("Radio in Italia") { repo.nationalStations() } }
        listRow(body, "🗺", "Per regione", null) {
            MaterialAlertDialogBuilder(this).setTitle("Regione").setItems(AudioRepository.REGIONS.map { it.first }.toTypedArray()) { _, w ->
                val r = AudioRepository.REGIONS[w]; openStations("Radio · ${r.first}") { repo.regionStations(r.second) }
            }.show()
        }
        listRow(body, "🔍", "Cerca una radio", "Per nome") { openStationSearch() }
        if (favs.isEmpty()) hintLabel(body, "Tieni premuta una radio per metterla tra le preferite.")
    }
    refreshRadio = { render() }
    render()
    return root
}

fun MainActivity.stationMenu(s: RadioStation) {
    val fav = repo.favoriteStations().any { it.id == s.id }
    MaterialAlertDialogBuilder(this).setTitle(s.name).setItems(arrayOf("▶ Ascolta", if (fav) "☆ Togli dai preferiti" else "⭐ Metti tra i preferiti")) { _, w ->
        if (w == 0) playStation(s) else { repo.toggleFavorite(s); refreshTop(); refreshRadio?.invoke(); refreshHome?.invoke() }
    }.show()
}

fun MainActivity.openStations(title: String, load: () -> List<RadioStation>) {
    openScreen(title) { body, _ ->
        hintLabel(body, "Cerco le radio…")
        io.execute {
            val list = runCatching { load() }.getOrDefault(emptyList())
            runOnUiThread {
                body.removeAllViews()
                if (list.isEmpty()) hintLabel(body, "Non trovo radio: controlla la connessione.")
                list.forEach { s -> listRow(body, "📻", s.name, listOf(s.state, s.tags.split(',').firstOrNull().orEmpty(), if (s.bitrate > 0) "${s.bitrate} kbps" else "").filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }, { stationMenu(s) }) { playStation(s) } }
                if (list.isNotEmpty()) hintLabel(body, "Tieni premuta una radio per metterla tra le preferite.")
            }
        }
    }
}

fun MainActivity.openStationSearch() {
    openScreen("Cerca una radio") { body, _ ->
        val et = field("Nome della radio", null).apply { setSingleLine(); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(et); body.addView(results)
        fun search() {
            val q = et.text.toString().trim(); if (q.length < 2) return
            results.removeAllViews(); hintLabel(results, "Cerco…")
            io.execute {
                val list = runCatching { repo.searchStations(q) }.getOrDefault(emptyList())
                runOnUiThread {
                    results.removeAllViews()
                    if (list.isEmpty()) hintLabel(results, "Nessuna radio trovata per «$q».")
                    list.forEach { s -> listRow(results, "📻", s.name, s.state.ifBlank { null }, { stationMenu(s) }) { playStation(s) } }
                }
            }
        }
        et.setOnEditorActionListener { _, _, _ -> search(); true }
    }
}
