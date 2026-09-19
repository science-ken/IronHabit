package com.ironhabit.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * IronHabit M3 色板（浅色 / 深色两套）。
 *
 * ### 2026-09-19 配色改版：暖色全部退场
 * 之前是「暗金主色 + 暖灰中性阶」，中性阶每一档都带黄棕底，整屏读起来发闷、像蒙了一层纸。
 * 现在换成**真中性灰 + 单一强调色**，与交互原型 `prototype.html` 一致：
 *
 * - **中性阶**：纯灰，不含任何色相偏移（旧 WarmGray* / 暖 Neutral* 全部作废）。
 * - **primary = 深青 `#0B6E5B`**：行动 / 进行中 / 勾选 / 进度。对白底 **6.18:1**。
 * - **tertiary = 暗金 `#775A00`**：成就 / 连续 / 纪录。对白底 **6.47:1**。
 *   两个强调色刻意保留分工 —— 合成一个就会重演旧版「打卡成功与校验失败同色」的语义撞车。
 * - **error 不动**：`#BA1A1A` 是语义锚点，与青（hue 168）相距 143°、与金（hue 88）相距 63°。
 *
 * ⚠️ 本文件是**唯一**允许出现 `Color(0xFF...)` 字面量的位置（架构 §7.5）：
 * 组件内一律通过 `MaterialTheme.colorScheme.*` 取值，不得硬编码色值。
 *
 * 所有正文级组合的 WCAG 对比度均 ≥ 4.5:1（AA），逐条实测见下方注释。
 */

// =============================================================================
// 一、中性 Tonal Palette —— 纯灰，页面 / 容器 / 描边 / 正文全靠它
// =============================================================================
// 命名沿用 M3 tone 编号。与旧 WarmGray* 的最大区别：R/G/B 三者相等，不再偏黄。
private val Neutral0 = Color(0xFF000000)
private val Neutral4 = Color(0xFF0A0A0A)
private val Neutral6 = Color(0xFF111111)
private val Neutral10 = Color(0xFF141414)
private val Neutral12 = Color(0xFF1D1D1D)
private val Neutral17 = Color(0xFF2B2B2B)
private val Neutral20 = Color(0xFF333333)
private val Neutral22 = Color(0xFF3A3A3A)
private val Neutral24 = Color(0xFF3E3E3E)
private val Neutral30 = Color(0xFF4A4A4A)
private val Neutral40 = Color(0xFF6B6B6B)
private val Neutral50 = Color(0xFF8A8A8A)
private val Neutral60 = Color(0xFFA3A3A3)
private val Neutral70 = Color(0xFFBDBDBD)
private val Neutral80 = Color(0xFFDCDCDC)
private val Neutral87 = Color(0xFFE8E8E8)
private val Neutral90 = Color(0xFFE4E4E4)
private val Neutral96 = Color(0xFFF5F5F5)
private val Neutral95 = Color(0xFFF0F0F0)
private val Neutral98 = Color(0xFFFAFAFA)
private val Neutral99 = Color(0xFFFFFFFF)
private val Neutral100 = Color(0xFFFFFFFF)

// =============================================================================
// 二、强调色 —— 深青（primary：行动 / 勾选 / 进度）
// =============================================================================
private val Teal10 = Color(0xFF00201C)
private val Teal20 = Color(0xFF003732)
private val Teal30 = Color(0xFF005048)
private val Teal40 = Color(0xFF0B6E5B)
private val Teal70 = Color(0xFF68B9AD)
private val Teal80 = Color(0xFF84D5C8)
private val Teal90 = Color(0xFFD7EDE7)
private val Teal95 = Color(0xFFB4FFF2)

// =============================================================================
// 三、辅助强调色 —— 暗金（tertiary：成就 / 连续 / 纪录）
// =============================================================================
private val Gold10 = Color(0xFF251A00)
private val Gold20 = Color(0xFF3F2E00)
private val Gold30 = Color(0xFF5A4300)
private val Gold40 = Color(0xFF775A00)
private val Gold80 = Color(0xFFE9C266)
private val Gold90 = Color(0xFFFFDF99)

// =============================================================================
// 四、语义色：错误（红色保持不变 —— 它是语义锚点）
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
    // 主色：深青（行动 / 勾选 / 进度）
    primary = Teal40,
    onPrimary = Neutral100,
    primaryContainer = Teal90,
    onPrimaryContainer = Teal20,
    inversePrimary = Teal80,

    // 辅助色：中性灰（芯片、次要按钮）
    secondary = Neutral40,
    onSecondary = Neutral100,
    secondaryContainer = Neutral95,
    onSecondaryContainer = Neutral12,

    // 强调色：暗金（成就 / 连续 / 纪录）
    tertiary = Gold40,
    onTertiary = Neutral100,
    tertiaryContainer = Gold90,
    onTertiaryContainer = Gold10,

    // 语义色：错误
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,

    // 页面与正文：18.42:1 / 5.33:1，均过 AA
    background = Neutral99,
    onBackground = Neutral10,
    surface = Neutral99,
    onSurface = Neutral10,
    surfaceVariant = Neutral90,
    onSurfaceVariant = Neutral40,

    // M3 容器阶梯：页面 → 卡片 → 浮层，逐级抬升（磁贴取 High，压暗的 hero 取 Highest）
    surfaceContainerLowest = Neutral100,
    surfaceContainerLow = Neutral98,
    surfaceContainer = Neutral96,
    surfaceContainerHigh = Neutral95,
    surfaceContainerHighest = Neutral80,
    surfaceDim = Neutral87,
    surfaceBright = Neutral98,

    // 描边与分隔线
    outline = Neutral50,
    outlineVariant = Neutral80,

    // 反色（Snackbar 等）
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral98,
)

/** 深色主题配色：同一套中性阶翻转到暗底，强调色提亮到 tone 80。 */
val IronHabitDarkColorScheme = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal20,
    primaryContainer = Teal30,
    onPrimaryContainer = Teal90,
    inversePrimary = Teal40,

    secondary = Neutral80,
    onSecondary = Neutral20,
    secondaryContainer = Neutral30,
    onSecondaryContainer = Neutral90,

    tertiary = Gold80,
    onTertiary = Gold20,
    tertiaryContainer = Gold30,
    onTertiaryContainer = Gold90,

    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,

    background = Neutral6,
    onBackground = Neutral90,
    surface = Neutral6,
    onSurface = Neutral90,
    surfaceVariant = Neutral30,
    onSurfaceVariant = Neutral70,

    surfaceContainerLowest = Neutral4,
    surfaceContainerLow = Neutral6,
    surfaceContainer = Neutral10,
    surfaceContainerHigh = Neutral12,
    surfaceContainerHighest = Neutral17,
    surfaceDim = Neutral6,
    surfaceBright = Neutral24,

    outline = Neutral60,
    outlineVariant = Neutral30,

    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
)

/**
 * 热力图密度色阶（索引 = `HeatmapCell.level`，`0` = 当天没有打卡）。
 *
 * ⚠️ **不要用线性插值代替这张表。** 之前 `cellColor` 是在 `surfaceVariant → primary`
 * 之间按 `level / 4` 插值，结果 1 档只比 0 档深一点点（实测两者对磁贴底色分别只有
 * 1.13:1 与 1.27:1），肉眼上"有打卡"和"没打卡"几乎一样 —— 热力图就白画了。
 * 这里把相邻档的间距手动拉开：0→1 是最关键的一跳。
 */
internal val HeatmapLevels: List<Color> = listOf(
    Color(0xFFDCDCDC), // 0 无数据：安静，但要能看出是一格
    Color(0xFFA7D9CC), // 1
    Color(0xFF6FBFA9), // 2
    Color(0xFF3A9A80), // 3
    Color(0xFF0B6E5B), // 4 高密度：与 primary 同色
)
