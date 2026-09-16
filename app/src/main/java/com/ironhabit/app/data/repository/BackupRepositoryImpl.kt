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
import com.ironhabit.app.di.AppVersion
import com.ironhabit.app.domain.model.AppSettings
import com.ironhabit.app.domain.model.BackupPayload
import com.ironhabit.app.domain.model.BodyMetricBackup
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.CheckInBackup
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.ExerciseBackup
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseSource
import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.HabitBackup
import com.ironhabit.app.domain.model.HabitFrequency
import com.ironhabit.app.domain.model.HabitLogBackup
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.SettingsBackup
import com.ironhabit.app.domain.model.ThemeMode
import com.ironhabit.app.domain.model.UnitSystem
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlanBackup
import com.ironhabit.app.domain.model.decodeEnum
import com.ironhabit.app.domain.model.decodeEnumSet
import com.ironhabit.app.domain.model.encodeEnumSet
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
 *
 * v3 修复：① 备份快照带上**用户档案 + AI 联网开关**（此前恢复会静默抹掉档案）；
 * ② 6 张表全部导出 / 还原真实 `createdAt`（老备份缺失 → 回落导入时刻，不再硬编码 `0L`）；
 * ③ `appVersion` 由 [AppVersion] 注入真实版本名（此前硬编码 `"1.0"`）。
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
    @AppVersion private val appVersion: String,
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
        // 档案与 AI 开关同处一个 DataStore，但各自是独立 Flow（读取失败均回落默认值，不崩）。
        val profile = settingsDataStore.profile.first()
        val aiRemoteEnabled = settingsDataStore.aiRemoteEnabled.first()
        val payload = BackupPayload(
            schemaVersion = BackupPayload.CURRENT_SCHEMA_VERSION,
            exportedAt = clock.now().toEpochMilliseconds(),
            appVersion = appVersion,
            exercises = exerciseDao.getAll().map { it.toBackup() },
            weekPlans = weekPlanDao.getAll().map { it.toBackup() },
            checkIns = checkInDao.getAll().map { it.toBackup() },
            habits = habitDao.getAll().map { it.toBackup() },
            habitLogs = habitLogDao.getAll().map { it.toBackup() },
            bodyMetrics = bodyMetricDao.getAll().map { it.toBackup() },
            settings = settings.toBackup(profile, aiRemoteEnabled),
        )
        return jsonCodec.encodeToString(BackupPayload.serializer(), payload)
    }

    override suspend fun import(json: String): Result<Unit> = runCatching {
        val payload = jsonCodec.decodeFromString(BackupPayload.serializer(), json)
        require(payload.schemaVersion <= BackupPayload.CURRENT_SCHEMA_VERSION) {
            "备份文件版本过高（v${payload.schemaVersion}），请升级 App 后再导入"
        }

        // 老备份（v1/v2）不携带 `createdAt` → 回落到**本次导入时刻**，避免 `created_at = 0`
        // 破坏 `ORDER BY created_at DESC` 的历史排序（同一个值，保证同批数据次序稳定）。
        val importMillis = clock.now().toEpochMilliseconds()

        database.withTransaction {
            exerciseDao.clearAll()
            weekPlanDao.clearAll()
            checkInDao.clearAll()
            habitDao.clearAll()
            habitLogDao.clearAll()
            bodyMetricDao.clearAll()

            exerciseDao.insertAll(payload.exercises.map { it.toEntity(importMillis) })
            weekPlanDao.insertAll(payload.weekPlans.map { it.toEntity(importMillis) })
            checkInDao.insertAll(payload.checkIns.map { it.toEntity(importMillis) })
            habitDao.insertAll(payload.habits.map { it.toEntity(importMillis) })
            habitLogDao.insertAll(payload.habitLogs.map { it.toEntity(importMillis) })
            bodyMetricDao.insertAll(payload.bodyMetrics.map { it.toEntity(importMillis) })
        }

        // 数据库导入成功后同步设置快照（DataStore 不参与 Room 事务）。
        applySettings(payload.settings, payload.schemaVersion)
    }

    /**
     * 把设置快照写回 DataStore。
     *
     * 主题 / 单位 / 提醒三项沿用「整体替换」（与旧版一致，缺失字段解码即默认值）；
     * 档案与 AI 开关按 [schemaVersion] 决定写入口径，见 [applyProfile]。
     */
    private suspend fun applySettings(settings: SettingsBackup, schemaVersion: Int) {
        settingsDataStore.setTheme(parseThemeMode(settings.themeMode))
        settingsDataStore.setUnit(parseUnitSystem(settings.unitSystem))
        settingsDataStore.setReminderEnabled(settings.reminderEnabled)
        settingsDataStore.setReminderTime(settings.reminderHour, settings.reminderMinute)
        applyProfile(settings, schemaVersion)
    }

    /**
     * 还原用户档案 + 「AI 联网生成」开关（**只写备份确实携带的字段**）。
     *
     * - 可空字段（性别 / 年龄 / 身高 / 体脂 / 目标 / 目标体重 / 伤病备注）：`null` = 「老备份未携带」
     *   或「用户本就未填」→ **一律跳过**，绝不用 `null` 覆盖本地已有值（宁可少写，不可误抹）。
     * - 集合字段（器械 / 伤病部位 / 忌口）与 [SettingsBackup.aiRemoteEnabled]：非空默认值，
     *   无法用「非空」自证携带，故以**结构版本**判定 —— v3+ 备份因导出 `encodeDefaults = true`
     *   一定显式编码（空集 / `false` 都是明确快照 → 照写）；v1/v2 老备份无该键（解码为默认空集
     *   / `false`）→ 空集一律视为「未携带」而跳过，避免清空本地已选器械 / 伤病 / 忌口。
     *   若 v2 JSON 被人为填入了非空集合，仍按「显式携带」处理并写回。
     */
    private suspend fun applyProfile(settings: SettingsBackup, schemaVersion: Int) {
        val carriesProfile = BackupRestoreRules.carriesProfileSnapshot(schemaVersion)

        settings.gender?.let { raw ->
            decodeEnum<Gender>(raw)?.let { settingsDataStore.setProfileGender(it) }
        }
        settings.age?.let { settingsDataStore.setProfileAge(it) }
        settings.heightCm?.let { settingsDataStore.setProfileHeightCm(it) }
        settings.bodyFatPct?.let { settingsDataStore.setProfileBodyFatPct(it) }
        settings.goal?.let { raw ->
            decodeEnum<Goal>(raw)?.let { settingsDataStore.setProfileGoal(it) }
        }
        settings.goalWeightKg?.let { settingsDataStore.setProfileGoalWeightKg(it) }
        settings.injuryNote?.let { settingsDataStore.setProfileInjuryNote(it) }

        if (BackupRestoreRules.shouldRestoreSet(settings.equipment, carriesProfile)) {
            settingsDataStore.setProfileEquipment(decodeEnumSet<Equipment>(settings.equipment))
        }
        if (BackupRestoreRules.shouldRestoreSet(settings.injuryAreas, carriesProfile)) {
            settingsDataStore.setProfileInjuryAreas(decodeEnumSet<InjuryArea>(settings.injuryAreas))
        }
        if (BackupRestoreRules.shouldRestoreSet(settings.dietaryAvoid, carriesProfile)) {
            settingsDataStore.setProfileDietaryAvoid(
                decodeEnumSet<DietRestriction>(settings.dietaryAvoid),
            )
        }
        if (carriesProfile) {
            settingsDataStore.setAiRemoteEnabled(settings.aiRemoteEnabled)
        }
    }
}

// ============================ 备份 → 实体 的还原规则（本文件 internal，可 JVM 单测） ============================

/**
 * 备份 → 实体的还原规则（**纯函数**，不依赖 Room / Context / Hilt，JVM 单测可直接覆盖）。
 *
 * 抽出本对象的唯一目的：`BackupRepositoryImpl` 自身需要 AppDatabase / DataStore 才能构造，
 * 无法在 JVM 单测里实例化；把「老备份缺字段怎么回落」的判定与映射抽成纯函数后即可单测。
 */
internal object BackupRestoreRules {

    /** 备份自 v3 起携带用户档案快照（`settings` 新增 11 项）。 */
    const val PROFILE_SNAPSHOT_SCHEMA_VERSION: Int = 3

    /**
     * 备份是否携带档案快照。
     *
     * v3+ 的备份对档案字段「照写」（含空集 / `null` 的明确语义）；
     * 更老的备份完全没有这些键 → 恢复时整体跳过，避免用缺失值覆盖本地档案。
     */
    fun carriesProfileSnapshot(schemaVersion: Int): Boolean =
        schemaVersion >= PROFILE_SNAPSHOT_SCHEMA_VERSION

    /**
     * 备份 `createdAt` → 实体 `createdAt`。
     *
     * v1/v2 老备份没有该字段（解码为 `0L`）→ 回落到 [importMillis]（本次导入时刻），
     * 保证 `ORDER BY created_at DESC` 仍有确定次序；v3+ 备份原样透传。
     */
    fun resolveCreatedAt(backupCreatedAt: Long, importMillis: Long): Long =
        backupCreatedAt.takeIf { it > 0L } ?: importMillis

    /**
     * 集合字段是否写回。
     *
     * v3+ 备份一定显式编码（空集 = 用户的明确快照）→ 写回；
     * 老备份无该键时解码为空集 → 视为「未携带」跳过，非空集则仍按显式携带处理。
     */
    fun shouldRestoreSet(values: Set<String>, carriesProfileSnapshot: Boolean): Boolean =
        carriesProfileSnapshot || values.isNotEmpty()
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
    createdAt = createdAt,
)

@Suppress("DEPRECATION")
private fun ExerciseBackup.toEntity(importMillis: Long): ExerciseEntity {
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
        createdAt = BackupRestoreRules.resolveCreatedAt(createdAt, importMillis),
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
    weekStartEpochDay = weekStartEpochDay,
    createdAt = createdAt,
)

private fun WeekPlanBackup.toEntity(importMillis: Long): WeekPlanEntity = WeekPlanEntity(
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
    weekStartEpochDay = weekStartEpochDay,
    createdAt = BackupRestoreRules.resolveCreatedAt(createdAt, importMillis),
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
    createdAt = createdAt,
)

private fun CheckInBackup.toEntity(importMillis: Long): CheckInEntity {
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
        createdAt = BackupRestoreRules.resolveCreatedAt(createdAt, importMillis),
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
    createdAt = createdAt,
)

private fun HabitBackup.toEntity(importMillis: Long): HabitEntity = HabitEntity(
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
    createdAt = BackupRestoreRules.resolveCreatedAt(createdAt, importMillis),
)

private fun HabitLogEntity.toBackup(): HabitLogBackup = HabitLogBackup(
    id = id,
    habitId = habitId,
    dateEpochDay = dateEpochDay,
    dateStartMillis = dateStartMillis,
    isCompleted = isCompleted,
    note = note,
    loggedAtMillis = loggedAtMillis,
    createdAt = createdAt,
)

private fun HabitLogBackup.toEntity(importMillis: Long): HabitLogEntity = HabitLogEntity(
    id = id,
    habitId = habitId,
    dateEpochDay = dateEpochDay,
    dateStartMillis = dateStartMillis,
    isCompleted = isCompleted,
    note = note,
    loggedAtMillis = loggedAtMillis,
    createdAt = BackupRestoreRules.resolveCreatedAt(createdAt, importMillis),
)

private fun BodyMetricEntity.toBackup(): BodyMetricBackup = BodyMetricBackup(
    id = id,
    type = type.name,
    value = value,
    unit = unit,
    dateEpochDay = dateEpochDay,
    dateStartMillis = dateStartMillis,
    note = note,
    createdAt = createdAt,
)

private fun BodyMetricBackup.toEntity(importMillis: Long): BodyMetricEntity = BodyMetricEntity(
    id = id,
    type = parseMetricType(type),
    value = value,
    unit = unit,
    dateEpochDay = dateEpochDay,
    dateStartMillis = dateStartMillis,
    note = note,
    createdAt = BackupRestoreRules.resolveCreatedAt(createdAt, importMillis),
)

/** 设置 + 用户档案 + AI 联网开关 → 备份快照（枚举一律存 `name`，与 [SettingsDataStore] 同口径）。 */
private fun AppSettings.toBackup(
    profile: UserProfile,
    aiRemoteEnabled: Boolean,
): SettingsBackup = SettingsBackup(
    themeMode = themeMode.name,
    unitSystem = unitSystem.name,
    reminderEnabled = reminderEnabled,
    reminderHour = reminderHour,
    reminderMinute = reminderMinute,
    gender = profile.gender?.name,
    age = profile.age,
    heightCm = profile.heightCm,
    bodyFatPct = profile.bodyFatPct,
    goal = profile.goal.name,
    goalWeightKg = profile.goalWeightKg,
    equipment = encodeEnumSet(profile.equipment),
    injuryAreas = encodeEnumSet(profile.injuryAreas),
    injuryNote = profile.injuryNote,
    dietaryAvoid = encodeEnumSet(profile.dietaryAvoid),
    aiRemoteEnabled = aiRemoteEnabled,
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
