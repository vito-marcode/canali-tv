package it.mdv.dttchannels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import it.mdv.dttchannels.LIST_ROWS
import it.mdv.dttchannels.LiveState
import it.mdv.dttchannels.TrackOpt

// ---------------------------------------------------------------- 1h  Lista canali

@Composable
fun ChannelListPanel(s: LiveState) {
    Box(Modifier.fillMaxSize().padding(top = 40.u, end = 40.u, bottom = 40.u), contentAlignment = Alignment.TopEnd) {
        Column(
            Modifier.width(720.u).fillMaxHeight().glass(56, strong = true).padding(horizontal = 32.u, vertical = 36.u),
            verticalArrangement = Arrangement.spacedBy(22.u),
        ) {
            T("Canali", 40, 700)
            // open already positioned on the watched channel (e.g. 551): no scroll from the top through every row
            val state = rememberLazyListState(initialFirstVisibleItemIndex = (s.listFocus - LIST_ROWS / 2).coerceAtLeast(0))
            LaunchedEffect(s.listFocus) {
                val first = state.firstVisibleItemIndex
                val target = when {
                    s.listFocus < first + 1 -> (s.listFocus - 1).coerceAtLeast(0)
                    s.listFocus > first + LIST_ROWS - 2 -> s.listFocus - LIST_ROWS + 2
                    else -> first
                }
                // ↑/↓ glide one row; big jumps (CH± pages) snap so only the destination rows are composed
                if (target != first) {
                    if (kotlin.math.abs(target - first) > 2) state.scrollToItem(target) else state.animateScrollToItem(target)
                }
            }
            LazyColumn(Modifier.weight(1f), state = state, verticalArrangement = Arrangement.spacedBy(8.u),
                contentPadding = PaddingValues(horizontal = 12.u, vertical = 6.u)) {
                itemsIndexed(s.channels, key = { _, c -> c.id }) { i, ch ->
                    val focused = i == s.listFocus
                    val playing = i == s.current
                    val p = s.nowOn(ch)
                    val fg = if (focused) C.OnFocus else C.White
                    val fg2 = if (focused) C.OnFocus2 else Color.White.copy(alpha = .72f)
                    Row(
                        Modifier.fillMaxWidth().height(100.u)
                            .focusCard(focused, 30, idle = if (playing) Color.White.copy(alpha = .10f) else Color.Transparent)
                            .padding(horizontal = 22.u),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.u),
                    ) {
                        Box(Modifier.width(64.u), contentAlignment = Alignment.CenterEnd) { T(ch.lcn, 28, 600, fg, tabular = true) }  // fits 3 digits
                        LogoTile(ch, 96, 60, 16, focused)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.u)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.u)) {
                                T(ch.name, 28, 600, fg, modifier = Modifier.weight(1f, fill = false))
                                if (playing) T("· IN VISIONE", 22, 600, fg2, letterSpacing = .04f)
                            }
                            if (p == null && !s.epgLoaded) SkeletonLine(24, 300, light = focused)
                            else T(p?.title ?: if (ch.radio) "Radio" else "—", 24, color = fg2)
                            if (p != null) Progress((s.now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1), 4, Modifier.fillMaxWidth(), glow = false,
                                fill = if (focused) C.OnFocus else Color.White, track = if (focused) Color(0x2E0D0F13) else C.Track)
                        }
                    }
                }
            }
            Hints("OK" to "Guarda", "Indietro" to "Chiudi")
        }
    }
}

// ---------------------------------------------------------------- 1i  Menu rapido + Audio e sottotitoli

private val QUICK_ITEMS = listOf("Guida TV", "Lista canali", "Audio e sottotitoli", "Impostazioni")

@Composable
fun QuickMenu(s: LiveState) {
    Box(Modifier.fillMaxSize()) {
        BottomScrim()
        if (s.avOpen) AvPanel(s, Modifier.align(Alignment.BottomCenter).padding(bottom = 200.u))
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 64.u).glass(999, strong = true).padding(10.u),
            horizontalArrangement = Arrangement.spacedBy(10.u),
        ) {
            QUICK_ITEMS.forEachIndexed { i, label ->
                val focused = !s.avInPanel && i == s.quickFocus
                val open = i == 2 && s.avOpen
                Box(
                    Modifier.focusCard(focused, 999, idle = if (open) Color.White.copy(alpha = .2f) else Color.Transparent,
                        idleBorder = if (open) Color.White.copy(alpha = .3f) else null)
                        .padding(horizontal = 34.u, vertical = 18.u),
                ) { T(label, 26, if (focused || open) 600 else 500, if (focused) C.OnFocus else C.White) }
            }
        }
    }
}

@Composable
private fun AvPanel(s: LiveState, modifier: Modifier) {
    Row(modifier.width(1200.u).glass(56, strong = true).padding(36.u), horizontalArrangement = Arrangement.spacedBy(36.u)) {
        AvColumn("AUDIO", s.audioTracks, s.selAudio, focusedRow = if (s.avInPanel && s.avCol == 0) s.avRow else -1, Modifier.weight(1f),
            empty = "Nessuna traccia audio disponibile")
        AvColumn("SOTTOTITOLI", s.subTracks, s.selSub, focusedRow = if (s.avInPanel && s.avCol == 1) s.avRow else -1, Modifier.weight(1f))
    }
}

@Composable
private fun AvColumn(title: String, opts: List<TrackOpt>, selected: String?, focusedRow: Int, modifier: Modifier, empty: String = "") {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.u)) {
        T(title, 24, 600, Color.White.copy(alpha = .7f), letterSpacing = .08f, modifier = Modifier.padding(start = 20.u, bottom = 8.u))
        if (opts.isEmpty()) T(empty, 24, color = C.Text3, modifier = Modifier.padding(start = 20.u))
        opts.forEachIndexed { i, o ->
            val focused = i == focusedRow
            val fg = if (focused) C.OnFocus else C.White
            Row(
                Modifier.fillMaxWidth().focusCard(focused, 26).padding(horizontal = 20.u, vertical = 18.u),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.u),
            ) {
                RadioRing(o.id == selected, fg)
                Column {
                    T(o.label, 28, 600, fg)
                    if (o.meta.isNotBlank()) T(o.meta, 24, color = if (focused) C.OnFocus2 else Color.White.copy(alpha = .75f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 1j  Impostazioni

@Composable
fun SettingsScreen(s: LiveState, version: String) {
    Box(Modifier.fillMaxSize().background(Color(0xF00A0B0E))) {
        TintedBackground(Color.Transparent, Color(0x332A1A12), Color(0x331F3F5E))
        T("Impostazioni", 64, 700, modifier = Modifier.padding(start = 160.u, top = 96.u))
        Box(Modifier.align(Alignment.TopEnd).padding(end = 160.u, top = 104.u)) { Clock(s.now, 34) }
        Row(Modifier.padding(start = 160.u, end = 160.u, top = 290.u).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.u)) {
            SettingsTile("Scansiona canali", s.settingsFocus == 0, Modifier.weight(1f), icon = { c -> Box(Modifier.size(28.u).clip(RoundedCornerShape(99.u)).border(3f.u, c, RoundedCornerShape(99.u))) })
            SettingsTile("Sincronizza", s.settingsFocus == 1, Modifier.weight(1f), icon = { c -> Box(Modifier.size(24.u).rotate(45f).border(3f.u, c, RoundedCornerShape(4.u))) })
        }
        val sub = s.busy ?: if (s.channels.isEmpty()) "Nessun canale: premi Sincronizza per scaricare la lista dal Raspberry" else
            "${s.channels.size} canali · guida TV per ${s.epg.size} canali"
        T(sub, 28, 500, Color.White.copy(alpha = .8f), modifier = Modifier.padding(start = 160.u, top = 650.u))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 160.u, vertical = 96.u), horizontalArrangement = Arrangement.SpaceBetween) {
            T("Sintonizzatore DVB-T2 · Technicolor · Versione $version", 24, color = Color.White.copy(alpha = .7f))
            Hints("Indietro" to "Chiudi", color = Color.White.copy(alpha = .7f))
        }
    }
}

@Composable
private fun SettingsTile(title: String, focused: Boolean, modifier: Modifier, icon: @Composable (Color) -> Unit) {
    val fg = if (focused) C.OnFocus else C.White
    Column(
        modifier.height(300.u).then(if (focused) Modifier.focusCard(true, 56) else Modifier.glass(56)).padding(52.u),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.size(72.u).clip(RoundedCornerShape(99.u)).background(if (focused) C.OnFocus else Color.White.copy(alpha = .14f)),
            contentAlignment = Alignment.Center) { icon(if (focused) Color.White else Color.White.copy(alpha = .9f)) }
        T(title, 52, 700, fg)
    }
}
