package com.ironhabit.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * IronHabit M3 色板（浅色 / 深色两套）。
 *
 * 品牌定位「自律健身」：以深红（[IronRed]）为主色、铁灰（[IronGray]）为辅助色、琥珀（[IronAmber]）为强调色。
 *
 * ⚠️ 本文件是**唯一**允许出现 `Color(0xFF...)` 字面量的位置（架构 §7.5）：
 * 组件内一律通过 `MaterialTheme.colorScheme.*` 取值，不得硬编码色值。
 */

// ---- 主色：深红 ----
private val IronRed = Color(0xFFB3261E)
private val IronRedLight = Color(0xFFFFDAD6)
private val IronRedOnLight = Color(0xFF410001)
private val IronRedContainerDark = Color(0xFF93000A)
private val IronRedOnDark = Color(0xFFFFB4AB)
private val IronRedOnContainerDark = Color(0xFF690005)

// ---- 辅助色：铁灰 ----
private val IronGray = Color(0xFF455A64)
private val IronGrayContainer = Color(0xFFCFD8DC)
private val IronGrayOnContainer = Color(0xFF11191D)
private val IronGrayDark = Color(0xFFB0BEC5)
private val IronGrayContainerDark = Color(0xFF37474F)
private val IronGrayOnContainerDark = Color(0xFFCFD8DC)

// ---- 强调色：琥珀 ----
private val IronAmber = Color(0xFF8A6D00)
private val IronAmberContainer = Color(0xFFFFDF9E)
private val IronAmberOnContainer = Color(0xFF251A00)
private val IronAmberDark = Color(0xFFE6C36A)
private val IronAmberContainerDark = Color(0xFF5B4300)
private val IronAmberOnContainerDark = Color(0xFFFFDF9E)

// ---- 中性 / 表面 ----
private val LightSurface = Color(0xFFFFF8F7)
private val LightOnSurface = Color(0xFF201A19)
private val LightSurfaceVariant = Color(0xFFE7E0DE)
private val LightOnSurfaceVariant = Color(0xFF4A4645)
private val LightOutline = Color(0xFF7F7573)
private val LightBackground = Color(0xFFFFF8F7)
private val LightOnBackground = Color(0xFF201A19)

private val DarkSurface = Color(0xFF1A1110)
private val DarkOnSurface = Color(0xFFF1DEDC)
private val DarkSurfaceVariant = Color(0xFF524341)
private val DarkOnSurfaceVariant = Color(0xFFD8C2BF)
private val DarkOutline = Color(0xFFA08C89)
private val DarkBackground = Color(0xFF1A1110)
private val DarkOnBackground = Color(0xFFF1DEDC)

// ---- 语义色：错误 ----
private val ErrorLight = Color(0xFFBA1A1A)
private val ErrorContainerLight = Color(0xFFFFDAD6)
private val OnErrorContainerLight = Color(0xFF410002)
private val ErrorDark = Color(0xFFFFB4AB)
private val ErrorContainerDark = Color(0xFF93000A)
private val OnErrorContainerDark = Color(0xFFFFDAD6)

/** 浅色主题配色。 */
val IronHabitLightColorScheme = lightColorScheme(
    primary = IronRed,
    onPrimary = Color.White,
    primaryContainer = IronRedLight,
    onPrimaryContainer = IronRedOnLight,
    secondary = IronGray,
    onSecondary = Color.White,
    secondaryContainer = IronGrayContainer,
    onSecondaryContainer = IronGrayOnContainer,
    tertiary = IronAmber,
    onTertiary = Color.White,
    tertiaryContainer = IronAmberContainer,
    onTertiaryContainer = IronAmberOnContainer,
    error = ErrorLight,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
)

/** 深色主题配色。 */
val IronHabitDarkColorScheme = darkColorScheme(
    primary = IronRedOnDark,
    onPrimary = IronRedOnContainerDark,
    primaryContainer = IronRedContainerDark,
    onPrimaryContainer = IronRedLight,
    secondary = IronGrayDark,
    onSecondary = IronGrayOnContainer,
    secondaryContainer = IronGrayContainerDark,
    onSecondaryContainer = IronGrayOnContainerDark,
    tertiary = IronAmberDark,
    onTertiary = IronAmberOnContainer,
    tertiaryContainer = IronAmberContainerDark,
    onTertiaryContainer = IronAmberOnContainerDark,
    error = ErrorDark,
    onError = IronRedOnContainerDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
)
