package com.tannmenghong.tbchat.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The palette leans cool and graphite with a single ultramarine accent, so that
 * green / amber / red stay reserved for one job only: compatibility verdicts.
 * In an app whose whole point is telling you what will and will not run, that
 * separation is worth more than a broader brand palette.
 */
private val Ultramarine = Color(0xFF2743C4)
private val UltramarineLight = Color(0xFF8399FF)

private val LightColors = lightColorScheme(
    primary = Ultramarine,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E7FA),
    onPrimaryContainer = Color(0xFF0B1445),
    secondary = Color(0xFF4A5060),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7E9EE),
    onSecondaryContainer = Color(0xFF14161C),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF14161C),
    surface = Color(0xFFFBFBFD),
    onSurface = Color(0xFF14161C),
    surfaceVariant = Color(0xFFE7E9EE),
    onSurfaceVariant = Color(0xFF4A5060),
    outline = Color(0xFFC2C7D2),
    outlineVariant = Color(0xFFD5D9E1),
    error = Color(0xFFA32C22),
    onError = Color.White,
    errorContainer = Color(0xFFF6E2DF),
    onErrorContainer = Color(0xFF5A140E)
)

private val DarkColors = darkColorScheme(
    primary = UltramarineLight,
    onPrimary = Color(0xFF0B1445),
    primaryContainer = Color(0xFF1B2340),
    onPrimaryContainer = Color(0xFFD8DFFF),
    secondary = Color(0xFFA2AABC),
    onSecondary = Color(0xFF14161C),
    secondaryContainer = Color(0xFF1E232D),
    onSecondaryContainer = Color(0xFFE5E8EF),
    background = Color(0xFF0D0F14),
    onBackground = Color(0xFFE5E8EF),
    surface = Color(0xFF161A22),
    onSurface = Color(0xFFE5E8EF),
    surfaceVariant = Color(0xFF1E232D),
    onSurfaceVariant = Color(0xFFA2AABC),
    outline = Color(0xFF39414F),
    outlineVariant = Color(0xFF282E3A),
    error = Color(0xFFF08578),
    onError = Color(0xFF2E1A18),
    errorContainer = Color(0xFF2E1A18),
    onErrorContainer = Color(0xFFFFD9D3)
)

/**
 * Verdict colours live outside the Material scheme on purpose. They mean one
 * fixed thing everywhere in the app -- runs / marginal / blocked -- and must not
 * drift when the accent changes or dynamic colour is switched on.
 */
data class VerdictColors(
    val ok: Color,
    val okContainer: Color,
    val warn: Color,
    val warnContainer: Color,
    val blocked: Color,
    val blockedContainer: Color
)

private val LightVerdict = VerdictColors(
    ok = Color(0xFF15734A), okContainer = Color(0xFFE0F0E7),
    warn = Color(0xFF8F5C04), warnContainer = Color(0xFFF6EBD6),
    blocked = Color(0xFFA32C22), blockedContainer = Color(0xFFF6E2DF)
)

private val DarkVerdict = VerdictColors(
    ok = Color(0xFF5CC694), okContainer = Color(0xFF13291F),
    warn = Color(0xFFE0A94A), warnContainer = Color(0xFF2C2312),
    blocked = Color(0xFFF08578), blockedContainer = Color(0xFF2E1A18)
)

val LocalVerdictColors = staticCompositionLocalOf { LightVerdict }

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 27.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp
    )
)

/** Numbers that line up in columns: model sizes, token rates, memory figures. */
val MonoNumberStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    fontWeight = FontWeight.Normal
)

object Dimens {
    val gutter = 16.dp
    val cardRadius = 14.dp
    val chipRadius = 6.dp
    val sectionGap = 24.dp
}

@Composable
fun TbChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    CompositionLocalProvider(
        LocalVerdictColors provides if (darkTheme) DarkVerdict else LightVerdict
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            content = content
        )
    }
}
