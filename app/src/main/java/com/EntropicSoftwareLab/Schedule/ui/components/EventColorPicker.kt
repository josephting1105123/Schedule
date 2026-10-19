package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Picks any colour at all.
 *
 * The event colour used to be one of the twenty-four Google Calendar offers, because the chosen
 * colour was written to the provider as a key and a key is all the sync adapter round-trips. The app
 * keeps its own colour now, so the picker is free: the palette is a starting point, and the hue and
 * saturation controls below it reach everything else.
 *
 * Hand-rolled because Compose ships no colour picker. Hue is a horizontal strip; saturation and
 * value are the two axes of the square, which is the arrangement most people have already used
 * somewhere else and so need no explaining.
 */
@Composable
fun EventColorPickerDialog(
    initial: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val colors = AppThemeTokens.colors
    val d = AppThemeTokens.dimens

    val seed = initial ?: DEFAULT_SEED
    val seedHsv = remember(seed) { rgbToHsv(seed) }
    var hue by remember(seed) { mutableFloatStateOf(seedHsv[0]) }
    var saturation by remember(seed) { mutableFloatStateOf(seedHsv[1]) }
    var value by remember(seed) { mutableFloatStateOf(seedHsv[2]) }

    val current = Color.hsv(hue, saturation, value)
    val currentArgb = current.toArgb()

    // Kept as its own state rather than derived, so typing a partial hex doesn't fight the sliders
    // by resetting the field on every keystroke.
    var hexText by remember(seed) { mutableStateOf(hexOf(seed)) }

    fun syncFromControls() {
        hexText = hexOf(Color.hsv(hue, saturation, value).toArgb())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(currentArgb) }) { Text("Use colour") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Custom colour") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(current)
                            .border(d.hairlineWidth, colors.divider, CircleShape),
                    )
                    Spacer(Modifier.width(d.spaceMd))
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { typed ->
                            hexText = typed
                            parseHex(typed)?.let { parsed ->
                                val hsv = rgbToHsv(parsed)
                                hue = hsv[0]
                                saturation = hsv[1]
                                value = hsv[2]
                            }
                        },
                        label = { Text("Hex") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            capitalization = KeyboardCapitalization.Characters,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Saturation across, value down — the usual arrangement, and the one that keeps the
                // chosen hue's most saturated form in the corner people reach for first.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(d.radiusSm))
                        .background(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        .border(d.hairlineWidth, colors.divider, RoundedCornerShape(d.radiusSm))
                        .pointerInput(hue) {
                            fun apply(position: Offset) {
                                saturation = (position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (position.y / size.height).coerceIn(0f, 1f)
                                syncFromControls()
                            }
                            detectTapGestures { apply(it) }
                        }
                        .pointerInput(hue) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                saturation = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                                syncFromControls()
                            }
                        },
                )

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(d.radiusSm))
                        .background(Brush.horizontalGradient(HUE_STOPS))
                        .border(d.hairlineWidth, colors.divider, RoundedCornerShape(d.radiusSm))
                        .pointerInput(Unit) {
                            fun apply(position: Offset) {
                                hue = ((position.x / size.width).coerceIn(0f, 1f)) * 360f
                                syncFromControls()
                            }
                            detectTapGestures { apply(it) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                hue = ((change.position.x / size.width).coerceIn(0f, 1f)) * 360f
                                syncFromControls()
                            }
                        },
                )
            }
        },
        containerColor = colors.card,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textPrimary,
    )
}

@Composable
private fun Spacer(modifier: Modifier) = Box(modifier)

/** A mid-tone the picker opens on when the event has no colour yet. */
private const val DEFAULT_SEED = 0xFF3560C4.toInt()

private val HUE_STOPS: List<Color> = (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }

private fun hexOf(argb: Int): String = "#%06X".format(Locale.US, argb and 0xFFFFFF)

/** Accepts `#RRGGBB` or `RRGGBB`, any case. Returns null for anything else, including a partial. */
internal fun parseHex(text: String): Int? {
    val body = text.trim().removePrefix("#")
    if (body.length != 6) return null
    val rgb = body.toIntOrNull(16) ?: return null
    return 0xFF000000.toInt() or rgb
}

/**
 * ARGB to hue/saturation/value.
 *
 * `android.graphics.Color.colorToHSV` would do this, but it is an Android framework call and this
 * has to stay unit-testable on the JVM like the rest of the colour logic.
 */
internal fun rgbToHsv(argb: Int): FloatArray {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min

    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    val saturation = if (max == 0f) 0f else delta / max
    return floatArrayOf(if (hue < 0f) hue + 360f else hue, saturation, max)
}

/** Rounds to the nearest whole percent, for captioning without a wall of decimals. */
internal fun percent(fraction: Float): Int = (fraction * 100f).roundToInt()
