package com.ironhabit.app.data.repository

import androidx.room.withTransaction
import com.ironhabit.app.data.local.AppDatabase
import com.ironhabit.app.data.local.dao.BodyMetricDao
import com.ironhabit.app.data.local.dao.CheckInDao
import com.ironhabit.app.data.local.dao.ExerciseDao
import com.ironhabit.app.data.local.dao.HabitDao
import com.ironhabit.app.data.local.dao.HabitLogDao
import com.ironhabit.app.data.local.dao.WeekPlanDao
import com.ironhabit.app.data.local.entity.BodyMetricEntity
import com.ironhabit.app.data.local.entity.CheckInEntity
import com.ironhabit.app.data.local.entity.ExerciseEntity
import com.ironhabit.app.data.local.entity.HabitEntity
import com.ironhabit.app.data.local.entity.HabitLogEntity
import com.ironhabit.app.data.local.entity.WeekPlanEntity
import com.ironhabit.app.data.preferences.SettingsDataStore
import com.ironhabit.app.domain.model.AppSettings
import com.ironhabit.app.domain.model.BackupPayload
import com.ironhabit.app.domain.model.BodyMetricBackup
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.CheckInBackup
import com.ironhabit.app.domain.model.ExerciseBackup
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseSource
import com.ironhabit.app.domain.model.HabitBackup
import com.ironhabit.app.domain.model.HabitFrequency
import com.ironhabit.app.domain.model.HabitLogBackup
import com.ironhabit.app.domain.model.SettingsBackup
import com.ironhabit.app.domain.model.ThemeMode
import com.ironhabit.app.domain.model.UnitSystem
import com.ironhabit.app.domain.model.WeekPlanBackup
import com.ironhabit.app.domain.repository.BackupRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

/**
 * [BackupRepository] 的 data 层实现。
 *
 * 纯本地文件 JSON 导出 / 导入（kotlinx.serialization），**绝不引入任何网络库**。
 * 导入为「整体替换」：在单个 Room 事务内清空 6 张表并保留原 `id` 重建，失败整体回滚。
 */
@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val exerciseDao: ExerciseDao,
    private val weekPlanDao: WeekPlanDao,
    private val checkInDao: CheckInDao,
    private val habitDao: HabitDao,
    private val habitLogDao: HabitLogDao,
    private val bodyMetricDao: BodyMetricDao,
    private val settingsDataStore: SettingsDataStore,
    private val clock: Clock,
) : BackupRepository {

    private val jsonCodec: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    override suspend fun export(): String {
        val settings = settingsDataStore.settings.first()
        val payload = BackupPayload(
            schemaVersion = BackupPayload.CURRENT_SCHEMA_VERSION,
            exportedAt = clock.now().toEpochMilliseconds(),
            appVersion = DEFAULT_APP_VERSION,
            exercises = exerciseDao.getAll().map { it.toBackup() },
            weekPlans = weekPlanDao.getAll().map { it.toBackup() },
            checkIns = checkInDao.getAll().map { it.toBackup() },
            habits = habitDao.getAll().map { it.toBackup() },
            habitLogs = habitLogDao.getAll().map { it.toBackup() },
            bodyMetrics = bodyMetricDao.getAll().map { it.toBackup() },
            settings = settings.toBackup(),
        )
        return jsonCodec.encodeToString(BackupPayload.serializer(), payload)
    }

    override suspend fun import(json: String): Result<Unit> = runCatching {
        val payload = jsonCodec.decodeFromString(BackupPayload.serializer(), json)
        require(payload.schemaVersion <= BackupPayload.CURRENT_SCHEMA_VERSION) {
            "备份文件版本过高（v${payload.schemaVersion}），请升级 App 后再导入"
        }

        database.withTransaction {
            exerciseDao.clearAll()
            weekPlanDao.clearAll()
            checkInDao.clearAll()
            habitDao.clearAll()
            habitLogDao.clearAll()
            bodyMetricDao.clearAll()

            exerciseDao.insertAll(payload.exercises.map { it.toEntity() })
            weekPlanDao.insertAll(payload.weekPlans.map { it.toEntity() })
            checkInDao.insertAll(payload.checkIns.map { it.toEntity() })
            habitDao.insertAll(payload.habits.map { it.toEntity() })
            habitLogDao.insertAll(payload.habitLogs.map { it.toEntity() })
            bodyMetricDao.insertAll(payload.bodyMetrics.map { it.toEntity() })
        }

        // 数据库导入成功后同步设置快照（DataStore 不参与 Room 事务）。
        applySettings(payload.settings)
    }

    /** 把设置快照写回 DataStore。 */
    private suspend fun applySettings(settings: SettingsBackup) {
        settingsDataStore.setTheme(parseThemeMode(settings.themeMode))
        settingsDataStore.setUnit(parseUnitSystem(settings.unitSystem))
        settingsDataStore.setReminderEnabled(settings.reminderEnabled)
        settingsDataStore.setReminderTime(settings.reminderHour, settings.reminderMinute)
    }

    private companion object {
        const val DEFAULT_APP_VERSION: String = "1.0"
    }
}

// ============================ 实体 ⇄ 备份 DTO 互转（本文件私有） ============================

@Suppress("DEPRECATION")
private fun ExerciseEntity.toBackup(): ExerciseBackup = ExerciseBackup(
    id = id,
    name = name,
    category = category.name,
    muscleGroup = muscleGroup,
    source = source.name,
    note = note,
    isBuiltIn = isBuiltIn,
    isActive = isActive,
    defaultSets = defaultSets,
    defaultReps = defaultReps,
    defaultDurationSec = defaultDurationSec.takeIf { it > 0 },
    sortOrder = sortOrder,
    timesUsed = timesUsed,
)

@Suppress("DEPRECATION")
private fun ExerciseBackup.toEntity(): ExerciseEntity {
    // v2 备份直接用 source；v1 老备份 source 为空 → 由 isBuiltIn 推导三态来源。
    val resolvedSource = parseExerciseSource(source)
        ?: if (isBuiltIn) ExerciseSource.BUILT_IN else ExerciseSource.CUSTOM
    return ExerciseEntity(
        id = id,
        name = name,
        category = parseCategory(category),
        muscleGroup = muscleGroup,
        source = resolvedSource,
        note = note,
        isBuiltIn = resolvedSource == ExerciseSource.BUILT_IN,
        isActive = isActive,
        defaultSets = defaultSets ?: 0,
        defaultReps = defaultReps ?: 0,
        defaultDurationSec = defaultDurationSec ?: 0,
        timesUsed = timesUsed,
        sortOrder = sortOrder,
        createdAt = 0L,
    )
}

private fun WeekPlanEntity.toBackup(): WeekPlanBackup = WeekPlanBackup(
    id = id,
    exerciseId = exerciseId,
    dayOfWeek = dayOfWeek,
    targetSets = targetSets,
    targetReps = targetReps,
    targetWeightKg = targetWeightKg,
    targetDurationMin = targetDurationMin,
    sortOrder = sortOrder,
    isActive = isActive,
    isUserEdited = isUserEdited,
)

private fun WeekPlanBackup.toEntity(): WeekPlanEntity = WeekPlanEntity(
    id = id,
    exerciseId = exerciseId,
    dayOfWeek = dayOfWeek,
    targetSets = targetSets,
    targetReps = targetReps,
    targetWeightKg = targetWeightKg,
    targetDurationMin = targetDurationMin,
    sortOrder = sortOrder,
    isActive = isActive,
    isUserEdited = isUserEdited,
    createdAt = 0L,
)

private fun CheckInEntity.toBackup(): CheckInBackup = CheckInBackup(
    id = id,
    exerciseId = exerciseId,
    planId = planId,
    dateEpochDay = dateEpochDay,
    dateStartMillis = dateStartMillis,
    completedSets = completedSets,
    completedSetsMask = completedSetsMask,
    rpe = rpe,
    completedReps = completedReps,
    weightKg = weightKg,
    durationMinutes = durationMinutes,
    notes = notes,
    isQuick = isQuick,
    loggedAtMillis = loggedAtMillis,
)

private fun CheckInBackup.toEntity(): CheckInEntity {
    // 唯一真源是 mask；老备份（mask == null）由 completedSets 折算为低 n 位全 1。
    val mask = completedSetsMask ?: CheckIn.maskFromCount(completedSets)
    return CheckInEntity(
        id = id,
        exerciseId = exerciseId,
        planId = planId,
        dateEpochDay = dateEpochDay,
        dateStartMillis = dateStartMillis,
        completedSets = mask.countOneBits(),
        completedSetsMask = mask,
        rpe = rpe,
        completedReps = completedReps,
        weightKg = weightKg,
        durationMinutes = durationMinutes,
        notes = notes,
        isQuick = isQuick,
        loggedAtMillis = loggedAtMillis,
        createdAt = 0L,
    )
}

private fun HabitEntity.toBackup(): HabitBackup = HabitBackup(
    id = id,
    name = name,
    emoji = emoji,
    colorHex = colorHex,
    frequency = frequency.name,
    weeklyDaysMask = weeklyDaysMask,
    reminderEnabled = reminderEnabled,
    reminderHour = reminderHour.takeIf { reminderEnabled },
    reminderMinute = reminderMinute.takeIf { reminderEnabled },
    note = note,
    targetValue = targetValue,
    targetUnit = targetUnit,
    isActive = isActive,
    sortOrder = sortOrder,
)

private fun HabitBackup.toEntity(): HabitEntity = HabitEntity(
    id = id,
    name = name,
    emoji = emoji,
    colorHex = colorHex,
    frequency = parseFrequency(frequency),
    weeklyDaysMask = weeklyDaysMask,
    reminderEnabled = reminderEnabled,
    reminderHour = reminderHour ?: 0,
    reminderMinute = reminderMinute ?: 0,
    note = note,
    targetValue = targetValue,
    targetUnit = targetUnit,
    isActive = isActive,
    sortOrder = sortOrder,
    createdAt = 0L,
)

private fun HabitLogEntity.toBackup(): HabitLogBackup = HabitLogBackup(
    id = id,
    habitId = habitId,
    dateEpochDay = dateEpochDay,
    dateStartMillis = dateStartMillis,
    isCompleted = isCompleted,
    note = note,
    loggedAtMillis = loggedAtMillis,
)

private fun HabitLogBackup.toEntity(): HabitLogEntity = HabitLogEntity(
    id = id,
    habitId = habitId,
    dateEpochDay = dateEpochDay,
    dateStartMillis = dateStartMillis,
    isCompleted = isCompleted,
    note = note,
    loggedAtMillis = loggedAtMillis,
    createdAt = 0L,
)

private fun BodyMetricEntity.toBackup(): BodyMetricBackup = BodyMetricBackup(
    id = id,
    type = type.name,
    value = value,
    unit = unit,
    dateEpochDay = dateEpochDay,
    dateStartMillis = dateStartMillis,
    note = note,
)

private fun BodyMetricBackup.toEntity(): BodyMetricEntity = BodyMetricEntity(
    id = id,
    type = parseMetricType(type),
    value = value,
    unit = unit,
    dateEpochDay = dateEpochDay,
    dateStartMillis = dateStartMillis,
    note = note,
    createdAt = 0L,
)

private fun AppSettings.toBackup(): SettingsBackup = SettingsBackup(
    themeMode = themeMode.name,
    unitSystem = unitSystem.name,
    reminderEnabled = reminderEnabled,
    reminderHour = reminderHour,
    reminderMinute = reminderMinute,
)

private fun parseCategory(value: String): ExerciseCategory =
    runCatching { ExerciseCategory.valueOf(value) }.getOrNull() ?: ExerciseCategory.CUSTOM

/** 解析三态来源；无法识别（含旧备份的空值）返回 `null`，由调用方按 `isBuiltIn` 推导。 */
private fun parseExerciseSource(value: String?): ExerciseSource? =
    value?.let { runCatching { ExerciseSource.valueOf(it) }.getOrNull() }

private fun parseFrequency(value: String): HabitFrequency =
    runCatching { HabitFrequency.valueOf(value) }.getOrNull() ?: HabitFrequency.DAILY

private fun parseMetricType(value: String): BodyMetricType =
    runCatching { BodyMetricType.valueOf(value) }.getOrNull() ?: BodyMetricType.WEIGHT

private fun parseThemeMode(value: String): ThemeMode =
    runCatching { ThemeMode.valueOf(value) }.getOrNull() ?: ThemeMode.SYSTEM

private fun parseUnitSystem(value: String): UnitSystem =
    runCatching { UnitSystem.valueOf(value) }.getOrNull() ?: UnitSystem.METRIC
