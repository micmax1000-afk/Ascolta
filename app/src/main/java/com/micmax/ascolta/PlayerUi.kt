package com.micmax.ascolta

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun clockText(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

/** Il lettore a schermo intero: sfondo viola, copertina grande, comandi, velocità, timer, coda, testo (musica) e volume. */
fun MainActivity.openPlayer() {
    if (nowInfo() == null) { toast("Non sta suonando niente"); return }
    val h = Handler(Looper.getMainLooper())
    val white = android.graphics.Color.WHITE
    val soft = 0xFFCFC3EA.toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF5B2C8F.toInt(), 0xFF241238.toInt(), 0xFF0E0B16.toInt()))
    }
    val scr = Scr(root); scr.isPlayer = true
    fun icon(res: Int, size: Int = 28, tint: Int = white) = ImageView(this).apply { setImageResource(res); setColorFilter(tint); layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)) }
    // intestazione
    val clock = TextView(this).apply { textSize = 15f; setTextColor(soft) }
    val sourceName = TextView(this).apply { textSize = 16f; setTextColor(white); setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER }
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(10), dp(16), dp(4)) }
    head.addView(icon(R.drawable.ic_down, 30).apply { setPadding(dp(4), dp(4), dp(4), dp(4)); setOnClickListener { pop() }; contentDescription = "Chiudi" }, LinearLayout.LayoutParams(dp(48), dp(48)))
    val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    mid.addView(TextView(this).apply { text = "IN RIPRODUZIONE DA"; textSize = 11f; letterSpacing = 0.12f; setTextColor(soft); gravity = Gravity.CENTER })
    mid.addView(sourceName)
    head.addView(mid, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(clock)
    root.addView(head)
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(24)) }
    root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))

    // copertina quadrata con la nota musicale di riserva
    val side = (resources.displayMetrics.widthPixels - dp(48)).coerceAtMost(dp(340))
    val coverBox = FrameLayout(this).apply { background = roundBg(0x33000000, 22); clipToOutline = true }
    coverBox.addView(ImageView(this).apply { setImageResource(R.drawable.ic_note); setColorFilter(0x66FFFFFF); setPadding(side / 4, side / 4, side / 4, side / 4) }, FrameLayout.LayoutParams(-1, -1))
    val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    coverBox.addView(art, FrameLayout.LayoutParams(-1, -1))
    body.addView(coverBox, LinearLayout.LayoutParams(side, side).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(6); bottomMargin = dp(20) })

    // titolo + cuore
    val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val title = TextView(this).apply { textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(white); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
    val sub = TextView(this).apply { textSize = 14.5f; setTextColor(soft); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    titleCol.addView(title); titleCol.addView(sub)
    val heart = ImageView(this).apply { setImageResource(R.drawable.ic_heart); setPadding(dp(8), dp(8), dp(8), dp(8)); contentDescription = "Preferita"; visibility = View.GONE }
    titleRow.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f)); titleRow.addView(heart, LinearLayout.LayoutParams(dp(48), dp(48)))
    body.addView(titleRow)

    // avanzamento
    val seek = SeekBar(this).apply {
        progressTintList = android.content.res.ColorStateList.valueOf(white); thumbTintList = android.content.res.ColorStateList.valueOf(white)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x44FFFFFF)
    }
    val posT = TextView(this).apply { textSize = 12.5f; setTextColor(soft) }
    val durT = TextView(this).apply { textSize = 12.5f; setTextColor(soft); gravity = Gravity.END }
    val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    times.addView(posT, LinearLayout.LayoutParams(0, -2, 1f)); times.addView(durT, LinearLayout.LayoutParams(0, -2, 1f))
    body.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }); body.addView(times)

    // comandi
    val ctrl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun cell(v: View, w: Int = 0, hgt: Int = 64) { ctrl.addView(v, if (w == 0) LinearLayout.LayoutParams(0, dp(hgt), 1f) else LinearLayout.LayoutParams(dp(w), dp(hgt))) }
    fun skipBtn(res: Int, label: String, f: () -> Unit) = FrameLayout(this).apply {
        addView(ImageView(this@openPlayer).apply { setImageResource(res); setColorFilter(white) }, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER))
        addView(TextView(this@openPlayer).apply { text = label; textSize = 9.5f; setTextColor(white); setTypeface(typeface, android.graphics.Typeface.BOLD) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { topMargin = dp(3) })
        setOnClickListener { f() }
    }
    fun transport(res: Int, f: () -> Unit) = ImageView(this).apply { setImageResource(res); setColorFilter(white); setPadding(dp(14), dp(14), dp(14), dp(14)); setOnClickListener { f() } }
    val back = skipBtn(R.drawable.ic_rew, "15") { ctl?.seekBack() }
    val prev = transport(R.drawable.ic_prev) { if (active == "web") musicWeb?.evaluateJavascript(MusicWeb.command("prev"), null) else ctl?.seekTo(0) }
    val play = ImageView(this).apply { setImageResource(R.drawable.ic_pause); setColorFilter(0xFF241238.toInt()); setPadding(dp(18), dp(18), dp(18), dp(18)); background = roundBg(white, 40); setOnClickListener { togglePlay() } }
    val next = transport(R.drawable.ic_next) { if (active == "web") musicWeb?.evaluateJavascript(MusicWeb.command("next"), null) else if (!playNextInQueue()) toast("La coda è vuota") }
    val fwd = skipBtn(R.drawable.ic_fwd, "30") { ctl?.seekForward() }
    cell(back); cell(prev); cell(play, 80, 80); cell(next); cell(fwd)
    body.addView(ctrl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

    // pulsanti
    val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(4)) }
    body.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
    // volume
    val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    val vol = SeekBar(this).apply {
        max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC); progress = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        progressTintList = android.content.res.ColorStateList.valueOf(white); thumbTintList = android.content.res.ColorStateList.valueOf(white)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x44FFFFFF)
    }
    val volRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    volRow.addView(TextView(this).apply { text = "🔈"; textSize = 18f })
    volRow.addView(vol, LinearLayout.LayoutParams(0, -2, 1f))
    volRow.addView(TextView(this).apply { text = "🔊"; textSize = 18f })
    body.addView(volRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    val lyricsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = roundBg(0x1AFFFFFF, 16); setPadding(dp(12), dp(10), dp(12), dp(10)); visibility = View.GONE }
    body.addView(lyricsBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

    var lastKey = ""; var lines: List<Lyrics.Line> = emptyList(); var lineViews: List<TextView> = emptyList(); var activeLine = -2
    var dragging = false; var draggingVol = false; var showLyrics = true; var lyricsLoaded = false
    fun station(): RadioStation? = if (active == "exo" && ctl?.currentMediaItem?.mediaId?.startsWith("radio:") == true) lastStation() else null
    fun updateHeart() { val s = station(); heart.visibility = if (s != null) View.VISIBLE else View.GONE; if (s != null) heart.setImageResource(if (repo.favoriteStations().any { it.id == s.id }) R.drawable.ic_heart_on else R.drawable.ic_heart) }
    heart.setOnClickListener { station()?.let { s -> val on = repo.toggleFavorite(s); toast(if (on) "⭐ Radio preferita" else "Tolta dai preferiti"); updateHeart(); refreshRadio?.invoke(); refreshHome?.invoke() } }

    fun pill(text: String, f: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 13.5f; maxLines = 1; isSingleLine = true; setTextColor(white); setTypeface(typeface, android.graphics.Typeface.BOLD); background = roundBg(0x40FFFFFF, 20); setPadding(dp(14), dp(10), dp(14), dp(10)); setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4); marginEnd = dp(4) }
    }
    fun buildChips() {
        chips.removeAllViews()
        if (active == "exo") {
            val sp = ctl?.playbackParameters?.speed ?: 1f
            chips.addView(pill("⚡ ${if (sp % 1f == 0f) sp.toInt().toString() else sp.toString()}×") {
                val speeds = floatArrayOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)
                MaterialAlertDialogBuilder(this).setTitle("Velocità").setItems(speeds.map { "$it×" }.toTypedArray()) { _, w ->
                    ctl?.setPlaybackSpeed(speeds[w])
                    val feed = ctl?.mediaMetadata?.extras?.getString("feed")
                    if (feed != null) PodcastPrefs.set(this, feed, PodcastPrefs.get(this, feed).copy(speed = speeds[w]))
                    buildChips()
                }.show()
            })
        } else chips.addView(pill(if (showLyrics) "📝 Testo ✓" else "📝 Testo") { showLyrics = !showLyrics; lyricsBox.visibility = if (showLyrics && lyricsLoaded) View.VISIBLE else View.GONE; buildChips() })
        chips.addView(pill(if (sleepAt > 0) "⏲ ${((sleepAt - System.currentTimeMillis()) / 60000 + 1)} min" else "⏲ Timer") {
            val opts = mutableListOf("15 minuti" to 15, "30 minuti" to 30, "45 minuti" to 45, "1 ora" to 60, "1 ora e mezza" to 90)
            val dur = ctl?.duration ?: C.TIME_UNSET
            if (active == "exo" && dur != C.TIME_UNSET && dur > 0) opts += "Alla fine della puntata" to (((dur - (ctl?.currentPosition ?: 0L)) / 60000).toInt() + 1)
            if (sleepAt > 0) opts += "Spegni il timer" to 0
            MaterialAlertDialogBuilder(this).setTitle("Timer del sonno").setItems(opts.map { it.first }.toTypedArray()) { _, w -> setSleep(opts[w].second); buildChips() }.show()
        })
        chips.addView(pill("📋 Coda") { openQueue() })
    }

    fun loadLyrics(t: String, a: String, durSec: Long) {
        lyricsBox.removeAllViews(); lines = emptyList(); lineViews = emptyList(); activeLine = -2; lyricsLoaded = true
        lyricsBox.visibility = if (showLyrics) View.VISIBLE else View.GONE
        lyricsBox.addView(TextView(this).apply { text = "Cerco il testo…"; textSize = 13f; setTextColor(soft) })
        io.execute {
            val r = Lyrics.fetch(this, t, a, durSec)
            runOnUiThread {
                if (stack.lastOrNull() !== scr) return@runOnUiThread
                lyricsBox.removeAllViews()
                when (r) {
                    is Lyrics.Result.Synced -> {
                        lines = r.lines
                        lineViews = r.lines.take(200).map { l -> TextView(this).apply { text = l.text.ifBlank { "♪" }; textSize = 16f; gravity = Gravity.CENTER; setTextColor(soft); setPadding(0, dp(5), 0, dp(5)) }.also { lyricsBox.addView(it) } }
                    }
                    is Lyrics.Result.Plain -> lyricsBox.addView(TextView(this).apply { text = r.text; textSize = 15f; gravity = Gravity.CENTER; setTextColor(white); setLineSpacing(0f, 1.2f) })
                    is Lyrics.Result.Instrumental -> lyricsBox.addView(TextView(this).apply { text = "♪ Brano strumentale"; textSize = 14f; setTextColor(soft) })
                    else -> lyricsBox.addView(TextView(this).apply { text = "Testo non trovato"; textSize = 13f; setTextColor(soft) })
                }
            }
        }
    }

    fun tick() {
        val info = nowInfo() ?: run { pop(); return }
        val web = active == "web"
        val key = info.first + "|" + info.second + "|" + active
        if (key != lastKey) {
            lastKey = key
            title.text = info.first; sub.text = info.second
            sourceName.text = if (web) "YouTube Music" else if (ctl?.currentMediaItem?.mediaId?.startsWith("radio:") == true) "Radio" else "Podcast"
            art.setImageDrawable(null)
            if (info.third != null) Img.load(info.third!!, art)
            buildChips(); updateHeart()
            lyricsBox.removeAllViews(); lines = emptyList(); lineViews = emptyList(); lyricsLoaded = false; lyricsBox.visibility = View.GONE
            if (web && info.first.isNotBlank()) loadLyrics(info.first, info.second, webState.durMs / 1000)
        }
        play.setImageResource(if (isPlayingNow()) R.drawable.ic_pause else R.drawable.ic_play)
        back.visibility = if (web) View.INVISIBLE else View.VISIBLE; fwd.visibility = if (web) View.INVISIBLE else View.VISIBLE
        val c = ctl
        val pos: Long; val dur: Long; val live: Boolean
        if (web) { pos = webState.posMs; dur = webState.durMs; live = false }
        else { live = c?.isCurrentMediaItemLive == true || (c?.duration ?: C.TIME_UNSET) == C.TIME_UNSET; pos = c?.currentPosition ?: 0; dur = if (live) 0 else c?.duration ?: 0 }
        seek.visibility = if (live) View.INVISIBLE else View.VISIBLE
        posT.text = if (live) "🔴 In diretta" else clockText(pos); durT.text = if (live || dur <= 0) "" else "−" + clockText(dur - pos)
        if (!dragging && dur > 0) { seek.max = dur.toInt().coerceAtMost(Int.MAX_VALUE); seek.progress = pos.toInt().coerceAtMost(seek.max) }
        if (!draggingVol) vol.progress = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        clock.text = SimpleDateFormat("HH:mm", Locale.ITALIAN).format(Date())
        if (lines.isNotEmpty() && lineViews.isNotEmpty()) {
            val idx = Lyrics.activeIndex(lines, pos).coerceAtMost(lineViews.size - 1)
            if (idx != activeLine) {
                activeLine = idx
                lineViews.forEachIndexed { i, v -> v.setTextColor(if (i == idx) white else soft); v.textSize = if (i == idx) 19f else 16f; v.setTypeface(null, if (i == idx) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL) }
                if (idx >= 0) (root.getChildAt(1) as? ScrollView)?.smoothScrollTo(0, (lyricsBox.top + lineViews[idx].top - dp(160)).coerceAtLeast(0))
            }
        }
    }
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) posT.text = clockText(p.toLong()) }
        override fun onStartTrackingTouch(s: SeekBar?) { dragging = true }
        override fun onStopTrackingTouch(s: SeekBar?) {
            dragging = false
            val p = (s?.progress ?: 0).toLong()
            if (active == "web") musicWeb?.evaluateJavascript(MusicWeb.command("seek", p), null) else ctl?.seekTo(p)
        }
    })
    vol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) runCatching { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, p, 0) } }
        override fun onStartTrackingTouch(s: SeekBar?) { draggingVol = true }
        override fun onStopTrackingTouch(s: SeekBar?) { draggingVol = false }
    })
    val loop = object : Runnable { override fun run() { tick(); h.postDelayed(this, 500) } }
    scr.onClose = { h.removeCallbacksAndMessages(null); onPlayerTick = null; runOnUiThread { updateMini() } }
    onPlayerTick = { tick() }
    push(scr)
    loop.run()
}

// ======================= Sveglia con la radio =======================

fun MainActivity.openRadioAlarm() {
    openScreen("Sveglia con la radio") { body, scr ->
        fun render() {
            body.removeAllViews()
            val on = RadioAlarm.enabled(this); val h = RadioAlarm.hour(this); val m = RadioAlarm.minute(this); val wd = RadioAlarm.weekdaysOnly(this)
            val st = RadioAlarm.station(this)
            hintLabel(body, "Ti sveglia con una radio preferita. Se la radio non parte, suona la sveglia del telefono.")
            listRow(body, if (on) "⏰" else "🔕", if (on) "Sveglia accesa" else "Sveglia spenta", if (on) "Prossima: " + SimpleDateFormat("EEE d MMM HH:mm", Locale.ITALIAN).format(Date(RadioAlarm.next(System.currentTimeMillis(), h, m, wd))) else "Tocca per accenderla") {
                if (!on && st == null) { toast("Scegli prima una radio"); return@listRow }
                if (!on) ensureNotificationPermission()
                RadioAlarm.set(this, !on, h, m, wd, st); render()
            }
            listRow(body, "🕖", "Ora", "%02d:%02d".format(h, m)) {
                TimePickerDialog(this, { _, hh, mm -> RadioAlarm.set(this, on, hh, mm, wd, st); render() }, h, m, true).show()
            }
            listRow(body, "📅", if (wd) "Solo nei giorni feriali" else "Tutti i giorni", "Tocca per cambiare") { RadioAlarm.set(this, on, h, m, !wd, st); render() }
            listRow(body, "📻", st?.name ?: "Scegli la radio", if (st == null) "Una delle tue preferite" else "Tocca per cambiarla") {
                val favs = repo.favoriteStations()
                if (favs.isEmpty()) { toast("Metti prima una radio tra le preferite (tienila premuta nella scheda Radio)"); return@listRow }
                MaterialAlertDialogBuilder(this).setTitle("Radio").setItems(favs.map { it.name }.toTypedArray()) { _, w -> RadioAlarm.set(this, on, h, m, wd, favs[w]); render() }.show()
            }
        }
        scr.refresh = { render() }
        render()
    }
}

// ======================= Impostazioni =======================

fun MainActivity.openSettings() {
    val sp = getSharedPreferences("ascolta", android.content.Context.MODE_PRIVATE)
    openScreen("Impostazioni") { body, scr ->
        fun render() {
            body.removeAllViews()
            sectionLabel(body, "ASPETTO")
            listRow(body, "🎨", "Tema", listOf("Come il telefono", "Chiaro", "Scuro")[themeChoice()]) {
                MaterialAlertDialogBuilder(this).setTitle("Tema").setSingleChoiceItems(arrayOf("Come il telefono", "Chiaro", "Scuro"), themeChoice()) { dl, w -> dl.dismiss(); setThemeChoice(w) }.show()
            }
            sectionLabel(body, "PODCAST")
            val speed = sp.getFloat("speed", 1f)
            listRow(body, "⚡", "Velocità generale", "$speed×") {
                val speeds = floatArrayOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)
                MaterialAlertDialogBuilder(this).setTitle("Velocità").setItems(speeds.map { "$it×" }.toTypedArray()) { _, w -> sp.edit().putFloat("speed", speeds[w]).apply(); render() }.show()
            }
            val skip = sp.getBoolean("skipSilence", false)
            listRow(body, if (skip) "🔇" else "🔈", if (skip) "Salto i silenzi: sì" else "Salto i silenzi: no", "Accorcia le pause nei parlati") { sp.edit().putBoolean("skipSilence", !skip).apply(); render() }
            val auto = PodcastAuto.enabled(this)
            listRow(body, "⬇", if (auto) "Scarico le puntate nuove col wifi: sì" else "Scarico le puntate nuove col wifi: no", "Le ultime puntate dei podcast che segui, solo col wifi e la batteria non scarica") { PodcastAuto.setEnabled(this, !auto); render() }
            val clean = PodcastAuto.cleanup(this)
            listRow(body, "🧹", if (clean) "Tolgo le puntate ascoltate: sì" else "Tolgo le puntate ascoltate: no", "Libera spazio dai file già ascoltati") { PodcastAuto.setCleanup(this, !clean); render() }
            sectionLabel(body, "RADIO")
            listRow(body, "⏰", "Sveglia con la radio", if (RadioAlarm.enabled(this)) "Accesa · %02d:%02d".format(RadioAlarm.hour(this), RadioAlarm.minute(this)) else "Spenta") { openRadioAlarm() }
            sectionLabel(body, "I MIEI DATI")
            listRow(body, "📤", "Esporta i miei dati", "Podcast, radio preferite e coda in un file") { shareFile("ascolta.json", "application/json", Backup.export(this)) }
            listRow(body, "📥", "Importa da un file", "Un file di Ascolta (.json) oppure un elenco di podcast (OPML)") { importFile() }
            listRow(body, "🎙", "Esporta i podcast (OPML)", "Il formato che capiscono tutte le app di podcast") { shareFile("podcast.opml", "text/x-opml", Opml.build(repo.subscriptions())) }
            sectionLabel(body, "INFO")
            hintLabel(body, "Podcast: elenco di Apple Podcasts. Radio: radio-browser.info. Testi delle canzoni: LRCLIB. La musica è la pagina di YouTube Music: se la pagina cambia, qualche tasto del lettore può smettere di funzionare.")
        }
        scr.refresh = { render() }
        render()
    }
}

// ======================= File =======================

internal var fileCb: ((android.net.Uri) -> Unit)? = null

fun MainActivity.pickFile(cb: (android.net.Uri) -> Unit) { fileCb = cb; filePicker.launch(arrayOf("*/*")) }

fun MainActivity.shareFile(name: String, mime: String, text: String) {
    runCatching {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val f = File(dir, name).apply { writeText(text) }
        val u = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Salva o condividi il file"))
    }.onFailure { toast("Non riesco a creare il file") }
}

fun MainActivity.importFile() {
    pickFile { uri ->
        io.execute {
            val text = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull().orEmpty()
            val r = Backup.importText(this, text)
            runOnUiThread { if (r == null) toast("Questo file non contiene podcast o radio") else { toast("✓ $r"); refreshTop(); refreshPodcasts?.invoke(); refreshRadio?.invoke(); refreshHome?.invoke() } }
        }
    }
}

fun MainActivity.importOpml() = importFile()
