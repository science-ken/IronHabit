package com.ironhabit.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ironhabit.app.ui.screens.ai.AiCoachScreen
import com.ironhabit.app.ui.screens.bodymetrics.BodyMetricsScreen
import com.ironhabit.app.ui.screens.discipline.DisciplineScreen
import com.ironhabit.app.ui.screens.exercise.AddEditExerciseScreen
import com.ironhabit.app.ui.screens.exercise.ExerciseDetailScreen
import com.ironhabit.app.ui.screens.habit.AddEditHabitScreen
import com.ironhabit.app.ui.screens.history.HistoryScreen
import com.ironhabit.app.ui.screens.plan.AddEditPlanScreen
import com.ironhabit.app.ui.screens.planpreview.PlanPreviewScreen
import com.ironhabit.app.ui.screens.profile.ProfileScreen
import com.ironhabit.app.ui.screens.settings.BackupScreen
import com.ironhabit.app.ui.screens.settings.SettingsScreen
import com.ironhabit.app.ui.screens.today.TodayScreen
import com.ironhabit.app.ui.screens.train.TrainScreen

/**
 * 应用路由图。
 *
 * - 5 个一级 Tab 路由在此注册（v3 起新增 `AI_COACH`）；
 * - 二级页由 [registerSecondaryRoutes] 统一挂载（T05 实现），
 *   这样底部栏可见性规则（[Destinations.TabRoutes]）自适应：二级页路由会自动隐藏底栏。
 */
@Composable
fun IronHabitNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Destinations.TODAY,
        modifier = modifier,
    ) {
        composable(Destinations.TODAY) {
            TodayScreen(
                // 「自己创建」带着光标所在的那一周进表单 —— 否则在「下周」那页点它，
                // 落库的却是本周（新增路径不传周就回落到当前周）。
                onCreatePlan = { week ->
                    navController.navigate(
                        Destinations.planAddEdit(
                            dayOfWeek = 1,
                            week = if (week > 0L) week else Destinations.PLAN_WEEK_UNSPECIFIED,
                        ),
                    )
                },
                onOpenPlanPreview = { navController.navigate(Destinations.PLAN_PREVIEW) },
                onCreateHabit = { navController.navigateToTab(Destinations.DISCIPLINE) },
                onEditHabit = { habitId -> navController.navigate(Destinations.habitAddEdit(habitId)) },
                onOpenExerciseDetail = { exerciseId ->
                    navController.navigate(Destinations.exerciseDetail(exerciseId))
                },
                onEditPlan = { planId, dayOfWeek, week ->
                    navController.navigate(
                        Destinations.planAddEdit(
                            planId = planId,
                            dayOfWeek = dayOfWeek,
                            week = if (week > 0L) week else Destinations.PLAN_WEEK_UNSPECIFIED,
                        ),
                    )
                },
            )
        }
        composable(Destinations.TRAIN) {
            TrainScreen(
                onAddPlan = { dayOfWeek ->
                    navController.navigate(Destinations.planAddEdit(dayOfWeek = dayOfWeek))
                },
                onEditPlan = { planId, dayOfWeek ->
                    navController.navigate(Destinations.planAddEdit(planId = planId, dayOfWeek = dayOfWeek))
                },
                onAddExercise = { navController.navigate(Destinations.exerciseAddEdit()) },
                onOpenExercise = { exerciseId ->
                    navController.navigate(Destinations.exerciseDetail(exerciseId))
                },
                onOpenHistory = { navController.navigate(Destinations.HISTORY) },
            )
        }
        composable(Destinations.DISCIPLINE) {
            DisciplineScreen(
                onCreateHabit = { navController.navigate(Destinations.habitAddEdit()) },
                onEditHabit = { habitId -> navController.navigate(Destinations.habitAddEdit(habitId)) },
            )
        }
        composable(Destinations.AI_COACH) {
            AiCoachScreen(
                onEditProfile = { navController.navigate(Destinations.SETTINGS) },
                onOpenPlanPreview = { navController.navigate(Destinations.PLAN_PREVIEW) },
                // 补充动作建议已经搬进「训练」页的动作库分段，这里只是把用户送过去。
                onOpenExerciseLibrary = { navController.navigateToTab(Destinations.TRAIN) },
            )
        }
        composable(Destinations.PROFILE) {
            ProfileScreen(
                onOpenBodyMetrics = { navController.navigate(Destinations.BODY_METRICS) },
                onOpenSettings = { navController.navigate(Destinations.SETTINGS) },
                onOpenBackup = { navController.navigate(Destinations.BACKUP) },
            )
        }

        registerSecondaryRoutes(navController)
    }
}

/**
 * 二级页路由注册（T05 实现）。
 *
 * 8 条路由：动作新增编辑 / 动作详情 / 计划新增编辑 / 习惯新增编辑 / 打卡历史 / 身体数据 / 设置 / 数据备份。
 * query 参数路由用 `navArgument(type=..., defaultValue=...)`；path 参数路由（动作详情）用必填 [NavType.LongType]。
 */
fun NavGraphBuilder.registerSecondaryRoutes(navController: NavHostController) {
    // 1) 动作新增 / 编辑
    composable(
        route = Destinations.EXERCISE_ADD_EDIT_PATTERN,
        arguments = listOf(
            navArgument(Destinations.EXERCISE_ADD_EDIT_ARG) {
                type = NavType.LongType
                defaultValue = 0L
            },
        ),
    ) {
        AddEditExerciseScreen(
            onSaved = { navController.popBackStack() },
            onCancel = { navController.popBackStack() },
        )
    }

    // 2) 动作详情
    composable(
        route = Destinations.EXERCISE_DETAIL_PATTERN,
        arguments = listOf(
            navArgument(Destinations.EXERCISE_DETAIL_ARG) { type = NavType.LongType },
        ),
    ) {
        ExerciseDetailScreen(
            onEdit = { exerciseId ->
                navController.navigate(Destinations.exerciseAddEdit(exerciseId))
            },
        )
    }

    // 3) 计划新增 / 编辑
    composable(
        route = Destinations.PLAN_ADD_EDIT_PATTERN,
        arguments = listOf(
            navArgument(Destinations.PLAN_ARG_ID) {
                type = NavType.LongType
                defaultValue = 0L
            },
            navArgument(Destinations.PLAN_ARG_DAY) {
                type = NavType.IntType
                defaultValue = 1
            },
            navArgument(Destinations.PLAN_ARG_WEEK) {
                type = NavType.LongType
                defaultValue = Destinations.PLAN_WEEK_UNSPECIFIED
            },
        ),
    ) {
        AddEditPlanScreen(
            onSaved = { navController.popBackStack() },
            onCancel = { navController.popBackStack() },
        )
    }

    // 4) 习惯新增 / 编辑
    composable(
        route = Destinations.HABIT_ADD_EDIT_PATTERN,
        arguments = listOf(
            navArgument(Destinations.HABIT_ARG_ID) {
                type = NavType.LongType
                defaultValue = 0L
            },
        ),
    ) {
        AddEditHabitScreen(
            onSaved = { navController.popBackStack() },
            onCancel = { navController.popBackStack() },
        )
    }

    // 5) 打卡历史
    composable(Destinations.HISTORY) {
        HistoryScreen()
    }

    // 6) 身体数据
    composable(Destinations.BODY_METRICS) {
        BodyMetricsScreen()
    }

    // 7) 设置
    composable(Destinations.SETTINGS) {
        SettingsScreen(
            onOpenBackup = { navController.navigate(Destinations.BACKUP) },
            onOpenBodyMetrics = { navController.navigate(Destinations.BODY_METRICS) },
        )
    }

    // 9) 本周计划预览（生成 → 逐天采纳；不采纳就返回 = 一条都不写）
    composable(Destinations.PLAN_PREVIEW) {
        PlanPreviewScreen(onBack = { navController.popBackStack() })
    }

    // 8) 数据备份
    composable(Destinations.BACKUP) {
        BackupScreen()
    }
}

/**
 * Tab 间切换：单例、恢复各 Tab 已保存的状态，并避免回退栈无限增长。
 *
 * 显式构造 [NavOptions]，不依赖任何 Kotlin DSL 扩展导入。
 */
internal fun NavHostController.navigateToTab(route: String) {
    val options = NavOptions.Builder()
        .setPopUpTo(graph.startDestinationId, false, true)
        .setLaunchSingleTop(true)
        .setRestoreState(true)
        .build()
    navigate(route, options)
}
