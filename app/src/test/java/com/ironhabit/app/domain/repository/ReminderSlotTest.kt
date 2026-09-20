package com.ironhabit.app.domain.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 闹钟槽身份（2.0.9 每习惯一条提醒）。
 *
 * 这一组断言钉的是**"两个习惯不能抢同一个槽"**。
 * 2.0.8 及之前所有习惯共用 `requestCode = 2`，表现是：
 * 保存第二个习惯就把第一个的闹钟顶掉，用户看到"设了提醒却不响"。
 *
 * ⚠️ 这里只能验到"槽号算得对不对"。`PendingIntent` 真的按 requestCode 分开了没有，
 * 是 Android 框架行为，JVM 单测覆盖不到 —— 那部分要在设备上读闹钟才能证明。
 */
class ReminderSlotTest {

    @Test
    fun twoHabits_getTwoDifferentSlots() {
        val first = ReminderType.slotFor(ReminderType.HABIT, habitId = 1L)
        val second = ReminderType.slotFor(ReminderType.HABIT, habitId = 2L)

        assertNotEquals(
            "两个习惯共用一个 requestCode 就是这次的 bug：后保存的会把先保存的顶掉",
            first,
            second,
        )
    }

    @Test
    fun habitSlots_neverCollideWithTheTwoGlobalSlots() {
        // 遗留的 2 号槽仍要能被单独取消（升级前就存在的那条闹钟），所以习惯不能占用它。
        assertNotEquals(ReminderType.TRAINING.requestCode, ReminderType.slotFor(ReminderType.HABIT, 0L))
        assertNotEquals(ReminderType.HABIT.requestCode, ReminderType.slotFor(ReminderType.HABIT, 0L))
        assertEquals(
            "id=0 是「新建还没落库」的哨兵值，绝不能和任何全局槽同号",
            ReminderType.HABIT_SLOT_BASE,
            ReminderType.slotFor(ReminderType.HABIT, 0L),
        )
    }

    @Test
    fun trainingIgnoresHabitId_soItStaysOneGlobalSlot() {
        assertEquals(
            "训练提醒是全局唯一一条，传了 habitId 也不许分裂成多个槽",
            ReminderType.TRAINING.requestCode,
            ReminderType.slotFor(ReminderType.TRAINING, habitId = 7L),
        )
    }

    @Test
    fun nullHabitId_fallsBackToTheLegacySlotSoItCanBeCancelled() {
        // rescheduleAll 靠这条把升级前遗留的那个闹钟撤掉。
        assertEquals(
            2,
            ReminderType.slotFor(ReminderType.HABIT, habitId = null),
        )
    }
}
