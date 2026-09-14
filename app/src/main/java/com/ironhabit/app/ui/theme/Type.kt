package com.ironhabit.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * IronHabit 排版（基于 M3 默认字体族，不引入任何第三方字体/库）。
 *
 * 重点放大 `displayMedium` / `displaySmall`：用于「今日」页进度环中央的 streak 连续天数大字。
 */
private val DefaultTypography = Typography()

val IronHabitTypography: Typography = DefaultTypography.copy(
    displayMedium = DefaultTypography.displayMedium.copy(
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 56.sp,
    ),
    displaySmall = DefaultTypography.displaySmall.copy(
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 48.sp,
    ),
    headlineMedium = DefaultTypography.headlineMedium.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = DefaultTypography.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
    ),
)
