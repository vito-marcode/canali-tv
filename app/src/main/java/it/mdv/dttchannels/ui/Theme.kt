@file:OptIn(ExperimentalTextApi::class)

package it.mdv.dttchannels.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import it.mdv.dttchannels.R

// ---------------------------------------------------------------- scale: design px @1920 -> dp

/** dp per design px. 0.5 on a 1920x1080 / 320 dpi box (dp = px / 2, as in the handoff). */
val LocalScale = compositionLocalOf { 0.5f }

val Int.u: Dp @Composable get() = (this * LocalScale.current).dp
val Float.u: Dp @Composable get() = (this * LocalScale.current).dp
val Int.ts: TextUnit @Composable get() = with(LocalDensity.current) { (this@ts * LocalScale.current).dp.toSp() }

// ---------------------------------------------------------------- tokens

object C {
    val White = Color.White
    val Text2 = Color.White.copy(alpha = .78f)
    val Text3 = Color.White.copy(alpha = .65f)
    val OnFocus = Color(0xFF0D0F13)
    val OnFocus2 = Color(0xFF0D0F13).copy(alpha = .7f)
    val Accent = Color(0xFFE5484D)          // oklch(0.68 0.21 25)
    val FocusFill = Color.White.copy(alpha = .95f)
    val Chip = Color.White.copy(alpha = .12f)
    val ChipBorder = Color.White.copy(alpha = .17f)
    val Track = Color.White.copy(alpha = .18f)
    val GlassBase = Color(0xB81E2026)       // rgba(30,32,38,.72): fallback, SurfaceView can't be blurred
}

val Figtree = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(R.font.figtree, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

// ---------------------------------------------------------------- text

@Composable
fun T(
    text: String, size: Int, weight: Int = 400, color: Color = C.White, modifier: Modifier = Modifier,
    tabular: Boolean = false, maxLines: Int = 1, lineHeight: Float = 1.2f, letterSpacing: Float = 0f,
    align: TextAlign = TextAlign.Start,
) {
    // small text (design 26 px and below: meta, hints, times) is drawn ~3 px larger for legibility at TV distance
    val sp = (if (size <= 26) size + 3 else size).ts
    BasicText(
        text, modifier,
        style = TextStyle(
            fontFamily = Figtree, fontWeight = FontWeight(weight), fontSize = sp, color = color,
            lineHeight = sp * lineHeight, letterSpacing = sp * letterSpacing, textAlign = align,
            fontFeatureSettings = if (tabular) "tnum" else null,
        ),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

// ---------------------------------------------------------------- materials

/**
 * Glass panel (fallback variant: semi-opaque base + the design's gradient, border and top highlight).
 * No drop shadow by default: without a real blur the shadow shows through the translucent fill.
 */
@Composable
fun Modifier.glass(radius: Int, strong: Boolean = false, shadow: Boolean = false): Modifier {
    val shape = RoundedCornerShape(radius.u)
    val top = if (strong) .22f else .20f
    val bottom = if (strong) .07f else .06f
    return this
        .then(if (shadow) Modifier.shadow(28.u, shape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
        .clip(shape)
        .background(C.GlassBase)
        .background(Brush.linearGradient(listOf(Color.White.copy(alpha = top), Color.White.copy(alpha = bottom))))
        .border(1.5f.u, Brush.verticalGradient(
            listOf(Color.White.copy(alpha = if (strong) .50f else .45f), Color.White.copy(alpha = if (strong) .26f else .24f), Color.White.copy(alpha = .10f))
        ), shape)
}

/** Lighter glass used by the OSD "next" card. */
@Composable
fun Modifier.glassLight(radius: Int): Modifier {
    val shape = RoundedCornerShape(radius.u)
    return this.clip(shape)
        .background(Color(0x99202228))
        .background(Brush.linearGradient(listOf(Color.White.copy(alpha = .12f), Color.White.copy(alpha = .04f))))
        .border(1.5f.u, Color.White.copy(alpha = .18f), shape)
}

/** The single focus style: white .95 fill, scale 1.03–1.04, soft shadow, 150 ms ease-out. */
@Composable
fun Modifier.focusCard(focused: Boolean, radius: Int, scale: Float = 1.03f, idle: Color = Color.Transparent, idleBorder: Color? = null): Modifier {
    val s by animateFloatAsState(if (focused) scale else 1f, tween(150), label = "focus")
    val shape = RoundedCornerShape(radius.u)
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .then(if (focused) Modifier.shadow(22.u, shape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
        .clip(shape)
        .background(if (focused) C.FocusFill else idle)
        .then(if (!focused && idleBorder != null) Modifier.border(1.5f.u, idleBorder, shape) else Modifier)
}
