package com.EntropicSoftwareLab.Schedule.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens

/**
 * What the assistant is doing, spelled out under the ring.
 *
 * The ring animates identically in all three — only the word changes. That's deliberate: the model
 * takes seconds to load and seconds more to answer, and a spinner that looks different per phase
 * reads as four different failures rather than one process with steps.
 */
enum class AssistantPhase(val label: String) {
    /** The engine is loading the .task bundle. Seconds, and only on the first open. */
    LoadingModel("Loading"),

    /** Model is up and waiting for input. */
    Idle("What's happening?"),

    /** A prompt is in flight. */
    Thinking("Thinking"),
}

/**
 * The assistant's waiting animation, ported from Clipboard Share's `ReceivingLoader`.
 *
 * Kept structurally identical to the original so the two apps stay recognisably related — same
 * three-band liquid ring, same rotation and flow timings, same letter-wave on the label. Two things
 * changed in the port:
 *
 *  - the label scale now adapts to length. The original is fixed at 0.77 because it only ever
 *    renders "Receiving"; "What's happening?" is nearly twice that and would run off the ring.
 *  - the colour triad is unchanged from the original green, which already matches this app's accent.
 *
 * The glow relies on [BlurMaskFilter] through `asFrameworkPaint()` because Compose's DrawScope has
 * no blurred stroke. That's an Android-only API — fine here, but it's why this can't move to a
 * multiplatform module unchanged.
 */
@Composable
fun AssistantLoader(
    phase: AssistantPhase,
    modifier: Modifier = Modifier,
    size: Dp = 198.dp,
) = AssistantLoader(label = phase.label, modifier = modifier, size = size)

/**
 * The same loader under an arbitrary label.
 *
 * [AssistantPhase]'s three labels are assistant-specific wording; other callers can pass
 * their own. Splitting the label out rather than adding enum cases keeps that vocabulary
 * out of a type whose documented job is the assistant's state machine.
 */
@Composable
fun AssistantLoader(
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 198.dp,
) {
    val text = label
    val infiniteTransition = rememberInfiniteTransition(label = "AssistantLoader")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 90f,
        targetValue = 450f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "text_progress",
    )

    val flowTime by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "flow_time",
    )

    // 0.77 is the original's scale for a 9-character word. Longer labels shrink in proportion so
    // they stay inside the ring instead of spilling past it.
    val labelScale = (0.77f * REFERENCE_LABEL_LENGTH / maxOf(REFERENCE_LABEL_LENGTH, text.length.toFloat()))
        .coerceAtLeast(0.34f)

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(size)
                .graphicsLayer { rotationZ = rotation }
        ) {
            val radius = size.toPx() / 2f
            val center = Offset(radius, radius)

            val color1 = Color(0xFF34D399) // light green, innermost band
            val color2 = Color(0xFF10B981) // mid green
            val color3 = Color(0xFF047857) // deep green, outermost band

            drawIntoCanvas { canvas ->
                fun drawOutset(blurRadiusDp: Float, spreadDp: Float, color: Color) {
                    val strokeWidthPx = spreadDp.dp.toPx().coerceAtLeast(2f)
                    val paint = Paint().apply {
                        this.color = color
                        this.style = PaintingStyle.Stroke
                        this.strokeWidth = strokeWidthPx
                    }
                    paint.asFrameworkPaint().maskFilter =
                        BlurMaskFilter(blurRadiusDp.dp.toPx().coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
                    canvas.drawCircle(center, radius + (strokeWidthPx / 2f), paint)
                }

                drawOutset(3f, 1.2f, color1.copy(alpha = 0.27f))
                drawOutset(6f, 1.8f, color2.copy(alpha = 0.18f))
            }

            val clipPath = Path().apply { addOval(Rect(0f, 0f, size.toPx(), size.toPx())) }

            clipPath(clipPath) {
                drawIntoCanvas { canvas ->
                    fun drawLiquidInset(
                        yOffsetDp: Float,
                        blurRadiusDp: Float,
                        color: Color,
                        phaseOffset: Float,
                        amplitudeDp: Float,
                    ) {
                        val strokeWidthPx = 24.dp.toPx()
                        val paint = Paint().apply {
                            this.color = color
                            this.style = PaintingStyle.Stroke
                            this.strokeWidth = strokeWidthPx
                        }
                        paint.asFrameworkPaint().maskFilter =
                            BlurMaskFilter(blurRadiusDp.dp.toPx().coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)

                        val drawRadius = radius + (strokeWidthPx / 2f)
                        val offsetCenter = Offset(center.x, center.y + yOffsetDp.dp.toPx())

                        val path = Path()
                        val points = 60
                        val time = flowTime * Math.PI.toFloat() * 2f
                        for (i in 0..points) {
                            val angle = (i.toFloat() / points) * 2f * Math.PI.toFloat()
                            // Each multiplier on `time` must be a whole number, so every wave
                            // completes an exact number of cycles as flowTime wraps 1f -> 0f.
                            // A fractional multiplier makes the ring visibly jump on each loop.
                            val wave1 = kotlin.math.sin(angle * 3f + time + phaseOffset)
                            val wave2 = kotlin.math.cos(angle * 2f - time * 2f + phaseOffset)
                            val wave3 = kotlin.math.sin(angle * 5f + time * 1f)

                            val amplitudePx = amplitudeDp.dp.toPx()
                            val r = drawRadius + amplitudePx * (wave1 + wave2 + wave3) / 3f

                            val x = offsetCenter.x + r * kotlin.math.cos(angle)
                            val y = offsetCenter.y + r * kotlin.math.sin(angle)
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        path.close()
                        canvas.drawPath(path, paint)
                    }

                    drawLiquidInset(36f, 36f, color3, phaseOffset = 0f, amplitudeDp = 15f)
                    drawLiquidInset(12f, 18f, color2, phaseOffset = 2f, amplitudeDp = 10f)
                    drawLiquidInset(6f, 12f, color1, phaseOffset = 4f, amplitudeDp = 5f)
                }
            }
        }

        Row(
            modifier = Modifier.graphicsLayer {
                scaleX = labelScale
                scaleY = labelScale
            }
        ) {
            text.forEachIndexed { index, char ->
                val staggerOffset = (index * 0.1f) / 3.0f
                val letterProgress = (progress - staggerOffset).let { if (it < 0f) it + 1f else it }

                val opacity: Float
                val scaleFactor: Float
                val maxEnlargement = 0.05f
                when {
                    letterProgress < 0.2f -> {
                        val t = letterProgress / 0.2f
                        opacity = 0.4f + 0.6f * t
                        scaleFactor = 1.0f + maxEnlargement * t
                    }
                    letterProgress < 0.4f -> {
                        val t = (letterProgress - 0.2f) / 0.2f
                        opacity = 1.0f - 0.3f * t
                        scaleFactor = 1.0f + maxEnlargement - maxEnlargement * t
                    }
                    else -> {
                        val t = (letterProgress - 0.4f) / 0.6f
                        opacity = 0.7f - 0.3f * t
                        scaleFactor = 1.0f
                    }
                }

                Text(
                    text = char.toString(),
                    color = AppThemeTokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = (-2.5).sp,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier
                        .alpha(opacity)
                        .graphicsLayer {
                            scaleX = scaleFactor
                            scaleY = scaleFactor
                        },
                )
            }
        }
    }
}

/** Length of the original's only label ("Receiving"), which 0.77 was tuned against. */
private const val REFERENCE_LABEL_LENGTH = 9f
