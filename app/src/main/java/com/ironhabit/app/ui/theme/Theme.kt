package com.ironhabit.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.domain.model.AppSettings
import com.ironhabit.app.domain.model.ThemeMode
import com.ironhabit.app.domain.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt 入口点：允许在 Compose（非 `@AndroidEntryPoint` 注入目标）中拿到 [SettingsRepository]。
 *
 * 声明在**本文件内**（派工单 §4 要求），避免新增文件。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SettingsEntryPoint {
    fun settingsRepository(): SettingsRepository
}

/**
 * IronHabit 主题。
 *
 * - [themeMode] 为 `null`（即 [com.ironhabit.app.MainActivity] 的无参调用）时，
 *   从 `SettingsRepository` 读取用户设置，使主题跟随用户选择；
 * - 显式传入 [themeMode] 时（如设置页预览）以传入值为准；
 * - 只提供 M3 `lightColorScheme()` / `darkColorScheme()` 两套，保证品牌色一致（不启用动态取色）。
 */
@Composable
fun IronHabitTheme(
    themeMode: ThemeMode? = null,
    content: @Composable () -> Unit,
) {
    val resolvedMode: ThemeMode = themeMode ?: run {
        val context = LocalContext.current
        val settingsRepository = remember(context) {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                SettingsEntryPoint::class.java,
            ).settingsRepository()
        }
        val settings by settingsRepository.settings()
            .collectAsStateWithLifecycle(initialValue = AppSettings())
        settings.themeMode
    }

    val useDarkTheme: Boolean = when (resolvedMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (useDarkTheme) IronHabitDarkColorScheme else IronHabitLightColorScheme

    CompositionLocalProvider(LocalIsDarkTheme provides useDarkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = IronHabitTypography,
            shapes = IronHabitShapesSpec,
            content = content,
        )
    }
}

/**
 * 当前是否深色。由 [IronHabitTheme] 提供，值来自它**实际解析**出来的主题。
 *
 * 别用 `isSystemInDarkTheme()` 代替它：本 app 允许用户在设置里显式选浅色/深色，
 * 那时系统值和实际渲染的主题会不一致。
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }
