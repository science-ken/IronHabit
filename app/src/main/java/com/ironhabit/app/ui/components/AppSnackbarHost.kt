package com.ironhabit.app.ui.components

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 全局 Snackbar 宿主状态。
 *
 * 由 [com.ironhabit.app.ui.navigation.AppRoot] 在根部 `CompositionLocalProvider` 提供，
 * 各页面通过 `LocalSnackbarHostState.current` 取得同一个 [SnackbarHostState] 弹提示。
 */
val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState> {
    error("SnackbarHostState 未提供")
}

/**
 * 统一 Snackbar 宿主（写入操作的反馈出口）。
 *
 * 直接把 [state] 交给 M3 [SnackbarHost] 渲染，样式随 `MaterialTheme` 自动适配浅/深色。
 */
@Composable
fun AppSnackbarHost(
    state: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = state,
        modifier = modifier,
    )
}
