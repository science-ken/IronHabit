package com.ironhabit.app.data.repository

import androidx.room.withTransaction
import com.ironhabit.app.data.local.AppDatabase
import com.ironhabit.app.data.local.dao.BodyMetricDao
import com.ironhabit.app.data.local.dao.CheckInDao
import com.ironhabit.app.data.local.dao.ExerciseDao
import com.ironhabit.app.data.local.dao.HabitDao
import com.ironhabit.app.data.local.dao.HabitLogDao
import com.ironhabit.app.data.local.dao.MealDao
import com.ironhabit.app.data.local.dao.WeekPlanDao
import com.ironhabit.app.data.local.entity.BodyMetricEntity
import com.ironhabit.app.data.local.entity.CheckInEntity
import com.ironhabit.app.data.local.entity.ExerciseEntity
import com.ironhabit.app.data.local.entity.HabitEntity
import com.ironhabit.app.data.local.entity.HabitLogEntity
import com.ironhabit.app.data.local.entity.MealEntity
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
import com.ironhabit.app.domain.model.MealBackup
import com.ironhabit.app.domain.model.ProfileLimits
import com.ironhabit.app.domain.model.SettingsBackup
import com.ironhabit.app.domain.model.ThemeMode
import com.ironhabit.app.domain.model.UnitSystem
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlanBackup
import com.ironhabit.app.domain.model.decodeEnum
import com.ironhabit.app.domain.model.decodeEnumSet
import com.ironhabit.app.domain.model.encodeEnumSet
import com.ironhabit.app.domain.repository.BackupRepository
import com.ironhabit.app.domain.repository.ReminderScheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

/**
 * [BackupRepository] 的 data 层实现。
 *
 * 纯本地文件 JSON 导出 / 导入（kotlinx.serialization），**绝不引入任何网络库**。
 * 导入为「整体替换」：在单个 Room 事务内清空 7 张表并保留原 `id` 重建，失败整体回滚。
 *
 * v3 修复：① 备份快照带上**用户档案 + AI 联网开关**（此前恢复会静默抹掉档案）；
 * ② 6 张表全部导出 / 还原真实 `createdAt`（老备份缺失 → 回落导入时刻，不再硬编码 `0L`）；
 * ③ `appVersion` 由 [AppVersion] 注入真实版本名（此前硬编码 `"1.0"`）。
 *
 * v4 修复：④ `meals` 表纳入备份（B-2：此前换机丢全部饮食记录）；
 * ⑤ `settings.trainingDaysPerWeek` 恢复（B-6：v4+ 备份才写回，老备份不动本地值）；
 * ⑥ 恢复成功后**重排全部提醒**（B-7：AlarmManager 仍按旧时间响）。
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
    private val mealDao: MealDao,
    private val settingsDataStore: SettingsDataStore,
    private val reminderScheduler: ReminderScheduler,
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
            meals = mealDao.getAll().map { it.toBackup() },
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
            // P0-1：`meals` 自备份 schema **v4** 起才导出 → 导入 v1–v3 备份时 `payload.meals`
            // 恒为空列表。若无条件「清空 + 灌空列表」，本机**全部饮食记录**会被删掉且不可撤销
            // （与本文件 B-6 的既有口径自相矛盾：老备份不该覆盖本地已有数据）。
            // 判据用**版本号**而不是 `isEmpty()`：用户确实没有饮食记录时列表同样为空，
            // 那属于"显式携带的空快照"，与"老备份根本没这个键"是两回事。
            val carriesMeals: Boolean = BackupRestoreRules.carriesMeals(payload.schemaVersion)
            if (carriesMeals) {
                mealDao.clearAll()
            }

            exerciseDao.insertAll(payload.exercises.map { it.toEntity(importMillis) })
            weekPlanDao.insertAll(payload.weekPlans.map { it.toEntity(importMillis) })
            checkInDao.insertAll(payload.checkIns.map { it.toEntity(importMillis) })
            habitDao.insertAll(payload.habits.map { it.toEntity(importMillis) })
            habitLogDao.insertAll(payload.habitLogs.map { it.toEntity(importMillis) })
            bodyMetricDao.insertAll(payload.bodyMetrics.map { it.toEntity(importMillis) })
            if (carriesMeals) {
                mealDao.insertAll(payload.meals.map { it.toEntity(importMillis) })
            }
        }

        // 数据库导入成功后同步设置快照（DataStore 不参与 Room 事务）。
        applySettings(payload.settings, payload.schemaVersion)

        // B-7：设置（提醒时间/开关）与习惯提醒可能都变了 → 重排全部 AlarmManager 闹钟。
        // 必须放在事务与设置写回**都成功之后**：此时 DataStore 里才是最终生效的提醒配置。
        reminderScheduler.rescheduleAll()
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
        // B-6：v4+ 备份才显式携带「每周训练天数」（v1–v3 解码即默认 3，写回会覆盖本地已选值）。
        if (BackupRestoreRules.carriesTrainingDaysPerWeek(schemaVersion)) {
            settingsDataStore.setProfileTrainingDaysPerWeek(settings.trainingDaysPerWeek)
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

    /** 备份自 v4 起携带「每周训练天数」（`settings.trainingDaysPerWeek`，B-6）。 */
    const val TRAINING_DAYS_SCHEMA_VERSION: Int = 4

    /**
     * 备份是否携带档案快照。
     *
     * v3+ 的备份对档案字段「照写」（含空集 / `null` 的明确语义）；
     * 更老的备份完全没有这些键 → 恢复时整体跳过，避免用缺失值覆盖本地档案。
     */
    fun carriesProfileSnapshot(schemaVersion: Int): Boolean =
        schemaVersion >= PROFILE_SNAPSHOT_SCHEMA_VERSION

    /**
     * 备份是否显式携带「每周训练天数」。
     *
     * v4+ 一定显式编码（`encodeDefaults = true`）→ 照写；v1–v3 无该键（解码即默认 3）
     * → **不写回**，避免把用户本地选的 4/5/6 天覆盖回默认 3 天（B-6）。
     */
    fun carriesTrainingDaysPerWeek(schemaVersion: Int): Boolean =
        schemaVersion >= TRAINING_DAYS_SCHEMA_VERSION

    /** 备份自 v4 起携带 `meals` 表（B-2 饮食模块）。 */
    const val MEALS_SCHEMA_VERSION: Int = 4

    /**
     * 备份是否携带 `meals` 表。
     *
     * v4+ 一定显式编码整表（空列表 = "用户确实没有饮食记录"）→ 照写；
     * v1–v3 完全没有该键（解码即空列表）→ **本机 `meals` 一行都不动**，
     * 否则"导入老备份"会静默清空本机全部饮食历史（P0-1）。
     */
    fun carriesMeals(schemaVersion: Int): Boolean =
        schemaVersion >= MEALS_SCHEMA_VERSION

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
    equipment = equipment,
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
        equipment = equipment,
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

private fun MealEntity.toBackup(): MealBackup = MealBackup(
    id = id,
    dateEpochDay = dateEpochDay,
    mealType = mealType,
    itemsText = itemsText,
    kcal = kcal,
    proteinG = proteinG,
    isCompleted = isCompleted,
    sortOrder = sortOrder,
    isActive = isActive,
    isUserEdited = isUserEdited,
    createdAt = createdAt,
)

private fun MealBackup.toEntity(importMillis: Long): MealEntity = MealEntity(
    id = id,
    dateEpochDay = dateEpochDay,
    mealType = mealType,
    itemsText = itemsText,
    kcal = kcal,
    proteinG = proteinG,
    isCompleted = isCompleted,
    sortOrder = sortOrder,
    isActive = isActive,
    isUserEdited = isUserEdited,
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
    trainingDaysPerWeek = ProfileLimits.coerceTrainingDaysPerWeek(profile.trainingDaysPerWeek),
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
