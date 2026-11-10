package it.mdv.dttchannels

import android.content.SharedPreferences
import android.media.tv.TvTrackInfo
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Overlay { NONE, CHANNELS, QUICK, EPG, SETTINGS }
enum class Signal { TUNING, LOCKED, NO_SIGNAL, AUDIO_ONLY }

/** An audio/subtitle choice. id == null means "Disattivati" (subtitles off). */
data class TrackOpt(val id: String?, val label: String, val meta: String)

/** What the state machine needs from the video layer (TvView in LiveActivity). */
interface Player {
    fun tune(ch: Channel)
    fun selectTrack(type: Int, id: String?)
    fun startScan()
    fun timeShiftPause()
    fun timeShiftResume()
    fun timeShiftSeekTo(timeMs: Long)
}

const val EPG_SPAN = 150 * 60L          // visible window: 150 min
const val EPG_STEP = 30 * 60L           // ruler + window step: 30 min
const val EPG_ROWS = 5                  // rows visible in the grid panel
const val LIST_ROWS = 7                 // rows visible in the channel list
const val LIST_PAGE = 10                // CH± jump in the channel list
const val TS_LIVE_SLACK = 3_000L        // closer than this to live counts as live
const val TS_SETTLE_MS = 400L           // timeshift: at most one player command per burst of keys
const val ZAP_SETTLE_MS = 350L          // CH±: tune only after the presses stop for this long

/**
 * Single source of truth for the live-TV UI. Keys come from LiveActivity.dispatchKeyEvent;
 * Compose only renders this state (no Compose focus), which keeps TV focus predictable.
 */
class LiveState(
    private val repo: Repository,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val player: Player,
) {
    // data
    var channels by mutableStateOf<List<Channel>>(emptyList()); private set
    var epg by mutableStateOf<Map<Long, List<Programme>>>(emptyMap()); private set
    /** Grid lists (sub-5-minute fillers dropped), precomputed with [epg] off the main thread. */
    private var gridEpg by mutableStateOf<Map<Long, List<Programme>>>(emptyMap())
    /** False until epg.json has been parsed once: the UI shows skeletons instead of "no guide". */
    var epgLoaded by mutableStateOf(false); private set
    private var byLcn by mutableStateOf<Map<String, Int>>(emptyMap())
    var now by mutableLongStateOf(nowSec()); private set

    // playback
    var current by mutableIntStateOf(-1); private set
    private var previous = -1
    var signal by mutableStateOf(Signal.TUNING); private set
    var audioTracks by mutableStateOf<List<TrackOpt>>(emptyList()); private set
    var subTracks by mutableStateOf(listOf(TrackOpt(null, "Disattivati", ""))); private set
    var selAudio by mutableStateOf<String?>(null); private set
    var selSub by mutableStateOf<String?>(null); private set

    // overlays
    var overlay by mutableStateOf(Overlay.NONE); private set
    var osdVisible by mutableStateOf(false); private set
    /** OSD programme offset: 0 = on air, 1 = next, 2 = the one after… (→ / ←). */
    var osdOffset by mutableIntStateOf(0); private set
    /** Channel browsed in the OSD with ↑/↓ (not tuned until OK); -1 = the channel being watched. */
    var osdIndex by mutableIntStateOf(-1); private set
    val browsing: Boolean get() = osdIndex >= 0 && osdIndex != current
    /** Channel the OSD is describing: the browsed one, or the one being watched. */
    val osdChannel: Channel? get() = channels.getOrNull(if (browsing) osdIndex else current)
    /** Expanded OSD ("info"): ↑ from the OSD; ↓ closes it. */
    var info by mutableStateOf(false); private set
    var digits by mutableStateOf(""); private set
    var toast by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf<String?>(null); private set

    var listFocus by mutableIntStateOf(0); private set
    var quickFocus by mutableIntStateOf(0); private set
    var avOpen by mutableStateOf(false); private set
    var avInPanel by mutableStateOf(false); private set
    var avCol by mutableIntStateOf(0); private set
    var avRow by mutableIntStateOf(0); private set
    var epgRow by mutableIntStateOf(0); private set
    var epgAnchor by mutableLongStateOf(0L); private set
    var epgWindow by mutableLongStateOf(0L); private set
    var settingsFocus by mutableIntStateOf(0); private set

    // timeshift (Technicolor input reports TIME_SHIFT_STATUS_AVAILABLE after each tune)
    var tsAvailable by mutableStateOf(false); private set
    /** Epoch ms of the oldest buffered frame / of the frame on screen (0 = unknown, i.e. live). */
    var tsStart by mutableLongStateOf(0L); private set
    var tsPos by mutableLongStateOf(0L); private set
    var tsPaused by mutableStateOf(false); private set
    /** Timeshift bar on screen (never auto-hides while paused; 5 s after input otherwise). */
    var tsBar by mutableStateOf(false); private set
    private var tsJob: Job? = null
    private var exitArmed = false

    private var osdJob: Job? = null
    private var digitJob: Job? = null
    private var toastJob: Job? = null
    private var tuneWatch: Job? = null
    private var zapJob: Job? = null

    val channel: Channel? get() = channels.getOrNull(current)
    fun programmes(ch: Channel?): List<Programme> = ch?.let { epg[it.id] } ?: emptyList()
    /** Grid view: drop sub-5-minute fillers (weather bulletins, promos) so cells stay readable and focusable. */
    fun gridProgrammes(ch: Channel?): List<Programme> = ch?.let { gridEpg[it.id] } ?: emptyList()
    fun nowOn(ch: Channel?): Programme? = programmes(ch).at(now)
    fun nextOn(ch: Channel?): Programme? = nowOn(ch)?.let { programmes(ch).after(it) }
        ?: programmes(ch).firstOrNull { it.start > now }

    /** Programme at [offset] steps from the one on air (in-memory sorted list: O(n) on ~30 items, no I/O). */
    fun programmeAt(ch: Channel?, offset: Int): Programme? {
        val list = programmes(ch)
        val base = list.indexOfFirst { it.end > now }.takeIf { it >= 0 } ?: return null
        val first = if (list[base].start <= now) base else base - 1   // nothing on air -> offset 1 is the next start
        return list.getOrNull(first + offset)?.takeIf { offset > 0 || it.isOn(now) }
    }

    fun osdProgramme(): Programme? = programmeAt(osdChannel, osdOffset)

    /** How far behind live the picture is, in ms (0 when live or unknown). */
    fun tsBehind(nowMs: Long = System.currentTimeMillis()): Long =
        if (!tsAvailable || tsPos <= 0) 0 else (nowMs - tsPos).coerceAtLeast(0)
    val delayed: Boolean get() = tsAvailable && (tsPaused || tsBehind() > TS_LIVE_SLACK)

    /** Typed digits without leading zeros ("05" -> "5"); empty while only zeros were typed. */
    private val typedLcn: String get() = digits.trimStart('0')
    /** Channel whose LCN exactly matches the digits typed so far (O(1) lookup). */
    val digitMatch: Channel? get() = byLcn[typedLcn]?.let { channels.getOrNull(it) }

    // ------------------------------------------------------------ lifecycle

    fun start(requestedLcn: String?, requestedIndex: Int?) {
        // Critical path first: channels.json is small (one read, a few ms), so we can tune right away.
        // The EPG (MBs) is parsed in the background; the UI shows skeletons until it lands.
        applyChannels(repo.channels())
        if (channels.isEmpty()) openSettings() else tuneInitial(requestedLcn, requestedIndex)
        scope.launch { loadEpg() }
        scope.launch { while (true) { now = nowSec(); delay(15_000) } }
        scope.launch {                                   // keep the EPG fresh (Pi rebuilds it every 4 h)
            // don't compete with the first tune and the OSD images: refresh a little later
            if (channels.isNotEmpty()) delay(8_000)
            var first = true
            while (true) {
                if (first || repo.epgAgeSeconds() > 2 * 3600 || channels.isEmpty()) runCatching {
                    val (chChanged, epgChanged) = withContext(Dispatchers.IO) { repo.refreshFromPi() }
                    val hadNone = channels.isEmpty()
                    if (chChanged) reloadChannels()
                    if (epgChanged) loadEpg()
                    if (hadNone && channels.isNotEmpty() && overlay == Overlay.SETTINGS) { closeOverlay(); tuneInitial(null, null) }
                }
                first = false
                delay(30 * 60_000L)
            }
        }
    }

    fun tuneInitial(lcn: String?, index: Int?) {
        if (channels.isEmpty()) return
        val byNumber = lcn?.let { byLcn[it] }
        val last = prefs.getLong("last_id", -1).let { id -> channels.indexOfFirst { it.id == id } }.takeIf { it >= 0 }
        tuneIndex(byNumber ?: index?.coerceIn(0, channels.lastIndex) ?: last ?: firstTv())
    }

    private fun applyChannels(list: List<Channel>) {
        channels = list
        byLcn = HashMap<String, Int>(list.size * 2).apply { list.forEachIndexed { i, c -> putIfAbsent(c.lcn, i) } }
    }

    private suspend fun reloadChannels() {
        val curId = channel?.id
        val list = withContext(Dispatchers.IO) { repo.channels() }
        applyChannels(list)
        if (curId != null) current = channels.indexOfFirst { it.id == curId }
    }

    /** Parse + index the EPG on a background thread, then publish it in one state write. */
    private suspend fun loadEpg() {
        val (all, grid) = withContext(Dispatchers.Default) {
            val t0 = System.nanoTime()
            val all = repo.epg()
            (all to all.mapValues { (_, l) -> l.filter { it.end - it.start >= 5 * 60 } }).also {
                android.util.Log.i("DttLive", "epg: ${all.size} channels parsed in ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
        }
        epg = all; gridEpg = grid; epgLoaded = true
    }

    private fun firstTv() = channels.indexOfFirst { !it.radio }.coerceAtLeast(0)

    // ------------------------------------------------------------ tuning

    /**
     * [deferTuneMs] > 0 (CH± zapping): the channel number, OSD and state switch at once, but the tuner is
     * only asked to tune once the presses stop. The Technicolor video decoder (ampservice) crashed and froze
     * the whole box during fast zapping (2026-11-10 05:17), so a burst of CH± now costs a single tune.
     */
    fun tuneIndex(i: Int, deferTuneMs: Long = 0) {
        if (i !in channels.indices) return
        val inBurst = zapJob?.isActive == true
        if (i != current && !inBurst) previous = current          // "last channel" = the one before the burst
        current = i
        val ch = channels[i]
        prefs.edit().putLong("last_id", ch.id).apply()
        signal = if (ch.radio) Signal.AUDIO_ONLY else Signal.TUNING
        audioTracks = emptyList(); subTracks = listOf(TrackOpt(null, "Disattivati", "")); selAudio = null; selSub = null
        resetTimeShift()                               // a new tune is always live
        osdOffset = 0
        osdIndex = -1
        info = false
        showOsd()
        zapJob?.cancel()
        tuneWatch?.cancel()
        if (deferTuneMs > 0) zapJob = scope.launch { delay(deferTuneMs); startTune(ch) } else startTune(ch)
    }

    private fun startTune(ch: Channel) {
        player.tune(ch)
        tuneWatch?.cancel()
        tuneWatch = scope.launch { delay(8000); if (signal == Signal.TUNING) signal = Signal.NO_SIGNAL }
    }

    private fun zap(step: Int) {
        if (channels.isEmpty()) return
        val n = channels.size
        val target = ((if (current < 0) 0 else current) + step + n) % n
        tuneIndex(target, deferTuneMs = ZAP_SETTLE_MS)
    }

    // ------------------------------------------------------------ TvView callbacks

    fun onVideoAvailable() { if (channel?.radio != true) signal = Signal.LOCKED }

    fun onVideoUnavailable(reason: Int) {
        signal = when (reason) {
            android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> Signal.NO_SIGNAL
            android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY -> Signal.AUDIO_ONLY
            android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING -> if (channel?.radio == true) Signal.AUDIO_ONLY else Signal.TUNING
            else -> signal
        }
    }

    fun onConnectionLost() { signal = Signal.NO_SIGNAL }

    fun onTracks(tracks: List<TvTrackInfo>) {
        if (BuildConfig.DEBUG) tracks.forEach { t ->
            android.util.Log.d("DttLive", "track type=${t.type} id=${t.id} lang=${t.language} desc=${t.description} ch=${if (t.type == TvTrackInfo.TYPE_AUDIO) t.audioChannelCount else -1} rate=${if (t.type == TvTrackInfo.TYPE_AUDIO) t.audioSampleRate else -1} extra=${t.extra?.keySet()?.joinToString { k -> k + "=" + t.extra.get(k) }}")
        }
        audioTracks = tracks.filter { it.type == TvTrackInfo.TYPE_AUDIO }.mapIndexed { i, t -> audioOpt(t, i) }
        subTracks = listOf(TrackOpt(null, "Disattivati", "")) +
            tracks.filter { it.type == TvTrackInfo.TYPE_SUBTITLE }.mapIndexed { i, t -> subOpt(t, i) }
        val ch = channel ?: return
        prefs.getString("audio_${ch.id}", null)?.let { id -> if (audioTracks.any { it.id == id } && id != selAudio) player.selectTrack(TvTrackInfo.TYPE_AUDIO, id) }
        if (prefs.contains("sub_${ch.id}")) prefs.getString("sub_${ch.id}", null).let { id ->
            if ((id == null || subTracks.any { it.id == id }) && id != selSub) player.selectTrack(TvTrackInfo.TYPE_SUBTITLE, id)
        }
    }

    fun onTrackSelected(type: Int, id: String?) {
        if (type == TvTrackInfo.TYPE_AUDIO) selAudio = id
        if (type == TvTrackInfo.TYPE_SUBTITLE) selSub = id
    }

    /**
     * Technicolor track ids carry the DVB details, e.g. "pid=300 [Audio] ita-3-mpeg-1-stereo-0",
     * "pid=1157 [Audio] ita-6-ac3plus-stereo-0", "pid=601 [Subtitle] ita-teletext-7-119" (page 7/0x77 = 777),
     * "pid=2057 [Subtitle] ita-subtitle-0-0" (DVB). Language "oth"/"qaa" = original language, "qad" = audio description.
     */
    private fun idParts(t: TvTrackInfo): List<String> = t.id.substringAfter("] ", "").split('-')

    private fun audioOpt(t: TvTrackInfo, i: Int): TrackOpt {
        val parts = idParts(t)
        val lang = (t.language ?: parts.getOrNull(0))?.lowercase()
        val id = t.id.lowercase()
        val codec = when {
            "ac3plus" in id || "eac3" in id -> "Dolby Digital Plus"
            "ac3" in id -> "Dolby Digital"
            "heaac" in id || "aac" in id -> "AAC"
            "mpeg" in id -> "MPEG"
            else -> ""
        }
        val mode = when {
            t.audioChannelCount >= 6 || "5.1" in id || "multi" in id -> "5.1"
            "mono" in id || t.audioChannelCount == 1 -> "Mono"
            "stereo" in id || t.audioChannelCount == 2 -> "Stereo"
            else -> ""
        }
        val meta = if (codec.startsWith("Dolby")) listOf(codec, mode.takeIf { it == "5.1" }).filterNotNull().joinToString(" ")
                   else listOf(mode, codec).filter { it.isNotEmpty() }.joinToString(" · ")
        return when (lang) {
            "qad", "aud" -> TrackOpt(t.id, "Audiodescrizione", "Per non vedenti")
            "oth", "qaa", "mul", "orj" -> TrackOpt(t.id, "Lingua originale", meta)
            else -> TrackOpt(t.id, langName(lang) ?: "Traccia ${i + 1}", meta)
        }
    }

    private fun subOpt(t: TvTrackInfo, i: Int): TrackOpt {
        val parts = idParts(t)
        val name = langName((t.language ?: parts.getOrNull(0))?.lowercase()) ?: "Sottotitoli ${i + 1}"
        if (parts.getOrNull(1) == "teletext") {
            val mag = parts.getOrNull(2)?.toIntOrNull() ?: 8
            val page = parts.getOrNull(3)?.toIntOrNull()
            val label = if (page != null) "Televideo %d%02x".format(if (mag == 0) 8 else mag, page) else "Televideo"
            return TrackOpt(t.id, "$name per non udenti", label)
        }
        return TrackOpt(t.id, name, "DVB")
    }

    private fun langName(code: String?): String? {
        if (code.isNullOrBlank() || code == "und") return null
        val l = Locale(if (code == "ita") "it" else code).getDisplayLanguage(Locale.ITALIAN)
        return l.takeIf { it.isNotBlank() && it != code }?.replaceFirstChar { it.titlecase(Locale.ITALIAN) }
    }

    // ------------------------------------------------------------ timeshift (design 9a/9b)

    fun onTimeShiftStatus(status: Int) {
        tsAvailable = status == android.media.tv.TvInputManager.TIME_SHIFT_STATUS_AVAILABLE
        if (!tsAvailable) { tsPaused = false; tsBar = false }
    }

    fun onTimeShiftStart(ms: Long) {
        if (ms <= 0) return
        tsStart = ms
        // ring buffer full: the oldest point overtook a paused picture -> resume from there
        if (tsPaused && tsPos > 0 && ms > tsPos) {
            tsPaused = false; pendingSeek = ms; tsPos = ms
            applyTs()
            showToast("Buffer pieno, riproduzione ripresa")
            showTsBar()
        }
    }

    fun onTimeShiftPosition(ms: Long) {
        // while a command is pending/just sent the player still reports the old spot: keep the optimistic one
        if (pendingSeek != null || System.currentTimeMillis() < seekGuardUntil) return
        tsPos = if (ms > 0) ms else 0L
    }

    private fun resetTimeShift() {
        tsJob?.cancel(); resumeJob?.cancel(); tsCmdJob?.cancel()
        tsAvailable = false; tsStart = 0; tsPos = 0; tsPaused = false; tsBar = false
        tsOrigin = 0; playerPaused = false; pendingSeek = null; seekGuardUntil = 0
    }

    private fun timeShiftUsable(): Boolean {
        val ok = tsAvailable && channel?.radio == false && signal != Signal.NO_SIGNAL
        if (!ok) showToast("Pausa non disponibile su questo canale")
        return ok
    }

    /**
     * Technicolor timeshift, as observed on the box:
     *  - no rolling buffer while live (start position stays ~3 s behind now): recording begins at the first
     *    pause, so the oldest reachable point is that pause ([tsOrigin]); nothing before it can be played;
     *  - seek within [tsOrigin, now − few s] works; a seek to the live edge is ignored -> live = re-tune;
     *  - after a seek the player restores the speed in effect when the seek was issued -> resume BEFORE seeking.
     * The UI state (tsPaused, tsPos) changes at once on every key; the player gets at most one command per
     * [TS_SETTLE_MS] (first press immediately, the final state of a burst once it settles), like CH± zapping.
     */
    var tsOrigin by mutableLongStateOf(0L); private set
    /** Oldest playable point: the first pause of this tune (or later, once the ring buffer wraps). */
    val tsFloor: Long get() = maxOf(tsOrigin, tsStart)
    private var playerPaused = false          // what the player was last told
    private var pendingSeek: Long? = null
    private var seekGuardUntil = 0L
    private var tsCmdAt = 0L
    private var tsCmdJob: Job? = null
    private var resumeJob: Job? = null

    /** Throttle: apply now if the player has been idle long enough, else once after the burst settles. */
    private fun requestTs() {
        if (tsCmdJob?.isActive == true) return                 // pending commit will pick up the latest state
        val wait = tsCmdAt + TS_SETTLE_MS - System.currentTimeMillis()
        if (wait <= 0) applyTs() else tsCmdJob = scope.launch { delay(wait.coerceAtLeast(TS_SETTLE_MS / 2)); applyTs() }
    }

    private fun applyTs() {
        tsCmdJob?.cancel()
        tsCmdAt = System.currentTimeMillis()
        val seek = pendingSeek; pendingSeek = null
        resumeJob?.cancel()
        if (tsPaused) {
            if (!playerPaused) { player.timeShiftPause(); playerPaused = true }
            if (seek != null) { player.timeShiftSeekTo(seek); seekGuardUntil = tsCmdAt + 1_500 }   // stays paused on the new frame
        } else if (playerPaused || seek != null) {
            player.timeShiftResume(); playerPaused = false
            if (seek != null) {
                player.timeShiftSeekTo(seek); seekGuardUntil = tsCmdAt + 1_500
                resumeJob = scope.launch {                     // nudge once if the playhead doesn't move
                    val before = tsPos
                    delay(2_000)
                    if (!tsPaused && !playerPaused && tsPos == before) player.timeShiftResume()
                }
            }
        }
    }

    /** ▶❚❚: live/playing -> pause (the first pause starts the tuner's recording); paused -> resume. */
    private fun togglePause() {
        if (!timeShiftUsable()) return
        if (!tsPaused) {
            val now = System.currentTimeMillis()
            if (tsPos <= 0) tsPos = now
            if (tsOrigin <= 0) tsOrigin = tsPos               // nothing before this moment is ever recorded
        }
        tsPaused = !tsPaused
        requestTs()
        showTsBar()
    }

    private fun behindLive(): Long = if (tsPos > 0) System.currentTimeMillis() - tsPos else 0

    /** ← / ◀◀ −10 s, → / ▶▶ +10 s inside [tsFloor, live]; reaching the live edge goes live. Keeps pause/play. */
    private fun jumpBy(deltaMs: Long) {
        if (!timeShiftUsable()) return
        val now = System.currentTimeMillis()
        if (tsOrigin <= 0) {                                  // live, never paused: there is no recording yet
            showToast(if (deltaMs < 0) "Metti in pausa per poter tornare indietro" else "Sei già in diretta")
            return
        }
        val from = if (tsPos > 0) tsPos else now
        if (deltaMs > 0 && from + deltaMs >= now - TS_LIVE_SLACK) {
            if (behindLive() > TS_LIVE_SLACK || tsPaused) goLive() else showTsBar()
            return
        }
        val floor = tsFloor + 1_000
        if (deltaMs < 0 && from <= floor + 500) {
            showToast("Non puoi tornare prima della pausa")
            showTsBar()
            return
        }
        val target = (from + deltaMs).coerceIn(minOf(floor, now), now)
        pendingSeek = target
        tsPos = target
        requestTs()
        showTsBar()
    }

    /**
     * Circle key: "Torna in diretta" — re-tune the channel. On this tuner a seek to the live edge is ignored;
     * a tune is always live, drops the delay, and shows the normal live OSD for 5 s (tuneIndex).
     */
    private fun goLive() {
        if (!tsAvailable || current < 0) return
        resumeJob?.cancel(); tsCmdJob?.cancel()
        tuneIndex(current)
    }

    private fun showTsBar() {
        osdJob?.cancel(); osdVisible = false; osdOffset = 0; osdIndex = -1; info = false
        tsBar = true
        tsJob?.cancel()
        if (!tsPaused) tsJob = scope.launch { delay(5_000); tsBar = false }
    }

    private fun keyTimeshift(code: Int): Boolean {
        when (code) {
            in OK -> togglePause()                                // no on-screen buttons: OK = Pausa / Riprendi
            KeyEvent.KEYCODE_DPAD_LEFT -> jumpBy(-10_000)
            KeyEvent.KEYCODE_DPAD_RIGHT -> jumpBy(+10_000)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> showTsBar()
            KeyEvent.KEYCODE_BACK -> { tsJob?.cancel(); tsBar = false }
            else -> { tsJob?.cancel(); tsBar = false; return keyLive(code) }   // CH±, info…
        }
        return true
    }

    // ------------------------------------------------------------ OSD / toast / digits

    fun showOsd() {
        tsJob?.cancel(); tsBar = false
        osdVisible = true
        osdJob?.cancel()
        osdJob = scope.launch { delay(if (info) 15_000 else 5000); osdVisible = false; osdOffset = 0; osdIndex = -1; info = false }
    }

    private fun hideOsd() { osdJob?.cancel(); osdVisible = false; osdOffset = 0; osdIndex = -1; info = false }

    private fun showToast(msg: String) {
        toast = msg
        toastJob?.cancel()
        toastJob = scope.launch { delay(2500); toast = null }
    }

    /**
     * Digit entry: the typed number shows instantly (no OSD for the channel being watched); the matched
     * channel's preview loads behind it. Commits early when the number can't grow into another LCN.
     */
    private fun digit(d: Int) {
        digits = (if (digits.length >= 3) "" else digits) + d
        hideOsd()
        digitJob?.cancel()
        val n = typedLcn
        when {
            digits.length == 3 -> commitDigits()
            n.isEmpty() -> digitJob = scope.launch { delay(2000); commitDigits() }          // only zeros so far
            channels.none { it.lcn.startsWith(n) } -> { digits = ""; showToast("Canale non disponibile") }
            byLcn.containsKey(n) && channels.none { it.lcn.length > n.length && it.lcn.startsWith(n) } -> commitDigits()
            else -> digitJob = scope.launch { delay(2000); commitDigits() }
        }
    }

    private fun cancelDigits() { digitJob?.cancel(); digits = "" }

    private fun commitDigits() {
        digitJob?.cancel()
        val d = typedLcn.ifEmpty { digits }
        digits = ""
        if (d.isEmpty()) return
        val i = byLcn[d] ?: -1
        if (i >= 0) { closeOverlay(); tuneIndex(i) } else showToast("Canale non disponibile")
    }

    // ------------------------------------------------------------ overlays

    private fun closeOverlay() { overlay = Overlay.NONE; avOpen = false; avInPanel = false }

    fun openChannels() { overlay = Overlay.CHANNELS; listFocus = current.coerceAtLeast(0); hideOsd() }

    fun openQuick() { overlay = Overlay.QUICK; quickFocus = 0; avOpen = false; avInPanel = false; hideOsd() }

    fun openEpg() {
        overlay = Overlay.EPG; hideOsd()
        epgRow = current.coerceAtLeast(0)
        epgAnchor = now
        epgWindow = floorStep(now) - EPG_STEP
    }

    fun openSettings() { overlay = Overlay.SETTINGS; settingsFocus = 0; hideOsd() }

    private fun openAv() {
        avOpen = true; avInPanel = true
        avCol = 0
        avRow = audioTracks.indexOfFirst { it.id == selAudio }.coerceAtLeast(0)
    }

    // ------------------------------------------------------------ keys

    /** Returns true when the key was consumed. Only ACTION_DOWN events are passed in. */
    fun onKey(code: Int): Boolean {
        val d = digitOf(code)
        if (d != null && overlay != Overlay.SETTINGS) { digit(d); return true }
        // delayed: OK only drives the timeshift (bar / Riprendi / Torna in diretta), it never changes channel
        if (code in OK && overlay == Overlay.NONE && delayed) { if (tsBar) keyTimeshift(code) else showTsBar(); return true }
        if (code in OK && digits.isNotEmpty()) { commitDigits(); return true }
        if (code == KeyEvent.KEYCODE_BACK && digits.isNotEmpty()) { cancelDigits(); return true }
        if (code == KeyEvent.KEYCODE_GUIDE) { if (overlay == Overlay.EPG) closeOverlay() else openEpg(); return true }
        if (code == KeyEvent.KEYCODE_MENU) { if (overlay == Overlay.QUICK) closeOverlay() else openQuick(); return true }
        if (overlay == Overlay.NONE) when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (tsPaused) togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (!tsPaused) togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { jumpBy(-10_000); return true }       // same as ← on the timeline
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { jumpBy(+10_000); return true } // same as →
            KeyEvent.KEYCODE_MEDIA_STOP -> { goLive(); return true }
            // circle key on the TIM v2 remote (sends PROG_BLUE): "Torna in diretta"
            KeyEvent.KEYCODE_PROG_BLUE -> if (delayed) { goLive(); return true }
        }
        if (overlay == Overlay.NONE && tsBar) return keyTimeshift(code)
        return when (overlay) {
            Overlay.NONE -> keyLive(code)
            Overlay.CHANNELS -> keyChannels(code)
            Overlay.QUICK -> keyQuick(code)
            Overlay.EPG -> keyEpg(code)
            Overlay.SETTINGS -> keySettings(code)
        }
    }

    /** ↑/↓ in the OSD: move the described channel without tuning; ← → reset to the programme on air. */
    private fun browse(step: Int) {
        if (channels.isEmpty()) return
        val from = if (osdIndex >= 0) osdIndex else current.coerceAtLeast(0)
        osdIndex = (from + step + channels.size) % channels.size
        osdOffset = 0
        info = false
        showOsd()
    }

    private fun keyLive(code: Int): Boolean {
        val radioScreen = channel?.radio == true || signal == Signal.AUDIO_ONLY
        // nothing on screen: any arrow opens the quick menu
        if (!osdVisible && code in ARROWS) { openQuick(); return true }
        when (code) {
            in CH_UP -> zap(+1)
            in CH_DOWN -> zap(-1)
            // OK: shows the OSD; on the watched channel opens info; while browsing tunes the browsed channel;
            // inside info opens the channel list
            in OK -> when {
                !osdVisible -> showOsd()
                browsing -> tuneIndex(osdIndex)
                info -> openChannels()
                else -> { info = true; showOsd() }
            }
            // ↑/↓ on the OSD browse channels (only the OSD changes; OK tunes). ↑ = next number, like CH+.
            KeyEvent.KEYCODE_DPAD_UP -> if (!info) browse(+1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (info) openQuick() else browse(-1)   // info: ↓ = quick menu
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (programmeAt(osdChannel, osdOffset + 1) != null) osdOffset++; showOsd() }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (osdOffset > 0) osdOffset--; showOsd() }
            KeyEvent.KEYCODE_INFO -> if (osdVisible) hideOsd() else showOsd()
            KeyEvent.KEYCODE_LAST_CHANNEL -> if (previous >= 0) tuneIndex(previous)
            KeyEvent.KEYCODE_BACK -> when {
                info -> { info = false; showOsd() }                // Back closes info, back to the OSD
                osdVisible -> hideOsd()                            // also drops any browsed channel
                radioScreen && channel?.radio == true -> {         // from radio, back returns to TV
                    val tv = previous.takeIf { it >= 0 && !channels[it].radio } ?: firstTv()
                    tuneIndex(tv)
                }
                delayed && !exitArmed -> {                         // leaving drops the timeshift buffer: confirm
                    exitArmed = true
                    showToast("Uscendo perdi la differita · Indietro per confermare")
                    scope.launch { delay(4_000); exitArmed = false }
                }
                else -> return false                               // leave the app
            }
            else -> return false
        }
        return true
    }

    private fun keyChannels(code: Int): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> listFocus = (listFocus - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> listFocus = (listFocus + 1).coerceAtMost(channels.lastIndex)
            // CH± page 10 channels: CH+ towards higher numbers (down the list), CH− back
            in CH_UP -> listFocus = (listFocus + LIST_PAGE).coerceAtMost(channels.lastIndex)
            in CH_DOWN -> listFocus = (listFocus - LIST_PAGE).coerceAtLeast(0)
            in OK -> { closeOverlay(); tuneIndex(listFocus) }
            KeyEvent.KEYCODE_BACK -> closeOverlay()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {}
            else -> return false
        }
        return true
    }

    private fun keyQuick(code: Int): Boolean {
        if (avInPanel) return keyAv(code)
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> quickFocus = (quickFocus - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> quickFocus = (quickFocus + 1).coerceAtMost(3)
            KeyEvent.KEYCODE_DPAD_UP -> if (avOpen) avInPanel = true
            in OK -> when (quickFocus) {
                0 -> { closeOverlay(); openEpg() }
                1 -> { closeOverlay(); openChannels() }
                2 -> if (avOpen) { avOpen = false } else openAv()
                3 -> { closeOverlay(); openSettings() }
            }
            KeyEvent.KEYCODE_BACK -> if (avOpen) avOpen = false else closeOverlay()
            KeyEvent.KEYCODE_DPAD_DOWN -> {}
            else -> return false
        }
        return true
    }

    private fun keyAv(code: Int): Boolean {
        val rows = if (avCol == 0) audioTracks.size else subTracks.size
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> avRow = (avRow - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (avRow >= rows - 1) avInPanel = false else avRow++
            KeyEvent.KEYCODE_DPAD_LEFT -> if (avCol == 1) { avCol = 0; avRow = avRow.coerceAtMost((audioTracks.size - 1).coerceAtLeast(0)) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (avCol == 0) { avCol = 1; avRow = avRow.coerceAtMost(subTracks.size - 1) }
            in OK -> applyAv()
            KeyEvent.KEYCODE_BACK -> { avInPanel = false; avOpen = false }
            else -> return false
        }
        return true
    }

    private fun applyAv() {
        val ch = channel ?: return
        if (avCol == 0) {
            val t = audioTracks.getOrNull(avRow) ?: return
            player.selectTrack(TvTrackInfo.TYPE_AUDIO, t.id); selAudio = t.id
            prefs.edit().putString("audio_${ch.id}", t.id).apply()
        } else {
            val t = subTracks.getOrNull(avRow) ?: return
            player.selectTrack(TvTrackInfo.TYPE_SUBTITLE, t.id); selSub = t.id
            prefs.edit().putString("sub_${ch.id}", t.id).apply()
        }
    }

    // ---- EPG grid

    /** Focused programme in the grid, or null when the row has no data at the anchor. */
    fun epgFocused(): Programme? = gridProgrammes(channels.getOrNull(epgRow)).at(epgAnchor)

    private fun keyEpg(code: Int): Boolean {
        val list = gridProgrammes(channels.getOrNull(epgRow))
        val cur = list.at(epgAnchor)
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> epgRow = (epgRow - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> epgRow = (epgRow + 1).coerceAtMost(channels.lastIndex)
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val nxt = cur?.let { list.after(it) } ?: list.firstOrNull { it.start > epgAnchor }
                epgAnchor = nxt?.start ?: (epgAnchor + EPG_STEP)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val from = cur?.start ?: epgAnchor
                val prv = list.lastOrNull { it.end <= from }
                // long programmes: step back at most 30 min so the window doesn't jump hours
                epgAnchor = prv?.let { maxOf(it.start, from - EPG_STEP).coerceAtMost(it.end - 1) } ?: (epgAnchor - EPG_STEP)
            }
            in CH_UP -> { epgAnchor += 24 * 3600; epgWindow += 24 * 3600 }
            in CH_DOWN -> { epgAnchor -= 24 * 3600; epgWindow -= 24 * 3600 }
            in OK -> { closeOverlay(); tuneIndex(epgRow) }
            KeyEvent.KEYCODE_BACK -> closeOverlay()
            else -> return false
        }
        // keep the anchor inside the 150-min window (scrolling in 30-min steps)
        while (epgAnchor < epgWindow) epgWindow -= EPG_STEP
        while (epgAnchor >= epgWindow + EPG_SPAN - EPG_STEP) epgWindow += EPG_STEP
        return true
    }

    // ---- settings

    private fun keySettings(code: Int): Boolean {
        if (busy != null && code != KeyEvent.KEYCODE_BACK) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> settingsFocus = 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> settingsFocus = 1
            in OK -> if (settingsFocus == 0) startScan() else sync()
            KeyEvent.KEYCODE_BACK -> if (channels.isNotEmpty()) closeOverlay() else return false
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {}
            else -> return false
        }
        return true
    }

    /** "Scansiona canali": the Pi enables timvision.telemetry first (the vendor scan crashes without it). */
    private fun startScan() {
        if (busy != null) return
        busy = "Preparazione scansione…"
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { repo.scanPrepare() } }
            busy = null
            r.onSuccess {
                prefs.edit().putBoolean("sync_after_scan", true).apply()
                player.startScan()
            }.onFailure { e -> showToast("Scansione non disponibile: ${e.message}") }
        }
    }

    /** Called from onResume: after the vendor scan screen, restore telemetry off and refresh the list. */
    fun onResume() {
        if (prefs.getBoolean("sync_after_scan", false)) {
            prefs.edit().putBoolean("sync_after_scan", false).apply()
            overlay = Overlay.SETTINGS
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { repo.scanDone() } }   // Pi also does it after 30 min
                sync()
            }
        }
    }

    /**
     * Sincronizza (also runs after a scan). On success the guide is parsed first, then Impostazioni
     * closes and the Guida TV opens on the new data; on failure the user stays in Impostazioni.
     */
    fun sync() {
        if (busy != null) return
        busy = "Sincronizzazione in corso…"
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { repo.syncAll() } }
            val hadNone = channels.isEmpty()
            reloadChannels()
            loadEpg()
            busy = null
            r.onSuccess { n ->
                closeOverlay()
                if (hadNone || current < 0) tuneInitial(null, null)
                if (channels.isNotEmpty()) openEpg()
                showToast("Aggiornati $n canali e la guida TV")
            }.onFailure { e -> showToast("Sincronizzazione non riuscita: ${e.message}") }
        }
    }

    companion object {
        val OK = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        val CH_UP = setOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP)
        val CH_DOWN = setOf(KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN)
        val ARROWS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        fun digitOf(code: Int): Int? = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
            else -> null
        }
        fun nowSec() = System.currentTimeMillis() / 1000
        fun floorStep(t: Long) = t - (t % EPG_STEP)
    }
}
