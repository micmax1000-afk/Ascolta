package com.micmax.ascolta

import android.content.Context
import com.google.gson.Gson

/** I miei dati in un file solo: podcast che segui, radio preferite, coda. Si legge anche un file OPML. */
object Backup {
    data class Data(val version: Int = 1, val subs: List<Podcast> = emptyList(), val radioFavs: List<RadioStation> = emptyList(), val queue: List<Episode> = emptyList())

    fun export(c: Context): String {
        val r = AudioRepository(c)
        return Gson().toJson(Data(1, r.subscriptions(), r.favoriteStations(), AudioQueue.list(c)))
    }

    /** Unisce il contenuto del file (JSON di Ascolta oppure OPML) a quello che c'è; null se non è riconosciuto. */
    fun importText(c: Context, text: String): String? {
        val r = AudioRepository(c)
        val t = text.trim()
        if (t.startsWith("<")) {
            val found = Opml.parse(t)
            if (found.isEmpty()) return null
            val have = r.subscriptions().map { it.feedUrl }.toSet()
            val add = found.filter { it.second !in have }.map { (title, url) -> Podcast(url, title.ifBlank { "Podcast" }, "", null) }
            add.forEach { r.subscribe(it) }
            return "${add.size} podcast aggiunti"
        }
        val d = runCatching { Gson().fromJson(t, Data::class.java) }.getOrNull() ?: return null
        if (d.subs.isEmpty() && d.radioFavs.isEmpty() && d.queue.isEmpty()) return null
        val haveSubs = r.subscriptions().map { it.feedUrl }.toSet(); val newSubs = d.subs.filter { it.feedUrl !in haveSubs }
        newSubs.forEach { r.subscribe(it) }
        val haveFavs = r.favoriteStations().map { it.id }.toSet(); val newFavs = d.radioFavs.filter { it.id !in haveFavs }
        if (newFavs.isNotEmpty()) r.setFavoriteStations(r.favoriteStations() + newFavs)
        d.queue.forEach { AudioQueue.add(c, it) }
        return "${newSubs.size} podcast, ${newFavs.size} radio, ${d.queue.size} in coda"
    }
}
