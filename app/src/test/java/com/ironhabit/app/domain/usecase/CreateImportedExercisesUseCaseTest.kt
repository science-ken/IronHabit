package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.ai.external.ImportedNewExercise
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseSource
import com.ironhabit.app.domain.repository.ExerciseRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CreateImportedExercisesUseCase] 单测 —— 钉幂等与字段口径。
 *
 * 这一步是往用户库里**永久加一行**，所以"跳过了谁"和"写进去长什么样"都要能被钉住：
 * 撞名重建会把用户主动停用的动作悄悄放回可用池。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateImportedExercisesUseCaseTest {

    private val repository: ExerciseRepository = mockk(relaxed = true)
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_772_000_000_000L)
    }

    private fun useCase() = CreateImportedExercisesUseCase(
        exerciseRepository = repository,
        clock = clock,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun candidate(name: String) = ImportedNewExercise(
        name = name,
        category = ExerciseCategory.STRENGTH,
        muscleGroups = listOf("腿部", "臀部"),
        equipment = setOf(Equipment.DUMBBELL),
    )

    @Test
    fun create_writesEachSelectedCandidate_asAiSuggestedWithItsFields() = runTest {
        coEvery { repository.getAll() } returns listOf(Exercise(id = 1L, name = "深蹲"))
        val inserted = slot<Exercise>()

        val created = useCase()(listOf(candidate("保加利亚分腿蹲")))

        assertEquals(1, created)
        coVerify(exactly = 1) { repository.upsert(capture(inserted)) }
        val row = inserted.captured
        assertEquals("0 = 尚未落库，DAO 走自增插入", 0L, row.id)
        assertEquals("保加利亚分腿蹲", row.name)
        assertEquals(ExerciseCategory.STRENGTH, row.category)
        assertEquals(listOf("腿部", "臀部"), row.muscleGroups)
        assertEquals(listOf(Equipment.DUMBBELL), row.equipment)
        assertEquals(ExerciseSource.AI_SUGGESTED, row.source)
        assertEquals(1_772_000_000_000L, row.createdAt)
    }

    @Test
    fun create_skipsNamesAlreadyInLibrary_includingDeactivatedOnes() = runTest {
        // 停用 ≠ 没有：重建等于把用户主动停掉的东西悄悄放回可用池。
        coEvery { repository.getAll() } returns listOf(
            Exercise(id = 5L, name = "卧推", isActive = false),
        )

        val created = useCase()(listOf(candidate("卧推")))

        assertEquals(0, created)
        coVerify(exactly = 0) { repository.upsert(any()) }
    }

    @Test
    fun create_withNothingSelected_doesNotEvenReadTheLibrary() = runTest {
        assertEquals(0, useCase()(emptyList()))

        coVerify(exactly = 0) { repository.getAll() }
        coVerify(exactly = 0) { repository.upsert(any()) }
    }
}
