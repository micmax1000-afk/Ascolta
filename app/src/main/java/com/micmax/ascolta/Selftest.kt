package com.micmax.ascolta

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Dopo un riavvio la sveglia con la radio si rimette da sola. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: android.content.Context, i: android.content.Intent) { RadioAlarm.schedule(c); PodcastAuto.schedule(c) }
}

/** Prove automatiche: dati finti, un suono vero (silenzio) e le schermate. */
object Selftest {
    private const val TAG = "ASCOLTA_SELFTEST"

    /** Un file WAV di silenzio, per provare il lettore senza internet. */
    fun silence(dir: File, seconds: Int = 20): File {
        val f = File(dir, "prova-silenzio.wav")
        if (f.exists()) return f
        val rate = 8000; val data = ByteArray(rate * 2 * seconds)
        val b = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        b.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data.size).put(data)
        f.writeBytes(b.array()); return f
    }

    fun seed(a: MainActivity) {
        val wav = silence(a.cacheDir)
        val feed = "https://example.com/podcast-di-prova.xml"
        val ep = Episode("ep:prova1", "Podcast di prova", feed, "Prima puntata di prova", "file://" + wav.absolutePath, System.currentTimeMillis() - 3_600_000, 1500, null, "")
        val ep2 = ep.copy(id = "ep:prova2", title = "Seconda puntata di prova", time = System.currentTimeMillis() - 86_400_000)
        a.repo.subscribe(Podcast(feed, "Podcast di prova", "Autore di prova", null))
        AudioStore.addRecent(a, ep)
        AudioQueue.add(a, ep2)
        val st = RadioStation("st1", "Radio di prova", "https://example.com/stream", null, "musica,pop", "Lazio", 128)
        if (a.repo.favoriteStations().none { it.id == st.id }) a.repo.toggleFavorite(st)
    }

    fun run(a: MainActivity, action: String) {
        if (action == "backup") {
            seed(a)
            val json = Backup.export(a)
            val r = Backup.importText(a, json)
            val opml = Backup.importText(a, Opml.build(a.repo.subscriptions()))
            Log.i(TAG, "BACKUP bytes=${json.length} json=$r opml=$opml")
            return
        }
        if (action == "play") {
            seed(a)
            val ep = AudioStore.recent(a).first { it.id == "ep:prova1" }
            a.playEpisode(ep)
            Handler().postDelayed({
                val c = a.ctl
                Log.i(TAG, "PLAY connesso=${c != null} stato=${c?.playbackState} suona=${c?.isPlaying} voci=${c?.mediaItemCount} titolo=${c?.mediaMetadata?.title}")
                a.openPlayer()
            }, 7000)
            return
        }
        seed(a)
        a.runOnUiThread {
            when (action) {
                "home" -> a.showTab(0)
                "music" -> a.showTab(1)
                "podcast" -> a.showTab(2)
                "radio" -> a.showTab(3)
                "queue" -> a.openQueue()
                "settings" -> a.openSettings()
                "alarm" -> a.openRadioAlarm()
                "episodes" -> a.openPodcast(a.repo.subscriptions().first())
                else -> {}
            }
        }
    }

    private class Handler { fun postDelayed(f: () -> Unit, ms: Long) = android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(f, ms) }
}
