package com.ironhabit.app.domain.model

/**
 * 「我的」页记录台账：每一类记录**记了多少、最后一次是什么时候**。
 *
 * 这一页改版前只有四个"光板"入口（写着「食物库」，右边一个箭头），点进去才知道有没有东西。
 * 台账把那些箭头换成真条数 —— 入口自己带信息，缺口也就能显出来
 * （例如饮食 `2/32`：32 个已过去的餐次里只填了 2 条食物）。
 *
 * 每个数都必须能回到一条 SQL 上，见各 DAO 里 `xxxTally()` 的注释。
 */
data class ProfileLedger(
    val training: CheckInTally = CheckInTally(),
    val body: BodyTally = BodyTally(),
    val diet: DietTally = DietTally(),
    val habits: HabitTally = HabitTally(),
)

/**
 * 身体数据台账（跨全部 7 种指标，不只体重）。
 *
 * @property rowCount 记录条数
 * @property lastEpochDay 最近一条的日期；从没记过 = `null`
 */
data class BodyTally(
    val rowCount: Int = 0,
    val lastEpochDay: Long? = null,
)

/**
 * 训练打卡的台账。
 *
 * @property rowCount 打卡记录条数（一次练 5 个动作 = 5 条，不是"练了 5 天"）
 * @property setCount 完成的总组数
 * @property repCount 完成的总次数
 * @property rpeRowCount 填了 RPE 的条数
 * @property activeDayCount **有**打卡的天数（去重）
 * @property lastEpochDay 最近一次打卡日；从没打过 = `null`
 */
data class CheckInTally(
    val rowCount: Int = 0,
    val setCount: Int = 0,
    val repCount: Int = 0,
    val rpeRowCount: Int = 0,
    val activeDayCount: Int = 0,
    val lastEpochDay: Long? = null,
)

/**
 * 饮食的台账。
 *
 * 分母只算**已经过去**的餐次（饮食计划会提前排到未来几天，把没到的算进缺口是冤枉人）。
 *
 * @property mealRowCount 已过去的启用餐次数（那 32 个格子）
 * @property filledMealCount 其中**填过东西**的餐次数 —— 一餐记 3 条食物也只算 1 餐，
 *   所以它不等于 [itemCount]；右侧那个 `2/32` 用的就是它
 * @property itemCount 食物条数（副标题里"只填了 N 条食物"用这个）
 * @property kcal 这些食物条的合计热量
 * @property lastFilledEpochDay 最后一个填了食物的餐次日；一条没填 = `null`
 */
data class DietTally(
    val mealRowCount: Int = 0,
    val filledMealCount: Int = 0,
    val itemCount: Int = 0,
    val kcal: Int = 0,
    val lastFilledEpochDay: Long? = null,
) {
    /** 缺口比例 `0f..1f` = 没填的餐次 / 已过去的餐次；餐次为 0 时给 `0f`（无缺口可谈）。 */
    val shortfallRatio: Float
        get() = if (mealRowCount <= 0) {
            0f
        } else {
            (mealRowCount - filledMealCount).toFloat() / mealRowCount.toFloat()
        }
}

/**
 * 习惯的台账。
 *
 * @property activeHabits 在跑的习惯数（软删掉的不算）
 * @property completedLogs 完成的打卡数 —— **含已删除习惯的历史**，删掉习惯不该抹掉坚持过的证据
 * @property lastEpochDay 最近一次完成打卡日；从没打过 = `null`
 */
data class HabitTally(
    val activeHabits: Int = 0,
    val completedLogs: Int = 0,
    val lastEpochDay: Long? = null,
)
