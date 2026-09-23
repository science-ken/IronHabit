package com.ironhabit.app.ui

import kotlinx.datetime.LocalDate

/**
 * 「一个值怎么念给人看」的规则集中在这个文件。
 *
 * 以前它们散在三个不相关的文件里（日期格式化在 `components/TrendChart.kt`、
 * 浮点格式化一份在 `screens/settings/SettingsScreen.kt`、另一份在
 * `screens/ai/AiCoachScreen.kt`），于是下一个要显示数字的人既找不到、
 * 也不知道已经有两份、于是写第三份 —— `formatMonthDay` 就是这样长到六份的。
 *
 * ## 为什么浮点有**两条**规则，而不是重复
 *
 * | 用哪条 | 给什么值 | 为什么 |
 * |---|---|---|
 * | [toDisplayNumber] | **用户手填的档案值**（目标体重 71.25、体脂率 22.5） | 必须原样念回去。设置页显示 71.25、输入框里却是别的数，用户会以为没存住 |
 * | [formatKg] | **算出来的量**（总容量 `Σ 重量×次数`、周差值、平均 RPE） | 计算结果带一长串二进制小数（`1234.5678`），不四舍五入就没法读 |
 *
 * 所以这两条**不能合并成一条**：合成"都四舍五入"会让设置页把用户输入的
 * `71.25` 显示成 `71.3`；合成"都不四舍五入"会让今日页那块容量磁贴念出
 * `1234.5678` 这种东西。选哪条的判据是**这个数是谁产生的**，不是它长什么样。
 */

/**
 * `epochDay` → 本地日期「M/D」（纯数字，不硬编码中文本地化文案）。
 *
 * 全工程只有这一份。以前六份逐字相同的私有副本散在图表、日期栏、身体数据、
 * 动作详情、历史、训练六个文件里。
 */
internal fun formatMonthDay(epochDay: Long): String {
    val date = LocalDate.fromEpochDays(epochDay.toInt())
    return "${date.monthNumber}/${date.dayOfMonth}"
}

/**
 * 用户手填的浮点：整数去掉 `.0` 尾巴（`20.0f → "20"`），有小数**原样**（`22.5f → "22.5"`）。
 *
 * 不做四舍五入 —— 见文件头那张表。
 */
internal fun Float.toDisplayNumber(): String =
    if (this % 1f == 0f) this.toLong().toString() else this.toString()

/**
 * 算出来的浮点（公斤数、容量、差值、平均 RPE）：先四舍五入到 1 位小数，再按
 * [toDisplayNumber] 去尾（`58.0 → "58"`、`58.25 → "58.3"`）。
 *
 * 名字里的 `kg` 是历史遗留：它最早只给体重用，后来容量、RPE 也走它。
 * 没改名是因为改名要动 11 个调用点，而收益不如这条注释。
 */
internal fun formatKg(kg: Float): String {
    val rounded = kotlin.math.round(kg * 10) / 10f
    return rounded.toDisplayNumber()
}
