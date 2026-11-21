package com.example.studyunlock

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.*

internal val Ink = Color(0xff202335)
internal val Muted = Color(0xff565d73)
internal val Accent = Color(0xff534fb0)
internal val Paper = Color(0xfff2f3f9)
internal val Line = Color(0xffdce0ee)

@Composable
internal fun CapsuleNav(selected: Int, select: (Int) -> Unit) {
    val position by animateFloatAsState(selected.toFloat(), spring(0.63f, 460f), label = "navigation")
    val stretch = (abs(position - selected) * 0.24f).coerceAtMost(.42f)
    BoxWithConstraints(
        Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth().height(56.dp)
            .clip(CircleShape).background(Color(0xffe7eaf5)).border(1.dp, Color.White, CircleShape)
            .selectableGroup()
    ) {
        val cell = maxWidth / 3
        val width = cell * (.86f + stretch)
        Box(Modifier.offset(x = cell * (position + .5f) - width / 2, y = 6.dp)
            .size(width, 44.dp).background(Color.White.copy(alpha = .78f), CircleShape)
            .border(1.dp, Color.White, CircleShape))
        Row(Modifier.fillMaxSize()) {
            listOf("今日", "记录", "设置").forEachIndexed { index, label ->
                Box(Modifier.weight(1f).fillMaxHeight()
                    .selectable(selected == index, role = Role.Tab) { select(index) }, Alignment.Center) {
                    Text(label, color = if (selected == index) Accent else Muted, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
internal fun Glass(beam: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val hasWallpaper = WallpaperActive.current
    var mouse by remember { mutableStateOf<Offset?>(null) }
    var tilt by remember { mutableStateOf(Offset.Zero) }
    val x by animateFloatAsState(tilt.x, spring(.85f, 350f), label = "rotate x")
    val y by animateFloatAsState(tilt.y, spring(.85f, 350f), label = "rotate y")
    val halo by animateFloatAsState(if (mouse == null) 0f else 1f, tween(180), label = "pointer light")
    var lightCenter by remember { mutableStateOf(Offset.Zero) }
    mouse?.let { SideEffect { lightCenter = it } }
    val beamProgress = remember { Animatable(0f) }
    if (beam) LaunchedEffect(Unit) {
        // A single pass draws attention when the permission card opens.
        beamProgress.animateTo(1f, tween(2700, easing = LinearEasing))
    }
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxWidth().pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.type == PointerType.Mouse } ?: continue
                if (event.type == PointerEventType.Exit) {
                    mouse = null
                    tilt = Offset.Zero
                } else if (event.type == PointerEventType.Move || event.type == PointerEventType.Enter) {
                    mouse = change.position
                    val horizontal = (change.position.x / size.width.coerceAtLeast(1) * 2 - 1).coerceIn(-1f, 1f)
                    val vertical = (change.position.y / size.height.coerceAtLeast(1) * 2 - 1).coerceIn(-1f, 1f)
                    tilt = Offset(-vertical, horizontal) * 2.8f
                }
            }
        }
    }.graphicsLayer {
        rotationX = x
        rotationY = y
        cameraDistance = 24.dp.toPx()
        this.shape = shape
        clip = true
    }.background(Brush.linearGradient(listOf(Color.White.copy(alpha = .9f),
        Color(0xffe7eaf7).copy(alpha = if (hasWallpaper) .89f else .72f))))) {
        Canvas(Modifier.matchParentSize()) {
            val radius = 24.dp.toPx()
            val stroke = 1.dp.toPx()
            drawRoundRect(Color.White, cornerRadius = CornerRadius(radius), style = Stroke(stroke))
            if (halo > 0f) {
                val brush = Brush.radialGradient(
                    listOf(Color(0xffb7bafc).copy(alpha = .38f * halo), Color.Transparent),
                    center = lightCenter, radius = 150.dp.toPx()
                )
                drawRect(brush)
                drawRoundRect(Brush.radialGradient(
                    listOf(Color(0xff7774d2).copy(alpha = .85f * halo), Color.Transparent),
                    center = lightCenter, radius = 105.dp.toPx()
                ), cornerRadius = CornerRadius(radius), style = Stroke(2.dp.toPx()))
            }
            if (beam) {
                val inset = stroke
                val bounds = androidx.compose.ui.geometry.Rect(inset, inset, size.width - inset, size.height - inset)
                val path = Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(bounds, CornerRadius(radius - inset)))
                }
                val measure = PathMeasure().apply { setPath(path, true) }
                val length = measure.length
                val progress = beamProgress.value
                if (length > 0f && progress < 1f) {
                    val head = length * progress
                    for (part in 0 until 16) {
                        val end = (head - part * 5.dp.toPx() + length) % length
                        val start = (end - 6.dp.toPx()).coerceAtLeast(0f)
                        val segment = Path()
                        measure.getSegment(start, end, segment, true)
                        drawPath(segment, Color(0xff7169d6).copy(alpha = (1f - part / 16f) * .92f),
                            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
            }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
