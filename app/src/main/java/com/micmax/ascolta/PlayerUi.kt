package com.micmax.ascolta

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
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

/** Il lettore a schermo intero: copertina, avanzamento, comandi, velocità, timer del sonno, coda, testo (per la musica). */
fun MainActivity.openPlayer() {
    if (nowInfo() == null) { toast("Non sta suonando niente"); return }
    val h = Handler(Looper.getMainLooper())
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
    val scr = Scr(root); scr.isPlayer = true
    val clock = TextView(this).apply { textSize = 15f; setTextColor(col(R.color.muted)) }
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(16), dp(4)) }
    head.addView(TextView(this).apply { text = "⌄"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(col(R.color.text)); contentDescription = "Chiudi"; setOnClickListener { pop() } }, LinearLayout.LayoutParams(dp(52), dp(44)))
    head.addView(TextView(this).apply { text = "Sta suonando"; textSize = 16f; setTextColor(col(R.color.muted)) }, LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(clock)
    root.addView(head)
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(4), dp(20), dp(20)) }
    root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))

    val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = roundBg(col(R.color.accent), 22); clipToOutline = true }
    body.addView(art, LinearLayout.LayoutParams(dp(230), dp(230)).apply { topMargin = dp(8); bottomMargin = dp(14) })
    val title = TextView(this).apply { textSize = 20f; gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(col(R.color.text)); maxLines = 2 }
    val sub = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setTextColor(col(R.color.muted)); maxLines = 1 }
    body.addView(title); body.addView(sub)
    val seek = SeekBar(this)
    val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val posT = TextView(this).apply { textSize = 12f; setTextColor(col(R.color.muted)) }
    val durT = TextView(this).apply { textSize = 12f; setTextColor(col(R.color.muted)); gravity = Gravity.END }
    times.addView(posT, LinearLayout.LayoutParams(0, -2, 1f)); times.addView(durT, LinearLayout.LayoutParams(0, -2, 1f))
    body.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }); body.addView(times, LinearLayout.LayoutParams(-1, -2))

    val ctrl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    fun cbtn(t: String, size: Float = 26f, f: () -> Unit) = TextView(this).apply { text = t; textSize = size; gravity = Gravity.CENTER; setTextColor(col(R.color.text)); setOnClickListener { f() } }
        .also { ctrl.addView(it, LinearLayout.LayoutParams(0, dp(64), 1f)) }
    val prev = cbtn("⏮") { if (active == "web") musicWeb?.evaluateJavascript(MusicWeb.command("prev"), null) else ctl?.seekTo(0) }
    val back = cbtn("⏪ 15", 17f) { ctl?.seekBack() }
    val play = cbtn("⏸", 40f) { togglePlay() }
    val fwd = cbtn("30 ⏩", 17f) { ctl?.seekForward() }
    val next = cbtn("⏭") { if (active == "web") musicWeb?.evaluateJavascript(MusicWeb.command("next"), null) else if (!playNextInQueue()) toast("La coda è vuota") }
    body.addView(ctrl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    val chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    body.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    val lyricsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    body.addView(lyricsBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

    var lastKey = ""; var lines: List<Lyrics.Line> = emptyList(); var lineViews: List<TextView> = emptyList(); var activeLine = -2
    var dragging = false
    fun buildChips() {
        chips.removeAllViews()
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (active == "exo") {
            val c = ctl
            val sp = c?.playbackParameters?.speed ?: 1f
            items += "⚡ ${if (sp % 1f == 0f) sp.toInt().toString() else sp.toString()}×" to {
                val speeds = floatArrayOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)
                MaterialAlertDialogBuilder(this).setTitle("Velocità").setItems(speeds.map { "$it×" }.toTypedArray()) { _, w ->
                    ctl?.setPlaybackSpeed(speeds[w])
                    val feed = ctl?.mediaMetadata?.extras?.getString("feed")
                    if (feed != null) PodcastPrefs.set(this, feed, PodcastPrefs.get(this, feed).copy(speed = speeds[w]))
                    buildChips()
                }.show()
            }
        }
        items += (if (sleepAt > 0) "😴 ${((sleepAt - System.currentTimeMillis()) / 60000 + 1)} min" else "😴 Timer") to {
            val opts = mutableListOf("15 minuti" to 15, "30 minuti" to 30, "45 minuti" to 45, "1 ora" to 60, "1 ora e mezza" to 90)
            val dur = ctl?.duration ?: C.TIME_UNSET
            if (active == "exo" && dur != C.TIME_UNSET && dur > 0) opts += "Alla fine della puntata" to (((dur - (ctl?.currentPosition ?: 0L)) / 60000).toInt() + 1)
            if (sleepAt > 0) opts += "Spegni il timer" to 0
            MaterialAlertDialogBuilder(this).setTitle("Timer del sonno").setItems(opts.map { it.first }.toTypedArray()) { _, w -> setSleep(opts[w].second); buildChips() }.show()
        }
        items += "📋 Coda" to { openQueue() }
        chips.addView(chipBar(items))
    }

    fun loadLyrics(t: String, a: String, durSec: Long) {
        lyricsBox.removeAllViews(); lines = emptyList(); lineViews = emptyList(); activeLine = -2
        lyricsBox.addView(TextView(this).apply { text = "Cerco il testo…"; textSize = 13f; setTextColor(col(R.color.muted)) })
        io.execute {
            val r = Lyrics.fetch(this, t, a, durSec)
            runOnUiThread {
                if (stack.lastOrNull() !== scr) return@runOnUiThread
                lyricsBox.removeAllViews()
                when (r) {
                    is Lyrics.Result.Synced -> {
                        lines = r.lines
                        lineViews = r.lines.take(200).map { l -> TextView(this).apply { text = l.text.ifBlank { "♪" }; textSize = 16f; gravity = Gravity.CENTER; setTextColor(col(R.color.muted)); setPadding(0, dp(5), 0, dp(5)) }.also { lyricsBox.addView(it) } }
                    }
                    is Lyrics.Result.Plain -> lyricsBox.addView(TextView(this).apply { text = r.text; textSize = 15f; gravity = Gravity.CENTER; setTextColor(col(R.color.text)); setLineSpacing(0f, 1.2f) })
                    is Lyrics.Result.Instrumental -> lyricsBox.addView(TextView(this).apply { text = "♪ Brano strumentale"; textSize = 14f; setTextColor(col(R.color.muted)) })
                    else -> lyricsBox.addView(TextView(this).apply { text = "Testo non trovato"; textSize = 13f; setTextColor(col(R.color.muted)) })
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
            if (info.third != null) Img.load(info.third!!, art) else art.setImageDrawable(null)
            buildChips()
            lyricsBox.removeAllViews(); lines = emptyList(); lineViews = emptyList()
            if (web && info.first.isNotBlank()) loadLyrics(info.first, info.second, webState.durMs / 1000)
        }
        play.text = if (isPlayingNow()) "⏸" else "▶"
        back.visibility = if (web) View.GONE else View.VISIBLE; fwd.visibility = if (web) View.GONE else View.VISIBLE
        val c = ctl
        val pos: Long; val dur: Long; val live: Boolean
        if (web) { pos = webState.posMs; dur = webState.durMs; live = false }
        else { live = c?.isCurrentMediaItemLive == true || (c?.duration ?: C.TIME_UNSET) == C.TIME_UNSET; pos = c?.currentPosition ?: 0; dur = if (live) 0 else c?.duration ?: 0 }
        seek.visibility = if (live) View.INVISIBLE else View.VISIBLE
        posT.text = if (live) "🔴 In diretta" else clockText(pos); durT.text = if (live) "" else clockText(dur)
        if (!dragging && dur > 0) { seek.max = dur.toInt().coerceAtMost(Int.MAX_VALUE); seek.progress = pos.toInt().coerceAtMost(seek.max) }
        clock.text = SimpleDateFormat("HH:mm", Locale.ITALIAN).format(Date())
        // testo sincronizzato
        if (lines.isNotEmpty() && lineViews.isNotEmpty()) {
            val idx = Lyrics.activeIndex(lines, pos).coerceAtMost(lineViews.size - 1)
            if (idx != activeLine) {
                activeLine = idx
                lineViews.forEachIndexed { i, v -> v.setTextColor(if (i == idx) col(R.color.text) else col(R.color.muted)); v.textSize = if (i == idx) 19f else 16f; v.setTypeface(null, if (i == idx) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL) }
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
