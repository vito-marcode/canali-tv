package it.mdv.dttchannels

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/**
 * Channel list + EPG cache, synced with the Raspberry Pi companion (dtt-sync).
 * Files: files/channels.json (Pi /channels.json format), files/epg.json (Pi /epg.json format).
 */
class Repository(private val ctx: Context) {
    companion object {
        const val TAG = "DttRepo"
        val URI: Uri = Uri.parse("content://it.mdv.dttchannels/channels")
        const val ACTION_UPDATED = "it.mdv.dttchannels.CHANNELS_UPDATED"

        fun piUrl(ctx: Context): String =
            ctx.getSharedPreferences("dtt", 0).getString("pi", BuildConfig.PI_URL) ?: BuildConfig.PI_URL

        /**
         * Used by the ContentProvider too (no instance state). The Pi's channels.json when there is one; otherwise the
         * TV provider's broadcast channels (a box with no Pi). Either way only the LCNs in the "lcns" setting, if set.
         */
        fun readChannels(ctx: Context): List<Channel> {
            val f = File(ctx.filesDir, "channels.json")
            val all = if (!f.exists()) systemChannels(ctx)
                else try { parseChannels(JSONObject(f.readText()), piUrl(ctx)) } catch (e: Exception) {
                    Log.w(TAG, "bad channels.json", e); emptyList()
                }
            val keep = lcnFilter(ctx)
            return if (keep.isEmpty()) all else all.filter { it.lcn in keep }.distinctBy { it.lcn }
        }

        /** "lcns" setting: the channel numbers to show ("1,2,3,26"), empty for all. Set with MainActivity --es lcns. */
        fun lcnFilter(ctx: Context): Set<String> =
            ctx.getSharedPreferences("dtt", 0).getString("lcns", "").orEmpty()
                .split(',', ' ').map { it.trim().trimStart('0') }.filter { it.isNotEmpty() }.toSet()

        /**
         * Broadcast channels straight from the TV provider (needs READ_TV_LISTINGS): for a box without the Pi companion,
         * e.g. a TIMVISION box whose Sagemcom input publishes its DVB-T services there. Logos come from the provider.
         */
        private fun systemChannels(ctx: Context): List<Channel> {
            val out = ArrayList<Channel>()
            val logos = OnlineGuide.logos(ctx)               // tv-logos by name, as the Pi picks them
            try {
                ctx.contentResolver.query(TvContract.Channels.CONTENT_URI, arrayOf(
                    TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER, TvContract.Channels.COLUMN_DISPLAY_NAME,
                    TvContract.Channels.COLUMN_INPUT_ID, TvContract.Channels.COLUMN_TYPE, TvContract.Channels.COLUMN_SERVICE_TYPE,
                    TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID, TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID,
                    TvContract.Channels.COLUMN_SERVICE_ID, TvContract.Channels.COLUMN_BROWSABLE,
                ), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val type = c.getString(4).orEmpty()
                        if (!type.startsWith("TYPE_DVB") && !type.startsWith("TYPE_ATSC") && !type.startsWith("TYPE_ISDB")) continue
                        val id = c.getLong(0)
                        out += Channel(
                            id = id, lcn = c.getString(1).orEmpty(), name = c.getString(2).orEmpty(),
                            inputId = c.getString(3).orEmpty(), uri = TvContract.buildChannelUri(id).toString(),
                            radio = c.getString(5) == TvContract.Channels.SERVICE_TYPE_AUDIO,
                            logo = logos[c.getString(1).orEmpty()] ?: TvContract.buildChannelLogoUri(id).toString(),
                            onid = c.getInt(6), tsid = c.getInt(7), sid = c.getInt(8), browsable = c.getInt(9) != 0,
                        )
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "no READ_TV_LISTINGS: pm grant it.mdv.dttchannels android.permission.READ_TV_LISTINGS", e)
            } catch (e: Exception) {
                Log.w(TAG, "system channels", e)
            }
            return out.filter { it.browsable }.sortedWith(compareBy({ it.lcnInt }, { it.lcn }))
        }

        private fun parseChannels(doc: JSONObject, pi: String): List<Channel> {
            val a = doc.optJSONArray("channels") ?: return emptyList()
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val logo = o.optString("logo", "").ifEmpty { null }?.let { if (it.startsWith("/")) pi + it else it }
                Channel(
                    id = o.getLong("id"), lcn = o.optString("number"), name = o.optString("name"),
                    inputId = o.optString("input_id"), uri = o.optString("channel_uri"),
                    radio = o.optBoolean("radio") || o.optString("service_type") == "SERVICE_TYPE_AUDIO",
                    logo = logo, onid = o.optInt("onid"), tsid = o.optInt("tsid"), sid = o.optInt("sid"),
                    browsable = o.optBoolean("browsable", true),
                )
            }.filter { it.browsable }.sortedWith(compareBy({ it.lcnInt }, { it.lcn }))
        }
    }

    fun channels(): List<Channel> = readChannels(ctx)

    fun channelsGenerated(): String? = try {
        JSONObject(File(ctx.filesDir, "channels.json").readText()).optString("generated")
    } catch (e: Exception) { null }

    /**
     * Streaming parse of epg.json (54 h × every channel: several MB). Far cheaper in time and memory
     * than building an org.json tree; still call it off the main thread.
     */
    /** No Pi on this box: channels from the TV provider, guide and logos built here (OnlineGuide). */
    private val noPi: Boolean get() = !File(ctx.filesDir, "channels.json").exists()

    /**
     * Periodic refresh: from the Pi, or without one the online guide and logos built on the box. Blocking.
     * Returns which of channels / guide changed.
     */
    fun refresh(channels: List<Channel>): Pair<Boolean, Boolean> =
        if (noPi) OnlineGuide.refresh(ctx, channels).also { (logos, _) -> if (logos) notifyUpdated(-1) } else refreshFromPi()

    fun epg(): Map<Long, List<Programme>> {
        val f = File(ctx.filesDir, "epg.json")
        if (!f.exists()) return systemEpg()
        // no Pi: the XMLTV guide, plus the input's own now/next for the channels the XMLTV doesn't cover
        if (noPi) return systemEpg() + epgFile(f)
        return epgFile(f)
    }

    private fun epgFile(f: File): Map<Long, List<Programme>> {
        return try {
            JsonReader(f.bufferedReader(bufferSize = 64 * 1024)).use { r ->
                val pool = StringPool()
                val out = HashMap<Long, List<Programme>>()
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "channels") { r.skipValue(); continue }
                    r.beginObject()
                    while (r.hasNext()) {
                        val id = r.nextName().toLongOrNull()
                        if (id == null) { r.skipValue(); continue }
                        val list = ArrayList<Programme>()
                        r.beginArray()
                        while (r.hasNext()) readProgramme(r, pool)?.let(list::add)
                        r.endArray()
                        list.sortBy { it.start }
                        list.trimToSize()
                        out[id] = list
                    }
                    r.endObject()
                }
                r.endObject()
                out
            }
        } catch (e: Exception) { Log.w(TAG, "bad epg.json", e); emptyMap() }
    }

    /**
     * Parse-local dedup: series repeat their title, synopsis and artwork URL across the 54 h, and the Pi
     * reuses one image per series (≈1.3 k distinct URLs for ≈8.4 k programmes). One copy each on the heap.
     */
    private class StringPool {
        private val map = HashMap<String, String>(4096)
        operator fun invoke(s: String): String = map.getOrPut(s) { s }
    }

    /**
     * No Pi: the guide the broadcast input itself publishes to the TV provider (DVB EIT, e.g. the TIMVISION box's
     * Sagemcom input), 6 h back to 48 h ahead, keyed by channel id like epg.json. Same string pooling.
     */
    private fun systemEpg(): Map<Long, List<Programme>> {
        val now = System.currentTimeMillis()
        val out = HashMap<Long, ArrayList<Programme>>()
        val pool = StringPool()
        try {
            ctx.contentResolver.query(TvContract.Programs.CONTENT_URI, arrayOf(
                TvContract.Programs.COLUMN_CHANNEL_ID, TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
                TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, TvContract.Programs.COLUMN_TITLE,
                TvContract.Programs.COLUMN_SHORT_DESCRIPTION, TvContract.Programs.COLUMN_LONG_DESCRIPTION,
                TvContract.Programs.COLUMN_CANONICAL_GENRE, TvContract.Programs.COLUMN_POSTER_ART_URI,
                TvContract.Programs.COLUMN_EPISODE_TITLE,
            ), null, null, null)?.use { c ->   // no selection: the provider refuses one from a non-system app
                val from = (now - 6 * 3600_000L) / 1000; val to = (now + 48 * 3600_000L) / 1000
                while (c.moveToNext()) {
                    val s = c.getLong(1) / 1000; val e = c.getLong(2) / 1000
                    if (e <= s || e <= from || s >= to) continue
                    val desc = c.getString(5)?.takeIf { it.isNotBlank() } ?: c.getString(4).orEmpty()
                    val genre = TvContract.Programs.Genres.decode(c.getString(6).orEmpty()).firstOrNull().orEmpty()
                    out.getOrPut(c.getLong(0)) { ArrayList() } += Programme(s, e, pool(c.getString(3).orEmpty()),
                        pool(genre), pool(desc), c.getString(7)?.takeIf { it.isNotBlank() }?.let(pool::invoke),
                        c.getString(8)?.takeIf { it.isNotBlank() }?.let(pool::invoke))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "system epg", e)
        }
        val nowSec = now / 1000
        Log.i(TAG, "system epg: ${out.size} channels, ${out.values.sumOf { it.size }} programmes, on now for " +
            "${out.values.count { l -> l.any { nowSec in it.start until it.end } }}, until " +
            "${(out.values.maxOfOrNull { l -> l.maxOf { it.end } } ?: nowSec).minus(nowSec) / 3600} h ahead")
        return out.mapValues { (_, l) -> l.sortedBy { it.start } }
    }

    private fun readProgramme(r: JsonReader, pool: StringPool): Programme? {
        var s = -1L; var e = -1L
        var t = ""; var g = ""; var d = ""; var i: String? = null; var st: String? = null
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            if (r.peek() == JsonToken.NULL) { r.nextNull(); continue }
            when (name) {
                "s" -> s = r.nextLong()
                "e" -> e = r.nextLong()
                "t" -> t = pool(r.nextString())
                "g" -> g = pool(r.nextString())             // a few dozen distinct genres
                "d" -> d = pool(r.nextString())
                "i" -> i = r.nextString().ifEmpty { null }?.let(pool::invoke)
                "st" -> st = r.nextString().ifEmpty { null }?.let(pool::invoke)
                else -> r.skipValue()
            }
        }
        r.endObject()
        return if (s >= 0 && e > s) Programme(s, e, t, g, d, i, st) else null
    }

    fun epgAgeSeconds(): Long {
        val f = File(ctx.filesDir, "epg.json")
        return if (f.exists()) (System.currentTimeMillis() - f.lastModified()) / 1000 else Long.MAX_VALUE
    }

    /** Full sync: the Pi exports the box's channel list (adb backup) and rebuilds EPG + logos. Blocking. */
    fun syncAll(): Int {
        val body = http("POST", "/sync", 240_000)
        val doc = JSONObject(body)
        if (doc.has("error")) throw IllegalStateException(doc.getString("error"))
        save("channels.json", body)
        refreshEpg()
        notifyUpdated(doc.optInt("count"))
        return doc.optInt("count")
    }

    /**
     * Light refresh: download the current list + EPG the Pi already has. Blocking.
     * Returns which files actually changed, so callers only re-parse what is new.
     */
    fun refreshFromPi(): Pair<Boolean, Boolean> {
        val body = http("GET", "/channels.json", 30_000)
        val chChanged = JSONObject(body).has("channels") && save("channels.json", body)
        if (chChanged) notifyUpdated(-1)
        return chChanged to refreshEpg()
    }

    /** True when epg.json changed. Validated cheaply: the body is MBs, no need to build a JSON tree here. */
    fun refreshEpg(): Boolean {
        val body = http("GET", "/epg.json", 60_000)
        return body.contains("\"channels\"") && save("epg.json", body)
    }

    /**
     * Technicolor's scan app crashes while timvision.telemetry is disabled: the Pi enables it for the
     * scan and disables it again afterwards (and by itself after 30 min). Blocking.
     */
    fun scanPrepare() { http("POST", "/scan/prepare", 30_000) }
    fun scanDone() { http("POST", "/scan/done", 30_000) }

    private fun notifyUpdated(count: Int) {
        ctx.contentResolver.notifyChange(URI, null)
        ctx.sendBroadcast(Intent(ACTION_UPDATED).putExtra("count", count))
    }

    /** Atomic write; returns false (and only touches the mtime) when the content is unchanged. */
    private fun save(name: String, body: String): Boolean {
        val f = File(ctx.filesDir, name)
        val bytes = body.toByteArray()
        if (f.exists() && f.length() == bytes.size.toLong() && f.readBytes().contentEquals(bytes)) {
            f.setLastModified(System.currentTimeMillis())   // still fresh: keeps epgAgeSeconds() honest
            return false
        }
        val tmp = File(ctx.filesDir, "$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) throw IllegalStateException("cannot save $name")
        return true
    }

    private fun http(method: String, path: String, readTimeout: Int): String {
        val con = URL(piUrl(ctx) + path).openConnection() as HttpURLConnection
        con.requestMethod = method
        con.connectTimeout = 8000
        con.readTimeout = readTimeout
        con.setRequestProperty("Accept-Encoding", "gzip")
        if (method == "POST") { con.doOutput = true; con.outputStream.close() }
        val code = con.responseCode
        val raw: InputStream = (if (code < 400) con.inputStream else con.errorStream) ?: throw IllegalStateException("HTTP $code")
        val stream = if (con.contentEncoding == "gzip") GZIPInputStream(raw) else raw
        val text = stream.bufferedReader().use { it.readText() }
        if (code >= 400) throw IllegalStateException(runCatching { JSONObject(text).optString("error") }.getOrNull() ?: "HTTP $code")
        return text
    }
}
