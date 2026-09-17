package com.ironhabit.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * IronHabit M3 色板（浅色 / 深色两套）。
 *
 * 品牌定位「自律健身」：以**暗金**（[Gold40]）为主色、**暖灰**（[WarmGray40]）为辅助色、
 * **深青**（[Teal40]）为强调色。
 *
 * ### 设计要点（配色重构）
 * 1. **主色与 error 语义分离**：旧主色 `#B3261E` 与 error `#BA1A1A` 色相仅差 **0.9°**（撞色），
 *    导致打卡成功 / 进度环 / 撤销 / 校验失败全部同色，语义无法区分。新主色 hue=88，
 *    与 error（hue=25）拉开 **62.8°**。
 * 2. **tertiary 真正投入使用**：旧琥珀 `#8A6D00` 定义了却几乎无引用；新 tertiary 改用
 *    **深青**（hue=186，与 error 相距 160.7°、与 primary 相距 97.8°），用于成就 / 连续打卡 /
 *    个人纪录等"荣誉类"语义，与 primary 的"行动类"语义天然分工。
 * 3. **中性色补齐 M3 容器阶梯**：新增 `surfaceContainerLowest/Low/Container/High/Highest`
 *    + `surfaceDim/Bright` + `outlineVariant`，让"页面 → 卡片 → 分隔线"有明确层次语言。
 *
 * ⚠️ 本文件是**唯一**允许出现 `Color(0xFF...)` 字面量的位置（架构 §7.5）：
 * 组件内一律通过 `MaterialTheme.colorScheme.*` 取值，不得硬编码色值。
 *
 * 所有色值由 Material Color Utilities（HCT 色彩空间）从种子色生成，
 * 关键组合的 WCAG 对比度均 ≥ 4.5:1（AA 正文级）已实测通过。
 */

// =============================================================================
// 一、主色 Tonal Palette —— 品牌暗金（seed #775A00, hue 88, chroma 38）
// =============================================================================
// 选它而非"深橙/铁锈"的原因：铁锈橙系（hue 33~61）与 error(25°) 仅差 8~36°，**无法满足
// 60° 分离要求**；hue >= 85 才进入合格区。暗金同时是"硬朗/自律"气质里最克制、最耐看的一支。
private val Gold0 = Color(0xFF000000)
private val Gold10 = Color(0xFF251A00)
private val Gold20 = Color(0xFF3F2E00)
private val Gold30 = Color(0xFF5A4300)
private val Gold40 = Color(0xFF775A00)
private val Gold50 = Color(0xFF92731E)
private val Gold60 = Color(0xFFAE8C36)
private val Gold70 = Color(0xFFCBA74D)
private val Gold80 = Color(0xFFE9C266)
private val Gold90 = Color(0xFFFFDF99)
private val Gold95 = Color(0xFFFFEFD2)
private val Gold99 = Color(0xFFFFFBFF)
private val Gold100 = Color(0xFFFFFFFF)

// =============================================================================
// 二、辅助色 Tonal Palette —— 暖灰（seed #7C7568, hue 90, chroma 7）
// =============================================================================
// 与主色同族的低饱和暖灰，取代旧的蓝调铁灰 #455A64（蓝灰与暗金不同族，搭一起显脏）。
private val WarmGray0 = Color(0xFF000000)
private val WarmGray10 = Color(0xFF1F1B12)
private val WarmGray20 = Color(0xFF353025)
private val WarmGray30 = Color(0xFF4C463B)
private val WarmGray40 = Color(0xFF645E51)
private val WarmGray50 = Color(0xFF7D7669)
private val WarmGray60 = Color(0xFF989082)
private val WarmGray70 = Color(0xFFB3AA9C)
private val WarmGray80 = Color(0xFFCEC5B6)
private val WarmGray90 = Color(0xFFEBE1D2)
private val WarmGray95 = Color(0xFFFAEFDF)
private val WarmGray99 = Color(0xFFFFFBFF)
private val WarmGray100 = Color(0xFFFFFFFF)

// =============================================================================
// 三、强调色 Tonal Palette —— 深青（seed #016A60, hue 186, chroma 44）
// =============================================================================
// 语义分工：primary（暗金）= 行动 / 进行中；tertiary（深青）= 成就 / 达成 / 纪录。
private val Teal0 = Color(0xFF000000)
private val Teal10 = Color(0xFF00201C)
private val Teal20 = Color(0xFF003732)
private val Teal30 = Color(0xFF005048)
private val Teal40 = Color(0xFF016A60)
private val Teal50 = Color(0xFF2E8479)
private val Teal60 = Color(0xFF4C9E93)
private val Teal70 = Color(0xFF68B9AD)
private val Teal80 = Color(0xFF84D5C8)
private val Teal90 = Color(0xFF9FF2E4)
private val Teal95 = Color(0xFFB4FFF2)
private val Teal99 = Color(0xFFF2FFFB)
private val Teal100 = Color(0xFFFFFFFF)

// =============================================================================
// 四、中性 Tonal Palette —— 页面与容器底色（沿用暖灰家族，M3 允许 neutral 同源）
// =============================================================================
private val Neutral4 = Color(0xFF110E06)
private val Neutral6 = Color(0xFF17130A)
private val Neutral12 = Color(0xFF231F15)
private val Neutral17 = Color(0xFF2E291F)
private val Neutral22 = Color(0xFF393429)
private val Neutral24 = Color(0xFF3E392E)
private val Neutral87 = Color(0xFFE2D9C9)
private val Neutral92 = Color(0xFFF1E7D7)
private val Neutral94 = Color(0xFFF7EDDD)
private val Neutral96 = Color(0xFFFCF2E2)
private val Neutral98 = Color(0xFFFFF8F1)

// =============================================================================
// 五、语义色：错误（红色保持不变 —— 它是语义锚点，且现已与 primary 充分分离）
// =============================================================================
private val ErrorLight = Color(0xFFBA1A1A)
private val OnErrorLight = Color(0xFFFFFFFF)
private val ErrorContainerLight = Color(0xFFFFDAD6)
private val OnErrorContainerLight = Color(0xFF410002)
private val ErrorDark = Color(0xFFFFB4AB)
private val OnErrorDark = Color(0xFF690005)
private val ErrorContainerDark = Color(0xFF93000A)
private val OnErrorContainerDark = Color(0xFFFFDAD6)

/** 浅色主题配色。 */
val IronHabitLightColorScheme = lightColorScheme(
    // 主色：暗金
    primary = Gold40,
    onPrimary = Gold100,
    primaryContainer = Gold90,
    onPrimaryContainer = Gold10,
    inversePrimary = Gold80,

    // 辅助色：暖灰
    secondary = WarmGray40,
    onSecondary = WarmGray100,
    secondaryContainer = WarmGray90,
    onSecondaryContainer = WarmGray10,

    // 强调色：深青（成就 / 纪录）
    tertiary = Teal40,
    onTertiary = Teal100,
    tertiaryContainer = Teal90,
    onTertiaryContainer = Teal10,

    // 语义色：错误
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,

    // 页面与正文
    background = WarmGray99,
    onBackground = WarmGray10,
    surface = Neutral98,
    onSurface = WarmGray10,
    surfaceVariant = WarmGray90,
    onSurfaceVariant = WarmGray30,

    // M3 容器阶梯：页面 → 卡片 → 浮层，逐级抬升
    surfaceContainerLowest = WarmGray100,
    surfaceContainerLow = Neutral96,
    surfaceContainer = Neutral94,
    surfaceContainerHigh = Neutral92,
    surfaceContainerHighest = WarmGray90,
    surfaceDim = Neutral87,
    surfaceBright = Neutral98,

    // 描边与分隔线
    outline = WarmGray50,
    outlineVariant = WarmGray80,

    // 反色（Snackbar 等）
    inverseSurface = WarmGray20,
    inverseOnSurface = WarmGray95,
)

/** 深色主题配色。 */
val IronHabitDarkColorScheme = darkColorScheme(
    // 主色：暗金
    primary = Gold80,
    onPrimary = Gold20,
    primaryContainer = Gold30,
    onPrimaryContainer = Gold90,
    inversePrimary = Gold40,

    // 辅助色：暖灰
    secondary = WarmGray80,
    onSecondary = WarmGray20,
    secondaryContainer = WarmGray30,
    onSecondaryContainer = WarmGray90,

    // 强调色：深青（成就 / 纪录）
    tertiary = Teal80,
    onTertiary = Teal20,
    tertiaryContainer = Teal30,
    onTertiaryContainer = Teal90,

    // 语义色：错误
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,

    // 页面与正文
    background = Neutral6,
    onBackground = WarmGray90,
    surface = Neutral6,
    onSurface = WarmGray90,
    surfaceVariant = WarmGray30,
    onSurfaceVariant = WarmGray80,

    // M3 容器阶梯（深色下 tone 4 → 22 逐级提亮）
    surfaceContainerLowest = Neutral4,
    surfaceContainerLow = WarmGray10,
    surfaceContainer = Neutral12,
    surfaceContainerHigh = Neutral17,
    surfaceContainerHighest = Neutral22,
    surfaceDim = Neutral6,
    surfaceBright = Neutral24,

    // 描边与分隔线
    outline = WarmGray60,
    outlineVariant = WarmGray30,

    // 反色（Snackbar 等）
    inverseSurface = WarmGray90,
    inverseOnSurface = WarmGray20,
)
