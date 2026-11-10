package it.mdv.dttchannels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- time formatting (Italian)

private val IT = Locale.ITALIAN
fun hhmm(epochSec: Long): String = SimpleDateFormat("HH:mm", IT).format(Date(epochSec * 1000))
fun hhmmss(epochMs: Long): String = SimpleDateFormat("HH:mm:ss", IT).format(Date(epochMs))
fun shortDate(epochSec: Long): String = SimpleDateFormat("EEE d MMM", IT).format(Date(epochSec * 1000))
fun longDate(epochSec: Long): String = SimpleDateFormat("EEEE d MMMM", IT).format(Date(epochSec * 1000))

fun durationIt(minutes: Long): String {
    val h = minutes / 60; val m = minutes % 60
    return when { h == 0L -> "$m min"; m == 0L -> "$h h"; else -> "$h h $m min" }
}

// ---------------------------------------------------------------- pills

/** Channel pill: white circle with the LCN (or the digits being typed) + channel name. */
@Composable
fun ChannelPill(lcn: String, name: String, modifier: Modifier = Modifier) {
    Row(
        modifier.glass(999).padding(start = 12.u, top = 12.u, bottom = 12.u, end = 30.u),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.u),
    ) {
        Box(Modifier.widthIn(min = 64.u).height(64.u).clip(RoundedCornerShape(999.u)).background(Color.White).padding(horizontal = 14.u),
            contentAlignment = Alignment.Center) {
            T(lcn, 34, 700, C.OnFocus, tabular = true)
        }
        T(name, 32, 600)
    }
}

@Composable
fun ClockPill(now: Long, withDate: Boolean = true, modifier: Modifier = Modifier) {
    Row(
        modifier.glass(999).padding(horizontal = 30.u, vertical = 14.u),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.u),
    ) {
        T(hhmm(now), 36, 600, tabular = true)
        if (withDate) T(shortDate(now), 24, color = Color.White.copy(alpha = .75f))
    }
}

@Composable
fun Clock(now: Long, size: Int = 36, modifier: Modifier = Modifier) = T(hhmm(now), size, 600, tabular = true, modifier = modifier)

// ---------------------------------------------------------------- live dot, progress, hints, chips

@Composable
fun LiveDot(size: Int = 12) {
    Box(Modifier.size(size.u).drawBehind {
        drawCircle(Brush.radialGradient(listOf(C.Accent.copy(alpha = .55f), Color.Transparent)), radius = this.size.minDimension * 1.4f)
        drawCircle(C.Accent)
    })
}

@Composable
fun Progress(fraction: Float, height: Int = 10, modifier: Modifier = Modifier, glow: Boolean = true, fill: Color = Color.White, track: Color = C.Track) {
    Box(modifier.height(height.u).clip(RoundedCornerShape(99.u)).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f))
            .then(if (glow) Modifier.shadow(8.u, RoundedCornerShape(99.u), ambientColor = Color.White, spotColor = Color.White) else Modifier)
            .clip(RoundedCornerShape(99.u)).background(fill))
    }
}

@Composable
fun Hints(vararg items: Pair<String, String>, color: Color = Color.White.copy(alpha = .75f), gap: Int = 36) {
    Row(horizontalArrangement = Arrangement.spacedBy(gap.u), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (k, label) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.u), verticalAlignment = Alignment.CenterVertically) {
                T(k, 24, 700, color); T(label, 24, 400, color)
            }
        }
    }
}

@Composable
fun Chip(large: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(999.u)).background(Color.White.copy(alpha = .14f))
            .border(1.5f.u, C.ChipBorder, RoundedCornerShape(999.u))
            .padding(horizontal = if (large) 20.u else 16.u, vertical = if (large) 8.u else 4.u),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.u), content = content,
    )
}

/** Soft full-screen background with two radial tints (radio / no-signal / settings). */
@Composable
fun TintedBackground(base: Color, a: Color, b: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(base).drawBehind {
        drawRect(Brush.radialGradient(listOf(a, Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * .35f, size.height * .4f), radius = size.width * .45f))
        drawRect(Brush.radialGradient(listOf(b, Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * .72f, size.height * .62f), radius = size.width * .4f))
    })
}

@Composable
fun RadioRing(selected: Boolean, color: Color) {
    Box(Modifier.size(26.u).clip(CircleShape).border(2.5f.u, color, CircleShape), contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size(12.u).clip(CircleShape).background(color))
    }
}
