package com.ironhabit.app.ui.screens.settings

import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.domain.model.AppSettings
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.usecase.ScheduleReminderUseCase
import com.ironhabit.app.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 设置页"读 profile 最新集合 → 翻转 → 写回"toggle 的并发安全（V3 报告 V2-P3-4）。
 *
 * 写路径全部收进同一把 Mutex 之后，第一个 toggle 还没落库时第二个就开始读是安全的：
 * 它会在锁上等到第一个写完，读到的就是包含第一项的集合 —— 两次翻转都生效。
 *
 * 测试里用虚拟时间 `delay` 把 `setProfileEquipment` 拉长，让第二个 toggle 必然在
 * 第一个"写入进行中"时启动 —— 旧实现（无锁）在这个场景下会丢第一次翻转。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelToggleRaceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settingsRepository = mockk<SettingsRepository>()
    private val bodyMetricRepository = mockk<BodyMetricRepository>(relaxed = true)
    private val aiCredentialsStore = mockk<AiCredentialsStore>(relaxed = true)
    private val scheduleReminder = mockk<ScheduleReminderUseCase>(relaxed = true)

    private val profileFlow = MutableStateFlow(UserProfile())

    private fun newViewModel(): SettingsViewModel {
        every { settingsRepository.settings() } returns flowOf(AppSettings())
        every { settingsRepository.profile() } returns profileFlow
        every { settingsRepository.aiRemoteEnabled() } returns flowOf(false)
        every { bodyMetricRepository.observeByType(BodyMetricType.WEIGHT) } returns flowOf(emptyList())
        // 落库有耗时（模拟 DataStore 写盘）：给第二个 toggle 创造"在第一个写完前启动"的窗口。
        coEvery { settingsRepository.setProfileEquipment(any()) } coAnswers {
            delay(1_000)
            val next: Set<Equipment> = firstArg()
            profileFlow.value = profileFlow.value.copy(equipment = next)
        }
        return SettingsViewModel(
            settingsRepository = settingsRepository,
            bodyMetricRepository = bodyMetricRepository,
            aiCredentialsStore = aiCredentialsStore,
            scheduleReminder = scheduleReminder,
        )
    }

    @Test
    fun concurrentEquipmentTogglesDoNotLoseUpdates() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        // 连点两颗 chip：两个 persist 块先后排队（StandardTestDispatcher 不提前执行），
        // 推进虚拟时间后交错跑 —— Mutex 保证第二个读到的是已含 DUMBBELL 的集合。
        viewModel.onProfileEquipmentToggle(Equipment.DUMBBELL)
        viewModel.onProfileEquipmentToggle(Equipment.BARBELL)
        advanceUntilIdle()

        assertEquals(
            "两次翻转都必须落库（串行化后第二个 toggle 读到的是包含第一项的集合）",
            setOf(Equipment.DUMBBELL, Equipment.BARBELL),
            profileFlow.value.equipment,
        )
    }

    @Test
    fun repeatedToggleOfTheSameItemIsIdempotent() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.onProfileEquipmentToggle(Equipment.DUMBBELL)
        advanceUntilIdle()
        viewModel.onProfileEquipmentToggle(Equipment.DUMBBELL)
        advanceUntilIdle()

        assertEquals(
            "同一项翻两次回到原点",
            emptySet<Equipment>(),
            profileFlow.value.equipment,
        )
    }
}
