package com.ironhabit.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ironhabit.app.R
import com.ironhabit.app.ui.components.AppSnackbarHost
import com.ironhabit.app.ui.components.LocalSnackbarHostState

/**
 * 应用根容器。
 *
 * 组装 `Scaffold`（顶栏标题随路由变化 + 底部 4 Tab + 全局 SnackbarHost），
 * 并通过 [LocalSnackbarHostState] 把同一个 [SnackbarHostState] 暴露给所有页面，
 * 页面只需 `LocalSnackbarHostState.current.showSnackbar(...)`。
 *
 * 底部栏仅在 [Destinations.TabRoutes] 命中时可见 —— 规则自适应，T05 新增的任何二级页路由都会自动隐藏底栏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    val navController: NavHostController = rememberNavController()
    val snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute: String? = backStackEntry?.destination?.route
    val showBottomBar: Boolean = currentRoute == null || currentRoute in Destinations.TabRoutes

    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(titleResFor(backStackEntry))) },
                    navigationIcon = {
                        // 仅二级页显示返回箭头；Tab 路由不显示（否则首页/任意一级页出现返回箭头很怪）。
                        if (!showBottomBar) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.cd_navigate_back),
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            },
            bottomBar = {
                if (showBottomBar) {
                    IronHabitBottomBar(
                        currentRoute = currentRoute,
                        onTabSelected = { route -> navController.navigateToTab(route) },
                    )
                }
            },
            snackbarHost = { AppSnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            IronHabitNavGraph(
                navController = navController,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/**
 * 当前回退栈条目 → 顶栏标题资源。
 *
 * 一级 Tab 直接映射；二级页按 `destination.route`（**pattern**，例如
 * `exercise/edit?exerciseId={exerciseId}`）命中。其中 3 个「新增/编辑」表单页会再读取实际参数
 * （`arguments?.getLong(arg)`，`0L`/缺省表示新增）以区分文案。
 *
 * @param entry 当前 [NavBackStackEntry]（`null` 视为根/未就绪，回落到「今日」）
 */
@StringRes
private fun titleResFor(entry: NavBackStackEntry?): Int {
    val route = entry?.destination?.route
    return when (route) {
        Destinations.TODAY -> R.string.title_today
        Destinations.TRAIN -> R.string.title_train
        Destinations.DISCIPLINE -> R.string.title_discipline
        Destinations.AI_COACH -> R.string.title_ai_coach
        Destinations.PROFILE -> R.string.title_profile
        Destinations.EXERCISE_ADD_EDIT_PATTERN ->
            if (isEditing(entry, Destinations.EXERCISE_ADD_EDIT_ARG)) {
                R.string.title_edit_exercise
            } else {
                R.string.title_add_exercise
            }
        Destinations.EXERCISE_DETAIL_PATTERN -> R.string.title_exercise_detail
        Destinations.PLAN_ADD_EDIT_PATTERN ->
            if (isEditing(entry, Destinations.PLAN_ARG_ID)) {
                R.string.title_edit_plan
            } else {
                R.string.title_add_plan
            }
        Destinations.HABIT_ADD_EDIT_PATTERN ->
            if (isEditing(entry, Destinations.HABIT_ARG_ID)) {
                R.string.title_edit_habit
            } else {
                R.string.title_add_habit
            }
        Destinations.HISTORY -> R.string.section_history
        Destinations.BODY_METRICS -> R.string.title_body_metrics
        Destinations.TRAINING_STATS -> R.string.title_training_stats
        Destinations.SETTINGS -> R.string.title_settings
        Destinations.BACKUP -> R.string.title_backup
        // 漏了这一条时它会掉进下面的 else → 预览页顶栏写着「今日」。
        Destinations.PLAN_PREVIEW -> R.string.title_plan_preview_bar
        else -> R.string.title_today
    }
}

/**
 * 判断表单页是「编辑」还是「新增」。
 *
 * 读取回退栈参数 `argName`：非 `0L` 视为编辑，`0L`/参数缺省视为新增。
 */
private fun isEditing(entry: NavBackStackEntry?, argName: String): Boolean =
    (entry?.arguments?.getLong(argName) ?: 0L) != 0L
