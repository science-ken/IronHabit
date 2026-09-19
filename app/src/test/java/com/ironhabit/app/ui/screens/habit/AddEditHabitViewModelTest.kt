package com.ironhabit.app.ui.screens.habit

import androidx.lifecycle.SavedStateHandle
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Habit
import com.ironhabit.app.domain.model.HabitFrequency
import com.ironhabit.app.domain.repository.HabitRepository
import com.ironhabit.app.domain.repository.ReminderScheduler
import com.ironhabit.app.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * [AddEditHabitViewModel] 往返测试（Fix1：编辑习惯不得吞数据）。
 *
 * 这是**往返验证**而非读代码：造一条真实习惯 → 走「编辑 → 只改 emoji → 保存」这条
 * 生产路径（ViewModel 的 `load()` + `onSave()` + 真实 `Habit` 构造）→ 断言写库对象的字段。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddEditHabitViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val scheduler = mockk<ReminderScheduler>(relaxed = true)
    private val savedHabit = slot<Habit>()

    /** 计量型习惯：目标 8 杯、带备注。 */
    private val measurableHabit = Habit(
        id = 42L,
        name = "每天 8 杯水",
        emoji = "\u2705",
        colorHex = "#2196F3",
        frequency = HabitFrequency.DAILY,
        weeklyDaysMask = Habit.WEEKLY_DAYS_ALL,
        reminderEnabled = false,
        note = "某备注",
        targetValue = 8.0,
        targetUnit = "杯",
        isActive = true,
        sortOrder = 3,
        createdAt = 1_000L,
    )

    /** 纯勾选型习惯：目标值 / 备注均为 NULL。 */
    private val checkboxHabit = Habit(
        id = 7L,
        name = "冥想",
        emoji = "\u2705",
        frequency = HabitFrequency.DAILY,
        weeklyDaysMask = Habit.WEEKLY_DAYS_ALL,
        note = null,
        targetValue = null,
        targetUnit = null,
        isActive = true,
        createdAt = 2_000L,
    )

    private fun repositoryWith(seed: List<Habit>): HabitRepository {
        val repo = mockk<HabitRepository>(relaxed = true)
        every { repo.observeActiveHabits() } returns flowOf(seed)
        coEvery { repo.upsertHabit(capture(savedHabit)) } returns 42L
        return repo
    }

    private fun viewModel(repo: HabitRepository, habitId: Long) = AddEditHabitViewModel(
        savedStateHandle = SavedStateHandle(mapOf("habitId" to habitId)),
        habitRepository = repo,
        reminderScheduler = scheduler,
        clock = Clock.System,
    )

    @Test
    fun loadBackfillsTargetAndNoteInEditMode() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel(repositoryWith(listOf(measurableHabit)), habitId = 42L)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("整数值回显不带小数点", "8", state.targetValue)
        assertEquals("目标单位必须回填", "杯", state.targetUnit)
        assertEquals("备注必须回填", "某备注", state.note)
    }

    @Test
    fun editingOnlyEmojiKeepsTargetValueUnitAndNote() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = repositoryWith(listOf(measurableHabit))
        val vm = viewModel(repo, habitId = 42L)
        advanceUntilIdle()

        // 用户只改 emoji，其余字段一律不碰。
        vm.onEmojiChange("\uD83D\uDD25")
        vm.onSave()
        advanceUntilIdle()

        val saved = savedHabit.captured
        assertEquals("emoji 应更新", "\uD83D\uDD25", saved.emoji)
        assertEquals("target_value 必须原样留存", 8.0, saved.targetValue!!, 0.0)
        assertEquals("target_unit 必须原样留存", "杯", saved.targetUnit)
        assertEquals("note 必须原样留存", "某备注", saved.note)
        assertEquals("id 必须保留", 42L, saved.id)
    }

    @Test
    fun nullTargetAndNoteStayNullAfterUnrelatedEdit() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = repositoryWith(listOf(checkboxHabit))
        val vm = viewModel(repo, habitId = 7L)
        advanceUntilIdle()

        assertEquals("NULL 目标回显为空串", "", vm.uiState.value.targetValue)
        assertEquals("NULL 备注回显为空串", "", vm.uiState.value.note)

        vm.onNameChange("冥想 10 分钟")
        vm.onSave()
        advanceUntilIdle()

        val saved = savedHabit.captured
        assertNull("原为 NULL 的目标值不得被写成 0.0", saved.targetValue)
        assertNull("无目标值时不得残留单位", saved.targetUnit)
        assertNull("原为 NULL 的备注不得被写成空串", saved.note)
    }

    @Test
    fun changingTargetValueTrulyUpdates() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = repositoryWith(listOf(measurableHabit))
        val vm = viewModel(repo, habitId = 42L)
        advanceUntilIdle()

        // 反例：故意改成新值 → 必须真的更新（别修成「永远保留旧值」）。
        vm.onTargetValueChange("10")
        vm.onTargetUnitChange("次")
        vm.onSave()
        advanceUntilIdle()

        val saved = savedHabit.captured
        assertEquals(10.0, saved.targetValue!!, 0.0)
        assertEquals("次", saved.targetUnit)
    }

    @Test
    fun clearingTargetValueWritesNullNotZero() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = repositoryWith(listOf(measurableHabit))
        val vm = viewModel(repo, habitId = 42L)
        advanceUntilIdle()

        // 用户清空目标值 → 应落 NULL（纯勾选型），而非 0.0，也不应残留单位。
        vm.onTargetValueChange("")
        vm.onTargetUnitChange("杯")
        vm.onSave()
        advanceUntilIdle()

        val saved = savedHabit.captured
        assertNull("清空目标值应写 NULL", saved.targetValue)
        assertNull("无目标值时单位必须清空", saved.targetUnit)
    }

    @Test
    fun targetValueWithoutUnitIsRejectedAndNeverSaved() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = repositoryWith(listOf(measurableHabit))
        val vm = viewModel(repo, habitId = 42L)
        advanceUntilIdle()

        vm.onTargetUnitChange("")
        vm.onSave()
        advanceUntilIdle()

        assertEquals(R.string.error_target_unit_required, vm.uiState.value.snackbarRes)
        assertFalse("被拒的保存绝不能落库", savedHabit.isCaptured)
    }

    @Test
    fun fractionalTargetRoundTripsExactly() = runTest(mainDispatcherRule.testDispatcher) {
        val fractional = measurableHabit.copy(targetValue = 2.5)
        val repo = repositoryWith(listOf(fractional))
        val vm = viewModel(repo, habitId = 42L)
        advanceUntilIdle()

        assertEquals("小数目标回显应保留小数", "2.5", vm.uiState.value.targetValue)

        vm.onSave()
        advanceUntilIdle()

        assertEquals(2.5, savedHabit.captured.targetValue!!, 0.0)
    }

    // ---------------- P0-4：保存防抖（连点不得产生重复数据）----------------

    /**
     * 连点「保存」：两次点击都会读到 `habitId = 0`（表单尚未清空），历史实现会插入两条同名习惯。
     * 现在进入即置位 `isSaving` 并提前返回 → 只落一条。
     */
    @Test
    fun rapidDoubleSave_writesOnlyOnce() = runTest(mainDispatcherRule.testDispatcher) {
        var writes = 0
        val repo = mockk<HabitRepository>(relaxed = true)
        every { repo.observeActiveHabits() } returns flowOf(emptyList())
        coEvery { repo.upsertHabit(capture(savedHabit)) } answers {
            writes += 1
            42L
        }

        val vm = viewModel(repo, habitId = 0L)   // 新增态
        advanceUntilIdle()
        vm.onNameChange("晨跑")

        vm.onSave()
        vm.onSave()
        vm.onSave()
        assertEquals("写入中必须置位（按钮据此禁用）", true, vm.uiState.value.isSaving)

        advanceUntilIdle()
        assertEquals("连点三次只允许落库一次", 1, writes)
        assertEquals("写完后必须复位，否则按钮永久禁用", false, vm.uiState.value.isSaving)
    }

    /** 写入抛异常时同样要复位，否则保存失败后按钮就再也点不动了。 */
    @Test
    fun failedSave_resetsSavingFlag() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = mockk<HabitRepository>(relaxed = true)
        every { repo.observeActiveHabits() } returns flowOf(emptyList())
        coEvery { repo.upsertHabit(capture(savedHabit)) } throws RuntimeException("disk full")

        val vm = viewModel(repo, habitId = 0L)
        advanceUntilIdle()
        vm.onNameChange("晨跑")
        vm.onSave()
        advanceUntilIdle()

        assertEquals("失败后必须复位，否则用户只能退出页面重来", false, vm.uiState.value.isSaving)
    }
}
