package com.sakura.romance

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class GlassBackdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}
private val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }
private val LocalContentBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }
internal val LocalBottomClearance = staticCompositionLocalOf { 0.dp }

@Composable
internal fun RomanceScene(content: @Composable BoxScope.() -> Unit) {
    val layer = rememberGraphicsLayer()
    val backdrop = remember(layer) { GlassBackdrop(layer) }
    val contentLayer = rememberGraphicsLayer()
    val contentBackdrop = remember(contentLayer) { GlassBackdrop(contentLayer) }
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop, LocalContentBackdrop provides contentBackdrop) {
        Box(Modifier.fillMaxSize()) {
            // Record only the decorative background: private photos never enter the glass layer.
            RomanceBackground(Modifier.onGloballyPositioned { backdrop.origin = it.positionOnScreen() }
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                })
            content()
        }
    }
}

@Composable
internal fun GlassContent(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val backdrop = LocalGlassBackdrop.current
    val foreground = LocalContentBackdrop.current
    Box(modifier.onGloballyPositioned { foreground?.origin = it.positionOnScreen() }.drawWithContent {
        if (foreground != null && backdrop != null) {
            val offset = foreground.origin - backdrop.origin
            foreground.layer.record {
                translate(-offset.x, -offset.y) { drawLayer(backdrop.layer) }
                this@drawWithContent.drawContent()
            }
            drawLayer(foreground.layer)
        } else drawContent()
    }, content = content)
}

@Composable
internal fun RomanceBackground(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize().background(WarmWhite)) {
        val glows = listOf(
            Triple(Offset(size.width * .95f, size.height * .08f), Color(0xFFF0B3C5), .90f),
            Triple(Offset(size.width * .02f, size.height * .44f), Color(0xFFB2D7CD), .70f),
            Triple(Offset(size.width * .98f, size.height * .78f), Color(0xFFE6B2CA), .72f),
            Triple(Offset(size.width * .15f, size.height * .96f), Color(0xFFEEC9B8), .48f)
        )
        glows.forEach { (center, color, alpha) ->
            val radius = size.width * .90f
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center, radius), radius, center)
        }
        // A few large translucent petals give the frosted panels something to diffuse.
        listOf(Offset(.91f, .20f), Offset(.03f, .63f), Offset(.95f, .92f)).forEachIndexed { index, point ->
            val center = Offset(size.width * point.x, size.height * point.y)
            val r = size.width * .23f
            rotate(25f + index * 80f, center) {
                val petal = Path().apply {
                    moveTo(center.x, center.y + r)
                    cubicTo(center.x - r * 1.2f, center.y, center.x - r, center.y - r, center.x, center.y - r * .70f)
                    cubicTo(center.x + r, center.y - r, center.x + r * 1.2f, center.y, center.x, center.y + r)
                }
                drawPath(petal, PureWhite.copy(alpha = .23f))
                drawPath(petal, PureWhite.copy(alpha = .48f), style = Stroke(1.dp.toPx()))
            }
        }
    }
}

@Composable
internal fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    tint: Color = PureWhite.copy(alpha = .26f),
    overContent: Boolean = false,
    content: @Composable () -> Unit
) {
    val backdrop = if (overContent) LocalContentBackdrop.current else LocalGlassBackdrop.current
    val frost = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Zero) }
    val canBlur = Build.VERSION.SDK_INT >= 31 && backdrop != null
    Surface(
        modifier.shadow(10.dp, shape, ambientColor = Color(0xFF896577).copy(alpha = .12f),
            spotColor = Color(0xFF896577).copy(alpha = .16f)),
        shape = shape, color = Color.Transparent, contentColor = TextPrimary,
        border = BorderStroke(.75.dp, Brush.linearGradient(listOf(PureWhite.copy(alpha = .90f),
            PureWhite.copy(alpha = .12f), PureWhite.copy(alpha = .60f))))
    ) {
        Box(Modifier.onGloballyPositioned { position = it.positionOnScreen() }.drawWithContent {
            if (canBlur) {
                val offset = position - backdrop.origin
                // Blur only a panel-sized copy, never its foreground text or controls.
                val radius = 20.dp.toPx()
                val bleed = (radius * 2).roundToInt()
                frost.renderEffect = if (overContent && Build.VERSION.SDK_INT >= 31) {
                    android.graphics.RenderEffect.createColorFilterEffect(
                        android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(.28f) }),
                        android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP)
                    ).asComposeRenderEffect()
                } else BlurEffect(radius, radius, TileMode.Clamp)
                frost.record(size = IntSize(size.width.roundToInt() + bleed * 2, size.height.roundToInt() + bleed * 2)) {
                    translate(bleed - offset.x, bleed - offset.y) { drawLayer(backdrop.layer) }
                }
                translate(-bleed.toFloat(), -bleed.toFloat()) { drawLayer(frost) }
            }
            drawRect(if (canBlur) tint else tint.copy(alpha = .86f))
            drawRect(Brush.linearGradient(listOf(PureWhite.copy(alpha = .18f), Color.Transparent,
                MistPink.copy(alpha = .08f))))
            drawContent()
        }) { content() }
    }
}

@Composable
internal fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    GlassPanel(Modifier.size(48.dp), RoundedCornerShape(16.dp)) {
        IconButton(onClick, enabled = enabled) {
            Icon(icon, description, Modifier.size(22.dp), tint = if (enabled) TextPrimary else TextTertiary.copy(alpha = .45f))
        }
    }
}

@Composable
internal fun GlassBottomBar(selected: Int, onSelected: (Int) -> Unit,
    actionIcon: ImageVector? = null, actionLabel: String = "", onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassPanel(Modifier.weight(1f), RoundedCornerShape(26.dp), PureWhite.copy(alpha = .36f), overContent = true) {
            Row(Modifier.fillMaxWidth().selectableGroup().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("相册" to Icons.Outlined.PhotoLibrary, "我们的空间" to Icons.Outlined.FavoriteBorder).forEachIndexed { index, (label, icon) ->
                    val active = selected == index
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelected(index) })
                        .heightIn(min = 52.dp).padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(if (active && index == 1) Icons.Filled.Favorite else icon, null, Modifier.size(21.dp),
                                tint = if (active) RoseInk else TextSecondary)
                            Spacer(Modifier.width(7.dp))
                            Text(label, fontSize = 13.sp, color = if (active) RoseInk else TextSecondary,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                        }
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.size(width = 14.dp, height = 2.dp).clip(CircleShape)
                            .background(if (active) RoseInk.copy(alpha = .72f) else Color.Transparent))
                    }
                }
            }
        }
        if (actionIcon != null) {
            GlassPanel(Modifier.size(60.dp), RoundedCornerShape(23.dp), RoseInk.copy(alpha = .86f), overContent = true) {
                IconButton(onAction, Modifier.fillMaxSize()) { Icon(actionIcon, actionLabel, Modifier.size(26.dp), tint = PureWhite) }
            }
        }
    }
}

@Composable
internal fun SettingsIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(PureWhite.copy(alpha = .52f)),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(21.dp), tint = RoseInk)
    }
}

@Composable
internal fun AlbumPlaceholder(modifier: Modifier = Modifier, seed: Int = 0) {
    val variant = (seed.toLong().let { if (it < 0) -it else it } % 3).toInt()
    val hills = listOf(Color(0xFF88AAA8), Color(0xFF97A8BD), Color(0xFFA3B49B))[variant]
    Canvas(modifier.background(Brush.verticalGradient(listOf(Color(0xFFF4DDDF), Color(0xFFFFF0E2))))) {
        drawCircle(Color(0xFFFFD2AF), size.width * .085f, Offset(size.width * (.73f - variant * .12f), size.height * .25f))
        val distant = Path().apply {
            moveTo(0f, size.height * .60f)
            cubicTo(size.width * .18f, size.height * .50f, size.width * .18f, size.height * .32f, size.width * .36f, size.height * .44f)
            cubicTo(size.width * .62f, size.height * .66f, size.width * .72f, size.height * .35f, size.width, size.height * .42f)
            lineTo(size.width, size.height); lineTo(0f, size.height); close()
        }
        drawPath(distant, hills.copy(alpha = .40f))
        val near = Path().apply {
            moveTo(0f, size.height * .72f)
            cubicTo(size.width * .28f, size.height * .52f, size.width * .40f, size.height * .71f, size.width * .62f, size.height * .57f)
            cubicTo(size.width * .83f, size.height * .44f, size.width * .92f, size.height * .52f, size.width, size.height * .60f)
            lineTo(size.width, size.height); lineTo(0f, size.height); close()
        }
        drawPath(near, hills)
        drawRect(Brush.verticalGradient(listOf(hills.copy(alpha = .60f), Color(0xFFDDE8DF))),
            Offset(0f, size.height * .76f), androidx.compose.ui.geometry.Size(size.width, size.height * .24f))
        repeat(3) { i ->
            val y = size.height * (.80f + i * .065f)
            drawLine(PureWhite.copy(alpha = .35f - i * .08f), Offset(size.width * (.12f + i * .12f), y),
                Offset(size.width * (.70f + i * .06f), y), .7.dp.toPx())
        }
    }
}
