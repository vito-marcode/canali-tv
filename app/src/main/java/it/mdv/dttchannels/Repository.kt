package it.mdv.dttchannels

import android.content.Context
import android.content.Intent
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

        /** Used by the ContentProvider too (no instance state). */
        fun readChannels(ctx: Context): List<Channel> {
            val f = File(ctx.filesDir, "channels.json")
            if (!f.exists()) return emptyList()
            return try { parseChannels(JSONObject(f.readText()), piUrl(ctx)) } catch (e: Exception) {
                Log.w(TAG, "bad channels.json", e); emptyList()
            }
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
    fun epg(): Map<Long, List<Programme>> {
        val f = File(ctx.filesDir, "epg.json")
        if (!f.exists()) return emptyMap()
        return try {
            JsonReader(f.bufferedReader(bufferSize = 64 * 1024)).use { r ->
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
                        while (r.hasNext()) readProgramme(r)?.let(list::add)
                        r.endArray()
                        list.sortBy { it.start }
                        out[id] = list
                    }
                    r.endObject()
                }
                r.endObject()
                out
            }
        } catch (e: Exception) { Log.w(TAG, "bad epg.json", e); emptyMap() }
    }

    private fun readProgramme(r: JsonReader): Programme? {
        var s = -1L; var e = -1L
        var t = ""; var g = ""; var d = ""; var i: String? = null; var st: String? = null
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            if (r.peek() == JsonToken.NULL) { r.nextNull(); continue }
            when (name) {
                "s" -> s = r.nextLong()
                "e" -> e = r.nextLong()
                "t" -> t = r.nextString()
                "g" -> g = r.nextString().intern()          // a few dozen distinct genres
                "d" -> d = r.nextString()
                "i" -> i = r.nextString().ifEmpty { null }
                "st" -> st = r.nextString().ifEmpty { null }
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
