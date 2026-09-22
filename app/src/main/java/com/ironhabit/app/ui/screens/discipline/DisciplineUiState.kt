package com.ironhabit.app.ui.screens.discipline

import com.ironhabit.app.domain.model.Habit
import com.ironhabit.app.domain.model.HabitItem
import com.ironhabit.app.domain.model.HeatmapCell

/**
 * 「自律」页 UI 状态（不可变）。
 *
 * @property isLoading 加载中
 * @property dateEpochDay 今日日期口径（习惯勾选写库时使用）
 * @property habits 启用习惯（含今日完成状态与连续天数）
 * @property deletedHabits **软删掉的习惯**（`is_active = 0`）。默认折叠，靠 [showDeleted] 展开
 * @property showDeleted 是否展开已删除列表 —— 门控在 [visibleDeletedHabits] 里，不放界面层
 * @property heatmap 近 90 天热力图单元格（升序）
 * @property monthCompletionRate 本月完成率（`0f..100f`）
 * @property errorRes 页面级错误资源 id
 * @property snackbarRes 一次性 Snackbar 资源 id
 */
data class DisciplineUiState(
    val isLoading: Boolean = true,
    val dateEpochDay: Long = 0L,
    val habits: List<HabitItem> = emptyList(),
    val deletedHabits: List<Habit> = emptyList(),
    val showDeleted: Boolean = false,
    val heatmap: List<HeatmapCell> = emptyList(),
    val monthCompletionRate: Float = 0f,
    /**
     * 有没有记过任何一次打卡。
     *
     * `monthCompletionRate == 0f` 单独看是歧义的（"本月 0 天"还是"这人还没开始用"），
     * 而后者该显示「还没有数据」—— 见本仓库反复立的那条「不用 0 冒充 null」。
     */
    val hasAnyCheckIn: Boolean = false,
    val errorRes: Int? = null,
    val snackbarRes: Int? = null,
) {

    /** 展开时才渲染已删除那一段；收起时长度为 0，[nothingToShow] 也随之回到空态。 */
    val visibleDeletedHabits: List<Habit>
        get() = if (showDeleted) deletedHabits else emptyList()

    /**
     * 整页有没有任何东西可渲染（决定「还没有习惯」空态要不要出现）。
     *
     * ⚠️ 只看 [habits] 会把「只剩已删除习惯」这种状态画成空态，而空态里没有恢复入口 ——
     * 单向门就是这么来的。与 `FoodLibraryUiState.nothingToShow` 同一形状。
     */
    val nothingToShow: Boolean
        get() = habits.isEmpty() && visibleDeletedHabits.isEmpty()
}
