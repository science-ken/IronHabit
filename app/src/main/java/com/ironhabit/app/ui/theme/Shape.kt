package com.ironhabit.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * IronHabit 形状标尺（shape scale）。
 *
 * ### 为什么需要它
 * 改造前全项目**仅 2 处**显式 `RoundedCornerShape`（`HeatmapGrid` 的 2.dp、`LoadingSkeleton` 的
 * `CORNER_RADIUS`），其余 30+ 个卡片/区块**全部依赖 M3 各组件的默认圆角**。不同组件（Card /
 * Chip / TextField / Sheet）默认圆角并不一致，视觉上"圆得不统一"，但在代码里看不出来。
 *
 * 本文件把圆角收敛为一套 M3 标准 corner token，并作为 [Shapes] 交给 `MaterialTheme`，
 * 让整个 App 的圆角有单一事实来源。
 *
 * ### M3 标准 corner 标尺（数值来自 Material Design 3 规范）
 * | Token | 值 | 典型组件 |
 * |-------|-----|---------|
 * | extraSmall | 4dp | Snackbar、Chip |
 * | small | 8dp | 输入框、菜单 |
 * | medium | 12dp | **卡片** |
 * | large | 16dp | FAB、导航抽屉 |
 * | extraLarge | 28dp | 对话框、底部弹层 |
 *
 * ### 用法
 * ```kotlin
 * // 交给 MaterialTheme（已在 Theme.kt 中接线），组件自动生效
 * MaterialTheme.shapes.medium
 *
 * // 需要自定义形状时取语义别名
 * Modifier.clip(IronHabitShapes.card)
 * ```
 */
object IronHabitShapes {

    /** 4dp —— Chip / Snackbar / 小标记。 */
    val extraSmall = RoundedCornerShape(4.dp)

    /** 8dp —— 输入框 / 菜单 / 内嵌小容器。 */
    val small = RoundedCornerShape(8.dp)

    /** 12dp —— **卡片默认圆角**。 */
    val medium = RoundedCornerShape(12.dp)

    /** 16dp —— FAB / 抽屉 / 大区块。 */
    val large = RoundedCornerShape(16.dp)

    /** 28dp —— 对话框 / 底部弹层。 */
    val extraLarge = RoundedCornerShape(28.dp)

    /** 全圆角（胶囊形）—— 按钮 / 徽章 / 标签。 */
    val full = RoundedCornerShape(percent = 50)

    // ---- 语义别名 ----

    /** 卡片（= [medium]）。 */
    val card = medium

    /** 对话框与底部弹层（= [extraLarge]）。 */
    val sheet = extraLarge

    /** 图表单元格等微小方块（= [extraSmall]）。 */
    val cell = extraSmall
}

/**
 * 交给 `MaterialTheme(shapes = ...)` 的 M3 [Shapes] 实例。
 *
 * 与 [IronHabitShapes] 数值一一对应：这样既能让 M3 内置组件（Card / Chip / TextField /
 * BottomSheet…）统一圆角，又给业务代码留了语义化的取用入口。
 */
val IronHabitShapesSpec = Shapes(
    extraSmall = IronHabitShapes.extraSmall,
    small = IronHabitShapes.small,
    medium = IronHabitShapes.medium,
    large = IronHabitShapes.large,
    extraLarge = IronHabitShapes.extraLarge,
)
