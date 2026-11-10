package it.mdv.dttchannels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import coil.compose.AsyncImage
import coil.decode.DataSource
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import it.mdv.dttchannels.Channel
import it.mdv.dttchannels.LiveState
import it.mdv.dttchannels.Programme
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

// All animations here are time-driven (withFrameMillis) rather than Compose tweens: they must also run
// when the box's animator duration scale is 0 (see LevelBars).

/** URLs decoded at least once this session: shown again without the browse debounce. */
private val seenUrls: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

private fun easeOutCubic(x: Float): Float { val i = 1f - x; return 1f - i * i * i }

/** Runs [target] from its current value to 1 over [ms] once [go] is true. Read it in the draw phase only. */
@Composable
private fun rememberRamp(go: Boolean, ms: Int): MutableFloatState {
    val v = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(go) {
        if (!go || v.floatValue >= 1f) return@LaunchedEffect
        val from = v.floatValue
        val t0 = withFrameMillis { it }
        while (v.floatValue < 1f) withFrameMillis { t -> v.floatValue = (from + (t - t0).toFloat() / ms).coerceAtMost(1f) }
    }
    return v
}

/**
 * Network image that stays invisible until decoded, then fades (and optionally settles from a slight zoom) in
 * over whatever is drawn beneath it. Memory-cache hits appear instantly. [delayMs] debounces the request while
 * the user is moving fast through channels/programmes (skipped for images already seen).
 */
@Composable
fun FadeInImage(
    url: String, modifier: Modifier, contentScale: ContentScale,
    delayMs: Long = 0, zoom: Float = 0f, ms: Int = 450, onError: () -> Unit = {},
) = key(url) {
    var requested by remember { mutableStateOf(delayMs <= 0 || url in seenUrls) }
    if (!requested) LaunchedEffect(Unit) { delay(delayMs); requested = true }
    var loaded by remember { mutableStateOf(false) }
    val reveal = rememberRamp(loaded, ms)
    if (requested) AsyncImage(
        model = url, contentDescription = null, contentScale = contentScale,
        modifier = modifier.graphicsLayer {
            val e = easeOutCubic(reveal.floatValue)
            alpha = e
            if (zoom > 0f) { val s = 1f + zoom * (1f - e); scaleX = s; scaleY = s }
        },
        onSuccess = { st ->
            if (st.result.dataSource == DataSource.MEMORY_CACHE) reveal.floatValue = 1f
            seenUrls += url
            loaded = true
        },
        onError = { onError() },
    )
}

/** Logo (fit), fading in over an empty tile; the channel name only when there is no logo or it fails. */
@Composable
private fun ChannelLogo(ch: Channel?, textSize: Int, textColor: Color, upper: Boolean) {
    var failed by remember(ch?.logo) { mutableStateOf(false) }
    val logo = ch?.logo
    if (logo != null && !failed) FadeInImage(logo, Modifier.fillMaxSize(), ContentScale.Fit, ms = 220, onError = { failed = true })
    else T((ch?.name ?: "").let { if (upper) it.uppercase() else it }, textSize, 700, textColor,
        maxLines = 2, align = TextAlign.Center, lineHeight = 1.05f)
}

/** Channel logo on a chip tile; falls back to the channel name. */
@Composable
fun LogoTile(ch: Channel?, w: Int, h: Int, radius: Int, focused: Boolean = false) {
    val shape = RoundedCornerShape(radius.u)
    Box(
        Modifier.size(w.u, h.u).clip(shape)
            .background(if (focused) Color(0x140D0F13) else Color.White.copy(alpha = .10f))
            .border(1.5f.u, if (focused) Color(0x220D0F13) else C.ChipBorder, shape)
            .padding(horizontal = 10.u, vertical = 8.u),
        contentAlignment = Alignment.Center,
    ) { ChannelLogo(ch, 18, if (focused) C.OnFocus2 else C.Text2, upper = true) }
}

/**
 * Programme artwork, progressive: the branded backdrop (tinted gradient + channel logo) is drawn immediately,
 * the XMLTV image fades and settles in on top once it has downloaded and decoded.
 */
@Composable
fun ProgrammeImage(p: Programme?, ch: Channel?, modifier: Modifier, radius: Int, logoScale: Float = .5f, delayMs: Long = 0) {
    val shape = RoundedCornerShape(radius.u)
    Box(modifier.clip(shape)) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2B3140), Color(0xFF15181F)))),
            contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize(logoScale), contentAlignment = Alignment.Center) { ChannelLogo(ch, 26, C.Text2, upper = false) }
        }
        p?.image?.let { FadeInImage(it, Modifier.fillMaxSize(), ContentScale.Crop, delayMs = delayMs, zoom = .06f, ms = 520) }
        Box(Modifier.fillMaxSize().border(1.5f.u, Color.White.copy(alpha = .16f), shape))
    }
}

// ---------------------------------------------------------------- skeletons

/** Frame clock shared by a skeleton: only invalidates the draw phase, never recomposes. */
@Composable
private fun rememberFrameTime(): State<Long> {
    val t = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { t.longValue = it } }
    return t
}

/** Shimmering placeholder bar. [w] in design px (null = fill width); [h] the bar height. */
@Composable
fun Skeleton(w: Int?, h: Int, modifier: Modifier = Modifier, radius: Int = 12, light: Boolean = false) {
    val t = rememberFrameTime()
    val base = if (light) Color(0x1A0D0F13) else Color.White.copy(alpha = .10f)
    val hi = if (light) Color(0x260D0F13) else Color.White.copy(alpha = .16f)
    Box(
        modifier.then(if (w != null) Modifier.width(w.u) else Modifier.fillMaxWidth()).height(h.u)
            .clip(RoundedCornerShape(radius.u))
            .drawBehind {
                drawRect(base)
                val band = 360f * density
                val phase = (t.value % 1500L) / 1500f                       // same phase for every bar: they sweep together
                val x = -band + (size.width + 2 * band) * phase
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, hi, Color.Transparent), startX = x, endX = x + band))
            },
    )
}

/** Skeleton sized like one line of [T] text at design size [size]. */
@Composable
fun SkeletonLine(size: Int, w: Int?, lineHeight: Float = 1.2f, light: Boolean = false) {
    Box(Modifier.height((size * lineHeight).u), contentAlignment = Alignment.CenterStart) {
        Skeleton(w, (size * .72f).toInt(), radius = 10, light = light)
    }
}

// ---------------------------------------------------------------- prefetch

/**
 * Warms the image caches in priority order, after the visible UI has had its turn:
 *  1. logo + on-air/next artwork of the described channel and its ±2 neighbours (CH±, ↑/↓ land on cached images);
 *  2. every channel logo, once (decoded at tile size: ~64 KB each), so lists and the guide open fully drawn.
 * Requests run one at a time and are cancelled as soon as the user moves on.
 */
/** Covers every logo tile (≤112 design px wide) and the OSD backdrop; larger requests just reload from disk. */
private const val LOGO_PX = 128

@Composable
fun ImageWarmup(s: LiveState) {
    val ctx = LocalContext.current
    val loader = ctx.imageLoader
    suspend fun warm(url: String, px: Int, memory: Boolean) {
        loader.execute(ImageRequest.Builder(ctx).data(url).size(px)
            .memoryCachePolicy(if (memory) CachePolicy.ENABLED else CachePolicy.DISABLED).build())
    }
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(s.osdChannel ?: s.channel, s.epgLoaded, s.now / 600) }
            .distinctUntilChanged()
            .collectLatest { (ch, _, _) ->
                val list = s.channels
                val i = ch?.let { list.indexOf(it) } ?: return@collectLatest
                delay(700)                                   // the card on screen loads first
                for (d in listOf(0, 1, -1, 2, -2)) {
                    val c = list[(i + d + list.size) % list.size]
                    c.logo?.let { warm(it, LOGO_PX, memory = true) }
                    s.nowOn(c)?.image?.let { warm(it, 400, memory = true) }
                    s.nextOn(c)?.image?.let { warm(it, 400, memory = false) }
                }
            }
    }
    LaunchedEffect(s.channels) {
        delay(5_000)                                         // after the first picture and the OSD artwork
        for (url in s.channels.mapNotNull { it.logo }.distinct()) warm(url, LOGO_PX, memory = true)
    }
}
