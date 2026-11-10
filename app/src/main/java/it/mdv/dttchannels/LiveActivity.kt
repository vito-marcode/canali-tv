package it.mdv.dttchannels

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
import it.mdv.dttchannels.ui.LiveRoot

/**
 * Live TV: TvView (video layer) + Compose overlay. Entry points (manifest aliases):
 *  .MainActivity  launcher "Canali TV"     -> last channel
 *  .PlayerActivity / action it.mdv.dttchannels.PLAY, extras: number (LCN String) or index (Int)
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
            override fun onVideoAvailable(inputId: String) = state.onVideoAvailable()
            override fun onVideoUnavailable(inputId: String, reason: Int) = state.onVideoUnavailable(reason)
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val (lcn, idx) = requested(intent)
        if (lcn != null || idx != null) state.tuneInitial(lcn, idx)
    }

    override fun onResume() { super.onResume(); state.onResume() }

    override fun onStart() {
        super.onStart()
        if (::state.isInitialized && stopped) { stopped = false; state.channel?.let { tune(it) } }
    }

    private var stopped = false
    override fun onStop() { super.onStop(); stopped = true; tv.reset() }

    private fun requested(i: Intent?): Pair<String?, Int?> {
        if (i == null) return null to null
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

    override fun tune(ch: Channel) {
        Log.i(TAG, "tune ${ch.lcn} ${ch.name} ${ch.uri}" + if (preview) " (preview, not tuned)" else "")
        if (preview) { tv.postDelayed({ if (!ch.radio) { state.onVideoAvailable(); fakeTs.start() } }, 300); return }
        tv.tune(ch.inputId, Uri.parse(ch.uri))
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

    override fun startScan() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).setComponent(
                ComponentName("com.technicolor.android.dtvinput", "com.technicolor.android.dtvinput.setup.SetupActivity")))
        } catch (e: Exception) { Log.w(TAG, "scan screen unavailable", e) }
    }

    companion object { const val TAG = "DttLive" }
}
