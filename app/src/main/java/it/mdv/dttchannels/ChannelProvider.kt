package it.mdv.dttchannels

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Read-only, world-readable channel list (contract kept from v1):
 *   content://it.mdv.dttchannels/channels            all channels
 *   content://it.mdv.dttchannels/channels?number=5   one channel by LCN
 * Columns: _id, number, name, type, onid, tsid, sid, input_id, channel_uri, browsable, radio, logo_uri
 */
class ChannelProvider : ContentProvider() {
    private val cols = arrayOf("_id", "number", "name", "type", "onid", "tsid", "sid", "input_id",
        "channel_uri", "browsable", "radio", "logo_uri")

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val c = MatrixCursor(cols)
        val only = uri.getQueryParameter("number")
        for (ch in Repository.readChannels(context!!)) {
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
