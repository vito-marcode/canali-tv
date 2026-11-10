package it.mdv.dttchannels

/** A DVB service as exported by the Pi companion (LCN order). */
data class Channel(
    val id: Long,              // Android TvProvider channel id
    val lcn: String,           // logical channel number as broadcast ("1", "101", "701")
    val name: String,
    val inputId: String,
    val uri: String,           // content://android.media.tv/channel/<id>
    val radio: Boolean,
    val logo: String?,         // absolute URL (resolved against the Pi), or null
    val onid: Int, val tsid: Int, val sid: Int,
    val browsable: Boolean,
) {
    val lcnInt: Int get() = lcn.toIntOrNull() ?: Int.MAX_VALUE
}

/** One EPG event (times in epoch seconds). */
data class Programme(
    val start: Long, val end: Long,
    val title: String, val genre: String, val desc: String,
    val image: String?, val subtitle: String?,
) {
    val minutes: Int get() = ((end - start) / 60).toInt()
    fun isOn(now: Long) = now in start until end
}

/** EPG lookup helpers on a start-sorted list. */
fun List<Programme>.at(t: Long): Programme? {
    var lo = 0; var hi = size - 1
    while (lo <= hi) {
        val m = (lo + hi) ushr 1
        val p = this[m]
        when {
            t < p.start -> hi = m - 1
            t >= p.end -> lo = m + 1
            else -> return p
        }
    }
    return null
}

fun List<Programme>.indexAtOrAfter(t: Long): Int {
    val i = indexOfFirst { it.end > t }
    return if (i < 0) size - 1 else i
}

fun List<Programme>.after(p: Programme): Programme? = firstOrNull { it.start >= p.end }
