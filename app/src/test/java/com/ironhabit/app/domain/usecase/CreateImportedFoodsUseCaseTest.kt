package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.ai.external.ImportedNewFood
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.model.FoodSource
import com.ironhabit.app.domain.repository.FoodRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CreateImportedFoodsUseCase] 单测 —— 钉的是"什么情况下允许往食物库加一行"。
 *
 * 这一刀改的是**成分表**，而一餐的 kcal / 蛋白就是由它现算的，所以两种失败都要钉住：
 * - 把没填的数值写成 0 → 那一餐系统性偏小，勾了「吃了这餐」时还会进今日摄入；
 * - 撞 `foods.name` 的 UNIQUE 重建 → 抛异常中断整批，并把用户停用的条目放回可用池。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateImportedFoodsUseCaseTest {

    private val repository: FoodRepository = mockk(relaxed = true)
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_772_000_000_000L)
    }

    private fun useCase() = CreateImportedFoodsUseCase(
        foodRepository = repository,
        clock = clock,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun candidate(
        name: String,
        kcal: Int? = 60,
        tags: Set<DietRestriction> = emptySet(),
    ) = ImportedNewFood(
        name = name,
        kcalPer100g = kcal,
        proteinPer100g = 1.6,
        carbsPer100g = 13.0,
        fatPer100g = 0.1,
        dietaryTags = tags,
    )

    /** 空库。⚠️ 必须显式 stub：relaxed 的 mock 对 `Flow<List<Food>>` 返回的是一个**空 Flow**，
     * `first()` 会当场抛 NoSuchElementException，看起来像"用例坏了"而不是"测试没接上"。 */
    private fun emptyLibrary() = coEvery { repository.observeAll() } returns flowOf(emptyList())

    @Test
    fun create_writesTheConfirmedRow_asExternalAi_withTagsAndNoServings() = runTest {
        emptyLibrary()
        val inserted = slot<Food>()

        val created = useCase()(listOf(candidate("紫薯", tags = setOf(DietRestriction.GLUTEN))))

        assertEquals(1, created)
        coVerify(exactly = 1) { repository.upsert(capture(inserted)) }
        val row = inserted.captured
        assertEquals("0 = 尚未落库，DAO 走自增插入", 0L, row.id)
        assertEquals("紫薯", row.name)
        assertEquals(60, row.kcalPer100g)
        assertEquals(1.6, row.proteinPer100g, 1e-9)
        assertEquals(13.0, row.carbsPer100g, 1e-9)
        assertEquals(0.1, row.fatPer100g, 1e-9)
        assertEquals(
            "忌口标签要跟着建进去：新条目不标就等于对忌口零保护",
            setOf(DietRestriction.GLUTEN),
            row.dietaryTags,
        )
        assertEquals(FoodSource.AI_SUGGESTED, row.source)
        assertTrue("份量一律留空：模型说「一碗 = 200g」是最不可信的一句", row.servings.isEmpty())
        assertEquals(1_772_000_000_000L, row.createdAt)
    }

    @Test
    fun create_skipsNamesAlreadyInLibrary_includingDeactivatedOnes() = runTest {
        // 停用 ≠ 没有；而且 `foods.name` 有 UNIQUE，撞上去是**抛**，不是"这一条没建成"。
        coEvery { repository.observeAll() } returns flowOf(
            listOf(
                Food(
                    id = 5L,
                    name = "红薯",
                    kcalPer100g = 86,
                    proteinPer100g = 1.6,
                    carbsPer100g = 20.0,
                    fatPer100g = 0.1,
                    isActive = false,
                ),
            ),
        )

        assertEquals(0, useCase()(listOf(candidate(" 红薯 "))))
        coVerify(exactly = 0) { repository.upsert(any()) }
    }

    @Test
    fun create_skipsTheSecondOfTwoCandidatesWithTheSameName() = runTest {
        // 用户在表单里把两行改成同一个名字：第二次同样撞 UNIQUE。
        emptyLibrary()

        val created = useCase()(listOf(candidate("紫薯"), candidate("紫薯")))

        assertEquals(1, created)
        coVerify(exactly = 1) { repository.upsert(any()) }
    }

    @Test
    fun create_neverWritesZeroForANumberTheUserLeftBlank() = runTest {
        // 红线：`0 kcal/100g` 是一个陈述，不是"还不知道"。缺任何一格 = 这一条不建。
        emptyLibrary()

        val created = useCase()(listOf(candidate("紫薯", kcal = null)))

        assertEquals(0, created)
        coVerify(exactly = 0) { repository.upsert(any()) }
    }

    @Test
    fun create_withNothingSelected_doesNotEvenReadTheLibrary() = runTest {
        assertEquals(0, useCase()(emptyList()))

        coVerify(exactly = 0) { repository.observeAll() }
        coVerify(exactly = 0) { repository.upsert(any()) }
    }
}
