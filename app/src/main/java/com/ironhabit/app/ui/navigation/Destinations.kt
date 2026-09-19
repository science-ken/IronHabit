package com.ironhabit.app.ui.navigation

/**
 * 全工程路由常量 + 二级页路由工厂（纯常量/纯函数，无 Compose 依赖）。
 *
 * 约定（架构 §7.1）：路由名 snake_case。
 * - 4 个一级 Tab 路由见 [TabRoutes]；
 * - 二级页常量与工厂由 **T05** 消费并注册到 `NavHost`（本层只声明，不注册，避免引用 T05 尚未存在的 Screen）。
 */
object Destinations {

    // ---------------- 一级 Tab ----------------
    const val TODAY = "today"
    const val TRAIN = "train"
    const val DISCIPLINE = "discipline"

    /** 本地规则版「AI 教练」页（v3 增量）：插在「自律」与「我的」之间，与预览版底栏顺序一致。 */
    const val AI_COACH = "ai_coach"

    const val PROFILE = "profile"

    /** 底部栏展示顺序。 */
    val TabRoutes: List<String> = listOf(TODAY, TRAIN, DISCIPLINE, AI_COACH, PROFILE)

    // ---------------- 二级页：动作新增/编辑 ----------------
    const val EXERCISE_ADD_EDIT_PATTERN = "exercise/edit?exerciseId={exerciseId}"
    const val EXERCISE_ADD_EDIT_ARG = "exerciseId"

    /** `exerciseId == 0L` 表示新增。 */
    fun exerciseAddEdit(exerciseId: Long = 0L): String =
        "exercise/edit?exerciseId=$exerciseId"

    // ---------------- 二级页：动作详情 ----------------
    const val EXERCISE_DETAIL_PATTERN = "exercise/detail/{exerciseId}"
    const val EXERCISE_DETAIL_ARG = "exerciseId"

    fun exerciseDetail(exerciseId: Long): String =
        "exercise/detail/$exerciseId"

    // ---------------- 二级页：计划新增/编辑 ----------------
    const val PLAN_ADD_EDIT_PATTERN =
        "plan/edit?planId={planId}&dayOfWeek={dayOfWeek}&week={week}"
    const val PLAN_ARG_ID = "planId"
    const val PLAN_ARG_DAY = "dayOfWeek"

    /** 所属周的周一 epochDay；`-1` = 调用方没指定，由编辑页落到「当前这一周」。 */
    const val PLAN_ARG_WEEK = "week"
    const val PLAN_WEEK_UNSPECIFIED = -1L

    /** `planId == 0L` 表示新增；[dayOfWeek] 为 `1..7`。 */
    fun planAddEdit(planId: Long = 0L, dayOfWeek: Int, week: Long = PLAN_WEEK_UNSPECIFIED): String =
        "plan/edit?planId=$planId&dayOfWeek=$dayOfWeek&week=$week"

    // ---------------- 二级页：习惯新增/编辑 ----------------
    const val HABIT_ADD_EDIT_PATTERN = "habit/edit?habitId={habitId}"
    const val HABIT_ARG_ID = "habitId"

    /** `habitId == 0L` 表示新增。 */
    fun habitAddEdit(habitId: Long = 0L): String =
        "habit/edit?habitId=$habitId"

    // ---------------- 其它二级页 ----------------
    const val HISTORY = "history"
    const val BODY_METRICS = "body_metrics"
    const val SETTINGS = "settings"
    const val BACKUP = "backup"

    /** 「本周计划预览」：生成后、写库前停在这里，逐天采纳。 */
    const val PLAN_PREVIEW = "plan/preview"
}
