package com.ironhabit.app.domain.diet

import com.ironhabit.app.data.preset.BuiltInMealTemplates
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.DietTarget
import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DietPlanGenerator] 的 JVM 单测（**纯函数、零 Android**）。
 *
 * 覆盖设计文档 §7 的三件事：① 目标公式与降级/钳制；② 餐次比例拆分；
 * ③ 模板选取的**确定性 / 可复现（幂等）**。
 */
class DietPlanGeneratorTest {

    private val target1670 = DietTarget(targetKcal = 1670, targetProtein = 126, usedDefaults = false)

    // ------------------------------------------------------------------
    // ① 目标公式 / 降级 / 钳制
    // ------------------------------------------------------------------

    @Test
    fun mealType_ordinalFollowsPreviewOrder() {
        // F3：早餐 → 午餐 → 加餐 → 晚餐（加餐在午/晚之间）。
        assertEquals(
            listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.SNACK, MealType.DINNER),
            MealType.entries.toList(),
        )
    }

    @Test
    fun dailyTarget_fullProfile_restDayMaintain_matchesMifflinAnchor() {
        // BMR = 10·78 + 6.25·175 − 5·30 + 5 = 1728.75；TDEE ×1.375 = 2377.03… → 维持 ×1.0
        val target = DietPlanGenerator.dailyTarget(
            weightKg = 78f,
            gender = Gender.MALE,
            age = 30,
            heightCm = 175,
            goal = Goal.MAINTAIN,
            isTrainingDay = false,
        )
        assertEquals(2377, target.targetKcal)
        assertEquals(125, target.targetProtein) // 78 × 1.6 = 124.8 → 125
        assertFalse(target.usedDefaults)
    }

    @Test
    fun dailyTarget_trainingDay_usesHigherActivityFactor() {
        // 1728.75 × 1.55 = 2679.5625 → 2680
        val target = DietPlanGenerator.dailyTarget(
            weightKg = 78f,
            gender = Gender.MALE,
            age = 30,
            heightCm = 175,
            goal = Goal.MAINTAIN,
            isTrainingDay = true,
        )
        assertEquals(2680, target.targetKcal)
        assertFalse(target.usedDefaults)
    }

    @Test
    fun dailyTarget_goalFactor_appliesMultiplier() {
        // 减脂 ×0.85：2377.03125 × 0.85 = 2020.476… → 2020
        val cut = DietPlanGenerator.dailyTarget(78f, Gender.MALE, 30, 175, Goal.CUT, false)
        assertEquals(2020, cut.targetKcal)
        // 增肌 ×1.10：2377.03125 × 1.10 = 2614.734… → 2615
        val bulk = DietPlanGenerator.dailyTarget(78f, Gender.MALE, 30, 175, Goal.BULK, false)
        assertEquals(2615, bulk.targetKcal)
    }

    @Test
    fun dailyTarget_emptyProfile_withWeight_usesConservativeDefaults() {
        // §7.5.3 例：性别缺→女、年龄缺→30、身高缺→175
        // BMR = 780 + 1093.75 − 150 − 161 = 1562.75；×1.375 = 2148.78… → 2149
        val target = DietPlanGenerator.dailyTarget(
            weightKg = 78f,
            gender = null,
            age = null,
            heightCm = null,
            goal = Goal.MAINTAIN,
            isTrainingDay = false,
        )
        assertEquals(2149, target.targetKcal)
        assertEquals(125, target.targetProtein)
        assertTrue(target.usedDefaults)
    }

    @Test
    fun dailyTarget_nullWeight_fallsBackTo70kg() {
        // 体重缺 → 70kg；BMR = 700 + 1093.75 − 150 + 5 = 1648.75；×1.375 = 2267.03… → 2267
        val target = DietPlanGenerator.dailyTarget(null, Gender.MALE, 30, 175, Goal.MAINTAIN, false)
        assertEquals(2267, target.targetKcal)
        assertEquals(112, target.targetProtein) // 70 × 1.6 = 112
        assertTrue(target.usedDefaults)
    }

    @Test
    fun dailyTarget_clampsExtremesToSafeRange() {
        val high = DietPlanGenerator.dailyTarget(300f, Gender.MALE, 20, 220, Goal.BULK, true)
        assertEquals(4000, high.targetKcal) // 上限钳制
        assertEquals(300, high.targetProtein) // 300 × 1.6 = 480 → 上限 300

        val low = DietPlanGenerator.dailyTarget(30f, Gender.FEMALE, 100, 140, Goal.MAINTAIN, false)
        assertEquals(1200, low.targetKcal) // 下限钳制
        assertEquals(50, low.targetProtein) // 30 × 1.6 = 48 → 下限 50
    }

    @Test
    fun dailyTarget_neverProducesNaNOrNonPositive_overWideInputSpace() {
        for (weight in listOf(1f, 30f, 70f, 150f, 300f, 999f)) {
            for (age in listOf(1, 14, 30, 100, 200)) {
                for (height in listOf(50, 140, 175, 220, 300)) {
                    for (goal in Goal.entries) {
                        for (training in listOf(true, false)) {
                            val t = DietPlanGenerator.dailyTarget(weight, Gender.MALE, age, height, goal, training)
                            assertTrue("kcal out of range: $t", t.targetKcal in 1200..4000)
                            assertTrue("protein out of range: $t", t.targetProtein in 50..300)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun dailyTarget_usedDefaults_trueWhenAnyBodyFieldMissing() {
        assertTrue(DietPlanGenerator.dailyTarget(78f, Gender.MALE, null, 175, Goal.MAINTAIN, false).usedDefaults)
        assertTrue(DietPlanGenerator.dailyTarget(78f, null, 30, 175, Goal.MAINTAIN, false).usedDefaults)
        assertTrue(DietPlanGenerator.dailyTarget(78f, Gender.MALE, 30, null, Goal.MAINTAIN, false).usedDefaults)
        assertFalse(DietPlanGenerator.dailyTarget(78f, Gender.MALE, 30, 175, Goal.MAINTAIN, false).usedDefaults)
    }

    // ------------------------------------------------------------------
    // ② 餐次比例拆分
    // ------------------------------------------------------------------

    @Test
    fun mealRatios_sumToOne() {
        val sum = MealType.entries.sumOf { DietPlanGenerator.ratioOf(it) }
        assertEquals(1.0, sum, 1e-9)
    }

    @Test
    fun buildMeal_ratioSplit_mealKcalSumEqualsTarget() {
        // 预览 F5：4 餐精确加总到日目标。
        val sumKcal = MealType.entries.sumOf { type ->
            DietPlanGenerator.buildMeal(epochDay = 20_000L, mealType = type, target = target1670, createdAt = 0L).kcal
        }
        assertEquals(1670, sumKcal)
    }

    @Test
    fun buildMeal_ratioSplit_mealProteinSumEqualsTarget() {
        val sumProtein = MealType.entries.sumOf { type ->
            DietPlanGenerator.buildMeal(epochDay = 20_000L, mealType = type, target = target1670, createdAt = 0L).proteinG
        }
        assertEquals(126.0, sumProtein, 1e-9)
    }

    @Test
    fun buildMeal_ratioSplit_matchesPerMealRatios() {
        val breakfast = DietPlanGenerator.buildMeal(20_000L, MealType.BREAKFAST, target1670, 0L)
        assertEquals(418, breakfast.kcal) // 1670 × 0.25 = 417.5 → 418
        assertEquals(31.5, breakfast.proteinG, 1e-9)

        val lunch = DietPlanGenerator.buildMeal(20_000L, MealType.LUNCH, target1670, 0L)
        assertEquals(559, lunch.kcal) // 1670 × 0.335 = 559.45 → 559

        val snack = DietPlanGenerator.buildMeal(20_000L, MealType.SNACK, target1670, 0L)
        assertEquals(209, snack.kcal) // 1670 × 0.125 = 208.75 → 209

        val dinner = DietPlanGenerator.buildMeal(20_000L, MealType.DINNER, target1670, 0L)
        assertEquals(484, dinner.kcal) // 1670 × 0.29 = 484.3 → 484
    }

    // ------------------------------------------------------------------
    // ③ 模板选取：确定性 / 可复现 / 幂等
    // ------------------------------------------------------------------

    @Test
    fun builtInTemplates_everyMealTypeHasAtLeastOneTemplate() {
        for (type in MealType.entries) {
            assertTrue("template empty for $type", BuiltInMealTemplates.forType(type).isNotEmpty())
        }
    }

    @Test
    fun buildMeal_sameInputs_producesIdenticalMeal() {
        val a = DietPlanGenerator.buildMeal(20_000L, MealType.LUNCH, target1670, 1_700_000_000_000L)
        val b = DietPlanGenerator.buildMeal(20_000L, MealType.LUNCH, target1670, 1_700_000_000_000L)
        assertEquals(a, b)
    }

    @Test
    fun buildMeal_sameDaySameMeal_deterministicContentRegardlessOfCreatedAt() {
        // 内容（条目 / 热量 / 蛋白 / 排序）只由 (日期, 餐次, 目标) 决定，与构建时刻无关。
        val a = DietPlanGenerator.buildMeal(31_234L, MealType.DINNER, target1670, 1L)
        val b = DietPlanGenerator.buildMeal(31_234L, MealType.DINNER, target1670, 999_999_999L)
        assertEquals(a.items, b.items)
        assertEquals(a.kcal, b.kcal)
        assertEquals(a.proteinG, b.proteinG, 0.0)
        assertEquals(a.sortOrder, b.sortOrder)
    }

    @Test
    fun buildMeal_adjacentDays_rotateTemplate() {
        // 相邻两天 → index 差 1（模板数 > 1）→ 内容应不同。
        val day1 = DietPlanGenerator.buildMeal(100L, MealType.BREAKFAST, target1670, 0L)
        val day2 = DietPlanGenerator.buildMeal(101L, MealType.BREAKFAST, target1670, 0L)
        assertNotEquals(day1.items, day2.items)
    }

    @Test
    fun buildMeal_initializesDraftFields() {
        val meal: Meal = DietPlanGenerator.buildMeal(31_234L, MealType.SNACK, target1670, 42L)
        assertEquals(0L, meal.id)
        assertEquals(31_234L, meal.dateEpochDay)
        assertEquals(MealType.SNACK, meal.mealType)
        assertEquals(MealType.SNACK.ordinal, meal.sortOrder)
        assertTrue(meal.items.isNotEmpty())
        assertTrue(meal.kcal >= 0)
        assertTrue(meal.proteinG >= 0.0)
        assertFalse(meal.isCompleted)
        assertTrue(meal.isActive)
        assertFalse(meal.isUserEdited)
        assertEquals(42L, meal.createdAt)
    }

    @Test
    fun buildMeal_itemsAreNewlineFreeDisplayStrings() {
        // 诚实登记的边界：条目内不得含换行（否则 items_text 会被切错）。
        for (type in MealType.entries) {
            val meal = DietPlanGenerator.buildMeal(7L, type, target1670, 0L)
            for (item in meal.items) {
                assertFalse("item contains newline: $item", item.contains('\n'))
                assertTrue("blank item in $type", item.isNotBlank())
            }
        }
    }

    // ------------------------------------------------------------------
    // ④ 忌口过滤（dietaryAvoid）+ ⑤ 按比例缩放宏量
    // ------------------------------------------------------------------

    /** 取某日某餐的「原样」条目（不过滤），作为过滤前的对照。 */
    private fun originalItems(epochDay: Long, type: MealType): List<String> =
        DietPlanGenerator.buildDraft(epochDay, type, target1670, 0L, emptySet()).meal.items

    @Test
    fun dietaryAvoid_emptySet_isByteIdenticalToUnfiltered() {
        // 验收 6：空忌口时，buildMeal（旧签名）与 buildDraft(emptySet) 必须逐字一致，且 filteredCount=0。
        for (day in listOf(0L, 7L, 100L, 20_000L, 20_001L, 20_002L, 20_003L, 31_234L)) {
            for (type in MealType.entries) {
                val viaBuildMeal = DietPlanGenerator.buildMeal(day, type, target1670, 0L)
                val draft = DietPlanGenerator.buildDraft(day, type, target1670, 0L, emptySet())
                assertEquals(viaBuildMeal, draft.meal)
                assertEquals(0, draft.filteredCount)
            }
        }
    }

    @Test
    fun dietaryAvoid_dairy_dropsDairyItemAndScalesMacros() {
        // BREAKFAST，epochDay=20000 → 模板下标 0 = [燕麦+牛奶(GLUTEN,DAIRY), 水煮蛋, 蓝莓]，原 3 条。
        // 过滤 {DAIRY} → 剩 2 条 → factor = 2/3。
        // kcal   = round(1670 × 0.25 × 2/3) = round(278.333…) = 278
        // 蛋白   = 126 × 0.25 × 2/3          = 21.0
        val before = originalItems(20_000L, MealType.BREAKFAST)
        val draft = DietPlanGenerator.buildDraft(
            20_000L, MealType.BREAKFAST, target1670, 0L, setOf(DietRestriction.DAIRY),
        )
        assertEquals(3, before.size)
        assertEquals(listOf("水煮蛋 2 个", "蓝莓 80g"), draft.meal.items)
        assertEquals(278, draft.meal.kcal)
        assertEquals(21.0, draft.meal.proteinG, 1e-9)
        assertEquals(1, draft.filteredCount)
    }

    @Test
    fun dietaryAvoid_gluten_dropsGlutenItem() {
        // LUNCH，epochDay=20001 → 下标 2 = [全麦意面(GLUTEN), 瘦牛肉, 混合蔬菜]。
        val draft = DietPlanGenerator.buildDraft(
            20_001L, MealType.LUNCH, target1670, 0L, setOf(DietRestriction.GLUTEN),
        )
        assertEquals(listOf("瘦牛肉 120g", "混合蔬菜 200g"), draft.meal.items)
        // round(1670 × 0.335 × 2/3) = round(372.966…) = 373
        assertEquals(373, draft.meal.kcal)
        assertEquals(126 * 0.335 * 2.0 / 3.0, draft.meal.proteinG, 1e-9)
        assertEquals(1, draft.filteredCount)
    }

    @Test
    fun dietaryAvoid_seafood_dropsSeafoodItem() {
        // LUNCH，epochDay=20000 → 下标 1 = [藜麦, 清蒸鱼(SEAFOOD), 凉拌菠菜]。
        val draft = DietPlanGenerator.buildDraft(
            20_000L, MealType.LUNCH, target1670, 0L, setOf(DietRestriction.SEAFOOD),
        )
        assertEquals(listOf("藜麦 120g", "凉拌菠菜 200g"), draft.meal.items)
        assertEquals(1, draft.filteredCount)

        // DINNER，epochDay=20001 → 下标 0 = [三文鱼(SEAFOOD), 杂粮饭, 菠菜沙拉]。
        val dinner = DietPlanGenerator.buildDraft(
            20_001L, MealType.DINNER, target1670, 0L, setOf(DietRestriction.SEAFOOD),
        )
        assertEquals(listOf("杂粮饭 100g", "菠菜沙拉 200g"), dinner.meal.items)
        // round(1670 × 0.29 × 2/3) = round(322.866…) = 323
        assertEquals(323, dinner.meal.kcal)
    }

    @Test
    fun dietaryAvoid_peanut_dropsNutItem() {
        // SNACK，epochDay=20002 → 下标 0 = [酸奶(DAIRY), 杏仁(PEANUT)]，原 2 条。
        // 过滤 {PEANUT} → 剩 1 条 → factor = 1/2。
        val draft = DietPlanGenerator.buildDraft(
            20_002L, MealType.SNACK, target1670, 0L, setOf(DietRestriction.PEANUT),
        )
        assertEquals(listOf("无糖希腊酸奶 150g"), draft.meal.items)
        // round(1670 × 0.125 × 1/2) = round(104.375) = 104
        assertEquals(104, draft.meal.kcal)
        assertEquals(126 * 0.125 * 0.5, draft.meal.proteinG, 1e-9)
        assertEquals(1, draft.filteredCount)
    }

    @Test
    fun dietaryAvoid_spicyAndAlcohol_areNoOpWhenLibraryHasNoSuchItems() {
        // 诚实登记的边界：内置库无「辛辣 / 酒精」条目 → 这两个忌口**不过滤任何条目、不缩放**（不误伤）。
        for (restriction in listOf(DietRestriction.SPICY, DietRestriction.ALCOHOL)) {
            val draft = DietPlanGenerator.buildDraft(
                20_000L, MealType.BREAKFAST, target1670, 0L, setOf(restriction),
            )
            assertEquals(originalItems(20_000L, MealType.BREAKFAST), draft.meal.items)
            assertEquals(418, draft.meal.kcal) // 与不过滤完全一致
            assertEquals(0, draft.filteredCount)
        }
    }

    @Test
    fun dietaryAvoid_removesOnlyMatchingItems_keepsTheRest() {
        // 每种「有对应条目」的忌口，都只删它自己的条目、保留其余（其它条目原样在）。
        val cases = listOf(
            Triple(20_000L, MealType.BREAKFAST, DietRestriction.DAIRY),
            Triple(20_001L, MealType.LUNCH, DietRestriction.GLUTEN),
            Triple(20_000L, MealType.LUNCH, DietRestriction.SEAFOOD),
            Triple(20_002L, MealType.SNACK, DietRestriction.PEANUT),
            Triple(20_001L, MealType.DINNER, DietRestriction.SEAFOOD),
        )
        for ((day, type, restriction) in cases) {
            val before = originalItems(day, type)
            val after = DietPlanGenerator.buildDraft(day, type, target1670, 0L, setOf(restriction)).meal.items
            assertTrue("$type should lose at least one item on $restriction", after.size < before.size)
            // 保留项 ⊆ 原项，且顺序保持（子序列）。
            assertTrue("$type kept items must be a subsequence of the original", isSubsequence(after, before))
        }
    }

    @Test
    fun dietaryAvoid_whenPrimaryTemplateEmptied_fallsBackToNextRotationTemplate() {
        // SNACK，epochDay=20002 → 下标 0 = [酸奶(DAIRY), 杏仁(PEANUT)]。
        // 过滤 {DAIRY, PEANUT} → 本份全空 → 兜底到下标 1 = [苹果, 花生酱(PEANUT)] → 只剩苹果。
        val draft = DietPlanGenerator.buildDraft(
            20_002L, MealType.SNACK, target1670, 0L,
            setOf(DietRestriction.DAIRY, DietRestriction.PEANUT),
        )
        assertEquals(listOf("苹果 1 个"), draft.meal.items)
        // factor = 保留 1 / 兜底模板原 2 = 1/2 → round(1670 × 0.125 × 1/2) = 104
        assertEquals(104, draft.meal.kcal)
        assertEquals(126 * 0.125 * 0.5, draft.meal.proteinG, 1e-9)
        // filteredCount 口径 = 本应使用那份额外被丢掉的条目数（酸奶 + 杏仁 = 2）。
        assertEquals(2, draft.filteredCount)
    }

    @Test
    fun dietaryAvoid_allTemplatesFilteredOut_keepsEmptyMealWithoutCrash() {
        // 注入一份「全部条目都被忌口命中」的模板库，覆盖「整餐过滤空」路径。
        val allTagged: List<List<BuiltInMealTemplates.TaggedItem>> = listOf(
            listOf(BuiltInMealTemplates.TaggedItem("甲", setOf(DietRestriction.DAIRY))),
            listOf(BuiltInMealTemplates.TaggedItem("乙", setOf(DietRestriction.DAIRY))),
        )
        val draft = DietPlanGenerator.buildDraft(
            0L, MealType.BREAKFAST, target1670, 0L, setOf(DietRestriction.DAIRY), allTagged,
        )
        assertTrue("整餐被过滤空时应保留空条目（不删餐次）", draft.meal.items.isEmpty())
        assertEquals(0, draft.meal.kcal)
        assertEquals(0.0, draft.meal.proteinG, 1e-9)
        assertEquals(1, draft.filteredCount)
        // 不产生 NaN / 负值。
        assertFalse(draft.meal.kcal < 0)
        assertFalse(draft.meal.proteinG.isNaN())
        assertFalse(draft.meal.proteinG < 0.0)
    }

    @Test
    fun dietaryAvoid_filteredResult_isStillIdempotent() {
        val avoid = setOf(DietRestriction.SEAFOOD, DietRestriction.PEANUT)
        for (type in MealType.entries) {
            val a = DietPlanGenerator.buildDraft(31_234L, type, target1670, 5L, avoid)
            val b = DietPlanGenerator.buildDraft(31_234L, type, target1670, 5L, avoid)
            assertEquals(a, b)
        }
    }

    @Test
    fun dietaryAvoid_neverProducesNaNAcrossEveryRestrictionSubset() {
        // 覆盖各种忌口组合，确保不产出 NaN / 负值 / 越界。
        val all = DietRestriction.entries
        val subsets = listOf(
            emptySet(),
            setOf(DietRestriction.PEANUT),
            setOf(DietRestriction.SEAFOOD),
            setOf(DietRestriction.DAIRY),
            setOf(DietRestriction.GLUTEN),
            setOf(DietRestriction.SPICY),
            setOf(DietRestriction.ALCOHOL),
            all.toSet(),
            setOf(DietRestriction.DAIRY, DietRestriction.PEANUT),
            setOf(DietRestriction.SEAFOOD, DietRestriction.GLUTEN),
        )
        for (avoid in subsets) {
            for (day in listOf(0L, 1L, 2L, 3L, 20_000L, 20_001L, 20_002L, 20_003L)) {
                for (type in MealType.entries) {
                    val meal = DietPlanGenerator.buildDraft(day, type, target1670, 0L, avoid).meal
                    assertFalse("protein NaN in $avoid/$type", meal.proteinG.isNaN())
                    assertTrue("negative kcal in $avoid/$type", meal.kcal >= 0)
                    assertTrue("negative protein in $avoid/$type", meal.proteinG >= 0.0)
                }
            }
        }
    }

    /** `sub` 是否为 `full` 的子序列（保序）。 */
    private fun isSubsequence(sub: List<String>, full: List<String>): Boolean {
        var index = 0
        for (value in full) {
            if (index < sub.size && sub[index] == value) index++
        }
        return index == sub.size
    }
}
