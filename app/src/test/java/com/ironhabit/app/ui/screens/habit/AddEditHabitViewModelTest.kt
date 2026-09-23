package com.ironhabit.app.ui.screens.habit

import androidx.lifecycle.SavedStateHandle
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Habit
import com.ironhabit.app.domain.model.HabitFrequency
import com.ironhabit.app.domain.repository.HabitRepository
import com.ironhabit.app.domain.repository.ReminderScheduler
import com.ironhabit.app.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Assert.assertTrue
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

    // ---------------- 2.0.9：每个习惯各占一个提醒槽 ----------------

    /**
     * 开启提醒 → 只排**这一个**习惯的槽。
     *
     * 2.0.8 这里调的是全局 `schedule(HABIT, …)`：保存第二个习惯会把第一个的闹钟顶掉。
     * 所以"没碰全局槽"和"没去 cancel 全局槽"这两条断言才是这次的回归防线，
     * 光断言 scheduleHabit 被调用，旧代码改个名也能过。
     */
    @Test
    fun enablingReminder_schedulesThisHabitAndNeverTouchesTheGlobalSlot() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = repositoryWith(listOf(measurableHabit))
            val vm = viewModel(repo, habitId = 42L)
            advanceUntilIdle()

            vm.onReminderEnabledChange(true)
            vm.onReminderTimeChange(7, 30)
            vm.onSave()
            advanceUntilIdle()

            coVerify { scheduler.scheduleHabit(42L, 7, 30) }
            coVerify(exactly = 0) { scheduler.schedule(any(), any(), any()) }
            coVerify(exactly = 0) { scheduler.cancel(any()) }
        }

    /** 关掉这一个习惯的提醒 → 只撤它的槽，不能把别的习惯的闹钟一起撤了。 */
    @Test
    fun disablingReminder_cancelsOnlyThisHabit() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = repositoryWith(listOf(measurableHabit.copy(reminderEnabled = true, reminderHour = 7, reminderMinute = 30)))
        val vm = viewModel(repo, habitId = 42L)
        advanceUntilIdle()

        vm.onReminderEnabledChange(false)
        vm.onSave()
        advanceUntilIdle()

        coVerify { scheduler.cancelHabit(42L) }
        coVerify(exactly = 0) { scheduler.cancel(any()) }
        coVerify(exactly = 0) { scheduler.scheduleHabit(any(), any(), any()) }
    }

    /**
     * **新建**习惯：槽号必须用落库后返回的 id，不能用还是 0 的 `habit.id`。
     *
     * 用 0 的话，所有新建习惯都抢同一个槽 —— 表面上"设了提醒"，实际只有最后一个会响，
     * 而且这个坑只在新增路径上出现（编辑路径 id 本来就有），很容易漏测。
     */
    @Test
    fun newHabit_schedulesUnderTheIdTheDatabaseGaveBack() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = mockk<HabitRepository>(relaxed = true)
        every { repo.observeActiveHabits() } returns flowOf(emptyList())
        coEvery { repo.upsertHabit(any()) } returns 99L

        val vm = viewModel(repo, habitId = 0L)
        advanceUntilIdle()
        vm.onNameChange("晨跑")
        vm.onReminderEnabledChange(true)
        vm.onReminderTimeChange(6, 45)
        vm.onSave()
        advanceUntilIdle()

        coVerify { scheduler.scheduleHabit(99L, 6, 45) }
        coVerify(exactly = 0) { scheduler.scheduleHabit(0L, any(), any()) }
    }

    // ---------------- 台账 A13：「每周指定日」全不选 ----------------

    /** 只排周三的习惯，用来把掩码拨来拨去。 */
    private val weeklyHabit = Habit(
        id = 42L,
        name = "游泳",
        emoji = "\uD83C\uDFCA",
        frequency = HabitFrequency.WEEKLY,
        weeklyDaysMask = Habit.WEEKLY_DAYS_ALL,
        reminderEnabled = false,
        targetValue = null,
        targetUnit = null,
        isActive = true,
        createdAt = 3_000L,
    )

    /**
     * 全不选时保存**什么都不写**。
     *
     * 这条测的是 VM 而不是按钮：界面禁用保存只是第一道，直接调 `onSave()` 绕过它也得挡住。
     */
    @Test
    fun weeklyWithEveryDayClearedSavesNothing() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = repositoryWith(listOf(weeklyHabit))
        val vm = viewModel(repo, habitId = 42L)
        advanceUntilIdle()

        (0..6).forEach { index -> vm.onToggleWeekday(index) }

        assertTrue(
            "判据必须是 frequency + 掩码派生的，不能是一个会漂的字段",
            vm.uiState.value.needsWeeklyDays,
        )
        vm.onSave()
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.upsertHabit(any()) }
    }

    /**
     * 留一天时存的**就是那一天**：钉的是位序与"取消真的会清位"。
     *
     * ⚠️ 说清它**杀不掉什么**：把 A13 那个 `?: WEEKLY_DAYS_ALL` 兜底加回去，这条照样绿 ——
     * 掩码非 0 时兜底根本不触发。那条变异是上面那条测试抓的（实测过：加回兜底只有它红）。
     * 这条能抓的是另一类：bit 序错位（周三存成 `1 shl 3`），或 `xor` 被改成只置位不清位。
     */
    @Test
    fun weeklySavesExactlyTheDaysLeftSelected_notTheWholeWeek() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = repositoryWith(listOf(weeklyHabit))
        val vm = viewModel(repo, habitId = 42L)
        advanceUntilIdle()

        (0..6).filter { it != 2 }.forEach { index -> vm.onToggleWeekday(index) }
        assertFalse(vm.uiState.value.needsWeeklyDays)

        vm.onSave()
        advanceUntilIdle()

        assertEquals(
            "存下的掩码只能是用户留下的周三",
            1 shl 2,
            savedHabit.captured.weeklyDaysMask,
        )
    }
}
