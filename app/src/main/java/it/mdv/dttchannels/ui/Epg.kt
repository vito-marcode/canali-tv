package it.mdv.dttchannels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import it.mdv.dttchannels.EPG_ROWS
import it.mdv.dttchannels.EPG_SPAN
import it.mdv.dttchannels.EPG_STEP
import it.mdv.dttchannels.LiveState
import it.mdv.dttchannels.Programme
import java.util.Calendar

private const val PANEL_W = 1760          // x 80 -> 1840
private const val PANEL_PAD_H = 28
private const val CHAN_COL = 280
private const val TIMELINE_W = PANEL_W - 2 * PANEL_PAD_H - CHAN_COL   // 1424 px for 150 min
private const val ROW_H = 82
private const val ROW_GAP = 10

@Composable
fun EpgScreen(s: LiveState) {
    Box(Modifier.fillMaxSize().background(Color(0xF0080A0D))) {
        TintedBackground(Color.Transparent, Color(0x2E5A3A26), Color(0x2E1F3F5E))
        val focusRowCh = s.channels.getOrNull(s.epgRow)
        val fp = s.epgFocused()

        // header
        Row(Modifier.padding(start = 80.u, top = 56.u), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.u)) {
            T("Guida TV", 40, 700)
            T(dayLabel(s.epgAnchor, s.now), 26, color = Color.White.copy(alpha = .72f))
        }
        Box(Modifier.align(Alignment.TopEnd).padding(end = 80.u, top = 56.u)) { Clock(s.now) }

        // hero image (right, masked from the left)
        ProgrammeImage(fp, focusRowCh,
            Modifier.align(Alignment.TopEnd).padding(end = 80.u, top = 132.u).size(760.u, 320.u)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.horizontalGradient(0f to Color.Transparent, .4f to Color.Black), blendMode = BlendMode.DstIn)
                }, radius = 44, logoScale = .35f, delayMs = 180)   // ← → / ↑ ↓ repeat fast: load artwork once settled

        // hero text
        Column(Modifier.padding(start = 80.u, top = 140.u).width(1000.u), verticalArrangement = Arrangement.spacedBy(14.u)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.u)) {
                if (focusRowCh != null) T("${focusRowCh.lcn} · ${focusRowCh.name}", 24, 600)
                if (fp != null) {
                    T("·", 24, color = C.Text2); T("${hhmm(fp.start)} – ${hhmm(fp.end)}", 24, tabular = true, color = C.Text2)
                    if (fp.genre.isNotBlank()) { T("·", 24, color = C.Text2); T(fp.genre, 24, color = C.Text2) }
                    if (fp.isOn(s.now)) Chip { LiveDot(10); T("IN ONDA", 24, 600) }
                }
            }
            if (!s.epgLoaded) {
                SkeletonLine(76, 820, lineHeight = 1f)
                SkeletonLine(28, 900, lineHeight = 1.4f); SkeletonLine(28, 600, lineHeight = 1.4f)
            } else {
            T(fp?.title ?: (focusRowCh?.name ?: ""), 76, 700, lineHeight = 1f)
            T(fp?.let { p -> listOfNotNull(p.subtitle, p.desc.takeIf { it.isNotBlank() }).joinToString(" — ") }?.ifBlank { null }
                ?: if (fp != null) "Nessuna descrizione disponibile." else "Nessuna informazione disponibile per questo orario.",
                28, color = Color.White.copy(alpha = .8f), maxLines = 3, lineHeight = 1.4f, modifier = Modifier.widthIn(max = 900.u))
            }
            Hints("OK" to "Guarda ora", "← →" to "Orari", "↑ ↓" to "Canali", "CH±" to "Giorno")
        }

        // grid panel
        Box(
            Modifier.padding(start = 80.u, top = 484.u, bottom = 40.u).width(PANEL_W.u).fillMaxHeight()
                .glass(48, strong = true).padding(start = PANEL_PAD_H.u, end = PANEL_PAD_H.u, top = 20.u, bottom = 24.u),
        ) {
          val ws = s.epgWindow
          Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ROW_GAP.u)) {
            // ruler
            Box(Modifier.fillMaxWidth().height(44.u)) {
                Box(Modifier.padding(start = CHAN_COL.u).width(TIMELINE_W.u).fillMaxHeight().clipToBounds()) {
                    var t = ws
                    while (t < ws + EPG_SPAN) {
                        T(hhmm(t), 24, color = Color.White.copy(alpha = .65f), tabular = true,
                            modifier = Modifier.offset(x = (TIMELINE_W * (t - ws).toFloat() / EPG_SPAN).u).align(Alignment.CenterStart))
                        t += EPG_STEP
                    }
                }
            }
            // rows
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val n = s.channels.size
                val first = (s.epgRow - EPG_ROWS / 2).coerceIn(0, (n - EPG_ROWS).coerceAtLeast(0))
                Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP.u)) {
                    for (r in first until minOf(n, first + EPG_ROWS)) {
                        val ch = s.channels[r]
                        Row(Modifier.height(ROW_H.u), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.width(CHAN_COL.u), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.u)) {
                                Box(Modifier.width(64.u), contentAlignment = Alignment.CenterEnd) {  // fits 3 digits
                                    T(ch.lcn, 28, 600, if (r == s.epgRow) C.White else C.Text2, tabular = true)
                                }
                                LogoTile(ch, 112, 64, 18)
                            }
                            Box(Modifier.width(TIMELINE_W.u).fillMaxHeight().clipToBounds()) {
                                val progs = s.gridProgrammes(ch).filter { it.end > ws && it.start < ws + EPG_SPAN }
                                if (!s.epgLoaded) {
                                    // guide still parsing: two placeholder cells per row
                                    Row(Modifier.fillMaxSize()) {
                                        Skeleton(null, ROW_H, Modifier.weight(.45f).padding(horizontal = 4.u), radius = 22)
                                        Skeleton(null, ROW_H, Modifier.weight(.55f).padding(horizontal = 4.u), radius = 22)
                                    }
                                } else if (progs.isEmpty()) {
                                    val focused = r == s.epgRow
                                    Cell(null, "Nessuna informazione", null, focused, past = false, now = false,
                                        Modifier.fillMaxSize())
                                }
                                for (p in progs) {
                                    val l = ((p.start - ws).coerceAtLeast(0)).toFloat() / EPG_SPAN
                                    val rr = ((p.end - ws).coerceAtMost(EPG_SPAN)).toFloat() / EPG_SPAN
                                    val focused = r == s.epgRow && p.isOn(s.epgAnchor)
                                    Cell(p, p.title, "${hhmm(p.start)} – ${hhmm(p.end)}", focused, past = p.end <= s.now, now = p.isOn(s.now),
                                        Modifier.offset(x = (TIMELINE_W * l).u).width((TIMELINE_W * (rr - l)).u).fillMaxHeight())
                                }
                            }
                        }
                    }
                }
            }
          }
          // now line: from the top of the ruler down through the rows (as in the design)
          if (s.now in ws until ws + EPG_SPAN) {
              val x = CHAN_COL + TIMELINE_W * (s.now - ws).toFloat() / EPG_SPAN
              Box(Modifier.offset(x = x.u - 1.5f.u).width(3.u).fillMaxHeight().drawBehind {
                  drawRect(Brush.horizontalGradient(listOf(Color.Transparent, C.Accent.copy(alpha = .45f), Color.Transparent)),
                      topLeft = androidx.compose.ui.geometry.Offset(-size.width * 3, 0f),
                      size = androidx.compose.ui.geometry.Size(size.width * 7, size.height))
              }.background(C.Accent))
          }
        }
    }
}

@Composable
private fun Cell(p: Programme?, title: String, time: String?, focused: Boolean, past: Boolean, now: Boolean, modifier: Modifier) {
    val idle = if (now) Color.White.copy(alpha = .16f) else Color.White.copy(alpha = .07f)
    Box(modifier.padding(horizontal = 4.u).alpha(if (past && !focused) .6f else 1f)) {
        Column(
            Modifier.fillMaxSize().focusCard(focused, 22, scale = 1.04f, idle = idle, idleBorder = Color.White.copy(alpha = .12f))
                .padding(horizontal = 20.u),
            verticalArrangement = Arrangement.Center,
        ) {
            T(title, 26, 600, if (focused) C.OnFocus else C.White)
            if (time != null) T(time, 24, tabular = true, color = if (focused) C.OnFocus2 else Color.White.copy(alpha = .7f))
        }
    }
}

private fun dayLabel(t: Long, now: Long): String {
    fun day(x: Long) = Calendar.getInstance().apply { timeInMillis = x * 1000 }.let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) }
    val d = day(t) - day(now)
    val prefix = when (d) { 0 -> "Oggi, "; 1 -> "Domani, "; -1 -> "Ieri, "; else -> "" }
    return prefix + longDate(t)
}
