package com.ironhabit.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ironhabit.app.data.preferences.SettingsDataStore
import com.ironhabit.app.ui.navigation.AppRoot
import com.ironhabit.app.ui.theme.IronHabitTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 唯一 Activity（单 Activity + Compose 导航架构）。
 *
 * 职责：安装启动图 → 挂载 [IronHabitTheme] 主题 → 渲染根 [AppRoot]（含 TopBar/BottomBar/SnackbarHost）。
 * 页面跳转全部由 Compose Navigation 在 [AppRoot] 内部完成。
 *
 * 另外：在首个可见时机（API 33+ 且未授权时）弹一次 `POST_NOTIFICATIONS` 系统授权框。
 * 只弹一次，被拒绝后由设置页的引导卡承接二次申请，避免反复打扰用户。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    /** 通知权限申请器；结果交由设置页引导卡兜底，此处不做额外处理。 */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> /* no-op */ }

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

        requestNotificationPermissionOnce()
    }

    /** API 33+ 首次可见时申请通知权限；已授权 / 已问过 / 低版本均直接跳过。 */
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        lifecycleScope.launch {
            // 用 STARTED 而非直接在 onCreate 弹：确保首帧渲染完成、Activity 已可见再弹系统框
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) return@repeatOnLifecycle
                if (!settingsDataStore.shouldAskNotificationPermission()) return@repeatOnLifecycle

                // 先落标记再弹：无论用户选允许还是拒绝，都不会再自动弹第二次
                settingsDataStore.markNotificationPermissionAsked()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
