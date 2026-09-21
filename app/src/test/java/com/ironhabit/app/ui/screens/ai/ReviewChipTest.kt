package com.ironhabit.app.ui.screens.ai

import com.ironhabit.app.R
import com.ironhabit.app.domain.model.BodyReview
import com.ironhabit.app.domain.model.DietReview
import com.ironhabit.app.domain.model.ExerciseTrend
import com.ironhabit.app.domain.model.ReviewNote
import com.ironhabit.app.domain.model.TrainingReview
import com.ironhabit.app.domain.model.WeekDayDetail
import com.ironhabit.app.domain.model.WeekItemDetail
import com.ironhabit.app.domain.model.WeeklyReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [reviewChips] —— 复盘屏那排 chip 的取数规则。
 *
 * chip 上的数字是**要拿去问模型的**，所以每条都必须能追溯到 [WeeklyReview] 里的真实值：
 * 没有异常就一条都不生成（界面上只剩一枚虚线"问问教练"），有异常也只报算得出来的那个数。
 * 这里刻意把每条 chip 的参数逐个钉死，而不是只断言"非空"——
 * 断言非空的话，把 3 写成 30 也能过。
 */
class ReviewChipTest {

    private fun review(
        completedDays: Int = 3,
        plannedDays: Int = 3,
        totalSets: Int = 30,
        plannedSets: Int = 30,
        stalled: List<ExerciseTrend> = emptyList(),
        sampleCount: Int = 3,
        days: List<WeekDayDetail> = (0L until 7L).map { WeekDayDetail(dateEpochDay = it) },
        notes: List<ReviewNote> = emptyList(),
    ): WeeklyReview = WeeklyReview(
        weekStartEpochDay = 0L,
        weekEndEpochDay = 6L,
        training = TrainingReview(
            plannedDays = plannedDays,
            completedDays = completedDays,
            totalVolumeKg = 1000f,
            totalSets = totalSets,
            plannedSets = plannedSets,
            avgRpe = 7f,
            progressed = emptyList(),
            stalled = stalled,
        ),
        body = BodyReview(startWeightKg = 70f, latestWeightKg = 69f, sampleCount = sampleCount),
        diet = DietReview(loggedDays = 5, avgKcal = 2400, avgProteinG = 150, preciseDays = 5),
        days = days,
        notes = notes,
    )

    private fun item(sets: Int, rpe: Int?): WeekItemDetail = WeekItemDetail(
        exerciseId = 1L,
        exerciseName = "深蹲",
        sets = sets,
        reps = 10,
        weightKg = 40f,
        rpe = rpe,
        note = null,
    )

    @Test
    fun cleanWeek_producesNoChips() {
        assertTrue(
            "出勤满、无停滞、RPE 全记、称了 3 次 → 一条异常 chip 都不该有",
            reviewChips(review()).isEmpty(),
        )
    }

    @Test
    fun attendanceShortfall_carriesBothNumbers() {
        val chips = reviewChips(review(completedDays = 2, plannedDays = 3))

        val chip = chips.first { it.labelRes == R.string.ai_chip_attendance }
        assertEquals(listOf(2, 3), chip.labelArgs)
        assertEquals("提问里也要带上同样的两个数", listOf(2, 3), chip.questionArgs)
    }

    @Test
    fun fullAttendance_producesNoChip() {
        assertFalse(
            "练满了就不许出现出勤 chip",
            reviewChips(review(completedDays = 3, plannedDays = 3))
                .any { it.labelRes == R.string.ai_chip_attendance },
        )
        assertFalse(
            "一天都没排（plannedDays=0）不该报成「只练了 0/0 天」",
            reviewChips(review(completedDays = 0, plannedDays = 0))
                .any { it.labelRes == R.string.ai_chip_attendance },
        )
    }

    @Test
    fun stalled_picksTheWorstTrendAndNamesIt() {
        val chips = reviewChips(
            review(
                totalSets = 25,
                plannedSets = 55,
                stalled = listOf(
                    ExerciseTrend(1L, "卧推", 40f, 40f, 3),
                    ExerciseTrend(2L, "深蹲", 60f, 60f, 5),
                ),
            ),
        )

        val chip = chips.first { it.labelRes == R.string.ai_chip_stalled }
        assertEquals(
            "取停滞最久的那条，并把动作名一起塞进提问",
            listOf("深蹲", 5, 25, 55),
            chip.questionArgs,
        )
        assertEquals(listOf(5), chip.labelArgs)
    }

    @Test
    fun setsWithoutRpe_countsSetsNotRows() {
        val days = listOf(
            WeekDayDetail(
                dateEpochDay = 0L,
                items = listOf(item(sets = 4, rpe = null), item(sets = 3, rpe = 7)),
            ),
            WeekDayDetail(dateEpochDay = 1L, items = listOf(item(sets = 2, rpe = null))),
        )

        val chip = reviewChips(review(days = days)).first { it.labelRes == R.string.ai_chip_no_rpe }

        assertEquals("4 + 2 = 6 组没记 RPE（按组数算，不是按行数算 2）", listOf(6), chip.labelArgs)
        assertEquals(listOf(6), chip.questionArgs)
    }

    @Test
    fun allRpeRecorded_producesNoChip() {
        val days = listOf(
            WeekDayDetail(dateEpochDay = 0L, items = listOf(item(sets = 4, rpe = 7))),
        )
        assertFalse(
            reviewChips(review(days = days)).any { it.labelRes == R.string.ai_chip_no_rpe },
        )
    }

    @Test
    fun oneWeighin_chipsButZeroDoesNot() {
        assertTrue(
            "只称 1 次 → 体重变化算不出来，值得问一句",
            reviewChips(review(sampleCount = 1)).any { it.labelRes == R.string.ai_chip_one_weighin },
        )
        assertFalse(
            "一次都没称 ≠「只称了 1 次」，这句话是错的，不能出这条 chip",
            reviewChips(review(sampleCount = 0)).any { it.labelRes == R.string.ai_chip_one_weighin },
        )
    }
}
