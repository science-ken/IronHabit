package com.ironhabit.app.data.mapper

import com.ironhabit.app.data.local.entity.ExerciseEntity
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseSource

/**
 * `ExerciseEntity ⇄ domain.Exercise` 互转。
 *
 * - `exercises.muscle_group` 是**有序 CSV**（首个 = 主肌群），在本地解码为 `List<String>`。
 * - `exercises.equipment` 同样是**有序 CSV**（v7 新增），解码为 `List<Equipment>`。
 */
object ExerciseMapper {

    private const val CSV_SEPARATOR = ","

    /** 实体 → 领域模型（`defaultDurationSec = 0` 视作未设置，转为 `null`）。 */
    fun toDomain(entity: ExerciseEntity): Exercise = Exercise(
        id = entity.id,
        name = entity.name,
        category = entity.category,
        source = entity.source,
        muscleGroups = decodeMuscleGroups(entity.muscleGroup),
        equipment = decodeEquipment(entity.equipment),
        note = entity.note,
        isActive = entity.isActive,
        defaultSets = entity.defaultSets,
        defaultReps = entity.defaultReps,
        defaultDurationSec = entity.defaultDurationSec.takeIf { it > 0 },
        sortOrder = entity.sortOrder,
        timesUsed = entity.timesUsed,
        createdAt = entity.createdAt,
    )

    /**
     * 领域模型 → 实体（可空字段回落到数据库默认值）。
     *
     * 废弃列 `is_built_in` 由 [ExerciseSource] 派生，保持与 v1 数据语义一致。
     */
    @Suppress("DEPRECATION")
    fun toEntity(domain: Exercise): ExerciseEntity = ExerciseEntity(
        id = domain.id,
        name = domain.name,
        category = domain.category,
        muscleGroup = encodeMuscleGroups(domain.muscleGroups),
        equipment = encodeEquipment(domain.equipment),
        source = domain.source,
        note = domain.note,
        isBuiltIn = domain.source == ExerciseSource.BUILT_IN,
        isActive = domain.isActive,
        defaultSets = domain.defaultSets ?: 0,
        defaultReps = domain.defaultReps ?: 0,
        defaultDurationSec = domain.defaultDurationSec ?: 0,
        timesUsed = domain.timesUsed,
        sortOrder = domain.sortOrder,
        createdAt = domain.createdAt,
    )

    /** 有序 CSV → 有序列表（去空白、丢空项；顺序即主→辅）。 */
    fun decodeMuscleGroups(csv: String?): List<String> =
        csv.orEmpty()
            .split(CSV_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 有序列表 → 有序 CSV；空列表返回 `null`。 */
    fun encodeMuscleGroups(groups: List<String>): String? {
        val cleaned = groups.map { it.trim() }.filter { it.isNotEmpty() }
        return cleaned.takeIf { it.isNotEmpty() }?.joinToString(CSV_SEPARATOR)
    }

    /**
     * 器械 CSV → 枚举列表。
     *
     * **认不出的名字直接丢掉**（不抛异常）：降级安装（新版本写入 → 老版本读取）或手工改库时，
     * 一个未知器械名不该让整行动作读不出来；丢完为空即"未标注"，规则引擎自会回落旧判据。
     */
    fun decodeEquipment(csv: String?): List<Equipment> =
        csv.orEmpty()
            .split(CSV_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { name -> runCatching { Equipment.valueOf(name) }.getOrNull() }
            .distinct()

    /** 枚举列表 → CSV；空列表返回 `null`（= 未标注，与"不需要器械"的 `NONE` 区分）。 */
    fun encodeEquipment(equipment: List<Equipment>): String? =
        equipment.distinct().takeIf { it.isNotEmpty() }?.joinToString(CSV_SEPARATOR) { it.name }
}
