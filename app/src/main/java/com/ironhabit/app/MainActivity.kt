package com.ironhabit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.ironhabit.app.ui.navigation.AppRoot
import com.ironhabit.app.ui.theme.IronHabitTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 唯一 Activity（单 Activity + Compose 导航架构）。
 *
 * 职责：安装启动图 → 挂载 [IronHabitTheme] 主题 → 渲染根 [AppRoot]（含 TopBar/BottomBar/SnackbarHost）。
 * 页面跳转全部由 Compose Navigation 在 [AppRoot] 内部完成。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 启动图必须先于 super.onCreate 安装
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            // 主题模式（浅/深/跟随系统）由 IronHabitTheme 内部从 SettingsDataStore 读取，
            // 此处使用其默认值即可，无需额外参数。
            IronHabitTheme {
                AppRoot()
            }
        }
    }
}
