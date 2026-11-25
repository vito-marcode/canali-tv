package it.mdv.dttchannels

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.io.File

/**
 * Read-only, world-readable channel list (contract kept from v1):
 *   content://it.mdv.dttchannels/channels            all channels
 *   content://it.mdv.dttchannels/channels?number=5   one channel by LCN
 *   content://it.mdv.dttchannels/channels?q=metti%20rai%20due[&q=...]
 *                                                    the channel a spoken phrase names (VoiceMatch; the first of
 *                                                    several hypotheses that matches), or no row. The launcher's
 *                                                    voice command uses it to tell a channel from an app.
 * Columns: _id, number, name, type, onid, tsid, sid, input_id, channel_uri, browsable, radio, logo_uri
 */
class ChannelProvider : ContentProvider() {
    private val cols = arrayOf("_id", "number", "name", "type", "onid", "tsid", "sid", "input_id",
        "channel_uri", "browsable", "radio", "logo_uri")

    override fun onCreate() = true

    /** channels.json parsed once per change (the launcher asks on every voice command): also keeps VoiceMatch's index. */
    private var cached: Pair<Long, List<Channel>>? = null

    @Synchronized private fun channels(): List<Channel> {
        val f = File(context!!.filesDir, "channels.json")
        // channels.json (Pi) or, without it, the TV provider's list; the "lcns" filter changes the result too
        val stamp = (f.lastModified() * 31 + f.length()) * 31 + Repository.lcnFilter(context!!).hashCode()
        cached?.takeIf { it.first == stamp }?.let { return it.second }
        return Repository.readChannels(context!!).also { cached = stamp to it }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val c = MatrixCursor(cols)
        val only = uri.getQueryParameter("number")
        val phrases = uri.getQueryParameters("q").filter { it.isNotBlank() }
        val all = channels()
        val list = if (phrases.isEmpty()) all
            else listOfNotNull(synchronized(this) { phrases.firstNotNullOfOrNull { VoiceMatch.find(it, all) } })
        for (ch in list) {
            if (only != null && only != ch.lcn) continue
            c.addRow(arrayOf<Any?>(ch.id, ch.lcn, ch.name, if (ch.radio) "TYPE_DVB_T_RADIO" else "TYPE_DVB_T",
                ch.onid, ch.tsid, ch.sid, ch.inputId, ch.uri, if (ch.browsable) 1 else 0, if (ch.radio) 1 else 0, ch.logo))
        }
        c.setNotificationUri(context!!.contentResolver, Repository.URI)
        return c
    }

    override fun getType(uri: Uri) = "vnd.android.cursor.dir/vnd.it.mdv.dttchannels.channel"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = throw UnsupportedOperationException("read-only")
}
