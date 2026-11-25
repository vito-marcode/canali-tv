package it.mdv.dttchannels

import android.content.Context
import android.util.Log
import android.util.Xml
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/**
 * The Pi companion's guide and logos, built on the box itself when there is no Pi (a box that takes its channels from
 * the TV provider, e.g. the TIMVISION box, whose input publishes only now/next for the multiplex in use).
 * Same sources and matching as companion/dtt_sync.py: the Italian XMLTV of epgshare01, channels matched by
 * normalised name or by LCN in overrides.json (bundled asset), logos from tv-logo/tv-logos. Writes epg.json in the
 * Pi's format (keyed by this box's channel ids) and logos.json (LCN -> logo URL). Blocking: call off the main thread.
 */
object OnlineGuide {
    private const val TAG = "DttGuide"
    private const val XMLTV_URL = "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz"
    private const val LOGO_INDEX = "https://api.github.com/repos/tv-logo/tv-logos/contents/countries/italy"
    private const val LOGO_RAW = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/"
    private val DROP = setOf("hd", "fhd", "uhd", "4k", "sd", "tv", "it", "italia")

    /** dtt_sync.norm(): "Rai 3 TGR Marche" -> "rai3", "Canale5 HD" -> "canale5", "Rai 4" -> "rai4". */
    fun norm(name: String?): String {
        var s = Normalizer.normalize(name.orEmpty(), Normalizer.Form.NFKD).replace(Regex("[^\\p{ASCII}]"), "").lowercase()
        s = s.replace(Regex("\\btgr\\b.*$"), "").replace("+", " plus ")
        val words = Regex("[a-z0-9]+").findAll(s).map { it.value }.toMutableList()
        while (words.size > 1 && words.last() in DROP) words.removeAt(words.lastIndex)
        return words.joinToString("")
    }

    private class Override(val epg: String?, val logo: String?)

    private fun overrides(ctx: Context): Map<String, Override> = try {
        val o = JSONObject(ctx.assets.open("overrides.json").bufferedReader().readText())
        o.keys().asSequence().associateWith { k ->
            val v = o.getJSONObject(k); Override(v.optString("epg").ifEmpty { null }, v.optString("logo").ifEmpty { null })
        }
    } catch (e: Exception) { emptyMap() }

    /** Returns (logos changed, guide changed). */
    fun refresh(ctx: Context, channels: List<Channel>): Pair<Boolean, Boolean> {
        if (channels.isEmpty()) return false to false
        val ovr = overrides(ctx)
        val logos = runCatching { refreshLogos(ctx, channels, ovr) }.onFailure { Log.w(TAG, "logos", it) }.getOrDefault(false)
        val epg = runCatching { refreshEpg(ctx, channels, ovr) }.onFailure { Log.w(TAG, "guide", it) }.getOrDefault(false)
        return logos to epg
    }

    // ---------------------------------------------------------------- logos

    /** LCN -> logo URL, written by [refresh]; read by Repository for channels from the TV provider. */
    fun logos(ctx: Context): Map<String, String> = try {
        val o = JSONObject(File(ctx.filesDir, "logos.json").readText())
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) { emptyMap() }

    private fun refreshLogos(ctx: Context, channels: List<Channel>, ovr: Map<String, Override>): Boolean {
        val out = File(ctx.filesDir, "logos.json")
        val cache = File(ctx.filesDir, "logo_index.json")
        // the index changes rarely: once a week (and GitHub's API is rate-limited)
        val index: List<String> = if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 7 * 86_400_000L) {
            JSONArray(cache.readText()).let { a -> (0 until a.length()).map { a.getString(it) } }
        } else {
            val a = JSONArray(get(LOGO_INDEX, 30_000)?.decodeToString() ?: return false)
            val names = (0 until a.length()).map { a.getJSONObject(it).optString("name") }.filter { it.endsWith(".png") }
            cache.writeText(JSONArray(names).toString()); names
        }
        val byNorm = HashMap<String, String>()
        for (f in index) byNorm.putIfAbsent(norm(if (f.endsWith("-it.png")) f.removeSuffix("-it.png") else f.removeSuffix(".png")), f)
        val map = JSONObject()
        for (c in channels) {
            val f = ovr[c.lcn]?.logo ?: byNorm[norm(c.name)] ?: continue
            map.put(c.lcn, LOGO_RAW + f)
        }
        val text = map.toString()
        if (out.exists() && out.readText() == text) return false
        out.writeText(text)
        return true
    }

    // ---------------------------------------------------------------- guide

    private class Prog(val s: Long, val e: Long, val t: String, val g: String, val d: String, val i: String, val st: String?)

    private fun refreshEpg(ctx: Context, channels: List<Channel>, ovr: Map<String, Override>): Boolean {
        val prefs = ctx.getSharedPreferences("dtt", 0)
        val epgFile = File(ctx.filesDir, "epg.json")
        // epg.json is keyed by channel id, and a channel scan renumbers every id: a different channel list
        // rebuilds the guide even when the XMLTV itself has not changed
        val sig = channels.joinToString(",") { "${it.id}:${it.lcn}:${it.name}" }.hashCode().toString()
        val sameChannels = prefs.getString("xmltv_channels", null) == sig
        val etag = if (epgFile.exists() && sameChannels) prefs.getString("xmltv_etag", null) else null
        val con = URL(XMLTV_URL).openConnection() as HttpURLConnection
        con.connectTimeout = 15_000; con.readTimeout = 120_000
        con.setRequestProperty("User-Agent", "canali-tv")
        etag?.let { con.setRequestProperty("If-None-Match", it) }
        if (con.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
            epgFile.setLastModified(System.currentTimeMillis())        // still fresh (epgAgeSeconds)
            return false
        }
        if (con.responseCode != 200) throw IllegalStateException("XMLTV HTTP ${con.responseCode}")
        val t0 = System.nanoTime()

        // which XMLTV channels can feed which local channels: LCN override first, else any display name / id that
        // normalises to the channel's name (the duplicate with most programmes wins, as on the Pi)
        val wantedNorm = HashMap<String, MutableList<Channel>>()
        val byOverride = HashMap<String, MutableList<Channel>>()
        for (c in channels) {
            val o = ovr[c.lcn]?.epg
            if (o != null) byOverride.getOrPut(o) { ArrayList() } += c else wantedNorm.getOrPut(norm(c.name)) { ArrayList() } += c
        }
        val candidateNorms = HashMap<String, MutableSet<String>>()      // xmltv id -> norms it can stand for
        val progs = HashMap<String, ArrayList<Prog>>()
        val now = System.currentTimeMillis() / 1000
        val lo = now - 6 * 3600; val hi = now + 48 * 3600

        val p = Xml.newPullParser()
        GZIPInputStream(con.inputStream.buffered(64 * 1024), 64 * 1024).use { input ->
            p.setInput(input, "UTF-8")
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "channel") {
                    val id = p.getAttributeValue(null, "id").orEmpty()
                    val names = mutableListOf(id.removeSuffix(".it").replace('.', ' '))
                    while (!(p.next() == XmlPullParser.END_TAG && p.name == "channel")) {
                        if (p.eventType == XmlPullParser.START_TAG && p.name == "display-name") names += p.nextText()
                    }
                    for (n in names) { val k = norm(n); if (k.isNotEmpty() && k in wantedNorm) candidateNorms.getOrPut(id) { HashSet() } += k }
                } else if (ev == XmlPullParser.START_TAG && p.name == "programme") {
                    val ch = p.getAttributeValue(null, "channel").orEmpty()
                    if (ch !in candidateNorms && ch !in byOverride) { skip(p); ev = p.next(); continue }
                    val s = xmltvTime(p.getAttributeValue(null, "start")); val e = xmltvTime(p.getAttributeValue(null, "stop"))
                    var title = ""; var cat = ""; var desc = ""; var icon = ""; var sub: String? = null
                    while (!(p.next() == XmlPullParser.END_TAG && p.name == "programme")) {
                        if (p.eventType != XmlPullParser.START_TAG) continue
                        when (p.name) {
                            "title" -> title = p.nextText()
                            "category" -> { val v = p.nextText(); if (cat.isEmpty()) cat = v }
                            "desc" -> desc = p.nextText().trim()
                            "sub-title" -> sub = p.nextText().ifBlank { null }
                            "icon" -> icon = p.getAttributeValue(null, "src").orEmpty()
                        }
                    }
                    if (e > s && e >= lo && s <= hi) progs.getOrPut(ch) { ArrayList() } += Prog(s, e, title, cat, desc, icon, sub)
                }
                ev = p.next()
            }
        }
        con.headerFields["ETag"]?.firstOrNull()?.let { prefs.edit().putString("xmltv_etag", it).apply() }
        prefs.edit().putString("xmltv_channels", sig).apply()

        // best XMLTV id per norm: the one with most programmes
        val bestForNorm = HashMap<String, String>()
        for ((id, norms) in candidateNorms) for (k in norms) {
            val cur = bestForNorm[k]
            if (cur == null || (progs[id]?.size ?: 0) > (progs[cur]?.size ?: 0)) bestForNorm[k] = id
        }
        val out = JSONObject(); val chans = JSONObject()
        fun emit(id: String, targets: List<Channel>) {
            val list = progs[id]?.sortedBy { it.s } ?: return
            val arr = JSONArray()
            for (x in list) arr.put(JSONObject().put("s", x.s).put("e", x.e).put("t", x.t).put("g", x.g).put("d", x.d)
                .put("i", x.i).apply { x.st?.let { put("st", it) } })
            for (c in targets) chans.put(c.id.toString(), arr)
        }
        for ((k, targets) in wantedNorm) bestForNorm[k]?.let { emit(it, targets) }
        for ((id, targets) in byOverride) emit(id, targets)
        out.put("generated", now).put("source", XMLTV_URL).put("window", JSONArray().put(lo).put(hi)).put("channels", chans)
        val tmp = File(ctx.filesDir, "epg.json.tmp")
        tmp.writeText(out.toString())
        if (!tmp.renameTo(epgFile)) throw IllegalStateException("cannot save epg.json")
        Log.i(TAG, "guide: ${chans.length()} of ${channels.size} channels in ${(System.nanoTime() - t0) / 1_000_000} ms")
        return true
    }

    private fun skip(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) when (p.next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
        }
    }

    /** "20261125173000 +0100" -> epoch seconds. */
    private fun xmltvTime(v: String?): Long {
        val s = v?.trim() ?: return 0
        if (s.length < 14) return 0
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(s.substring(0, 4).toInt(), s.substring(4, 6).toInt() - 1, s.substring(6, 8).toInt(),
            s.substring(8, 10).toInt(), s.substring(10, 12).toInt(), s.substring(12, 14).toInt())
        var epoch = cal.timeInMillis / 1000
        val tz = s.substring(14).trim()
        if (tz.length == 5) {
            val off = (tz.substring(1, 3).toInt() * 3600 + tz.substring(3, 5).toInt() * 60) * if (tz[0] == '-') -1 else 1
            epoch -= off
        }
        return epoch
    }

    private fun get(url: String, timeout: Int): ByteArray? {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 15_000; con.readTimeout = timeout
        con.setRequestProperty("User-Agent", "canali-tv")
        return if (con.responseCode == 200) con.inputStream.use { it.readBytes() } else null
    }
}
