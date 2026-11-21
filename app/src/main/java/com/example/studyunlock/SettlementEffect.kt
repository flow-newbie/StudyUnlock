package com.example.studyunlock

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.*
import kotlin.random.Random

@Composable
internal fun SettlementEffect(result: Settlement?, balance: Offset?, finished: (Settlement) -> Unit) {
    if (result == null) return
    val animation = remember(result) { Animatable(0f) }
    val finish by rememberUpdatedState(finished)
    LaunchedEffect(result) {
        if (ValueAnimator.areAnimatorsEnabled()) {
            animation.animateTo(1f, tween(if (result.success) 1450 else 2550, easing = LinearEasing))
        }
        finish(result)
    }
    val sparks = remember(result) {
        val random = Random(78)
        List(76) { Spark(random.nextFloat() * (PI * 2).toFloat(), .35f + random.nextFloat() * .65f, it % 6) }
    }
    val palette = listOf(Color(0xffe957a1), Color(0xff6751e2), Color(0xffffaa26),
        Color(0xff23aaba), Color(0xff57b463), Color(0xffff6262))
    Canvas(Modifier.fillMaxSize()) {
        val t = animation.value
        if (result.success) {
            val origin = balance ?: Offset(size.width * .79f, size.height * .49f)
            val peak = origin - Offset(36f, 120f)
            if (t < .3f) {
                val rocket = origin + (peak - origin) * (t / .3f)
                drawLine(palette[2].copy(alpha = .7f), rocket, rocket + Offset(8f, 28f), 3.5f)
                drawCircle(palette[3], 5f, rocket)
            } else {
                val progress = (t - .3f) / .7f
                val radius = size.width.coerceAtMost(size.height) * .25f
                sparks.forEach { spark ->
                    val distance = radius * spark.speed * progress
                    val point = peak + Offset(cos(spark.angle) * distance, sin(spark.angle) * distance + radius * progress * progress * .25f)
                    drawCircle(palette[spark.color].copy(alpha = (1f - progress).coerceIn(0f, 1f)),
                        (5f - 3f * progress), point)
                }
            }
        } else {
            val center = this.center
            val radius = hypot(size.width, size.height) / 2f
            val shade = Color(0xff08090f)
            if (t < .12f) {
                val r = size.minDimension * .035f
                drawCircle(shade, r, center)
                drawLine(Color(0xff4b495c), center + Offset(r * .45f, -r * .75f),
                    center + Offset(r * .75f, -r * 1.45f), r * .21f)
                drawCircle(Color(0xffffab43), r * .22f, center + Offset(r * .78f, -r * 1.48f))
            } else if (t < .32f) {
                val progress = (t - .12f) / .2f
                drawCircle(shade, radius * progress, center)
                drawCircle(Color(0xfff28a4d).copy(alpha = .65f * (1f - progress)), radius * progress, center,
                    style = Stroke(width = 10f * (1f - progress) + 1f))
            } else {
                drawRect(shade.copy(alpha = (1f - ((t - .67f) / .33f).coerceIn(0f, 1f))))
            }
        }
    }
}

private data class Spark(val angle: Float, val speed: Float, val color: Int)
