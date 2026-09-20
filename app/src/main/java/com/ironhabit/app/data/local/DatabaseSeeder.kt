package com.ironhabit.app.data.local

import com.ironhabit.app.data.local.dao.ExerciseDao
import com.ironhabit.app.data.local.entity.ExerciseEntity
import com.ironhabit.app.data.mapper.ExerciseMapper
import com.ironhabit.app.data.preset.BuiltInExercises
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内置动作播种器。
 *
 * 通过 `exercises.name` 唯一索引 + `OnConflictStrategy.IGNORE` 保证：二次启动不产生重复动作。
 * **只做播种与补空，不得写入任何业务逻辑**（架构 §7.6）。
 *
 * ## 为什么要"补空"（v7）
 * `IGNORE` 只保证不重复，代价是**同名行永远赢**：用户自建（或编辑过而降级为 `CUSTOM`）的动作
 * 占住了「卧推」这个名字，内置那条就再也进不来 —— 于是它**永远拿不到**内置库后来补上的标注
 * （如 v7 的器械列），规则引擎只能按分类猜。补空 = 只往**空白字段**里填内置值，
 * 用户填过的一律不动，因此不需要"合并冲突"这回事。
 *
 * 🔒 不变量：**本类绝不覆盖任何非空字段，也绝不改 `name` / `category` / `source` / `sort_order` /
 * `times_used` / `created_at`**。被编辑过的动作仍是 `CUSTOM`（产品规则：用户编辑单向降级）。
 */
@Singleton
class DatabaseSeeder @Inject constructor(
    private val exerciseDao: ExerciseDao,
) {

    /** 一次播种的结果（供上层决定是否提示，不参与任何业务判据）。 */
    data class SeedSummary(
        /** 本次**新插入**的条数（已存在被忽略的不计入）。 */
        val inserted: Int,
        /** 本次**补空**（原为空白、现填入内置值）的条数。 */
        val supplemented: Int,
    )

    suspend fun seedIfNeeded(): SeedSummary {
        val presets: List<ExerciseEntity> = BuiltInExercises.all.map(ExerciseMapper::toEntity)
        val rowIds: List<Long> = exerciseDao.insertAllIgnore(presets)
        val inserted: Int = rowIds.count { it != IGNORED_ROW_ID }

        // 只有被 IGNORE 掉的行才需要补空；全新安装时 inserted == presets.size，一次查询都不用发。
        val skipped: List<ExerciseEntity> = presets.filterIndexed { index, _ -> rowIds[index] == IGNORED_ROW_ID }
        if (skipped.isEmpty()) return SeedSummary(inserted = inserted, supplemented = 0)

        val existingByName: Map<String, ExerciseEntity> =
            exerciseDao.getByNames(skipped.map { it.name }).associateBy { it.name }

        var supplemented = 0
        for (preset in skipped) {
            val existing = existingByName[preset.name] ?: continue
            val merged: ExerciseEntity = mergeFillingBlanks(existing, preset) ?: continue
            // 走 update(entity.copy(id)) 而不是 upsert/REPLACE：保住 rowid，不触发指向本行的
            // week_plans / check_ins 级联删除。
            exerciseDao.update(merged.copy(id = existing.id))
            supplemented++
        }
        return SeedSummary(inserted = inserted, supplemented = supplemented)
    }

    private companion object {
        /** Room `OnConflictStrategy.IGNORE` 冲突行返回的 rowId。 */
        const val IGNORED_ROW_ID: Long = -1L
    }
}

/**
 * **只补空、不覆盖**：把 [preset] 里非空的值填进 [existing] 的空白字段。
 *
 * 返回 `null` = 没有任何可补的空位（调用方据此跳过写库，避免每次冷启动都产生一次无意义 UPDATE）。
 *
 * 各字段的"空"口径与领域模型一致：
 * - 文本列 `muscle_group` / `equipment`：`null` 或全空白 = 空；
 * - `default_sets` / `default_reps` / `default_duration_sec`：`0` = 空 —— 因为
 *   [ExerciseMapper.toEntity] 正是把领域层的 `null` 写成 `0` 的。
 */
internal fun mergeFillingBlanks(existing: ExerciseEntity, preset: ExerciseEntity): ExerciseEntity? {
    var changed = false
    var merged = existing

    if (merged.muscleGroup.isNullOrBlank() && !preset.muscleGroup.isNullOrBlank()) {
        merged = merged.copy(muscleGroup = preset.muscleGroup)
        changed = true
    }
    if (merged.equipment.isNullOrBlank() && !preset.equipment.isNullOrBlank()) {
        merged = merged.copy(equipment = preset.equipment)
        changed = true
    }
    if (merged.note.isNullOrBlank() && !preset.note.isNullOrBlank()) {
        merged = merged.copy(note = preset.note)
        changed = true
    }
    if (merged.defaultSets <= 0 && preset.defaultSets > 0) {
        merged = merged.copy(defaultSets = preset.defaultSets)
        changed = true
    }
    if (merged.defaultReps <= 0 && preset.defaultReps > 0) {
        merged = merged.copy(defaultReps = preset.defaultReps)
        changed = true
    }
    if (merged.defaultDurationSec <= 0 && preset.defaultDurationSec > 0) {
        merged = merged.copy(defaultDurationSec = preset.defaultDurationSec)
        changed = true
    }
    return merged.takeIf { changed }
}
