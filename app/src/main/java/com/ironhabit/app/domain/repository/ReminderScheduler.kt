package com.ironhabit.app.domain.repository

/**
 * 提醒类型。
 *
 * @property requestCode 对应 `PendingIntent` 的 requestCode（训练 = 1，习惯 = 2）
 */
enum class ReminderType(val requestCode: Int) {
    TRAINING(1),
    HABIT(2),
}

/**
 * 提醒调度接口（domain 只描述"排期 / 取消 / 重排"，实现在 data 层用 AlarmManager）。
 */
interface ReminderScheduler {

    /**
     * 排期一次精确提醒：触发时刻为「今天 hour:minute」，若已过则顺延到明天。
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

    /** 取消某类型提醒。 */
    suspend fun cancel(type: ReminderType)

    /** 取消全部提醒。 */
    suspend fun cancelAll()

    /** 依设置重排全部提醒（开机 / 应用更新后调用）。 */
    suspend fun rescheduleAll()
}
