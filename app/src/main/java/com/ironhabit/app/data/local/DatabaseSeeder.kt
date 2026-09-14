package com.ironhabit.app.data.local

import com.ironhabit.app.data.local.dao.ExerciseDao
import com.ironhabit.app.data.mapper.ExerciseMapper
import com.ironhabit.app.data.preset.BuiltInExercises
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 首启幂等播种器。
 *
 * **只做幂等播种，不得写入任何业务逻辑**（架构 §7.6）。
 * 通过 `Exercises` 表 `name` 唯一索引 + `OnConflictStrategy.IGNORE` 保证：
 * 二次启动不会产生重复动作。
 */
@Singleton
class DatabaseSeeder @Inject constructor(
    private val exerciseDao: ExerciseDao,
) {

    /**
     * 若内置动作尚未写入则写入。
     *
     * @return 本次**新插入**的条数（已存在被忽略的不计入）
     */
    suspend fun seedIfNeeded(): Int {
        val entities = BuiltInExercises.all.map { ExerciseMapper.toEntity(it) }
        val rowIds = exerciseDao.insertAllIgnore(entities)
        return rowIds.count { it != IGNORED_ROW_ID }
    }

    private companion object {
        /** Room `OnConflictStrategy.IGNORE` 冲突行返回的 rowId。 */
        const val IGNORED_ROW_ID: Long = -1L
    }
}
