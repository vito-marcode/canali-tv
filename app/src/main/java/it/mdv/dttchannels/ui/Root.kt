package it.mdv.dttchannels.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import it.mdv.dttchannels.LiveState
import it.mdv.dttchannels.Overlay
import it.mdv.dttchannels.Signal

/** Everything above the TvView (which LiveActivity places underneath). */
@Composable
fun LiveRoot(s: LiveState, version: String) {
    val scale = LocalConfiguration.current.screenWidthDp / 1920f
    CompositionLocalProvider(LocalScale provides scale) {
        Box(Modifier.fillMaxSize()) {
            val ch = s.channel
            val radio = ch != null && (ch.radio || s.signal == Signal.AUDIO_ONLY)
            when {
                ch == null -> Box(Modifier.fillMaxSize().background(Color(0xFF0A0B0E)))
                radio -> RadioScreen(s)
                s.signal == Signal.NO_SIGNAL -> NoSignalScreen(s)
            }
            val layerVisible = s.overlay == Overlay.NONE && (s.osdVisible || s.tsBar || s.digits.isNotEmpty()) && !radio && s.signal != Signal.NO_SIGNAL
            // keep whatever layer was showing during its fade-out, instead of flashing the OSD
            val current = when { s.digits.isNotEmpty() -> 1; s.tsBar -> 2; else -> 0 }
            val lastMode = remember { mutableStateOf(0) }
            SideEffect { if (layerVisible) lastMode.value = current }
            AnimatedVisibility(layerVisible, enter = fadeIn(tween(150)), exit = fadeOut(tween(250))) {
                when (if (layerVisible) current else lastMode.value) {
                    1 -> DigitEntry(s)
                    2 -> TimeshiftBar(s)
                    else -> Osd(s)
                }
            }
            if (radio && s.digits.isNotEmpty()) DigitEntry(s)
            // delay indicator: bar hidden but behind live (also under the channel pill of the live OSD)
            if (s.overlay == Overlay.NONE && !s.tsBar && s.digits.isEmpty() && !radio && s.signal != Signal.NO_SIGNAL && s.delayed) TimeshiftIndicator(s)
            when (s.overlay) {
                Overlay.CHANNELS -> ChannelListPanel(s)
                Overlay.QUICK -> QuickMenu(s)
                Overlay.EPG -> EpgScreen(s)
                Overlay.SETTINGS -> SettingsScreen(s, version)
                Overlay.NONE -> {}
            }
            Toast(s.toast)
            ImageWarmup(s)
            if (s.busy != null && s.overlay != Overlay.SETTINGS) BusyCard(s.busy!!)
        }
    }
}

// ---------------------------------------------------------------- 1l  Radio

@Composable
fun RadioScreen(s: LiveState) {
    val ch = s.channel ?: return
    val p = s.nowOn(ch)
    Box(Modifier.fillMaxSize()) {
        TintedBackground(Color(0xFF0E0F12), Color(0xFF5A2E45), Color(0xFF1F3F5E))
        Box(Modifier.align(Alignment.TopEnd).padding(end = 80.u, top = 56.u)) { Clock(s.now) }
        Row(
            Modifier.align(Alignment.Center).width(1000.u).height(680.u).glass(64, strong = true).padding(56.u),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(52.u),
        ) {
            ProgrammeImage(null, ch, Modifier.size(300.u), radius = 56, logoScale = .7f)
            Column(verticalArrangement = Arrangement.spacedBy(16.u)) {
                T("RADIO · ${ch.lcn}", 24, 600, Color.White.copy(alpha = .72f), letterSpacing = .08f)
                T(ch.name, 64, 700, maxLines = 2, lineHeight = 1.05f)
                if (p == null && !s.epgLoaded) SkeletonLine(30, 420)
                else T(p?.title ?: "In diretta", 30, color = Color.White.copy(alpha = .82f), maxLines = 2)
                Spacer(Modifier.height(10.u))
                LevelBars()
            }
        }
        Box(Modifier.align(Alignment.BottomStart).padding(start = 80.u, bottom = 36.u)) {
            Hints("CH±" to "Cambia stazione", "OK" to "Lista canali", "Indietro" to "Torna alla TV")
        }
    }
}

/** Six gently looping level bars. Time-driven (withFrameMillis) so it also runs with animator scale 0. */
@Composable
private fun LevelBars() {
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { t = it } }
    val periods = longArrayOf(620, 840, 540, 960, 700, 480)
    Row(Modifier.height(56.u), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.u)) {
        periods.forEachIndexed { i, period ->
            val phase = ((t + i * 90) % (period * 2)).toFloat() / period          // 0..2
            val tri = if (phase < 1f) phase else 2f - phase                      // 0..1..0
            val h = .25f + .75f * (tri * tri * (3 - 2 * tri))                    // smoothstep
            Box(Modifier.width(10.u).fillMaxHeight(h).clip(RoundedCornerShape(5.u)).background(Color.White))
        }
    }
}

// ---------------------------------------------------------------- 2a  Nessun segnale

@Composable
fun NoSignalScreen(s: LiveState) {
    Box(Modifier.fillMaxSize()) {
        TintedBackground(Color(0xFF0A0B0E), Color(0xFF2A2F3A), Color(0xFF1C2230))
        TopPills(s)
        Column(
            Modifier.align(Alignment.Center).width(700.u).glass(64, strong = true).padding(56.u),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.u),
        ) {
            Box(Modifier.size(104.u).border(5.u, Color.White.copy(alpha = .85f), CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(5.u, 88.u).rotate(45f).background(Color.White.copy(alpha = .85f)))
            }
            T("Nessun segnale", 56, 700)
        }
        Box(Modifier.align(Alignment.BottomStart).padding(start = 80.u, bottom = 36.u)) {
            Hints("CH±" to "Cambia canale", "OK" to "Lista canali")
        }
    }
}

@Composable
private fun BusyCard(msg: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.glass(48, strong = true).padding(horizontal = 56.u, vertical = 36.u)) { T(msg, 32, 600) }
    }
}
