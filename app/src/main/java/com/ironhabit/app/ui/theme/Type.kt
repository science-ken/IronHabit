package com.ironhabit.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * IronHabit 排版标尺（typography scale）。
 *
 * ### 为什么需要它
 * 改造前 `Type.kt` **只覆盖 4 个样式**（displayMedium / displaySmall / headlineMedium /
 * titleLarge），其余全部依赖 M3 默认值。问题在于 M3 默认的字重阶梯对本项目偏平：
 *
 * | 样式 | M3 默认字重 | 项目使用频次 |
 * |------|------------|------------|
 * | titleLarge | Normal(400) → 项目改 SemiBold(600) | 11 |
 * | titleMedium | Medium(500) | 21 |
 * | titleSmall | **Medium(500)**（与上一档同级） | 26 |
 *
 * 即 `titleMedium` 与 `titleSmall` **字重完全相同**，而本项目两者都大量用于「区块标题 /
 * 卡片标题」—— 于是层级读起来是平的，标题之间没有视觉落差。这是"排版不够讲究"的直接根因。
 *
 * ### 本文件的原则
 * 1. **单一事实来源**：所有字重 / 字号 / 行高在此定义，界面代码**只引用 `MaterialTheme.typography.*`**，
 *    不再出现 `.copy(fontWeight = ...)`（唯一例外是等宽代码块等需要换字族的场景）。
 * 2. **字重阶梯重排**：给「标题族」建立清晰的三级落差，让卡片/区块标题互相拉开。
 * 3. **对齐 M3 语义**：`display*` 专供进度环大字，`headline*` 供页面标题，
 *    `title*` 供区块/卡片标题，`body*` 供正文，`label*` 供标签与按钮。
 *
 * ### 字重阶梯（改造后）
 * ```
 * displayMedium/Large  Bold(700)     ← 进度环中央大字
 * headlineMedium/Small SemiBold(600) ← 页面标题
 * titleLarge           SemiBold(600) ← 大区块标题
 * titleMedium          SemiBold(600) ← 卡片标题（原 500 → 600，拉开与 titleSmall 的落差）
 * titleSmall           Medium(500)   ← 小标题 / 表单字段名（原 500，保持不变）
 * bodyLarge/Medium/Small Normal(400) ← 正文，保持 M3 默认
 * labelLarge/Medium/Small Medium(500)← 标签，保持 M3 默认
 * ```
 *
 * ### 用法
 * ```kotlin
 * Text(text = "...", style = MaterialTheme.typography.titleMedium)
 * ```
 * 需要等宽（如 AI 回复的 JSON 预览）时才允许就地 `.copy(fontFamily = FontFamily.Monospace)`。
 */
private val DefaultTypography = Typography()

val IronHabitTypography: Typography = DefaultTypography.copy(

    // ---- display：进度环中央的 streak 大字（今日页），刻意放大 ----

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

    // ---- headline：页面级标题 ----

    headlineMedium = DefaultTypography.headlineMedium.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    headlineSmall = DefaultTypography.headlineSmall.copy(
        fontWeight = FontWeight.SemiBold,
    ),

    // ---- title：区块 / 卡片标题（三级落差：600 / 600 / 500）----
    // 注：titleLarge 与 titleMedium 同为 SemiBold 是**刻意的**——两者靠字号（22sp vs 16sp）
    // 区分层级，字重保持一致可避免"页面里出现两种标题粒度"。真正的落差在于
    // titleMedium(600) 与 titleSmall(500) 之间，解决了改造前二者同为 500 的扁平问题。

    titleLarge = DefaultTypography.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = DefaultTypography.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = DefaultTypography.titleSmall.copy(
        fontWeight = FontWeight.Medium,
    ),

    // ---- body：正文（保持 M3 默认字重 400，仅显式声明以表明"已审阅"）----

    bodyLarge = DefaultTypography.bodyLarge.copy(
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = DefaultTypography.bodyMedium.copy(
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = DefaultTypography.bodySmall.copy(
        fontWeight = FontWeight.Normal,
    ),

    // ---- label：标签 / 按钮（保持 M3 默认 500）----

    labelLarge = DefaultTypography.labelLarge.copy(
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = DefaultTypography.labelMedium.copy(
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = DefaultTypography.labelSmall.copy(
        fontWeight = FontWeight.Medium,
    ),
)

/**
 * 等宽排版样式（AI 回复中的 JSON / 数据预览用）。
 *
 * 此前这类文本散落为 `MaterialTheme.typography.bodySmall.copy(fontFamily = Monospace, fontSize = 11.sp)`
 * 的就地写法，现收敛到此。界面直接引用 `IronHabitTypeStyles.code`。
 */
object IronHabitTypeStyles {

    /** 等宽代码块：用于 AI 周报导出预览等需要对齐的文本。 */
    val code: TextStyle = IronHabitTypography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )
}
