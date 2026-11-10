package it.mdv.dttchannels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import it.mdv.dttchannels.LiveState
import it.mdv.dttchannels.Programme
import it.mdv.dttchannels.at
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Design handoff "Timeshift" 9a (in pausa), 9b (differita) + delay indicator. ◀◀ ▶▶ jump like ← → (no trick-play).
// Technicolor player: recording starts at the first pause, so the timeline runs pause → live, not further back.
// All sizes are design px at 1920×1080 (.u converts).

/** Wall clock in ms, ticking twice a second: the live edge and the delta keep moving. */
@Composable
private fun rememberNowMs(): Long {
    var t by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { t = System.currentTimeMillis(); delay(500) } }
    return t
}

/** "−2:00", "−15:00", "−1:02:30" */
fun deltaLabel(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "−%d:%02d:%02d".format(h, m, sec) else "−%d:%02d".format(m, sec)
}

// ---------------------------------------------------------------- bar

@Composable
fun TimeshiftBar(s: LiveState) {
    val ch = s.channel ?: return
    val now = rememberNowMs()
    val pos = if (s.tsPos > 0) s.tsPos.coerceAtMost(now) else now
    // the tuner records only from the first pause: that moment is the left edge, never anything earlier
    val floor = s.tsFloor
    val start = if (floor in 1..pos) floor else pos
    val delta = now - pos
    val progs = s.programmes(ch)
    val header = progs.at(pos / 1000)                       // programme at the playhead

    Box(Modifier.fillMaxSize()) {
        BottomScrim()
        TopPills(s)
        if (s.tsPaused) PauseBadge(Modifier.align(Alignment.TopCenter).padding(top = 300.u))

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 80.u, end = 80.u, bottom = 110.u)
                .glass(64, strong = true).padding(start = 56.u, end = 56.u, top = 40.u, bottom = 44.u),
            verticalArrangement = Arrangement.spacedBy(18.u),
        ) {
            // header: tag · delta · programme
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.u)) {
                Box(Modifier.border(2.u, Color.White.copy(alpha = .7f), RoundedCornerShape(999.u)).padding(horizontal = 20.u, vertical = 8.u)) {
                    T(if (s.tsPaused) "IN PAUSA" else "DIFFERITA", 26, 700, letterSpacing = .06f)
                }
                T(deltaLabel(delta), 32, 700, tabular = true)
                if (header != null) T("· ${header.title}", 28, color = Color.White.copy(alpha = .8f), modifier = Modifier.weight(1f, fill = false))
            }

            Timeline(start, pos, now, progs, bubble = hhmm(pos / 1000))
        }

        // hint row
        Row(Modifier.align(Alignment.BottomStart).padding(start = 80.u, bottom = 36.u),
            horizontalArrangement = Arrangement.spacedBy(40.u), verticalAlignment = Alignment.CenterVertically) {
            val c = Color.White.copy(alpha = .78f)
            KeyHint("OK", if (s.tsPaused) "Riprendi" else "Pausa", c)
            // only the moves the tuner can make: back down to the pause moment, forward up to live
            val canBack = pos - start > 2_000
            val canFwd = delta > it.mdv.dttchannels.TS_LIVE_SLACK + 10_000
            when {
                canBack && canFwd -> KeyHint("← →", "Indietro / Avanti", c)
                canBack -> KeyHint("←", "Indietro", c)
                canFwd -> KeyHint("→", "Avanti", c)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.u)) {
                Box(Modifier.size(24.u).clip(CircleShape).background(RemoteBlue))      // the remote's blue circle key
                T("Torna in diretta", 26, color = c)
            }
        }
    }
}

/** Fill colour of the TIM remote's circle key (sends PROG_BLUE). */
private val RemoteBlue = Color(0xFF2F7BF5)

@Composable
private fun KeyHint(key: String, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.u)) {
        T(key, 26, 700, color); T(label, 26, color = color)
    }
}

/** 9a: 160 px glass circle with the pause glyph (two 19×64 bars, radius 6, gap 18). */
@Composable
private fun PauseBadge(modifier: Modifier) {
    Box(modifier.size(160.u).glass(999, strong = true), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.u)) {
            repeat(2) { Box(Modifier.size(19.u, 64.u).clip(RoundedCornerShape(6.u)).background(Color.White)) }
        }
    }
}

/**
 * Pause moment (the tuner keeps nothing older) → live edge. 56 px top margin leaves room for the bubble (72 px above the playhead);
 * programme-start ticks are labelled below the track, never above it.
 */
@Composable
private fun Timeline(start: Long, pos: Long, now: Long, progs: List<Programme>, bubble: String) {
    val span = (now - start).coerceAtLeast(1)
    val frac = ((pos - start).toFloat() / span).coerceIn(0f, 1f)
    val ticks = progs.filter { it.start * 1000 in (start + 1) until now }
    Column(Modifier.fillMaxWidth().padding(top = 56.u)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(56.u), contentAlignment = Alignment.CenterStart) {
            val w = maxWidth
            Box(Modifier.fillMaxWidth().height(14.u).clip(RoundedCornerShape(99.u)).background(Color.White.copy(alpha = .22f)))
            Box(Modifier.width(w * frac).height(14.u).clip(RoundedCornerShape(99.u)).background(Color.White))
            ticks.forEach { p ->
                val f = (p.start * 1000 - start).toFloat() / span
                Box(Modifier.offset(x = w * f - 1.5f.u).size(3.u, 32.u).background(Color.White.copy(alpha = .75f)))
            }
            // live edge: 24 px red dot, 3 px white border, red glow
            Box(Modifier.offset(x = w - 12.u).size(24.u).drawBehind {
                drawCircle(Brush.radialGradient(listOf(C.Accent.copy(alpha = .6f), Color.Transparent)), radius = size.minDimension * 1.2f)
            }.clip(CircleShape).background(Color.White).padding(3.u).clip(CircleShape).background(C.Accent))
            // playhead: 40 px with an 8 px halo
            Box(Modifier.offset(x = w * frac - 28.u).size(56.u).clip(CircleShape).background(Color.White.copy(alpha = .22f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.u).shadow(12.u, CircleShape).clip(CircleShape).background(Color.White))
            }
            // bubble: centred 72 px above the playhead, kept inside the bar
            Bubble(bubble, w, w * frac)
        }
        // under-track labels + programme-boundary labels
        BoxWithConstraints(Modifier.fillMaxWidth().height(40.u).padding(top = 6.u)) {
            val w = maxWidth
            val dim = Color.White.copy(alpha = .78f)
            T("${hhmm(start / 1000)} · messa in pausa", 26, color = dim, tabular = true, modifier = Modifier.align(Alignment.TopStart))
            T("${hhmm(now / 1000)} · diretta", 26, 600, tabular = true, modifier = Modifier.align(Alignment.TopEnd))
            val edge = 380.u                                       // keep clear of the two edge labels
            ticks.forEach { p ->
                val x = w * ((p.start * 1000 - start).toFloat() / span)
                if (x > edge && x < w - edge) CenteredAt(x) {
                    T("${hhmm(p.start)} · inizio ${p.title}", 24, color = Color.White.copy(alpha = .7f), tabular = true,
                        modifier = Modifier.widthIn(max = 560.u))
                }
            }
        }
    }
}

/** Places [content] horizontally centred on [x] (clamped to the parent). */
@Composable
private fun CenteredAt(x: Dp, content: @Composable () -> Unit) {
    Box(Modifier.layout { m, c ->
        val p = m.measure(c.copy(minWidth = 0))
        layout(p.width, p.height) {
            val left = (x.toPx() - p.width / 2f).roundToInt().coerceIn(0, (c.maxWidth - p.width).coerceAtLeast(0))
            p.place(left, 0)
        }
    }) { content() }
}

@Composable
private fun Bubble(text: String, w: Dp, x: Dp) {
    val lift = 72.u
    Box(Modifier.fillMaxWidth().layout { m, c ->
        val p = m.measure(c.copy(minWidth = 0))
        layout(c.maxWidth, c.maxHeight) {
            val left = (x.toPx() - p.width / 2f).roundToInt().coerceIn(0, (w.toPx() - p.width).roundToInt().coerceAtLeast(0))
            val top = (c.maxHeight / 2f - lift.toPx() - p.height / 2f).roundToInt()
            p.place(left, top)
        }
    }) {
        Box(Modifier.clip(RoundedCornerShape(999.u)).background(C.FocusFill).padding(horizontal = 20.u, vertical = 8.u)) {
            T(text, 28, 700, C.OnFocus, tabular = true)
        }
    }
}

// ---------------------------------------------------------------- delay indicator

/** Bar hidden but playback delayed: small pill at x 80, y 150 (under the channel pill), updated every second. */
@Composable
fun TimeshiftIndicator(s: LiveState) {
    val now = rememberNowMs()
    Box(Modifier.fillMaxSize().padding(start = 80.u, top = 150.u), contentAlignment = Alignment.TopStart) {
        Row(Modifier.glass(999).padding(horizontal = 26.u, vertical = 12.u),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.u)) {
            Box(Modifier.size(12.u).border(3.u, Color.White, CircleShape))
            T((if (s.tsPaused) "IN PAUSA " else "DIFFERITA ") + deltaLabel(s.tsBehind(now)), 28, 700, tabular = true)
        }
    }
}
