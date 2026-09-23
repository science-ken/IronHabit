package com.ironhabit.app.ui.screens.checkin

import com.ironhabit.app.domain.model.InputLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打卡表单的输入范围校验。
 *
 * 钉两件事，缺一不可：
 * 1. **越界值被判非法**（`formValid = false`，保存按钮随之禁用）—— 这是 2026-09-20 那轮改的
 *    内容，此前这里只判"能不能 parse"+「> 0」，于是 99999 组、负重量、1e6 次一路写进库，
 *    把容量与趋势全部拉歪；
 * 2. **边界值本身仍可保存** —— 少了第二类断言，"把上限判成开区间"甚至"一律拒绝"都能让测试全绿。
 *
 * 为什么测的是抽出来的 [checkInFormValidity] 而不是 composable：判据原先直接长在 `CheckInSheet`
 * 函数体里，JVM 单测碰不到，于是那一轮的改动**一条测试都没有**（另外三个同类表单各有一份
 * `*InputLimitsTest`）。上限值一律从 [InputLimits] 现取，不抄字面量 —— 抄了就等于把口径钉死两次。
 */
class CheckInFormValidityTest {

    /** 一份"全部合法"的基线输入，各用例只改自己那一格。 */
    private fun valid() = checkInFormValidity(setsText = "3", repsText = "10", weightText = "60", durationText = "45")

    // ---------- 组数 ----------

    @Test
    fun setsAboveBitmapWidthIsRejected() {
        // 逐组勾选的唯一真源是 Int 位图，32 组会撞上符号位
        val over = checkInFormValidity(setsText = (InputLimits.MAX_SETS + 1).toString(), repsText = "10", weightText = "", durationText = "")
        assertFalse(over.setsValid)
        assertFalse(over.formValid)
    }

    @Test
    fun setsAtBitmapWidthIsStillAccepted() {
        assertTrue(
            checkInFormValidity(setsText = InputLimits.MAX_SETS.toString(), repsText = "10", weightText = "", durationText = "")
                .setsValid,
        )
    }

    @Test
    fun zeroAndNegativeAndBlankSetsAreAllRejected() {
        // 组数是必填：留空不能当"0 组"写进去
        for (raw in listOf("0", "-1", "", "   ")) {
            assertFalse("setsText=「$raw」应判非法", checkInFormValidity(setsText = raw, repsText = "10", weightText = "", durationText = "").setsValid)
        }
        assertTrue(checkInFormValidity(setsText = InputLimits.MIN_SETS.toString(), repsText = "10", weightText = "", durationText = "").setsValid)
    }

    // ---------- 次数 ----------

    @Test
    fun repsAboveCeilingIsRejectedAndCeilingItselfAccepted() {
        assertFalse(checkInFormValidity(setsText = "3", repsText = "101", weightText = "", durationText = "").repsValid)
        assertTrue(checkInFormValidity(setsText = "3", repsText = InputLimits.MAX_REPS.toString(), weightText = "", durationText = "").repsValid)
    }

    @Test
    fun millionRepsRegressionFromTheAudit() {
        // 报告点名的原症状："1e6 次都能一路写进库"
        assertFalse(checkInFormValidity(setsText = "3", repsText = "1000000", weightText = "", durationText = "").formValid)
    }

    // ---------- 负重 ----------

    @Test
    fun blankAndWhitespaceOnlyWeightMeanUnfilledNotInvalid() {
        // 「空 = 未填」：留空与纯空格都合法，且解析结果是 null 而不是 0f
        for (raw in listOf("", "   ")) {
            val parsed = checkInFormValidity(setsText = "3", repsText = "10", weightText = raw, durationText = "")
            assertTrue("weightText=「$raw」应判合法", parsed.weightValid)
            assertNull(parsed.weightKg)
            assertTrue(parsed.formValid)
        }
    }

    @Test
    fun nonFiniteWeightIsRejectedEvenThoughItParses() {
        // "NaN" / "Infinity" / "1e999" 都能被 toFloatOrNull() 解析出来，所以"能不能 parse"根本不是判据。
        // ⚠️ 变异检查实测过：把 isValidWeightKg 换成裸的 `weight >= 0f && weight <= 500f`，这一条**不会失败**。
        // NaN 与 ±Infinity 在裸区间比较下同样落网（NaN 与任何值比较都是 false，±Infinity 越上界/下界），
        // 两个判定对每个 Float 都相同 —— 那是个杀不掉的**等价变异**。`isFinite` 真正挣到钱的地方是
        // `coerceWeightKg`（`Float.coerceIn` 会让 NaN 原样穿过），不在 isValid 这条路上。
        // 所以本条钉的是"这些写法存不了"这个用户可见行为，不声称钉住了 isValidWeightKg 的实现。
        for (raw in listOf("NaN", "nan", "Infinity", "1e999")) {
            val parsed = checkInFormValidity(setsText = "3", repsText = "10", weightText = raw, durationText = "")
            assertFalse("weightText=「$raw」应判非法", parsed.weightValid)
            assertFalse(parsed.formValid)
        }
    }

    @Test
    fun negativeAndAbsurdWeightAreRejectedButRangeEdgesAccepted() {
        assertFalse(checkInFormValidity(setsText = "3", repsText = "10", weightText = "-5", durationText = "").weightValid)
        assertFalse(checkInFormValidity(setsText = "3", repsText = "10", weightText = "501", durationText = "").weightValid)
        assertTrue(checkInFormValidity(setsText = "3", repsText = "10", weightText = InputLimits.MAX_WEIGHT_KG.toString(), durationText = "").weightValid)
        assertTrue(checkInFormValidity(setsText = "3", repsText = "10", weightText = InputLimits.MIN_WEIGHT_KG.toString(), durationText = "").weightValid)
    }

    // ---------- 时长 ----------

    @Test
    fun durationAboveCeilingIsRejectedAndCeilingItselfAccepted() {
        assertFalse(checkInFormValidity(setsText = "3", repsText = "10", weightText = "", durationText = "601").durationValid)
        assertTrue(checkInFormValidity(setsText = "3", repsText = "10", weightText = "", durationText = InputLimits.MAX_DURATION_MIN.toString()).durationValid)
    }

    @Test
    fun blankDurationMeansUnfilledNotInvalid() {
        val parsed = checkInFormValidity(setsText = "3", repsText = "10", weightText = "", durationText = "")
        assertTrue(parsed.durationValid)
        assertNull(parsed.durationMinutes)
    }

    // ---------- 合起来 ----------

    @Test
    fun oneBadFieldDisablesTheWholeForm() {
        // 保存按钮看的是 formValid，所以任何一格越界都必须让它变 false
        assertTrue(valid().formValid)
        assertFalse(checkInFormValidity(setsText = "3", repsText = "10", weightText = "60", durationText = "9999").formValid)
    }

    @Test
    fun surroundingWhitespaceStillParses() {
        // 数字键盘与剪贴板都会带进空格；不 trim 的话 " 12 " 直接判非法，用户会觉得保存按钮坏了
        val parsed = checkInFormValidity(setsText = " 12 ", repsText = " 8 ", weightText = " 62.5 ", durationText = " 30 ")
        assertEquals(12, parsed.sets)
        assertEquals(8, parsed.reps)
        assertEquals(62.5f, parsed.weightKg!!, 0f)
        assertEquals(30, parsed.durationMinutes)
        assertTrue(parsed.formValid)
    }

    @Test
    fun nonNumericTextIsRejectedNotCrashed() {
        val parsed = checkInFormValidity(setsText = "abc", repsText = "10", weightText = "六零", durationText = "10")
        assertNull(parsed.sets)
        assertFalse(parsed.setsValid)
        assertFalse(parsed.weightValid)
        assertFalse(parsed.formValid)
    }
}
