package it.mdv.dttchannels.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import it.mdv.dttchannels.LiveState

/** Voice search card: mic disc pulsing with the input level + what has been heard so far. */
@Composable
fun VoiceCard(s: LiveState) {
    val heard = s.voice ?: return
    Box(Modifier.fillMaxSize().padding(bottom = 140.u), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.widthIn(min = 760.u, max = 1400.u).glass(999, strong = true).padding(start = 18.u, end = 48.u, top = 18.u, bottom = 18.u),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.u),
        ) {
            MicDisc(s)
            Column(verticalArrangement = Arrangement.spacedBy(6.u)) {
                if (heard.isEmpty()) {
                    T("Ti ascolto…", 34, 700)
                    T("Di' il canale, per esempio «Metti Rai 2»", 26, color = Color.White.copy(alpha = .72f))
                } else {
                    T("«$heard»", 34, 700, maxLines = 2)
                    T("Indietro per annullare", 24, color = Color.White.copy(alpha = .6f))
                }
            }
        }
    }
}

@Composable
private fun MicDisc(s: LiveState) {
    val level = s.voiceLevel
    Box(
        Modifier.size(96.u).drawBehind {
            // recogniser RMS is roughly -2..10 dB: map to a halo 0..1 around the white disc (draw phase only)
            val k = ((level.floatValue + 2f) / 12f).coerceIn(0f, 1f)
            val r = size.minDimension / 2
            drawCircle(Color.White.copy(alpha = .18f + .22f * k), radius = r * (.78f + .22f * k))
            drawCircle(Color.White, radius = r * .62f)
        },
        contentAlignment = Alignment.Center,
    ) {
        // mic glyph: capsule + cradle + stem, in the dark "on focus" colour
        Box(Modifier.size(40.u).drawBehind {
            val w = size.width; val h = size.height
            val c = C.OnFocus
            val sw = w * .09f
            drawRoundRect(c, topLeft = Offset(w * .34f, 0f), size = Size(w * .32f, h * .62f), cornerRadius = CornerRadius(w * .16f))
            drawArc(c, 0f, 180f, false, topLeft = Offset(w * .2f, h * .22f), size = Size(w * .6f, h * .56f), style = Stroke(sw))
            drawLine(c, Offset(w * .5f, h * .78f), Offset(w * .5f, h * .96f), strokeWidth = sw)
        })
    }
}
