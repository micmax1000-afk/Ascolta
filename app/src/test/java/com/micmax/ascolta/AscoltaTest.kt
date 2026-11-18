package com.micmax.ascolta

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AscoltaTest {
    @Test fun opmlRoundTrip() {
        val list = listOf(Podcast("https://a.it/feed?x=1&y=2", "Il Post & co", "A", null), Podcast("https://b.it/rss", "Storie \"Libere\"", "B", null))
        val xml = Opml.build(list)
        val back = Opml.parse(xml)
        assertEquals(listOf("Il Post & co" to "https://a.it/feed?x=1&y=2", "Storie \"Libere\"" to "https://b.it/rss"), back)
        assertTrue(Opml.parse("<opml><body><outline text='Solo titolo'/></body></opml>").isEmpty())
    }

    @Test fun webStateMessages() {
        val s = MusicWeb.parseState("""{"t":"state","playing":true,"title":"Canzone","artist":"Artista","art":"http://x/a.jpg","pos":12345,"dur":200000}""")!!
        assertTrue(s.playing); assertEquals("Canzone", s.title); assertEquals(12345L, s.posMs); assertTrue(s.has)
        assertNull(MusicWeb.parseState("""{"t":"altro"}""")); assertNull(MusicWeb.parseState("non json")); assertNull(MusicWeb.parseState(null))
        assertFalse(WebState().has)
        assertEquals("window.__asCmd && window.__asCmd('seek', 5000);", MusicWeb.command("seek", 5000))
    }

    @Test fun musicHosts() {
        assertTrue(MusicWeb.isMusicPageHost("https://music.youtube.com/watch?v=1")); assertTrue(MusicWeb.isMusicPageHost("https://accounts.google.com/signin"))
        assertFalse(MusicWeb.isMusicPageHost("https://www.youtube.com/watch?v=1")); assertFalse(MusicWeb.isMusicPageHost("https://example.com")); assertFalse(MusicWeb.isMusicPageHost(null))
    }

    @Test fun radioAlarmNext() {
        val fri = java.util.Calendar.getInstance().apply { set(2026, 10, 20, 8, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis   // venerdì 20 novembre 2026
        val cal = { t: Long -> java.util.Calendar.getInstance().apply { timeInMillis = t } }
        assertEquals(java.util.Calendar.SATURDAY, cal(RadioAlarm.next(fri, 7, 0, false)).get(java.util.Calendar.DAY_OF_WEEK))
        assertEquals(java.util.Calendar.MONDAY, cal(RadioAlarm.next(fri, 7, 0, true)).get(java.util.Calendar.DAY_OF_WEEK))
        assertEquals(java.util.Calendar.FRIDAY, cal(RadioAlarm.next(fri, 9, 30, true)).get(java.util.Calendar.DAY_OF_WEEK))
    }

    @Test fun lyricsLrc() {
        val lines = Lyrics.parseLrc("[00:10.50] Prima riga\n[00:20.00] Seconda\n[01:05.25] Terza\nnoise")
        assertEquals(3, lines.size); assertEquals(10_500L, lines[0].timeMs); assertEquals(65_250L, lines[2].timeMs)
        assertEquals(-1, Lyrics.activeIndex(lines, 5_000)); assertEquals(0, Lyrics.activeIndex(lines, 15_000)); assertEquals(2, Lyrics.activeIndex(lines, 70_000))
        assertEquals("Artista" to "Canzone", Lyrics.cleanQuery("Canzone (Official Video)", "Artista - Topic"))
    }

    @Test fun clocks() {
        assertEquals("0:09", clockText(9_000)); assertEquals("12:05", clockText(725_000)); assertEquals("1:02:03", clockText(3_723_000)); assertEquals("0:00", clockText(-5))
    }

    @Test fun backupData() {
        val d = Backup.Data(1, listOf(Podcast("https://f", "P", "A", null)), listOf(RadioStation("i", "R", "https://s", null, "pop", "Lazio", 128)),
            listOf(Episode("ep:1", "P", "https://f", "E", "https://a.mp3", 5, 60, null, "")))
        assertEquals(d, Gson().fromJson(Gson().toJson(d), Backup.Data::class.java))
        assertTrue(Gson().fromJson("""{"version":1}""", Backup.Data::class.java).subs.isEmpty())
    }
}
