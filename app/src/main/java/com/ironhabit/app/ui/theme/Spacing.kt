package com.ironhabit.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * IronHabit 间距标尺（spacing scale）。
 *
 * ### 为什么需要它
 * 改造前全项目 150+ 处 `padding()` / `spacedBy()` 全是就地硬编码 `dp` 字面量，
 * 取值散布在 2/4/6/8/10/12/14/16/18/20/24/44… 之间，其中 **4/6/10/14/18 破坏 8pt 节奏**
 * （M3 官方间距体系基于 8dp 网格），导致各页面"呼吸感"不一致。
 *
 * 本文件把间距收敛为一条 **4dp 基准 / 8dp 主节奏** 的标尺，全部布局间距从 [IronHabitSpacing] 取值。
 *
 * ### 标尺设计
 * - **基准单位 4dp** → 满足 M3 的 8dp 网格（4dp 作为半档，用于图标与文字、chip 间距等微调）
 * - 命名语义化，避免"数字魔法"：`xs` 最小可感知间距 → `xxl` 大区块分隔
 *
 * ### 用法
 * ```kotlin
 * import com.ironhabit.app.ui.theme.IronHabitSpacing
 *
 * Column(
 *     modifier = Modifier.padding(IronHabitSpacing.md),
 *     verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
 * ) { ... }
 * ```
 *
 * ⚠️ 约定（与 `Color.kt` 的"唯一字面量位置"规则对齐）：
 * **界面代码中的布局间距一律引用本标尺，不再写裸 `dp` 字面量**；
 * 唯一例外是**组件自身固有尺寸**（如 `size(48.dp)` 的图标容器、图表画布高度），
 * 那类属于组件规格而非布局间距，可就地定义并加注释。
 */
object IronHabitSpacing {

    /** 0dp —— 显式清零（如抵消父容器 padding）。 */
    val none: Dp = 0.dp

    /** 2dp —— 仅用于极小视觉微调（如两行文字的紧贴基线），慎用。 */
    val xxs: Dp = 2.dp

    /** 4dp —— 半档：图标与相邻文字、chip 内部、紧凑元素之间。 */
    val xs: Dp = 4.dp

    /** 8dp —— **主节奏基准**：列表项内部、密集控件之间。 */
    val sm: Dp = 8.dp

    /** 12dp —— 列表项之间、卡片内小分组的间距。 */
    val md: Dp = 12.dp

    /** 16dp —— **页面标准边距 / 卡片内边距**（最常用）。 */
    val lg: Dp = 16.dp

    /** 20dp —— 稍宽的区块内边距（底部弹层横向留白等）。 */
    val xl: Dp = 20.dp

    /** 24dp —— 区块之间的呼吸间距、大内边距。 */
    val xxl: Dp = 24.dp

    /** 32dp —— 页面级大分区、空态留白。 */
    val xxxl: Dp = 32.dp
}

/**
 * 间距标尺的**语义别名**——按用途取用，比按尺寸取用更能表达意图。
 *
 * 两套命名并存是刻意的：布局时若心里想的是"这是一个页面边距"，就用 [IronHabitDimens.pagePadding]；
 * 若想的是"这里要 16dp"，就用 [IronHabitSpacing.lg]。两者数值一致，不会产生新的散值。
 */
object IronHabitDimens {

    /** 页面横向边距（所有一级页面统一 16dp）。 */
    val pagePadding: Dp = IronHabitSpacing.lg

    /** 卡片内边距。 */
    val cardPadding: Dp = IronHabitSpacing.lg

    /** 卡片之间的间距。 */
    val cardGap: Dp = IronHabitSpacing.md

    /** 列表项之间的间距。 */
    val listItemGap: Dp = IronHabitSpacing.md

    /** 区块（section）之间的间距。 */
    val sectionGap: Dp = IronHabitSpacing.xxl

    /** 标题与正文之间的间距。 */
    val titleToBody: Dp = IronHabitSpacing.xs

    /** 紧凑垂直内边距（如文本行的上下留白）。 */
    val compactVertical: Dp = IronHabitSpacing.sm
}
