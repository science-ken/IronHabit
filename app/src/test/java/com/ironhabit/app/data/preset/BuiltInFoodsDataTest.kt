package com.ironhabit.app.data.preset

import com.ironhabit.app.domain.model.InputLimits
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置食物库 `assets/foods.json` 的数据体检。
 *
 * ## 为什么这份测试比看起来重要
 * 这批数值是**手抄的**，没有任何数据集可以导入（见 spec §6：合法可再分发的中文食物成分数据不存在）。
 * 手抄就会抄错。而一个抄错的 kcal 会顺着"食物 → 一餐合计 → 磁贴 → 周复盘 → AI 教练的输入"
 * 一路流进用户每天看的健康数字里，**并且被快照进 `meal_items` 永久留下**。
 * 所以这里用一条**不依赖外部权威**的判据来兜底：Atwater 系数自洽。
 *
 * ## 能量自洽为什么是有效判据
 * `kcal ≈ 4×蛋白 + 4×碳水 + 9×脂肪`。抄错任何一个数（小数点、单位、串行）都会让这个等式失衡，
 * 而真实食物数据本来就允许约 10% 的偏差（差额是膳食纤维和有机酸）。
 * 也就是说：**它能放过真实数据的正常误差，却能抓住"把 116 抄成 1160"这类错误。**
 */
class BuiltInFoodsDataTest {

    private val presets: List<FoodPresetDto> by lazy { BuiltInFoods.parse(readAssetsFoods()) }

    @Test
    fun libraryIsNotEmpty() {
        assertTrue("内置库为空 = assets 没打进包或解析失败", presets.isNotEmpty())
        assertTrue("内置库至少要有 20 条才谈得上「库」，当前 ${presets.size}", presets.size >= 20)
    }

    /** 唯一索引是播种幂等的前提；重名会让第二次启动行为不可预期。 */
    @Test
    fun namesAreUnique() {
        val duplicates = presets.groupingBy { it.name.trim() }.eachCount().filterValues { it > 1 }
        assertEquals("内置库有重名条目：$duplicates", emptyMap<String, Int>(), duplicates)
    }

    @Test
    fun everyRowIsWithinInputLimits() {
        for (preset in presets) {
            assertTrue(
                "「${preset.name}」每 100g 热量 ${preset.kcalPer100g} 越界",
                InputLimits.isValidFoodKcalPer100G(preset.kcalPer100g),
            )
            for ((label, value) in macroPairs(preset)) {
                assertTrue(
                    "「${preset.name}」的每 100g $label = $value 越界（每 100g 不可能超过 100g）",
                    InputLimits.isValidFoodMacroPer100G(value),
                )
            }
            for (serving in preset.servings) {
                assertTrue(
                    "「${preset.name}」的「${serving.unit}」= ${serving.grams}g 越界",
                    InputLimits.isValidServingGrams(serving.grams),
                )
            }
        }
    }

    /** 核心判据：能量与三大宏量必须自洽（容差 12%，覆盖纤维与有机酸造成的真实偏差）。 */
    @Test
    fun energyAgreesWithMacros() {
        val offenders = presets.mapNotNull { preset ->
            val expected: Double = ATWATER_PROTEIN * preset.proteinPer100g +
                ATWATER_CARBS * preset.carbsPer100g +
                ATWATER_FAT * preset.fatPer100g
            val actual: Double = preset.kcalPer100g.toDouble()
            val deviation: Double = if (expected <= 0.0) {
                if (actual <= 0.0) return@mapNotNull null else Double.MAX_VALUE
            } else {
                kotlin.math.abs(actual - expected) / expected
            }
            if (deviation > MAX_DEVIATION) {
                "「${preset.name}」标 ${preset.kcalPer100g} kcal，但按宏量算应为 " +
                    "%.0f kcal（偏差 %.0f%%；P=%.1f C=%.1f F=%.1f）".format(expected, deviation * 100, preset.proteinPer100g, preset.carbsPer100g, preset.fatPer100g)
            } else {
                null
            }
        }
        assertTrue("以下条目的热量与宏量对不上，几乎可以确定是抄错：\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** 份：单位不能空、不能重复（重复会让"选一碗"出现两个不同克数）。 */
    @Test
    fun servingsAreWellFormed() {
        for (preset in presets) {
            val units = preset.servings.map { it.unit.trim() }
            units.forEach { unit -> assertTrue("「${preset.name}」有一个空单位", unit.isNotEmpty()) }
            val duplicated = units.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertTrue("「${preset.name}」单位重复：$duplicated", duplicated.isEmpty())
        }
    }

    /** 忌口标签写错必须炸（拼错的 `"Dairy"` 若被静默丢掉，乳制品过敏的用户会被放行）。 */
    @Test
    fun dietaryTagsAreAllKnown() {
        BuiltInFoods.toDomain(presets) // 未知标签会在这里抛 IllegalArgumentException
    }

    /**
     * 「PEANUT」这个词表定义覆盖到的行，库里必须真的带上标签。
     *
     * 词表定义在 `BuiltInMealTemplates` 的忌口说明里：**花生、核桃、杏仁等坚果类**。
     * 2026-09-25 那份审查报告点出库里核桃仁/杏仁/腰果三条是空标签 —— 于是用户勾了
     * 忌口「花生/坚果」之后，外部 AI 计划里的杏仁行不会被这道闸挡下
     * （`ExternalPlanDocumentParser` 是拿 `food.dietaryTags ∩ 忌口` 判的）。
     * 模板那侧的核桃/杏仁一直都带标签，两侧不该两种口径。
     *
     * ⚠️ 这条闸是**尽力而为**，界面上也这么写着（`food_restricted_disclaimer`：
     * 「没有标注的食物不代表安全」）—— 葵花籽仁/黑芝麻这类种子刻意留在标签外，
     * 它们不在词表那句「坚果类」的措辞里。
     */
    @Test
    fun peanutRowsCarryThePeanutTag() {
        val nuts = presets.filter { preset -> NUT_NAMES.any { name -> preset.name.contains(name) } }
        assertTrue("内置库里有花生/坚果条目却没有 PEANUT 标签，忌口过滤会放行它", nuts.isNotEmpty())
        nuts.forEach {
            assertTrue("「${it.name}」缺 PEANUT 标签", it.dietaryTags.contains("PEANUT"))
        }
    }

    private fun macroPairs(preset: FoodPresetDto): List<Pair<String, Double>> = listOf(
        "蛋白质" to preset.proteinPer100g,
        "碳水" to preset.carbsPer100g,
        "脂肪" to preset.fatPer100g,
    )

    private fun readAssetsFoods(): String {
        val candidates = listOf(
            "src/main/assets/foods.json", // 工作目录 = app/
            "app/src/main/assets/foods.json", // 工作目录 = 仓库根
        )
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val tried = mutableListOf<String>()
        while (dir != null) {
            for (relative in candidates) {
                val candidate = File(dir, relative)
                tried += candidate.absolutePath
                if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile
        }
        error("未找到 foods.json（user.dir=${System.getProperty("user.dir")}）；已尝试：\n" + tried.joinToString("\n"))
    }

    private companion object {
        /** Atwater 系数（kcal/g）。 */
        const val ATWATER_PROTEIN: Double = 4.0
        const val ATWATER_CARBS: Double = 4.0
        const val ATWATER_FAT: Double = 9.0

        /** 真实食物数据里热量与宏量的正常偏差上限（纤维、有机酸、酒精造成）。 */
        const val MAX_DEVIATION: Double = 0.12

        /**
         * 词表那句「花生、核桃、杏仁等坚果类」点到的名字（定义见 `BuiltInMealTemplates`）。
         * 种子类（葵花籽仁、黑芝麻）刻意不在内 —— 那不是那句话覆盖的东西。
         */
        val NUT_NAMES: List<String> = listOf("花生", "核桃", "杏仁", "腰果")
    }
}
