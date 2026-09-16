package com.ironhabit.app.domain.ai

import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.ProfileLimits
import com.ironhabit.app.domain.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProfileLoadPolicy] 纯 JVM 单测（零 Android / 零 IO / 零随机）。
 *
 * 这个类是 P1 的核心："档案字段 → 规则引擎真正会用的数字"全部收在这里，
 * 所以**映射表的每一行都必须有断言**：改数值 = 改行为，必须同时改这里。
 *
 * 覆盖：
 * 1. 5 个目标的基准（组次区间 / 每周有氧数 / 加重步长）；
 * 2. 体脂高低（含"性别未知就不判断"的诚实边界）；
 * 3. 体重 vs 目标体重（含"没记过体重就不判断"）；
 * 4. 年龄（量级下调 + 恢复建议）；
 * 5. 脏数据钳制与确定性。
 */
class ProfileLoadPolicyTest {

    // ---------------- 1. 目标基准 ----------------

    @Test
    fun defaultProfile_usesMaintainBaseline() {
        val policy = ProfileLoadPolicy.of(UserProfile())

        assertEquals("空档案 → 默认每周 3 天（与旧版钉死 3 天一致）", 3, policy.trainingDaysPerWeek)
        assertEquals("空档案 → 每天 4 个动作", 4, policy.itemsPerDay)
        assertEquals("保持目标：3 组", 3..3, policy.setsRange)
        assertEquals("保持目标：10–12 次", 10..12, policy.repsRange)
        assertEquals("保持目标：每周 1 个有氧", 1, policy.cardioPerWeek)
        assertEquals(2.5f, policy.weightStepKg, 0.0001f)
        assertEquals(listOf(PolicyReason.GOAL_VOLUME), policy.reasons)
    }

    @Test
    fun goalBaselines_matchDocumentedTable() {
        val bulk = ProfileLoadPolicy.of(UserProfile(goal = Goal.BULK))
        assertEquals("增肌：8–12 次 / 3–4 组", 8..12, bulk.repsRange)
        assertEquals(3..4, bulk.setsRange)
        assertEquals(1, bulk.cardioPerWeek)
        assertEquals(2.5f, bulk.weightStepKg, 0.0001f)

        val cut = ProfileLoadPolicy.of(UserProfile(goal = Goal.CUT))
        assertEquals("减脂：12–15 次 / 3 组", 12..15, cut.repsRange)
        assertEquals(3..3, cut.setsRange)
        assertEquals("减脂：有氧比例最高（每周 3 个）", 3, cut.cardioPerWeek)
        assertEquals("减脂：加重步长更小（1.25kg）", 1.25f, cut.weightStepKg, 0.0001f)

        val recomp = ProfileLoadPolicy.of(UserProfile(goal = Goal.RECOMP))
        assertEquals(10..12, recomp.repsRange)
        assertEquals(2, recomp.cardioPerWeek)

        val shape = ProfileLoadPolicy.of(UserProfile(goal = Goal.SHAPE))
        assertEquals(12..15, shape.repsRange)
        assertEquals(2, shape.cardioPerWeek)
        assertEquals(1.25f, shape.weightStepKg, 0.0001f)
    }

    // ---------------- 2. 体脂 ----------------

    @Test
    fun highBodyFat_male_increasesCardio() {
        val policy = ProfileLoadPolicy.of(
            UserProfile(gender = Gender.MALE, bodyFatPct = 26f, goal = Goal.MAINTAIN),
        )
        assertEquals("体脂 26% ≥ 男 25% → 有氧 +1", 2, policy.cardioPerWeek)
        assertTrue(PolicyReason.BODY_FAT_HIGH in policy.reasons)
    }

    @Test
    fun lowBodyFat_withBulkGoal_decreasesCardioButNeverBelowOne() {
        val policy = ProfileLoadPolicy.of(
            UserProfile(gender = Gender.MALE, bodyFatPct = 10f, goal = Goal.BULK),
        )
        assertEquals("增肌基准 1 个有氧，再降也有下限 1", 1, policy.cardioPerWeek)
        assertTrue(PolicyReason.BODY_FAT_LOW in policy.reasons)
    }

    @Test
    fun lowBodyFat_withoutBulkGoal_doesNotTouchCardio() {
        val policy = ProfileLoadPolicy.of(
            UserProfile(gender = Gender.MALE, bodyFatPct = 10f, goal = Goal.MAINTAIN),
        )
        assertEquals(1, policy.cardioPerWeek)
        assertFalse(
            "低体脂只在「目标是增肌类」时才降有氧",
            PolicyReason.BODY_FAT_LOW in policy.reasons,
        )
    }

    @Test
    fun bodyFatWithoutGender_isNotJudged_atAll() {
        // 男女阈值差 7 个百分点 → 性别未知时不猜（诚实边界，写在 KDoc 里）。
        val male = ProfileLoadPolicy.of(UserProfile(gender = Gender.MALE, bodyFatPct = 30f))
        val unknown = ProfileLoadPolicy.of(UserProfile(gender = null, bodyFatPct = 30f))

        assertTrue(PolicyReason.BODY_FAT_HIGH in male.reasons)
        assertFalse(
            "性别未知 → 不做体脂判断",
            PolicyReason.BODY_FAT_HIGH in unknown.reasons,
        )
        assertEquals("性别未知 → 有氧保持目标基准值", 1, unknown.cardioPerWeek)
    }

    @Test
    fun femaleThresholds_areDifferentFromMale() {
        val female = ProfileLoadPolicy.of(
            UserProfile(gender = Gender.FEMALE, bodyFatPct = 28f),
        )
        assertFalse(
            "女 28% 未到 32% 阈值 → 不触发（男 25% 就会触发）",
            PolicyReason.BODY_FAT_HIGH in female.reasons,
        )
        assertTrue(
            PolicyReason.BODY_FAT_HIGH in ProfileLoadPolicy.of(
                UserProfile(gender = Gender.FEMALE, bodyFatPct = 33f),
            ).reasons,
        )
    }

    // ---------------- 3. 体重 vs 目标体重 ----------------

    @Test
    fun weightAboveTarget_increasesCardio() {
        val policy = ProfileLoadPolicy.of(
            profile = UserProfile(goal = Goal.MAINTAIN, goalWeightKg = 70f),
            bodyWeightKg = 80f,
        )
        assertEquals("要减 10kg → 有氧 +1", 2, policy.cardioPerWeek)
        assertTrue(PolicyReason.WEIGHT_TO_CUT in policy.reasons)
    }

    @Test
    fun weightBelowTarget_increasesSetVolume() {
        val policy = ProfileLoadPolicy.of(
            profile = UserProfile(goal = Goal.MAINTAIN, goalWeightKg = 80f),
            bodyWeightKg = 70f,
        )
        assertEquals("还要增 10kg（≥3kg）→ 组数上限 +1", 3..4, policy.setsRange)
        assertTrue(PolicyReason.WEIGHT_TO_GAIN in policy.reasons)
    }

    @Test
    fun weightWithinOneKg_isAlreadyOnTarget_noRule() {
        val policy = ProfileLoadPolicy.of(
            profile = UserProfile(goal = Goal.MAINTAIN, goalWeightKg = 70f),
            bodyWeightKg = 70.5f,
        )
        assertEquals(1, policy.cardioPerWeek)
        assertFalse(PolicyReason.WEIGHT_TO_CUT in policy.reasons)
        assertFalse(PolicyReason.WEIGHT_TO_GAIN in policy.reasons)
    }

    @Test
    fun missingWeightOrTarget_skipsWeightRules() {
        val noBodyWeight = ProfileLoadPolicy.of(UserProfile(goalWeightKg = 70f), bodyWeightKg = null)
        val noTarget = ProfileLoadPolicy.of(UserProfile(goalWeightKg = null), bodyWeightKg = 90f)
        val dirtyZero = ProfileLoadPolicy.of(UserProfile(goalWeightKg = 70f), bodyWeightKg = 0f)

        for (policy in listOf(noBodyWeight, noTarget, dirtyZero)) {
            assertFalse(PolicyReason.WEIGHT_TO_CUT in policy.reasons)
            assertFalse(PolicyReason.WEIGHT_TO_GAIN in policy.reasons)
            assertEquals("数据缺失 → 一律不调（不猜、不用 0 冒充）", 1, policy.cardioPerWeek)
        }
    }

    // ---------------- 4. 年龄 ----------------

    @Test
    fun age50Plus_reducesItemsPerDay() {
        val policy = ProfileLoadPolicy.of(UserProfile(age = 55))
        assertEquals("≥50 岁 → 每天 4 → 3 个动作", 3, policy.itemsPerDay)
        assertTrue(PolicyReason.AGE_VOLUME in policy.reasons)
        assertTrue("≥40 岁 → 附一条恢复建议", PolicyReason.RECOVERY_AGE in policy.reasons)
    }

    @Test
    fun age40to49_onlyAddsRecoveryNote() {
        val policy = ProfileLoadPolicy.of(UserProfile(age = 45))
        assertEquals("45 岁仍保持每天 4 个动作", 4, policy.itemsPerDay)
        assertTrue(PolicyReason.RECOVERY_AGE in policy.reasons)
        assertFalse(PolicyReason.AGE_VOLUME in policy.reasons)
    }

    @Test
    fun youngProfile_hasNoAgeReason() {
        val policy = ProfileLoadPolicy.of(UserProfile(age = 30))
        assertFalse(PolicyReason.RECOVERY_AGE in policy.reasons)
        assertFalse(PolicyReason.AGE_VOLUME in policy.reasons)
    }

    // ---------------- 5. 脏数据与确定性 ----------------

    @Test
    fun trainingDays_dirtyValues_areClamped() {
        assertEquals(6, ProfileLoadPolicy.of(UserProfile(trainingDaysPerWeek = 99)).trainingDaysPerWeek)
        assertEquals(3, ProfileLoadPolicy.of(UserProfile(trainingDaysPerWeek = 0)).trainingDaysPerWeek)
        assertEquals(4, ProfileLoadPolicy.of(UserProfile(trainingDaysPerWeek = 4)).trainingDaysPerWeek)
        assertEquals(
            "与 ProfileLimits 的合法域一致",
            ProfileLimits.TRAINING_DAYS_PER_WEEK.first,
            ProfileLoadPolicy.of(UserProfile(trainingDaysPerWeek = -5)).trainingDaysPerWeek,
        )
    }

    @Test
    fun reasonsOrder_isStable_andDeterministic() {
        val profile = UserProfile(
            gender = Gender.MALE,
            age = 55,
            bodyFatPct = 30f,
            goal = Goal.MAINTAIN,
            goalWeightKg = 60f,
        )
        val first = ProfileLoadPolicy.of(profile, bodyWeightKg = 80f)
        val second = ProfileLoadPolicy.of(profile, bodyWeightKg = 80f)

        assertEquals("同输入必同输出（零随机）", first, second)
        assertEquals(
            "规则顺序固定：目标 → 体脂 → 体重 → 年龄",
            listOf(
                PolicyReason.GOAL_VOLUME,
                PolicyReason.BODY_FAT_HIGH,
                PolicyReason.WEIGHT_TO_CUT,
                PolicyReason.AGE_VOLUME,
                PolicyReason.RECOVERY_AGE,
            ),
            first.reasons,
        )
        // 保持 1 有氧 + 体脂 +1 + 体重 +1 = 3；年龄再压每天动作数到 3。
        assertEquals(3, first.cardioPerWeek)
        assertEquals(3, first.itemsPerDay)
    }
}
