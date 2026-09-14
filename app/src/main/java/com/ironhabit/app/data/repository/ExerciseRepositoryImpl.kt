package com.ironhabit.app.data.repository

import com.ironhabit.app.data.local.DatabaseSeeder
import com.ironhabit.app.data.local.dao.ExerciseDao
import com.ironhabit.app.data.mapper.ExerciseMapper
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.repository.ExerciseRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * [ExerciseRepository] 的 data 层实现。
 */
@Singleton
class ExerciseRepositoryImpl @Inject constructor(
    private val exerciseDao: ExerciseDao,
    private val databaseSeeder: DatabaseSeeder,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ExerciseRepository {

    override fun observeActive(): Flow<List<Exercise>> =
        exerciseDao.observeActive()
            .map { entities -> entities.map(ExerciseMapper::toDomain) }
            .flowOn(ioDispatcher)

    override fun observeByCategory(category: ExerciseCategory): Flow<List<Exercise>> =
        exerciseDao.observeByCategory(category)
            .map { entities -> entities.map(ExerciseMapper::toDomain) }
            .flowOn(ioDispatcher)

    override suspend fun getById(id: Long): Exercise? =
        exerciseDao.getById(id)?.let(ExerciseMapper::toDomain)

    override suspend fun upsert(exercise: Exercise): Long =
        exerciseDao.upsert(ExerciseMapper.toEntity(exercise))

    override suspend fun setActive(id: Long, active: Boolean) {
        exerciseDao.setActive(id, active)
    }

    override suspend fun nameExists(name: String, excludeId: Long): Boolean =
        exerciseDao.countByName(name, excludeId) > 0

    override suspend fun bumpUsage(exerciseId: Long) {
        exerciseDao.bumpUsage(exerciseId)
    }

    override suspend fun seedBuiltIns(): Int = databaseSeeder.seedIfNeeded()
}
