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
import com.ironhabit.app.ui.screens.bodymetrics.BodyMetricsScreen
import com.ironhabit.app.ui.screens.discipline.DisciplineScreen
import com.ironhabit.app.ui.screens.exercise.AddEditExerciseScreen
import com.ironhabit.app.ui.screens.exercise.ExerciseDetailScreen
import com.ironhabit.app.ui.screens.habit.AddEditHabitScreen
import com.ironhabit.app.ui.screens.history.HistoryScreen
import com.ironhabit.app.ui.screens.plan.AddEditPlanScreen
import com.ironhabit.app.ui.screens.profile.ProfileScreen
import com.ironhabit.app.ui.screens.settings.BackupScreen
import com.ironhabit.app.ui.screens.settings.SettingsScreen
import com.ironhabit.app.ui.screens.today.TodayScreen
import com.ironhabit.app.ui.screens.train.TrainScreen

/**
 * 应用路由图。
 *
 * - 4 个一级 Tab 路由在此注册；
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
                onCreatePlan = { navController.navigateToTab(Destinations.TRAIN) },
                onCreateHabit = { navController.navigateToTab(Destinations.DISCIPLINE) },
                onEditHabit = { habitId -> navController.navigate(Destinations.habitAddEdit(habitId)) },
                onOpenExerciseDetail = { exerciseId ->
                    navController.navigate(Destinations.exerciseDetail(exerciseId))
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
