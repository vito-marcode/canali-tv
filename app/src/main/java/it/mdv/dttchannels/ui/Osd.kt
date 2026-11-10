package it.mdv.dttchannels.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import it.mdv.dttchannels.Channel
import it.mdv.dttchannels.LiveState
import it.mdv.dttchannels.Programme

/** Bottom scrim: rgba(0,0,0,.5) -> transparent at 45% height. */
@Composable
fun BottomScrim() {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .55f to Color.Transparent, 1f to Color.Black.copy(alpha = .5f))))
}

/** Channel pill + clock pill (shared by OSD, no-signal and digit entry). */
@Composable
fun TopPills(s: LiveState, showChannel: Boolean = true) {
    Box(Modifier.fillMaxWidth().padding(start = 80.u, end = 80.u, top = 48.u)) {
        if (showChannel || s.digits.isNotEmpty()) {
            val typed = s.digits
            if (typed.isNotEmpty()) {
                ChannelPill(typed, s.digitMatch?.name ?: "…", Modifier.align(Alignment.TopStart))
            } else s.osdChannel?.let { ChannelPill(it.lcn, it.name, Modifier.align(Alignment.TopStart)) }
        }
        ClockPill(s.now, modifier = Modifier.align(Alignment.TopEnd))
    }
}

@Composable
fun Osd(s: LiveState) {
    val ch = s.osdChannel ?: return
    val shown = s.osdProgramme()
    val offset = s.osdOffset

    Box(Modifier.fillMaxSize()) {
        BottomScrim()
        TopPills(s)
        // one full-width card: → steps forward through the schedule, ← steps back to the current programme
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 80.u, end = 80.u, bottom = 110.u)) {
            // while browsing with ↑/↓ the text updates at once; artwork waits for the user to settle
            val imageDelay = if (s.browsing || offset > 0) BROWSE_IMAGE_DELAY else 0L
            if (s.info) InfoCard(s, ch, shown, offset, imageDelay)
            else NowCard(ch, shown, offset, s.now, loading = !s.epgLoaded, imageDelay = imageDelay)
        }
        Box(Modifier.align(Alignment.BottomStart).padding(start = 80.u, bottom = 36.u)) {
            when {
                s.info -> Hints("Indietro" to "Chiudi info", "OK" to "Lista canali", "↓" to "Menu rapido")
                s.browsing -> Hints("OK" to "Guarda", "↑ ↓" to "Canali", "Indietro" to "Annulla")
                else -> Hints("OK" to "Info", "↑ ↓" to "Canali")
            }
        }
    }
}

private const val BROWSE_IMAGE_DELAY = 220L

/**
 * Digit entry: only the typed number (no name, no clock). The full OSD appears once the number
 * is committed and the channel is tuned (tuneIndex -> showOsd).
 */
@Composable
fun DigitEntry(s: LiveState) {
    if (s.digits.isEmpty()) return
    Box(Modifier.padding(start = 80.u, top = 48.u).glass(999).padding(12.u)) {
        Box(Modifier.widthIn(min = 64.u).height(64.u).clip(CircleShape).background(Color.White).padding(horizontal = 14.u),
            contentAlignment = Alignment.Center) {
            T(s.digits, 34, 700, C.OnFocus, tabular = true)
        }
    }
}

/** Status line: live dot + IN ONDA for the current programme, DOPO for the next, PIÙ TARDI (with day if not today) beyond. */
@Composable
private fun StatusLabel(p: Programme?, offset: Int, nowSec: Long) {
    when {
        p == null -> {}
        offset == 0 -> { LiveDot(); T("IN ONDA", 24, 600, Color.White.copy(alpha = .9f), letterSpacing = .06f) }
        else -> {
            val day = if (shortDate(p.start) != shortDate(nowSec)) " · " + shortDate(p.start).uppercase() else ""
            T((if (offset == 1) "DOPO" else "PIÙ TARDI") + day, 24, 600, Color.White.copy(alpha = .9f), letterSpacing = .06f)
        }
    }
}

@Composable
private fun NowCard(ch: Channel, p: Programme?, offset: Int, nowSec: Long, loading: Boolean, imageDelay: Long) {
    Row(
        Modifier.fillMaxWidth().glass(64, strong = true).padding(start = 36.u, top = 36.u, bottom = 36.u, end = 48.u),
        horizontalArrangement = Arrangement.spacedBy(36.u), verticalAlignment = Alignment.CenterVertically,
    ) {
        ProgrammeImage(p, ch, Modifier.size(200.u), radius = 44, delayMs = imageDelay)
        if (p == null && loading) { CardSkeleton(); return@Row }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.u)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.u)) {
                StatusLabel(p, offset, nowSec)
                val meta = p?.genre?.takeIf { it.isNotBlank() }.orEmpty()
                if (meta.isNotEmpty()) T((if (p != null) "·  " else "") + meta, 24, 400, Color.White.copy(alpha = .78f))
            }
            T(p?.title ?: ch.name, 56, 700, lineHeight = 1.05f)
            if (p == null) {
                T("Guida TV non disponibile per questo canale", 30, color = Color.White.copy(alpha = .75f))
            } else if (offset > 0) {
                T("${hhmm(p.start)} – ${hhmm(p.end)}", 32, 600, tabular = true)
                T("Inizia tra ${durationIt(((p.start - nowSec) / 60).coerceAtLeast(0))}", 30, color = Color.White.copy(alpha = .75f))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.u)) {
                    T(hhmm(p.start), 32, 600, tabular = true)
                    Progress((nowSec - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1), modifier = Modifier.weight(1f))
                    T(hhmm(p.end), 32, 600, tabular = true)
                }
                T("Mancano ${durationIt(((p.end - nowSec) / 60).coerceAtLeast(0))}", 30, color = Color.White.copy(alpha = .75f))
            }
        }
    }
}

/** 4a — Info (expanded OSD): time, synopsis and chips; only data the guide already has + tuner flags. */
@Composable
private fun InfoCard(s: LiveState, ch: Channel, p: Programme?, offset: Int, imageDelay: Long) {
    Row(
        Modifier.fillMaxWidth().glass(64, strong = true).padding(start = 36.u, top = 36.u, bottom = 36.u, end = 48.u),
        horizontalArrangement = Arrangement.spacedBy(36.u), verticalAlignment = Alignment.Top,
    ) {
        ProgrammeImage(p, ch, Modifier.size(200.u), radius = 44, delayMs = imageDelay)
        if (p == null && !s.epgLoaded) { CardSkeleton(lines = 3); return@Row }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.u)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.u)) {
                StatusLabel(p, offset, s.now)
                if (p != null) T("·  ${hhmm(p.start)} – ${hhmm(p.end)}", 32, 600, tabular = true)
            }
            T(p?.title ?: ch.name, 56, 700, lineHeight = 1.05f)
            val synopsis = p?.let { listOfNotNull(it.subtitle, it.desc.takeIf { d -> d.isNotBlank() }).joinToString(" — ") }.orEmpty()
            if (synopsis.isNotBlank()) T(synopsis, 32, color = Color.White.copy(alpha = .85f), maxLines = 3, lineHeight = 1.35f)
            else if (p == null) T("Guida TV non disponibile per questo canale", 30, color = Color.White.copy(alpha = .75f))
            val chips = buildList {
                p?.genre?.takeIf { it.isNotBlank() }?.let { add(it) }
                p?.let { add("${it.minutes} min") }
                if (offset == 0 && !s.browsing) {
                    s.audioTracks.firstOrNull { it.meta.startsWith("Dolby") }?.let { add(it.meta.replace("Dolby Digital", "Dolby")) }
                    if (s.subTracks.size > 1) add("Sottotitoli")
                }
            }
            if (chips.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.u)) { chips.forEach { c -> Chip(large = true) { T(c, 26, 600) } } }
            if (p != null && offset == 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.u)) {
                Progress((s.now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1), modifier = Modifier.weight(1f))
                T("Mancano ${durationIt(((p.end - s.now) / 60).coerceAtLeast(0))}", 30, tabular = true)
            } else if (p != null) T("Inizia tra ${durationIt(((p.start - s.now) / 60).coerceAtLeast(0))}", 30, color = Color.White.copy(alpha = .75f))
        }
    }
}

/** Programme text placeholder while the guide is still being parsed (first seconds after launch). */
@Composable
private fun RowScope.CardSkeleton(lines: Int = 1) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.u)) {
        SkeletonLine(24, 220)
        SkeletonLine(56, 760, lineHeight = 1.05f)
        repeat(lines) { SkeletonLine(30, if (it == lines - 1) 420 else null) }
        SkeletonLine(32, null)
    }
}

/** Short message, bottom-centre ("Canale non disponibile", sync results). */
@Composable
fun Toast(msg: String?) {
    AnimatedVisibility(msg != null, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().padding(bottom = 160.u), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.glass(999).padding(horizontal = 40.u, vertical = 20.u)) { T(msg ?: "", 28, 600) }
        }
    }
}
