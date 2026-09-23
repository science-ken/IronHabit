package com.ironhabit.app.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.SkeletonProfileHeader
import com.ironhabit.app.ui.components.SkeletonStatStrip
import com.ironhabit.app.ui.screens.food.FoodLibrarySheet
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 * Tab4「我的」页面。
 *
 * 首屏结构（方案 J）：档案卡（含完整度环）→ 关键数字四联 → 记录台账四行 → 存哪儿。
 * 改版前这一屏只有四行光板入口，一个数字都没有，两张图被挤到屏幕外。
 *
 * 台账四行右侧那个数是**真条数**，不是静态文案；缺口（饮食那行）只在缺口过半时才用金色，
 * 详见 [ProfileLedgerCard]。
 *
 * 两张统计图已经搬去「训练统计」二级页 —— 它们属于"按月回顾才看"的低频数据。
 *
 * 食物库这一行**开弹层而不是跳路由**：管理界面与「今日 → 饮食 → 食物库」是同一个
 * [FoodLibrarySheet]，新开路由就要把列表/搜索/新建/停用再写一遍。
 *
 * @param onOpenBodyMetrics 身体数据入口回调
 * @param onOpenSettings 设置（含「我的档案」区块）入口回调
 * @param onOpenBackup 数据备份入口回调
 * @param onOpenTrainingStats 训练统计（柱状 / 占比 / 热力图）入口回调
 * @param onOpenDiet 饮食台账行回调（跨 Tab 去「今日」—— 饮食记录记在那一页）
 * @param onOpenHabits 习惯台账行回调（跨 Tab 去「自律」）
 */
@Composable
fun ProfileScreen(
    onOpenBodyMetrics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenTrainingStats: () -> Unit,
    onOpenDiet: () -> Unit,
    onOpenHabits: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showFoodLibrary by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(IronHabitSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
    ) {
        // 普通本地 val：委托属性不支持智能转换，需先取出再在分支内使用。
        val errorRes: Int? = uiState.errorRes
        if (uiState.isLoading || errorRes != null) {
            if (errorRes != null) {
                EmptyState(
                    text = stringResource(errorRes),
                    actionText = stringResource(R.string.action_retry),
                    onAction = viewModel::onRetry,
                )
            } else {
                // 与真实结构同构：档案卡一块、四联一块，数据到位时不跳版式。
                SkeletonProfileHeader()
                SkeletonStatStrip()
            }
            // 读不到数据时也要能走开：存哪儿那块是静态的，不依赖这次的聚合。
            ProfileStorageCard(
                onOpenBackup = onOpenBackup,
                onOpenFoodLibrary = { showFoodLibrary = true },
                onOpenSettings = onOpenSettings,
            )
        } else {
            ProfileArchiveCard(
                profile = uiState.profile,
                onClick = onOpenSettings,
            )

            ProfileStatStrip(
                streak = uiState.trainingStreak,
                weekCompleted = uiState.weekCompletedDays,
                weekPlanned = uiState.weekPlannedDays,
                totalRecords = uiState.totalCheckIns,
                latestWeight = uiState.latestWeight,
            )

            ProfileLedgerCard(
                ledger = uiState.ledger,
                onOpenTrainingStats = onOpenTrainingStats,
                onOpenBodyMetrics = onOpenBodyMetrics,
                onOpenDiet = onOpenDiet,
                onOpenHabits = onOpenHabits,
            )

            ProfileStorageCard(
                onOpenBackup = onOpenBackup,
                onOpenFoodLibrary = { showFoodLibrary = true },
                onOpenSettings = onOpenSettings,
            )
        }
    }

    if (showFoodLibrary) {
        FoodLibrarySheet(onDismissRequest = { showFoodLibrary = false })
    }
}
