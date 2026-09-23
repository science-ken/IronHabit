package com.ironhabit.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UserProfile] / [ProfileLimits] / 解析与枚举编解码的**纯 JVM 单测**。
 *
 * 覆盖四块（对应 M2.5 测试要求）：
 * 1. **coerceIn 边界**（越界上下限、`Int.MIN/MAX`、临界值、非数字/空值解析）；
 * 2. **派生属性**（`isBodyProfileComplete` / `isTrainingProfileComplete` / `hasConstraints`）；
 * 3. **枚举集合读写往返**（`encodeEnumSet` ⇄ `decodeEnumSet`，即 DataStore 存取所用的同一对函数）；
 * 4. **档案完整度六项**（`missingProfileFields` 的分母、顺序与「无器械算不算填」口径）。
 */
class UserProfileTest {

    // ---------------- 派生属性 ----------------

    @Test
    fun isBodyProfileComplete_trueOnlyWhenGenderAgeHeightAllSet() {
        assertFalse("空档案不算填全", UserProfile().isBodyProfileComplete)
        assertFalse(UserProfile(gender = Gender.MALE).isBodyProfileComplete)
        assertFalse(UserProfile(gender = Gender.MALE, age = 30).isBodyProfileComplete)
        assertFalse(UserProfile(gender = Gender.MALE, heightCm = 175).isBodyProfileComplete)
        assertTrue(
            "性别 + 年龄 + 身高齐备才算体征填全（体脂/目标体重不参与判定）",
            UserProfile(gender = Gender.FEMALE, age = 30, heightCm = 175).isBodyProfileComplete,
        )
    }

    @Test
    fun isTrainingProfileComplete_trueOnlyWhenEquipmentNonEmpty() {
        assertFalse("未勾选任何器械 = 训练档案不可用", UserProfile().isTrainingProfileComplete)
        assertTrue(
            "勾了「无器械」也算已表态可用",
            UserProfile(equipment = setOf(Equipment.NONE)).isTrainingProfileComplete,
        )
        assertTrue(UserProfile(equipment = setOf(Equipment.DUMBBELL, Equipment.BARBELL)).isTrainingProfileComplete)
    }

    @Test
    fun hasConstraints_trueWhenInjuryOrDietNonEmpty() {
        assertFalse(UserProfile().hasConstraints)
        assertTrue(UserProfile(injuryAreas = setOf(InjuryArea.KNEE)).hasConstraints)
        assertTrue(UserProfile(dietaryAvoid = setOf(DietRestriction.PEANUT)).hasConstraints)
    }

    // ---------------- 档案完整度六项（「我的」页顶部那个环的分母） ----------------

    @Test
    fun profileField_denominatorIsSixAndOrderIsDisplayOrder() {
        // 「我的」页的环显示 `n/6`，分母由这里数出来而不是界面写死 —— 加一项就会让所有老用户的
        // 环整体跳一格，所以项数与展示顺序都要钉住。
        assertEquals(6, ProfileField.entries.size)
        assertEquals(
            listOf(
                ProfileField.GENDER,
                ProfileField.AGE,
                ProfileField.HEIGHT_CM,
                ProfileField.BODY_FAT_PCT,
                ProfileField.GOAL_WEIGHT_KG,
                ProfileField.EQUIPMENT,
            ),
            ProfileField.entries.toList(),
        )
    }

    @Test
    fun missingProfileFields_emptyProfileMissingAllSix() {
        assertEquals(
            "什么都没填 = 六项全缺（环是空圈，缺口行该把六项都念出来）",
            ProfileField.entries.toList(),
            UserProfile().missingProfileFields,
        )
    }

    @Test
    fun missingProfileFields_fullProfileMissingNothing() {
        val full = UserProfile(
            gender = Gender.MALE,
            age = 28,
            heightCm = 178,
            bodyFatPct = 15f,
            goalWeightKg = 71f,
            equipment = setOf(Equipment.DUMBBELL),
        )
        assertTrue("填满即无缺口：界面不得再提示「还差 N 项」", full.missingProfileFields.isEmpty())
    }

    @Test
    fun missingProfileFields_vitalSignsFilledLeavesFatGoalWeightEquipment() {
        // 这一条正是这台测试机的真实状态：体征三件套填了、器械勾了，缺体脂与目标体重。
        assertEquals(
            listOf(ProfileField.BODY_FAT_PCT, ProfileField.GOAL_WEIGHT_KG),
            UserProfile(
                gender = Gender.MALE,
                age = 18,
                heightCm = 180,
                equipment = setOf(Equipment.DUMBBELL),
            ).missingProfileFields,
        )
    }

    @Test
    fun missingProfileFields_equipmentNoneCountsAsFilled() {
        // 「无器械」是一次表态，不是没填 —— 与 isTrainingProfileComplete 同一口径，
        // 否则环说还差器械、排课却说档案可用，两处就打起来了。
        assertEquals(
            "勾了「无器械」不该再出现在缺口里",
            emptyList<ProfileField>(),
            UserProfile(
                gender = Gender.FEMALE,
                age = 30,
                heightCm = 165,
                bodyFatPct = 22f,
                goalWeightKg = 60f,
                equipment = setOf(Equipment.NONE),
            ).missingProfileFields,
        )
    }

    @Test
    fun missingProfileFields_zeroValuedNumbersCountAsFilled() {
        // 年龄 / 身高 / 体脂 / 目标体重都是非空即算填：`0` 不是"没填"的哨兵值
        // （写入侧有 coerceIn 钳制，能存进 0 就说明用户真写了 0）。
        assertEquals(
            listOf(ProfileField.GENDER, ProfileField.EQUIPMENT),
            UserProfile(age = 0, heightCm = 0, bodyFatPct = 0f, goalWeightKg = 0f).missingProfileFields,
        )
    }

    // ---------------- 写入钳制：coerceIn 边界 ----------------

    @Test
    fun coerceAge_clampsTo14To100() {
        assertEquals(14, ProfileLimits.coerceAge(13))
        assertEquals(14, ProfileLimits.coerceAge(14))
        assertEquals(50, ProfileLimits.coerceAge(50))
        assertEquals(100, ProfileLimits.coerceAge(100))
        assertEquals(100, ProfileLimits.coerceAge(101))
        assertEquals(14, ProfileLimits.coerceAge(Int.MIN_VALUE))
        assertEquals(100, ProfileLimits.coerceAge(Int.MAX_VALUE))
    }

    @Test
    fun coerceHeightCm_clampsTo140To220() {
        assertEquals(140, ProfileLimits.coerceHeightCm(139))
        assertEquals(140, ProfileLimits.coerceHeightCm(140))
        assertEquals(175, ProfileLimits.coerceHeightCm(175))
        assertEquals(220, ProfileLimits.coerceHeightCm(220))
        assertEquals(220, ProfileLimits.coerceHeightCm(221))
        assertEquals(140, ProfileLimits.coerceHeightCm(Int.MIN_VALUE))
        assertEquals(220, ProfileLimits.coerceHeightCm(Int.MAX_VALUE))
    }

    @Test
    fun coerceBodyFatPct_clampsTo3To60() {
        val delta = 0.0001f
        assertEquals(3f, ProfileLimits.coerceBodyFatPct(2.9f), delta)
        assertEquals(3f, ProfileLimits.coerceBodyFatPct(3f), delta)
        assertEquals(22.5f, ProfileLimits.coerceBodyFatPct(22.5f), delta)
        assertEquals(60f, ProfileLimits.coerceBodyFatPct(60f), delta)
        assertEquals(60f, ProfileLimits.coerceBodyFatPct(60.1f), delta)
        assertEquals(3f, ProfileLimits.coerceBodyFatPct(Float.NEGATIVE_INFINITY), delta)
        assertEquals(60f, ProfileLimits.coerceBodyFatPct(Float.POSITIVE_INFINITY), delta)
    }

    @Test
    fun coerceGoalWeightKg_clampsTo30To300() {
        val delta = 0.0001f
        assertEquals(30f, ProfileLimits.coerceGoalWeightKg(29.9f), delta)
        assertEquals(30f, ProfileLimits.coerceGoalWeightKg(30f), delta)
        assertEquals(72f, ProfileLimits.coerceGoalWeightKg(72f), delta)
        assertEquals(300f, ProfileLimits.coerceGoalWeightKg(300f), delta)
        assertEquals(300f, ProfileLimits.coerceGoalWeightKg(300.1f), delta)
    }

    @Test
    fun coerceInjuryNote_truncatesTo200CharsAndKeepsShortAsIs() {
        assertEquals(ProfileLimits.INJURY_NOTE_MAX_LENGTH, 200)
        val short = "右膝旧伤"
        assertEquals(short, ProfileLimits.coerceInjuryNote(short))

        val long = "x".repeat(250)
        val result = ProfileLimits.coerceInjuryNote(long)
        assertEquals(200, result.length)
        assertEquals(long.take(200), result)
    }

    @Test
    fun rangePredicates_matchBoundaries() {
        assertFalse(ProfileLimits.isAgeInRange(13))
        assertTrue(ProfileLimits.isAgeInRange(14))
        assertTrue(ProfileLimits.isAgeInRange(100))
        assertFalse(ProfileLimits.isAgeInRange(101))

        assertFalse(ProfileLimits.isHeightInRange(139))
        assertTrue(ProfileLimits.isHeightInRange(140))
        assertTrue(ProfileLimits.isHeightInRange(220))
        assertFalse(ProfileLimits.isHeightInRange(221))

        assertFalse(ProfileLimits.isBodyFatInRange(2.9f))
        assertTrue(ProfileLimits.isBodyFatInRange(3f))
        assertTrue(ProfileLimits.isBodyFatInRange(60f))
        assertFalse(ProfileLimits.isBodyFatInRange(60.1f))

        assertFalse(ProfileLimits.isGoalWeightInRange(29.9f))
        assertTrue(ProfileLimits.isGoalWeightInRange(30f))
        assertTrue(ProfileLimits.isGoalWeightInRange(300f))
        assertFalse(ProfileLimits.isGoalWeightInRange(300.1f))
    }

    // ---------------- 文本解析：非数字 / 空值 ----------------

    @Test
    fun parseOptionalInt_blankOrNonNumericReturnsNull_elseParses() {
        assertNull("空字符串", parseOptionalInt(""))
        assertNull("纯空白", parseOptionalInt("   "))
        assertNull("非数字", parseOptionalInt("abc"))
        assertNull("小数（非法整数）", parseOptionalInt("12.5"))
        assertNull("带单位", parseOptionalInt("30岁"))

        assertEquals(30, parseOptionalInt("30"))
        assertEquals(30, parseOptionalInt(" 30 "))
        assertEquals(-5, parseOptionalInt("-5"))
    }

    @Test
    fun parseOptionalFloat_blankOrNonNumericReturnsNull_elseParses() {
        assertNull(parseOptionalFloat(""))
        assertNull(parseOptionalFloat("   "))
        assertNull(parseOptionalFloat("abc"))
        assertNull(parseOptionalFloat("22%"))

        assertEquals(22.5f, parseOptionalFloat("22.5")!!, 0.0001f)
        assertEquals(22f, parseOptionalFloat(" 22 ")!!, 0.0001f)
    }

    // ---------------- 枚举集合读写往返（DataStore 存取契约） ----------------

    @Test
    fun equipmentSet_roundTripsThroughNames() {
        val input = setOf(Equipment.DUMBBELL, Equipment.MACHINE)
        val stored = encodeEnumSet(input)
        assertEquals("存 name 而非 ordinal", setOf("DUMBBELL", "MACHINE"), stored)
        assertEquals("读到同一集合", input, decodeEnumSet<Equipment>(stored))
    }

    @Test
    fun injuryAndDietSets_roundTripThroughNames() {
        val injuries = setOf(InjuryArea.KNEE, InjuryArea.LOWER_BACK)
        assertEquals(injuries, decodeEnumSet<InjuryArea>(encodeEnumSet(injuries)))

        val avoids = setOf(DietRestriction.PEANUT, DietRestriction.DAIRY, DietRestriction.ALCOHOL)
        assertEquals(avoids, decodeEnumSet<DietRestriction>(encodeEnumSet(avoids)))
    }

    @Test
    fun emptySet_roundTripsToEmptySet() {
        assertEquals(emptySet<String>(), encodeEnumSet(emptySet<Equipment>()))
        assertEquals(emptySet<Equipment>(), decodeEnumSet<Equipment>(emptySet()))
        assertEquals(emptySet<InjuryArea>(), decodeEnumSet<InjuryArea>(encodeEnumSet(emptySet<InjuryArea>())))
    }

    @Test
    fun everyEnumEntry_roundTripsThroughName() {
        for (entry in Equipment.entries) {
            assertEquals(entry, decodeEnum<Equipment>(encodeEnumSet(setOf(entry)).single()))
        }
        for (entry in InjuryArea.entries) {
            assertEquals(entry, decodeEnum<InjuryArea>(encodeEnumSet(setOf(entry)).single()))
        }
        for (entry in DietRestriction.entries) {
            assertEquals(entry, decodeEnum<DietRestriction>(encodeEnumSet(setOf(entry)).single()))
        }
        for (entry in Gender.entries) {
            assertEquals(entry, decodeEnum<Gender>(entry.name))
        }
        for (entry in Goal.entries) {
            assertEquals(entry, decodeEnum<Goal>(entry.name))
        }
    }

    @Test
    fun unknownEnumNames_areDroppedNotCrash() {
        val decoded = decodeEnumSet<InjuryArea>(setOf("KNEE", "NOT_A_REAL_AREA", ""))
        assertEquals("未知 name 被忽略，只保留合法项", setOf(InjuryArea.KNEE), decoded)

        assertNull("单值未知 name → null（由调用方回落默认）", decodeEnum<Gender>("OTHER"))
        assertNull("null 输入 → null", decodeEnum<Goal>(null))
        assertEquals(Gender.MALE, decodeEnum<Gender>("MALE"))
    }

    // ---------------- 默认值 ----------------

    @Test
    fun defaultProfile_allUnset_goalMaintain() {
        val profile = UserProfile()
        assertNull(profile.gender)
        assertNull(profile.age)
        assertNull(profile.heightCm)
        assertNull(profile.bodyFatPct)
        assertNull(profile.goalWeightKg)
        assertNull(profile.injuryNote)
        assertEquals(Goal.MAINTAIN, profile.goal)
        assertTrue(profile.equipment.isEmpty())
        assertTrue(profile.injuryAreas.isEmpty())
        assertTrue(profile.dietaryAvoid.isEmpty())
    }

    @Test
    fun goal_kcalFactors_matchDesign() {
        assertEquals(0.85, Goal.CUT.kcalFactor, 0.0001)
        assertEquals(1.10, Goal.BULK.kcalFactor, 0.0001)
        assertEquals(1.00, Goal.RECOMP.kcalFactor, 0.0001)
        assertEquals(0.95, Goal.SHAPE.kcalFactor, 0.0001)
        assertEquals(1.00, Goal.MAINTAIN.kcalFactor, 0.0001)
    }
}
