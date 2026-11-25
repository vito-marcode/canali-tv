package it.mdv.dttchannels

import android.app.SearchManager
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.media.tv.TvTrackInfo
import android.media.tv.TvView
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import it.mdv.dttchannels.ui.LiveRoot

/**
 * Live TV: TvView (video layer) + Compose overlay. Entry points (manifest aliases):
 *  .MainActivity  launcher "Canali TV"     -> last channel
 *  .PlayerActivity / action it.mdv.dttchannels.PLAY, extras: number (LCN String), index (Int) or query (spoken
 *                   phrase, "metti rai due"); also android.media.action.MEDIA_PLAY_FROM_SEARCH (SearchManager.QUERY)
 * Voice commands come from the launcher (com.gathin.tvhome, mic key): it resolves a phrase with the provider's ?q= and
 * sends PLAY with the channel number. VoiceMatch does the matching.
 */
class LiveActivity : ComponentActivity(), Player {
    private lateinit var tv: TvView
    private lateinit var state: LiveState
    private val consumedDown = HashSet<Int>()
    /** Debug only: draw the UI without tuning (live video blocks screenshots on this box). */
    private var preview = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FullScreenTvView ignores the tuner's 1919 px surface layout (soft first picture)
        tv = FullScreenTvView(this).apply { isFocusable = false; setBackgroundColor(Color.BLACK) }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val compose = ComposeView(this).apply { isFocusable = false }
        root.addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        preview = BuildConfig.DEBUG && intent?.getBooleanExtra("preview", false) == true
        state = LiveState(Repository(this), getSharedPreferences("dtt", 0), lifecycleScope, this)
        tv.setCallback(object : TvView.TvInputCallback() {
            override fun onVideoAvailable(inputId: String) {
                Log.i(TAG, "video available after ${System.currentTimeMillis() - tunedAt} ms"); state.onVideoAvailable()
            }
            override fun onVideoUnavailable(inputId: String, reason: Int) {
                Log.i(TAG, "video unavailable reason=$reason"); state.onVideoUnavailable(reason)
            }
            override fun onConnectionFailed(inputId: String) { Log.w(TAG, "connection failed"); state.onConnectionLost() }
            override fun onDisconnected(inputId: String) { Log.w(TAG, "disconnected"); state.onConnectionLost() }
            override fun onTracksChanged(inputId: String, tracks: MutableList<TvTrackInfo>) = state.onTracks(tracks)
            override fun onTrackSelected(inputId: String, type: Int, trackId: String?) = state.onTrackSelected(type, trackId)
            override fun onTimeShiftStatusChanged(inputId: String, status: Int) {
                if (BuildConfig.DEBUG) Log.d(TAG, "ts status=$status")
                state.onTimeShiftStatus(status)
            }
        })
        tv.setTimeShiftPositionCallback(object : TvView.TimeShiftPositionCallback() {
            override fun onTimeShiftStartPositionChanged(inputId: String, timeMs: Long) {
                if (BuildConfig.DEBUG) Log.d(TAG, "ts start=$timeMs (${(System.currentTimeMillis() - timeMs) / 1000}s ago)")
                state.onTimeShiftStart(timeMs)
            }
            override fun onTimeShiftCurrentPositionChanged(inputId: String, timeMs: Long) {
                if (BuildConfig.DEBUG) Log.d(TAG, "ts pos=$timeMs (${(System.currentTimeMillis() - timeMs) / 1000}s behind)")
                state.onTimeShiftPosition(timeMs)
            }
        })
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        compose.setContent { LiveRoot(state, version) }
        val (lcn, idx) = requested(intent)
        state.start(lcn, idx)
        query(intent)?.let { state.tuneByPhrase(listOf(it)) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val (lcn, idx) = requested(intent)
        if (lcn != null || idx != null) state.tuneInitial(lcn, idx)
        query(intent)?.let { state.tuneByPhrase(listOf(it)) }
    }

    private fun query(i: Intent?): String? =
        (i?.getStringExtra("query") ?: i?.getStringExtra(SearchManager.QUERY))?.takeIf { it.isNotBlank() }

    override fun onResume() { super.onResume(); state.onResume() }

    override fun onStart() {
        super.onStart()
        if (::state.isInitialized && stopped) {
            stopped = false; state.restoreEpg()
            // back on screen: retune the last channel a moment later, so a PLAY that brought us back (onNewIntent comes
            // after onStart) replaces it instead of making two tunes in a row (see START_TUNE_DEFER_MS)
            tv.postDelayed(resumeTune, START_TUNE_DEFER_MS)
        }
    }

    private var stopped = false
    override fun onStop() {
        super.onStop(); stopped = true; tv.removeCallbacks(resumeTune); tv.reset()
    }

    /**
     * Shrink the cached process. UI hidden: DttApp drops the decoded images. Memory tight while we are off
     * screen: drop the parsed EPG too. Then one GC, so the evicted Bitmaps' native pixels are really freed.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN can arrive just before onStop(): it means hidden by itself
        val hidden = stopped || level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        if (!hidden || level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) return
        if (stopped && (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
            state.releaseEpg()
        VoiceMatch.release()
        imageLoader.memoryCache?.clear()
        Runtime.getRuntime().gc()
    }

    private fun requested(i: Intent?): Pair<String?, Int?> {
        if (i == null) return null to null
        // which channel numbers to show ("1,2,3,26"; "" for all): am start -n it.mdv.dttchannels/.MainActivity --es lcns ...
        i.getStringExtra("lcns")?.let { getSharedPreferences("dtt", 0).edit().putString("lcns", it).commit() }
        val lcn = i.getStringExtra("number")
        val idx = if (i.hasExtra("index")) i.getIntExtra("index", 0) else null
        return lcn to idx
    }

    // ---------------------------------------------------------------- keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (BuildConfig.DEBUG && event.action == KeyEvent.ACTION_DOWN)
            Log.d(TAG, "key code=$code (${KeyEvent.keyCodeToString(code)}) scan=${event.scanCode} device=${event.device?.name}")
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (state.onKey(code)) { consumedDown.add(code); return true }
            consumedDown.remove(code)
        } else if (event.action == KeyEvent.ACTION_UP && consumedDown.remove(code)) return true
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- Player

    /** When the last tune was asked: the "video available" log gives the zap time. */
    private var tunedAt = 0L
    private val resumeTune = Runnable { state.channel?.let { tune(it) } }

    override fun tune(ch: Channel) {
        tv.removeCallbacks(resumeTune)                  // any tune replaces a pending retune on return
        tunedAt = System.currentTimeMillis()
        Log.i(TAG, "tune ${ch.lcn} ${ch.name} ${ch.uri}" + if (preview) " (preview, not tuned)" else "")
        if (preview) { tv.postDelayed({ if (!ch.radio) { state.onVideoAvailable(); fakeTs.start() } }, 300); return }
        val uri = Uri.parse(ch.uri)
        if (ch.inputId != SAGEMCOM_INPUT) { tv.tune(ch.inputId, uri); return }
        // TIMVISION box: the Sagemcom input tunes only for a caller that names one of its supported TV apps in the
        // tune params, and this firmware's TvView.tune() writes our own package name there. With no session open,
        // TvView keeps this same Bundle and sends it only once the new session is created (a later main-thread
        // message), so naming the TIM app right after tune() is what the input receives. Hence a fresh session per tune.
        tv.reset()
        val params = Bundle()
        tv.tune(ch.inputId, uri, params)
        params.putString(SAGEMCOM_CALLER_KEY, "timvision.launcher")
    }

    override fun selectTrack(type: Int, id: String?) = tv.selectTrack(type, id)

    override fun timeShiftPause() { if (preview) fakeTs.paused = true else tv.timeShiftPause() }
    override fun timeShiftResume() { if (preview) fakeTs.paused = false else tv.timeShiftResume() }
    override fun timeShiftSeekTo(timeMs: Long) { if (preview) fakeTs.pos = timeMs else tv.timeShiftSeekTo(timeMs) }

    /** Debug preview only: a pretend 30-min buffer so the timeshift UI can be exercised without video. */
    private val fakeTs = object : Runnable {
        var pos = 0L; var paused = false
        fun start() {
            tv.removeCallbacks(this); paused = false; pos = System.currentTimeMillis()
            state.onTimeShiftStatus(android.media.tv.TvInputManager.TIME_SHIFT_STATUS_AVAILABLE)
            state.onTimeShiftStart(pos - 30 * 60_000); run()
        }
        override fun run() {
            val now = System.currentTimeMillis()
            if (!paused) pos = minOf(pos + 1_000, now)
            state.onTimeShiftPosition(pos)
            tv.postDelayed(this, 1_000)
        }
    }

    /** The box's own scan screen: Technicolor's (box with the Pi) or the TIMVISION box's DTT scanner app. */
    override fun startScan() {
        for (c in SCAN_SCREENS) {
            try {
                startActivity(Intent(Intent.ACTION_MAIN).setComponent(c)); return
            } catch (e: Exception) { Log.w(TAG, "scan screen $c unavailable") }
        }
    }

    companion object {
        const val TAG = "DttLive"
        /** The TIMVISION box's DVB-T input and the tune param it checks (supported_tvapps_list: the TIM app is one). */
        private val SCAN_SCREENS = listOf(
            ComponentName("com.technicolor.android.dtvinput", "com.technicolor.android.dtvinput.setup.SetupActivity"),
            ComponentName("com.technicolor.android.dtvscan.project", "com.technicolor.android.dtvscan.project.MainActivity"),
        )
        private const val SAGEMCOM_INPUT = "com.sagemcom.stb.tvinput/.StbTvInputService/HW42"
        private const val SAGEMCOM_CALLER_KEY = "com.sagemcom.stb.callingPackageName"
    }
}
