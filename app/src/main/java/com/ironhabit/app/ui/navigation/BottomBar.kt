package com.ironhabit.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.ironhabit.app.R

/** 一个底部 Tab 的静态定义。 */
private data class BottomTab(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

/**
 * 底部 4 Tab 导航栏。
 *
 * 图标只用 `material-icons-extended` 中**确定存在**的矢量：[Icons.Filled.Today] / [Icons.Filled.FitnessCenter] /
 * [Icons.Filled.SelfImprovement] / [Icons.Filled.Person]；文案取自 `tab_*` 资源。
 *
 * @param currentRoute 当前路由（`null` 视为未命中，仅影响选中态高亮）
 * @param onTabSelected 点击 Tab 回调，参数为目标路由
 */
@Composable
fun IronHabitBottomBar(
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = bottomTabs()

    NavigationBar(modifier = modifier) {
        tabs.forEach { tab ->
            val label = stringResource(tab.labelRes)
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onTabSelected(tab.route) },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = label,
                    )
                },
                label = { Text(text = label) },
            )
        }
    }
}

@Composable
private fun bottomTabs(): List<BottomTab> = listOf(
    BottomTab(Destinations.TODAY, R.string.tab_today, Icons.Filled.Today),
    BottomTab(Destinations.TRAIN, R.string.tab_train, Icons.Filled.FitnessCenter),
    BottomTab(Destinations.DISCIPLINE, R.string.tab_discipline, Icons.Filled.SelfImprovement),
    BottomTab(Destinations.PROFILE, R.string.tab_profile, Icons.Filled.Person),
)
