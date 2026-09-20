package com.ironhabit.app.domain.repository

/**
 * 提醒类型。
 *
 * @property requestCode 对应 `PendingIntent` 的 requestCode（训练 = 1）
 *
 * ⚠️ [HABIT] 的 `requestCode = 2` 是**历史遗留的单槽**：2.0.9 起每个习惯各占一个槽
 * （见 [ReminderScheduler.scheduleHabit]），2 号槽不再排新闹钟。
 * 保留这个值只为了 [ReminderScheduler.rescheduleAll] 能把**升级前就存在的**那个
 * 旧闹钟取消掉 —— 否则老用户升级后会一直收到一条不指认任何习惯的提醒。
 */
enum class ReminderType(val requestCode: Int) {
    TRAINING(1),
    HABIT(2),
    ;

    companion object {

        /**
         * 习惯槽起始码。1 / 2 已被上面两个占掉（2 是升级前的遗留槽，仍要能撤）。
         * 习惯 id 是本地自增 Long，量级远小于 `Int.MAX - 10_000`；真要撞槽得先有上亿个习惯。
         */
        const val HABIT_SLOT_BASE: Int = 10_000

        /**
         * 一条闹钟的 requestCode —— **闹钟之间唯一的身份**。
         *
         * 为什么必须编码进 requestCode 而不是 Intent extra：`PendingIntent` 判等只看
         * action / data / type / package / class，**不看 extras**，
         * 所以两个习惯即使 extra 不同也会共用一个槽，后一个把前一个顶掉。
         */
        fun slotFor(type: ReminderType, habitId: Long?): Int =
            if (type != HABIT || habitId == null) {
                type.requestCode
            } else {
                HABIT_SLOT_BASE + habitId.toInt()
            }
    }
}

/**
 * 提醒调度接口（domain 只描述"排期 / 取消 / 重排"，实现在 data 层用 AlarmManager）。
 */
interface ReminderScheduler {

    /**
     * 排期一次精确提醒：触发时刻为「今天 hour:minute」，若已过则顺延到明天。
     *
     * 只用于**全局唯一**的那一类（训练）。习惯请走 [scheduleHabit]。
     *
     * @param type 提醒类型
     * @param hour 小时（`0..23`）
     * @param minute 分钟（`0..59`）
     */
    suspend fun schedule(type: ReminderType, hour: Int, minute: Int)

    /**
     * 续排下一次提醒（供触发后自续期使用，语义 = 排「明天」同一时刻）。
     */
    suspend fun scheduleNext(type: ReminderType, hour: Int, minute: Int)

    /**
     * 排期**某个习惯**的每日提醒 —— 每个习惯各占一个闹钟槽，互不覆盖。
     *
     * 为什么必须按习惯分槽：习惯表单本来就允许逐个设提醒时间，若全挤一个 requestCode，
     * 保存第二个习惯就会把第一个的闹钟**静默顶掉**，用户看到的是"设了不响"。
     */
    suspend fun scheduleHabit(habitId: Long, hour: Int, minute: Int)

    /** 续排某习惯的下一次提醒（语义 = 排「明天」同一时刻）。 */
    suspend fun scheduleHabitNext(habitId: Long, hour: Int, minute: Int)

    /** 取消某类型提醒（仅对全局槽有意义，即 [ReminderType.TRAINING] 与遗留的 [ReminderType.HABIT]）。 */
    suspend fun cancel(type: ReminderType)

    /** 取消**某个习惯**的提醒（删习惯 / 关掉它的提醒开关时调用）。 */
    suspend fun cancelHabit(habitId: Long)

    /** 取消全部提醒。 */
    suspend fun cancelAll()

    /**
     * 依设置重排全部提醒（开机 / 应用更新 / 时区变更后调用）。
     *
     * 要求是**幂等且自愈**的：遍历所有习惯（含已停用的），该排的排、该撤的撤，
     * 因此不需要额外记住"上次排了哪些"，也不会留下孤儿闹钟。
     */
    suspend fun rescheduleAll()
}
