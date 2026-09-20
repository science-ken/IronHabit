package com.ironhabit.app.domain.model

/**
 * 肌群标签词表 —— 全 App 的**唯一真源**。
 *
 * 这些值是写进 `exercises.muscle_group`（有序 CSV，第一个 = 主肌群）与备份 JSON 的**数据**，
 * 不是文案（架构 §7.5 允许预置数据内联在 Kotlin 的唯一例外），所以**不**走 `strings.xml`。
 *
 * ## 为什么必须只有一个真源
 * v2.0.8 之前同一套标签抄了**三份**：内置动作字面量、`LocalRuleAdvisor` 的伤病/重点映射表、
 * 动作表单的可选 chips。三份各自漂移过一次实际故障 —— 表单那份漏了 [GLUTE_LEG]「臀腿」，
 * 于是编辑「相扑深蹲」时页面上没有对应 chip，用户一保存就把这个肌群标签丢了。
 * 现在只有本对象能定义标签，其余全部引用它；漂移由 `MuscleGroupVocabularyTest` 拦。
 *
 * ⚠️ 改值 = 改数据：新增值可以，**改名/删名会让存量行的标签变成词表外的值**（伤病避让会静默失效）。
 */
object MuscleGroup {

    const val CHEST: String = "胸部"
    const val UPPER_CHEST: String = "上胸"
    const val BACK: String = "背部"
    const val REAR_DELT: String = "后肩"
    const val SHOULDER: String = "肩部"
    const val BICEPS: String = "肱二头肌"
    const val TRICEPS: String = "肱三头肌"
    const val LEG: String = "腿部"
    const val GLUTE: String = "臀部"

    /** 「臀腿」= 臀与腿同时主导（相扑深蹲一类）。与 [GLUTE]、[LEG] 是**不同**的标签，不是它们的合写。 */
    const val GLUTE_LEG: String = "臀腿"

    const val HAMSTRING: String = "腿后链"
    const val CORE: String = "核心"
    const val ABS: String = "腹部"
    const val FULL_BODY: String = "全身"

    /**
     * 有氧动作占用的标签。
     *
     * ⚠️ 它**不是**一块肌肉，而是拿分类名当肌群用（历史遗留）。保留是因为伤病映射
     * （`INJURY_AGGRAVATED_TAGS[CARDIO]`）和肌群筛选 chips 都依赖它；改成真枚举要连带迁移存量值。
     */
    const val CARDIO: String = "有氧"

    /**
     * 动作表单上可选的肌群 chips（顺序即 UI 顺序：大肌群在前）。
     *
     * 必须与 [all] 等价 —— 少一个就会让存量标签在编辑时静默丢失（见类文档）。
     */
    val formOptions: List<String> = listOf(
        CHEST, UPPER_CHEST, BACK, SHOULDER, REAR_DELT,
        BICEPS, TRICEPS, LEG, HAMSTRING, GLUTE, GLUTE_LEG,
        CORE, ABS, FULL_BODY, CARDIO,
    )

    /** 全部合法标签。 */
    val all: Set<String> = formOptions.toSet()

    /** 该标签是否在词表内（存量数据清洗 / 导入外部动作库时用）。 */
    fun isKnown(label: String): Boolean = label in all
}
