package com.ironhabit.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InputLimits] 边界单测：每个区间的 `min - 1 / min / max / max + 1`，
 * 以及钳制助手与按指标类型的身体数据区间。
 *
 * 纯 JVM 测试（无 Android / Room 依赖）—— 这正是把边界放进域层常量对象的目的。
 */
class InputLimitsTest {

    // ================= 组数 =================

    @Test
    fun setsBoundariesMatchCheckInBitmaskWidth() {
        assertEquals("上限必须等于打卡位图位宽", MAX_SETS, InputLimits.MAX_SETS)
        assertEquals(31, InputLimits.MAX_SETS)

        assertFalse("0 组", InputLimits.isValidSets(InputLimits.MIN_SETS - 1))
        assertTrue("1 组（下界）", InputLimits.isValidSets(InputLimits.MIN_SETS))
        assertTrue("31 组（上界）", InputLimits.isValidSets(InputLimits.MAX_SETS))
        assertFalse("32 组（越上界）", InputLimits.isValidSets(InputLimits.MAX_SETS + 1))
        assertFalse("负数", InputLimits.isValidSets(-3))

        assertEquals(InputLimits.MIN_SETS, InputLimits.coerceSets(0))
        assertEquals(InputLimits.MIN_SETS, InputLimits.coerceSets(-99))
        assertEquals(InputLimits.MAX_SETS, InputLimits.coerceSets(99))
        assertEquals(7, InputLimits.coerceSets(7))
    }

    // ================= 次数 =================

    @Test
    fun repsBoundaries() {
        assertFalse(InputLimits.isValidReps(0))
        assertTrue(InputLimits.isValidReps(1))
        assertTrue(InputLimits.isValidReps(100))
        assertFalse(InputLimits.isValidReps(101))
        assertFalse(InputLimits.isValidReps(-1))

        assertEquals(1, InputLimits.coerceReps(0))
        assertEquals(100, InputLimits.coerceReps(5_000))
        assertEquals(12, InputLimits.coerceReps(12))
    }

    // ================= 重量 =================

    @Test
    fun weightBoundariesAndNonFiniteValues() {
        assertTrue("自重 0 kg 合法", InputLimits.isValidWeightKg(0f))
        assertFalse("负数（越下界）", InputLimits.isValidWeightKg(-0.1f))
        assertTrue(InputLimits.isValidWeightKg(500f))
        assertFalse(InputLimits.isValidWeightKg(500.1f))
        assertFalse("NaN 必须非法", InputLimits.isValidWeightKg(Float.NaN))
        assertFalse("+Infinity 必须非法", InputLimits.isValidWeightKg(Float.POSITIVE_INFINITY))
        assertFalse("-Infinity 必须非法", InputLimits.isValidWeightKg(Float.NEGATIVE_INFINITY))

        assertEquals(0f, InputLimits.coerceWeightKg(-10f), 0f)
        assertEquals(500f, InputLimits.coerceWeightKg(9_999f), 0f)
        assertEquals(60f, InputLimits.coerceWeightKg(60f), 0f)
        // NaN 不得原样穿过（Float.coerceIn 会放过它）。
        assertEquals(0f, InputLimits.coerceWeightKg(Float.NaN), 0f)
    }

    // ================= 时长 =================

    @Test
    fun durationMinuteBoundaries() {
        assertFalse(InputLimits.isValidDurationMin(0))
        assertTrue(InputLimits.isValidDurationMin(1))
        assertTrue(InputLimits.isValidDurationMin(600))
        assertFalse(InputLimits.isValidDurationMin(601))
        assertFalse(InputLimits.isValidDurationMin(-5))

        assertEquals(1, InputLimits.coerceDurationMin(0))
        assertEquals(600, InputLimits.coerceDurationMin(100_000))
        assertEquals(45, InputLimits.coerceDurationMin(45))
    }

    @Test
    fun durationSecondBoundariesKeepBuiltInCardioSavable() {
        assertFalse(InputLimits.isValidDurationSec(0))
        assertTrue(InputLimits.isValidDurationSec(1))
        assertTrue(InputLimits.isValidDurationSec(7_200))
        assertFalse(InputLimits.isValidDurationSec(7_201))

        // 回归护栏：内置有氧动作的最大默认时长（2400 秒 = 40 分钟）必须自己就能通过校验，
        // 否则「编辑内置动作 → 直接保存」会被自身校验拦下。
        assertTrue("内置有氧最大默认值 2400 秒", InputLimits.isValidDurationSec(2_400))
        assertTrue("内置自重动作默认值 45 秒", InputLimits.isValidDurationSec(45))

        assertEquals(1, InputLimits.coerceDurationSec(0))
        assertEquals(7_200, InputLimits.coerceDurationSec(99_999))
    }

    // ================= 身体数据 =================

    @Test
    fun bodyMetricRangesByType() {
        val weight = InputLimits.rangeFor(BodyMetricType.WEIGHT)
        assertEquals(20f, weight.start, 0f)
        assertEquals(400f, weight.endInclusive, 0f)

        val bodyFat = InputLimits.rangeFor(BodyMetricType.BODY_FAT)
        assertEquals(3f, bodyFat.start, 0f)
        assertEquals(70f, bodyFat.endInclusive, 0f)

        val muscle = InputLimits.rangeFor(BodyMetricType.MUSCLE_MASS)
        assertEquals(5f, muscle.start, 0f)
        assertEquals(100f, muscle.endInclusive, 0f)

        for (type in listOf(
            BodyMetricType.WAIST,
            BodyMetricType.CHEST,
            BodyMetricType.ARM,
            BodyMetricType.HIP,
        )) {
            val girth = InputLimits.rangeFor(type)
            assertEquals("$type 围度下界", 20f, girth.start, 0f)
            assertEquals("$type 围度上界", 300f, girth.endInclusive, 0f)
        }
    }

    @Test
    fun bodyMetricBoundariesForEveryType() {
        for (type in BodyMetricType.values()) {
            val range = InputLimits.rangeFor(type)
            assertTrue("$type 下界本身合法", InputLimits.isValidBodyMetric(type, range.start))
            assertTrue("$type 上界本身合法", InputLimits.isValidBodyMetric(type, range.endInclusive))
            assertFalse(
                "$type 下界 - 0.1 非法",
                InputLimits.isValidBodyMetric(type, range.start - 0.1f),
            )
            assertFalse(
                "$type 上界 + 0.1 非法",
                InputLimits.isValidBodyMetric(type, range.endInclusive + 0.1f),
            )
            assertFalse("$type NaN 非法", InputLimits.isValidBodyMetric(type, Float.NaN))
            assertFalse(
                "$type +Infinity 非法",
                InputLimits.isValidBodyMetric(type, Float.POSITIVE_INFINITY),
            )
            assertFalse("$type 0 非法", InputLimits.isValidBodyMetric(type, 0f))
            assertFalse("$type 负数非法", InputLimits.isValidBodyMetric(type, -1f))

            assertEquals(
                "$type 钳制到上界",
                range.endInclusive,
                InputLimits.coerceBodyMetric(type, range.endInclusive + 1_000f),
                0f,
            )
            assertEquals(
                "$type 钳制到下界",
                range.start,
                InputLimits.coerceBodyMetric(type, range.start - 1_000f),
                0f,
            )
            assertEquals(
                "$type NaN 钳制到下界",
                range.start,
                InputLimits.coerceBodyMetric(type, Float.NaN),
                0f,
            )
        }
    }
}
