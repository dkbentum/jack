package com.example.jack

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =================================================================================================
// Triple Dimension — Jack design system (v3.0)
// Industrial / workshop, light, portrait. Brand: black, white, gray, a little blue.
// Every screen should use these colours, shapes and building blocks instead of ad-hoc styling.
// =================================================================================================

object JackColors {
    // Neutrals (brand black / white / gray)
    val Ink = Color(0xFF141414)          // near-black: headings, primary buttons, frames
    val Steel = Color(0xFF2B2B2B)        // dark panels
    val Graphite = Color(0xFF4A4A4A)     // secondary text on light
    val Gray = Color(0xFF8A8A8A)         // hints, disabled
    val Line = Color(0xFFD5D2CC)         // borders, dividers
    val Concrete = Color(0xFFF2F0EC)     // app background (warm off-white)
    val Panel = Color(0xFFE8E6E1)        // inset areas, keypad keys
    val Paper = Color(0xFFFFFFFF)        // cards

    // The "little blue": the one accent — main call-to-action, selection, links, focus.
    val Blue = Color(0xFF1F6FEB)
    val BlueDark = Color(0xFF1555B8)
    val BlueTint = Color(0xFFE6EFFD)

    // Status only (never decoration)
    val Green = Color(0xFF2E7D32)        // checked in, connected, success
    val GreenTint = Color(0xFFE5F2E6)
    val Amber = Color(0xFFE0A000)        // connecting, warnings
    val AmberTint = Color(0xFFFFF4D6)
    val Red = Color(0xFFC62828)          // errors, alerts, destructive
    val RedTint = Color(0xFFFBE7E7)
}

/** Condensed system font (Roboto Condensed on Android) for headings and labels — workshop signage feel. */
val CondensedFamily: FontFamily = FontFamily(
    androidx.compose.ui.text.font.Typeface(
        android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
    )
)

private val JackColorScheme = lightColorScheme(
    primary = JackColors.Ink,
    onPrimary = JackColors.Paper,
    primaryContainer = JackColors.Panel,
    onPrimaryContainer = JackColors.Ink,
    secondary = JackColors.Blue,
    onSecondary = JackColors.Paper,
    secondaryContainer = JackColors.BlueTint,
    onSecondaryContainer = JackColors.BlueDark,
    tertiary = JackColors.Blue,
    onTertiary = JackColors.Paper,
    tertiaryContainer = JackColors.BlueTint,
    onTertiaryContainer = JackColors.BlueDark,
    background = JackColors.Concrete,
    onBackground = JackColors.Ink,
    surface = JackColors.Paper,
    onSurface = JackColors.Ink,
    surfaceVariant = JackColors.Panel,
    onSurfaceVariant = JackColors.Graphite,
    surfaceContainer = JackColors.Paper,
    surfaceContainerHigh = JackColors.Paper,
    surfaceContainerHighest = JackColors.Paper,
    surfaceContainerLow = JackColors.Concrete,
    outline = JackColors.Line,
    outlineVariant = JackColors.Line,
    error = JackColors.Red,
    onError = JackColors.Paper,
    errorContainer = JackColors.RedTint,
    onErrorContainer = JackColors.Red
)

private val base = Typography()
private val JackTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = CondensedFamily, fontWeight = FontWeight.Bold),
    displayMedium = base.displayMedium.copy(fontFamily = CondensedFamily, fontWeight = FontWeight.Bold),
    displaySmall = base.displaySmall.copy(fontFamily = CondensedFamily, fontWeight = FontWeight.Bold),
    headlineLarge = base.headlineLarge.copy(fontFamily = CondensedFamily, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = CondensedFamily, fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontFamily = CondensedFamily, fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontFamily = CondensedFamily, fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontFamily = CondensedFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp),
    titleSmall = base.titleSmall.copy(fontFamily = CondensedFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp),
    labelLarge = base.labelLarge.copy(fontFamily = CondensedFamily, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
    labelMedium = base.labelMedium.copy(fontFamily = CondensedFamily, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
    labelSmall = base.labelSmall.copy(fontFamily = CondensedFamily, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
)

/** Square-cut, industrial corners. */
private val JackShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(10.dp)
)

/** Jack's v3 theme. Wraps the whole app (replaces the old ui/theme JackTheme). */
@Composable
fun JackBrandTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JackColorScheme,
        typography = JackTypography,
        shapes = JackShapes,
        content = content
    )
}

// =================================================================================================
// Building blocks
// =================================================================================================

/** The Triple Dimension cube logo (tinted to any colour). */
@Composable
fun BrandLogo(size: Dp, tint: Color = JackColors.Ink, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.company_logo),
        contentDescription = "Triple Dimension logo",
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size)
    )
}

/** Uppercase condensed section label with a short blue bar — e.g. "WHO IS IN THE BUILDING". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 16.dp)
                .background(JackColors.Blue)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.5.sp),
            color = JackColors.Ink,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** Flat white card with a thin steel border — the standard container. */
@Composable
fun JackCard(
    modifier: Modifier = Modifier,
    color: Color = JackColors.Paper,
    borderColor: Color = JackColors.Line,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = color,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(content = content)
    }
}

/** Small status chip, e.g. CONNECTED / WORKER / SYNCED. */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Box(
        modifier = modifier
            .background(if (filled) color else color.copy(alpha = 0.12f), MaterialTheme.shapes.extraSmall)
            .border(1.dp, color.copy(alpha = if (filled) 1f else 0.5f), MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) JackColors.Paper else color,
            maxLines = 1
        )
    }
}

/** Colour for a user's tag chip (Worker, Subscriber, Admin, Guest, Staff, …). */
fun tagColor(tag: String): Color = when (tag.lowercase()) {
    "worker", "staff" -> JackColors.Ink
    "subscriber" -> JackColors.Blue
    "admin" -> JackColors.Steel
    else -> JackColors.Graphite
}

/** Main action button: blue, tall, uppercase. */
@Composable
fun JackPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color = JackColors.Blue,
    height: Dp = 56.dp
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = JackColors.Paper,
            disabledContainerColor = JackColors.Line,
            disabledContentColor = JackColors.Gray
        )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text.uppercase(), style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 1.sp))
    }
}

/** Secondary action: outlined in ink. */
@Composable
fun JackOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color = JackColors.Ink
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.5.dp, if (enabled) color else JackColors.Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge)
    }
}

/** Square bordered icon button for the top bar. */
@Composable
fun JackIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Boolean = false
) {
    Box(modifier = modifier.size(48.dp)) {
        OutlinedIconButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, JackColors.Line),
            colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = JackColors.Paper, contentColor = JackColors.Ink)
        ) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
        }
        if (badge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(8.dp)
                    .background(JackColors.Blue, MaterialTheme.shapes.extraSmall)
            )
        }
    }
}

/** Thin diagonal-stripe bar (steel + ink) — the industrial accent under headers. */
@Composable
fun StripeBar(modifier: Modifier = Modifier, height: Dp = 6.dp, stripe: Color = JackColors.Ink, gap: Color = JackColors.Line) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds()
    ) {
        drawRect(gap)
        val w = size.height * 1.6f
        var x = -size.height
        while (x < size.width + size.height) {
            val p = Path().apply {
                moveTo(x, size.height)
                lineTo(x + size.height, 0f)
                lineTo(x + size.height + w / 2, 0f)
                lineTo(x + w / 2, size.height)
                close()
            }
            drawPath(p, stripe)
            x += w
        }
    }
}

/** Big number tile, e.g. "IN BUILDING 12". */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = JackColors.Ink) {
    JackCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = JackColors.Graphite)
            Text(value, style = MaterialTheme.typography.displaySmall, color = valueColor)
        }
    }
}

/** Standard text style for small secondary information. */
val HintStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodySmall.copy(color = JackColors.Graphite)


/**
 * v3.3.1: a page that really covers the whole screen, top to bottom.
 * A plain Compose Dialog window only grows as tall as its content even with
 * usePlatformDefaultWidth = false, so we stretch the dialog's window to MATCH_PARENT ourselves.
 */
@Composable
fun JackFullScreenDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        val window = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        androidx.compose.runtime.SideEffect {
            window?.let {
                it.setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT
                )
                it.setDimAmount(0f)
            }
        }
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}


/** What the volume-key shortcut banner says (v3.14). */
data class ShortcutFeedback(val key: String, val message: String, val color: Color)

/**
 * v3.14: volume-shortcut banner — a black bar at the bottom with a key-cap ("VOL ▲"), a coloured
 * edge and a short message. Slides up in and fades out. Pass null to hide it.
 */
@Composable
fun ShortcutBanner(feedback: ShortcutFeedback?, modifier: Modifier = Modifier) {
    // Keep the last message while the exit animation runs.
    var last by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(feedback) }
    if (feedback != null) last = feedback
    androidx.compose.animation.AnimatedVisibility(
        visible = feedback != null,
        enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        val f = last ?: return@AnimatedVisibility
        Surface(
            color = JackColors.Ink,
            shape = MaterialTheme.shapes.small,
            shadowElevation = 8.dp
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(6.dp).fillMaxHeight().background(f.color))
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .border(1.5.dp, JackColors.Paper, MaterialTheme.shapes.extraSmall)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(f.key, style = MaterialTheme.typography.labelLarge, color = JackColors.Paper)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(f.message.uppercase(), style = MaterialTheme.typography.labelLarge, color = JackColors.Paper)
                }
            }
        }
    }
}
