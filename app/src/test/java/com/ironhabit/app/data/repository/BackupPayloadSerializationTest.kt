package com.ironhabit.app.data.repository

import com.ironhabit.app.domain.model.BackupPayload
import com.ironhabit.app.domain.model.BodyMetricBackup
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.CheckInBackup
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.ExerciseBackup
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.HabitBackup
import com.ironhabit.app.domain.model.HabitFrequency
import com.ironhabit.app.domain.model.HabitLogBackup
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.SettingsBackup
import com.ironhabit.app.domain.model.ThemeMode
import com.ironhabit.app.domain.model.UnitSystem
import com.ironhabit.app.domain.model.WeekPlanBackup
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `v3` 备份 JSON 的**纯 JVM 序列化契约**（零 Android / Room / DataStore 依赖）。
 *
 * 覆盖两个方向：
 * 1. **往返保真**：档案 11 项 + 6 张表的 `createdAt` 编码后解码必须逐项不丢
 *    （修复「备份静默丢档案」「恢复把 `createdAt` 抹成 0」）。
 * 2. **向后兼容**：手写 v2 形状 JSON（无档案字段、无 `createdAt`）必须**正常解码**并落到默认值，
 *    且 `schemaVersion = 2` 仍被版本检查接受。
 *
 * ⚠️ 这里的 [jsonCodec] 刻意与 `BackupRepositoryImpl` 内的私有 `jsonCodec` 保持**同一份配置**
 * （`prettyPrint` / `ignoreUnknownKeys` / `encodeDefaults` / `explicitNulls`），
 * 否则测出来的行为与真实导出 / 导入不一致。
 */
class BackupPayloadSerializationTest {

    private val jsonCodec: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    // ---------------- 1. 往返保真 ----------------

    @Test
    fun roundTripPreservesProfileAndAllCreatedAt() {
        val payload = fullPayload()

        val restored = jsonCodec.decodeFromString(
            BackupPayload.serializer(),
            jsonCodec.encodeToString(BackupPayload.serializer(), payload),
        )

        // 整体相等即覆盖全部字段（data class 结构相等），失败时便于直接 diff。
        assertEquals("整份 v3 备份必须无损往返", payload, restored)
        assertEquals("appVersion 必须原样往返（不再是硬编码 1.0）", "2.0", restored.appVersion)

        // 档案 11 项逐项断言：这是 bug (a) 的直接回归守卫。
        val settings = restored.settings
        assertEquals(Gender.MALE.name, settings.gender)
        assertEquals(32, settings.age)
        assertEquals(178, settings.heightCm)
        assertEquals(18.5f, settings.bodyFatPct)
        assertEquals(Goal.CUT.name, settings.goal)
        assertEquals(72.5f, settings.goalWeightKg)
        assertEquals(setOf(Equipment.DUMBBELL.name, Equipment.YOGA_MAT.name), settings.equipment)
        assertEquals(setOf(InjuryArea.KNEE.name), settings.injuryAreas)
        assertEquals("左膝旧伤，避免深蹲", settings.injuryNote)
        assertEquals(setOf(DietRestriction.PEANUT.name), settings.dietaryAvoid)
        assertTrue("AI 联网开关必须随备份往返", settings.aiRemoteEnabled)

        // 6 张表的 createdAt 逐项断言：这是 bug (b) 的直接回归守卫。
        assertEquals(1_690_000_000_001L, restored.exercises.single().createdAt)
        assertEquals(1_690_000_000_002L, restored.weekPlans.single().createdAt)
        assertEquals(1_690_000_000_003L, restored.checkIns.single().createdAt)
        assertEquals(1_690_000_000_004L, restored.habits.single().createdAt)
        assertEquals(1_690_000_000_005L, restored.habitLogs.single().createdAt)
        assertEquals(1_690_000_000_006L, restored.bodyMetrics.single().createdAt)
    }

    @Test
    fun exportedJsonActuallyCarriesNewKeys() {
        // 只做「键确实写进 JSON」的字符串级守卫：漏写会让 defaultValue 掩盖丢字段问题。
        val json = jsonCodec.encodeToString(BackupPayload.serializer(), fullPayload())

        listOf(
            "\"gender\"",
            "\"age\"",
            "\"heightCm\"",
            "\"bodyFatPct\"",
            "\"goal\"",
            "\"goalWeightKg\"",
            "\"equipment\"",
            "\"injuryAreas\"",
            "\"injuryNote\"",
            "\"dietaryAvoid\"",
            "\"aiRemoteEnabled\"",
            "\"createdAt\"",
        ).forEach { key ->
            assertTrue("v3 导出 JSON 必须显式包含 $key（encodeDefaults 已开）", json.contains(key))
        }
        assertTrue("导出的 schemaVersion 必须是 3", json.contains("\"schemaVersion\": 3"))
    }

    // ---------------- 2. 向后兼容（v2 老备份） ----------------

    @Test
    fun v2PayloadStillDecodesWithDefaults() {
        val payload = jsonCodec.decodeFromString(BackupPayload.serializer(), V2_JSON)

        assertEquals("老备份的 schemaVersion 必须原样保留", 2, payload.schemaVersion)
        assertEquals("v2 备份的 appVersion 原样保留", "1.0", payload.appVersion)

        // 档案字段全部落到默认值（老备份没有这些键，绝不能抛异常）。
        val settings = payload.settings
        assertNull(settings.gender)
        assertNull(settings.age)
        assertNull(settings.heightCm)
        assertNull(settings.bodyFatPct)
        assertNull(settings.goal)
        assertNull(settings.goalWeightKg)
        assertEquals(emptySet<String>(), settings.equipment)
        assertEquals(emptySet<String>(), settings.injuryAreas)
        assertNull(settings.injuryNote)
        assertEquals(emptySet<String>(), settings.dietaryAvoid)
        assertEquals("v2 无该键 → 默认 false", false, settings.aiRemoteEnabled)

        // 老备份的主题/单位/提醒仍按原键还原。
        assertEquals(ThemeMode.DARK.name, settings.themeMode)
        assertEquals(UnitSystem.METRIC.name, settings.unitSystem)
        assertEquals(20, settings.reminderHour)
        assertEquals(30, settings.reminderMinute)

        // createdAt 缺失 → 解码为 0，交由 data 层回落导入时刻。
        assertEquals(0L, payload.exercises.single().createdAt)
        assertEquals(0L, payload.weekPlans.single().createdAt)
        assertEquals(0L, payload.checkIns.single().createdAt)
        assertEquals(0L, payload.habits.single().createdAt)
        assertEquals(0L, payload.habitLogs.single().createdAt)
        assertEquals(0L, payload.bodyMetrics.single().createdAt)
    }

    /**
     * 版本检查的口径守卫：与 `BackupRepositoryImpl.import` 内的
     * `require(schemaVersion <= CURRENT_SCHEMA_VERSION)` 同一表达式。
     */
    @Test
    fun v2SchemaVersionIsStillAcceptedByTheVersionCheck() {
        val payload = jsonCodec.decodeFromString(BackupPayload.serializer(), V2_JSON)

        assertTrue(
            "v2 备份必须仍被接受（require(schemaVersion <= CURRENT_SCHEMA_VERSION)）",
            payload.schemaVersion <= BackupPayload.CURRENT_SCHEMA_VERSION,
        )
        assertEquals(3, BackupPayload.CURRENT_SCHEMA_VERSION)
        assertTrue(
            "v2 备份不携带档案快照 → 恢复时必须整体跳过档案字段，避免覆盖本地档案",
            !BackupRestoreRules.carriesProfileSnapshot(payload.schemaVersion),
        )
    }

    @Test
    fun unknownKeysAreIgnoredForForwardCompatibility() {
        // ignoreUnknownKeys = true：未来版本新增字段的备份仍可被当前版本读出已知部分。
        val json = """{"schemaVersion":2,"brandNewField":123,"settings":{"themeMode":"LIGHT"}}"""

        val payload = jsonCodec.decodeFromString(BackupPayload.serializer(), json)

        assertEquals(ThemeMode.LIGHT.name, payload.settings.themeMode)
        assertEquals(0L, payload.exportedAt)
    }

    // ---------------- 夹具 ----------------

    /** v3 全量夹具：档案 11 项 + 6 张表各一行、`createdAt` 各不相同（便于逐项断言）。 */
    private fun fullPayload(): BackupPayload = BackupPayload(
        schemaVersion = BackupPayload.CURRENT_SCHEMA_VERSION,
        exportedAt = 1_700_000_000_000L,
        appVersion = "2.0",
        exercises = listOf(
            ExerciseBackup(
                id = 1L,
                name = "深蹲",
                category = ExerciseCategory.BODYWEIGHT.name,
                muscleGroup = "腿部",
                createdAt = 1_690_000_000_001L,
            ),
        ),
        weekPlans = listOf(
            WeekPlanBackup(id = 1L, exerciseId = 1L, createdAt = 1_690_000_000_002L),
        ),
        checkIns = listOf(
            CheckInBackup(id = 1L, exerciseId = 1L, createdAt = 1_690_000_000_003L),
        ),
        habits = listOf(
            HabitBackup(
                id = 1L,
                name = "喝水",
                frequency = HabitFrequency.DAILY.name,
                createdAt = 1_690_000_000_004L,
            ),
        ),
        habitLogs = listOf(
            HabitLogBackup(id = 1L, habitId = 1L, createdAt = 1_690_000_000_005L),
        ),
        bodyMetrics = listOf(
            BodyMetricBackup(
                id = 1L,
                type = BodyMetricType.WEIGHT.name,
                createdAt = 1_690_000_000_006L,
            ),
        ),
        settings = SettingsBackup(
            themeMode = ThemeMode.DARK.name,
            unitSystem = UnitSystem.IMPERIAL.name,
            reminderEnabled = true,
            reminderHour = 7,
            reminderMinute = 15,
            gender = Gender.MALE.name,
            age = 32,
            heightCm = 178,
            bodyFatPct = 18.5f,
            goal = Goal.CUT.name,
            goalWeightKg = 72.5f,
            equipment = setOf(Equipment.DUMBBELL.name, Equipment.YOGA_MAT.name),
            injuryAreas = setOf(InjuryArea.KNEE.name),
            injuryNote = "左膝旧伤，避免深蹲",
            dietaryAvoid = setOf(DietRestriction.PEANUT.name),
            aiRemoteEnabled = true,
        ),
    )

    private companion object {
        /**
         * 手写 **v2 形状**的备份（与 v2 版本 App 的真实导出一致）：
         * `settings` 只有 5 个旧键、6 张表都没有 `createdAt`。
         * 刻意不依赖生产代码生成，避免「用被测代码造夹具」导致测试自证。
         */
        val V2_JSON: String = """
            {
              "schemaVersion": 2,
              "exportedAt": 1700000000000,
              "appVersion": "1.0",
              "exercises": [
                {
                  "id": 1,
                  "name": "深蹲",
                  "category": "BODYWEIGHT",
                  "muscleGroup": "腿部",
                  "source": "CUSTOM",
                  "note": null,
                  "isBuiltIn": false,
                  "isActive": true,
                  "defaultSets": 3,
                  "defaultReps": 12,
                  "defaultDurationSec": null,
                  "sortOrder": 0,
                  "timesUsed": 5
                }
              ],
              "weekPlans": [
                {
                  "id": 1,
                  "exerciseId": 1,
                  "dayOfWeek": 1,
                  "targetSets": 3,
                  "targetReps": 12,
                  "targetWeightKg": null,
                  "targetDurationMin": null,
                  "sortOrder": 0,
                  "isActive": true,
                  "isUserEdited": false
                }
              ],
              "checkIns": [
                {
                  "id": 1,
                  "exerciseId": 1,
                  "planId": null,
                  "dateEpochDay": 19000,
                  "dateStartMillis": 0,
                  "completedSets": 3,
                  "completedSetsMask": 7,
                  "rpe": 8,
                  "completedReps": 12,
                  "weightKg": null,
                  "durationMinutes": null,
                  "notes": null,
                  "isQuick": false,
                  "loggedAtMillis": 1700000000000
                }
              ],
              "habits": [
                {
                  "id": 1,
                  "name": "喝水",
                  "emoji": "💧",
                  "colorHex": "#2196F3",
                  "frequency": "DAILY",
                  "weeklyDaysMask": 127,
                  "reminderEnabled": false,
                  "reminderHour": null,
                  "reminderMinute": null,
                  "note": null,
                  "targetValue": null,
                  "targetUnit": null,
                  "isActive": true,
                  "sortOrder": 0
                }
              ],
              "habitLogs": [
                {
                  "id": 1,
                  "habitId": 1,
                  "dateEpochDay": 19000,
                  "dateStartMillis": 0,
                  "isCompleted": true,
                  "note": null,
                  "loggedAtMillis": 1700000000000
                }
              ],
              "bodyMetrics": [
                {
                  "id": 1,
                  "type": "WEIGHT",
                  "value": 74.5,
                  "unit": "kg",
                  "dateEpochDay": 19000,
                  "dateStartMillis": 0,
                  "note": null
                }
              ],
              "settings": {
                "themeMode": "DARK",
                "unitSystem": "METRIC",
                "reminderEnabled": true,
                "reminderHour": 20,
                "reminderMinute": 30
              }
            }
        """.trimIndent()
    }
}
