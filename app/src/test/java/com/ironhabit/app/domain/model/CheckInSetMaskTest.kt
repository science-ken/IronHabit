package com.ironhabit.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CheckIn.mergedMask] 单测：把「已完成组数」折算为位图时**不得重编号**。
 *
 * 背景：`completed_sets_mask` 是全 app 唯一记录"做过哪几组"的地方（`HistoryViewModel`
 * 会丢弃 mask），而补录弹层只提交组数。折算若走「低 n 位全 1」，
 * 勾了第 1、3 组的用户保存一次补录就被改写成"第 1、2 组"。
 */
class CheckInSetMaskTest {

    /** 数量不变 → 位置不动。 */
    @Test
    fun mergedMask_countUnchanged_keepsExistingPositions() {
        assertEquals(0b101, CheckIn.mergedMask(count = 2, previousMask = 0b101))
        assertEquals(0b101010, CheckIn.mergedMask(count = 3, previousMask = 0b101010))
    }

    /** 数量变多 → 原有位保留，再从序号最小的空位起补齐。 */
    @Test
    fun mergedMask_countGrown_keepsExistingAndFillsLowestFree() {
        assertEquals(0b111, CheckIn.mergedMask(count = 3, previousMask = 0b101))
        assertEquals(0b1111, CheckIn.mergedMask(count = 4, previousMask = 0b110))
    }

    /** 数量变少 → 留序号最小的 n 位，而不是"最后勾的那几个"。 */
    @Test
    fun mergedMask_countShrunk_keepsLowestIndexedPositions() {
        assertEquals(0b001, CheckIn.mergedMask(count = 1, previousMask = 0b101))
        assertEquals(0b0110, CheckIn.mergedMask(count = 2, previousMask = 0b10110))
    }

    /** 无旧记录（`previousMask = 0`）时行为与 [CheckIn.maskFromCount] 完全一致。 */
    @Test
    fun mergedMask_noPreviousRecord_matchesMaskFromCount() {
        for (count in 0..12) {
            assertEquals(
                "count=$count 时两者应一致",
                CheckIn.maskFromCount(count),
                CheckIn.mergedMask(count = count, previousMask = 0),
            )
        }
    }

    /** 零与负数一律清空，不残留旧位。 */
    @Test
    fun mergedMask_nonPositiveCount_clearsMask() {
        assertEquals(0, CheckIn.mergedMask(count = 0, previousMask = 0b101))
        assertEquals(0, CheckIn.mergedMask(count = -5, previousMask = 0b111))
        assertEquals(0, CheckIn.mergedMask(count = 0, previousMask = 0))
    }

    /** 越界钳制到 [MAX_SETS]，且绝不触碰符号位（`1 shl 31` 会变负数 mask）。 */
    @Test
    fun mergedMask_overMaxSets_clampsWithoutTouchingSignBit() {
        val mask = CheckIn.mergedMask(count = MAX_SETS + 99, previousMask = 0)
        assertEquals(MAX_SETS, mask.countOneBits())
        assertEquals(0, mask and (1 shl 31))
    }

    /** 不变量：派生列 `completedSets` 恒等于 mask 的置位数。 */
    @Test
    fun mergedMask_anyCount_keepsCompletedSetsInvariant() {
        val previous = 0b10110100
        for (count in 0..MAX_SETS) {
            val mask = CheckIn.mergedMask(count = count, previousMask = previous)
            val row = CheckIn(completedSetsMask = mask)
            assertEquals("count=$count 破坏不变量", mask.countOneBits(), row.completedSets)
        }
    }

    // ---- coversTargetSets：「这个动作今天练完了没」的唯一判据 ----

    /** 勾满才算完成；只勾一部分不算（旧口径"有记录即完成"会让 1/3 组就变灰打勾）。 */
    @Test
    fun coversTargetSets_requiresAllTargetSetsTicked() {
        assertFalse(CheckIn(completedSetsMask = 0b001).coversTargetSets(targetSets = 3))
        assertFalse(CheckIn(completedSetsMask = 0b101).coversTargetSets(targetSets = 3))
        assertTrue(CheckIn(completedSetsMask = 0b111).coversTargetSets(targetSets = 3))
    }

    /** 目标 0 组的动作压根没有勾选框，一键打卡得到的 mask 也是 0 → 有记录就该算完成。 */
    @Test
    fun coversTargetSets_zeroTargetSets_anyRecordCounts() {
        assertTrue(CheckIn(completedSetsMask = 0).coversTargetSets(targetSets = 0))
        assertTrue(CheckIn(completedSetsMask = 0b1).coversTargetSets(targetSets = 0))
    }

    /** 计划组数被改小之后，旧打卡里多勾的位不该把这条反判成未完成。 */
    @Test
    fun coversTargetSets_planShrunkAfterCheckIn_staysComplete() {
        assertTrue(CheckIn(completedSetsMask = 0b1111).coversTargetSets(targetSets = 2))
    }
}
