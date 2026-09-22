package com.ironhabit.app.data.repository

import com.ironhabit.app.domain.model.BackupPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BackupRestoreRules] 纯 JVM 单测 —— 覆盖「备份 → 实体」两条回落规则。
 *
 * ⚠️ `BackupRepositoryImpl` 自身需要 `AppDatabase` / `SettingsDataStore`（Room + Context + Hilt）
 * 才能构造，无法在纯 JVM 单测里实例化；因此把与 Android 无关的**映射规则**抽成同文件的
 * `internal object BackupRestoreRules`，此处直接测该对象。**仓库对外 API 未改动**，
 * `toEntity(createdAtFallback)` 也逐个调用它，所以规则本身是生产路径上的同一份实现。
 */
class BackupRestoreRulesTest {

    // ---------------- createdAt：老备份缺失 → 回落导入时刻（bug b）----------------

    @Test
    fun missingCreatedAtFallsBackToImportMillis() {
        val importMillis = 1_700_000_000_000L

        assertEquals(
            "老备份（v1/v2）无 createdAt → 解码为 0 → 必须回落导入时刻，而不是硬编码 0",
            importMillis,
            BackupRestoreRules.resolveCreatedAt(0L, importMillis),
        )
    }

    @Test
    fun existingCreatedAtIsPreservedAsIs() {
        val importMillis = 1_700_000_000_000L
        val backupCreatedAt = 1_690_000_000_000L

        assertEquals(
            "v3 备份携带的 createdAt 必须原样透传（保住 ORDER BY created_at DESC 的历史排序）",
            backupCreatedAt,
            BackupRestoreRules.resolveCreatedAt(backupCreatedAt, importMillis),
        )
    }

    /** 防御性：负数不是合法时间戳，按「缺失」处理，避免写出负数 created_at。 */
    @Test
    fun nonPositiveCreatedAtIsTreatedAsMissing() {
        val importMillis = 1_700_000_000_000L

        assertEquals(importMillis, BackupRestoreRules.resolveCreatedAt(-1L, importMillis))
    }

    /**
     * D16：v3+ 的 `0` 是**合法值**（播种的内置食物、老计划行本来就没有创建时刻）。
     * 拿导入时刻去盖它，等于"导出自己的备份再原样导回来"也会改写数据 ——
     * 2026-09-22 真机一次往返改掉了 50 行（`foods` 29 / `week_plans` 17 / `exercises` 4）。
     */
    @Test
    fun currentBackupsNeverRewriteCreatedAt() {
        val importMillis = 1_700_000_000_000L

        assertEquals(
            "v3 起兜底恒为 0",
            0L,
            BackupRestoreRules.createdAtFallback(3, importMillis),
        )
        assertEquals(
            0L,
            BackupRestoreRules.createdAtFallback(BackupPayload.CURRENT_SCHEMA_VERSION, importMillis),
        )
        assertEquals(
            "v5 备份里 created_at=0 的行，导入之后仍然是 0",
            0L,
            BackupRestoreRules.resolveCreatedAt(
                0L,
                BackupRestoreRules.createdAtFallback(5, importMillis),
            ),
        )
    }

    @Test
    fun legacyBackupsStillFallBackToImportMillis() {
        val importMillis = 1_700_000_000_000L

        assertEquals(importMillis, BackupRestoreRules.createdAtFallback(1, importMillis))
        assertEquals(importMillis, BackupRestoreRules.createdAtFallback(2, importMillis))
        assertEquals(
            "v1/v2 压根没有这个键 → 必须回落，否则整表都没有创建时刻、历史排序会乱",
            importMillis,
            BackupRestoreRules.resolveCreatedAt(
                0L,
                BackupRestoreRules.createdAtFallback(2, importMillis),
            ),
        )
        assertEquals(
            "createdAt 与档案快照同为 v3 引入 —— 两个起始版本若漂移，说明有人只改了其中一处",
            BackupRestoreRules.PROFILE_SNAPSHOT_SCHEMA_VERSION,
            BackupRestoreRules.CREATED_AT_SCHEMA_VERSION,
        )
    }

    // ---------------- 档案携带判定：v2 不携带 → 恢复时跳过（bug a）----------------

    @Test
    fun legacySchemaVersionsDoNotCarryProfileSnapshot() {
        assertFalse("v1 备份没有档案字段", BackupRestoreRules.carriesProfileSnapshot(1))
        assertFalse("v2 备份没有档案字段", BackupRestoreRules.carriesProfileSnapshot(2))
        assertFalse(
            "0 等异常版本同样按「不携带」处理（偏保守，绝不覆盖本地档案）",
            BackupRestoreRules.carriesProfileSnapshot(0),
        )
    }

    @Test
    fun currentSchemaVersionCarriesProfileSnapshot() {
        assertTrue("v3 起备份携带档案快照", BackupRestoreRules.carriesProfileSnapshot(3))
        assertTrue(
            "更高版本沿用同一契约（导入前已被 require 拦住，这里只保证判定单调）",
            BackupRestoreRules.carriesProfileSnapshot(4),
        )
        // 每个快照字段各自的起始版本是独立契约（CURRENT 会随版本演进，不能与它强绑）：
        assertEquals("档案快照自 v3 起", 3, BackupRestoreRules.PROFILE_SNAPSHOT_SCHEMA_VERSION)
        assertEquals(
            "训练天数快照自 v4 起（v4 才引入 trainingDaysPerWeek）",
            4,
            BackupRestoreRules.TRAINING_DAYS_SCHEMA_VERSION,
        )
        assertTrue(
            "训练天数起始版本不得早于档案快照（否则老备份会被误判携带天数）",
            BackupRestoreRules.TRAINING_DAYS_SCHEMA_VERSION >= BackupRestoreRules.PROFILE_SNAPSHOT_SCHEMA_VERSION,
        )
    }

    // ---------------- 集合字段「缺失 vs 空集」判定 ----------------

    @Test
    fun emptySetIsSkippedForLegacyBackup() {
        assertFalse(
            "v2 老备份没有该键（解码为空集）→ 视为「未携带」，跳过以免清空本地已选器械",
            BackupRestoreRules.shouldRestoreSet(emptySet(), carriesProfileSnapshot = false),
        )
    }

    @Test
    fun nonEmptySetIsRestoredEvenForLegacyBackup() {
        assertTrue(
            "老备份里若确有非空集合（人为改写）→ 仍按显式携带写回",
            BackupRestoreRules.shouldRestoreSet(setOf("DUMBBELL"), carriesProfileSnapshot = false),
        )
    }

    @Test
    fun emptySetIsRestoredForCurrentBackup() {
        assertTrue(
            "v3 备份一定显式编码该键（encodeDefaults = true）→ 空集是用户的明确快照，照写",
            BackupRestoreRules.shouldRestoreSet(emptySet(), carriesProfileSnapshot = true),
        )
        assertTrue(
            BackupRestoreRules.shouldRestoreSet(setOf("KNEE"), carriesProfileSnapshot = true),
        )
    }

    // ---------------- P0-1：meals 表携带判定（老备份绝不覆盖本机饮食记录）----------------

    @Test
    fun legacySchemaVersionsDoNotCarryMeals() {
        assertFalse("v1 备份没有 meals 键", BackupRestoreRules.carriesMeals(1))
        assertFalse("v2 备份没有 meals 键", BackupRestoreRules.carriesMeals(2))
        assertFalse(
            "v3 备份仍没有 meals 键 —— 若无条件 clearAll + 灌空列表，本机饮食历史会被删干净",
            BackupRestoreRules.carriesMeals(3),
        )
        assertFalse(
            "0 等异常版本同样按「不携带」处理（偏保守，绝不覆盖本地饮食记录）",
            BackupRestoreRules.carriesMeals(0),
        )
    }

    @Test
    fun currentSchemaVersionCarriesMeals() {
        assertTrue("v4 起 meals 纳入备份", BackupRestoreRules.carriesMeals(4))
        assertTrue(
            "更高版本沿用同一契约（导入前已被 require 拦住，这里只保证判定单调）",
            BackupRestoreRules.carriesMeals(5),
        )
        assertEquals("meals 快照自 v4 起", 4, BackupRestoreRules.MEALS_SCHEMA_VERSION)
        assertEquals(
            "meals 与「每周训练天数」同属 v4（BackupPayload 的版本说明即如此定义）—— " +
                "两者起始版本若漂移，说明有人只改了其中一处",
            BackupRestoreRules.TRAINING_DAYS_SCHEMA_VERSION,
            BackupRestoreRules.MEALS_SCHEMA_VERSION,
        )
    }

    // ---------------- P0-1 的第二张多米诺：v4 备份会靠级联删掉本机明细 ----------------

    /**
     * `meal_items.meal_id` 对 `meals` 是 `ON DELETE CASCADE`（真机 schema v9 实测：
     * `DELETE FROM meals` 之后 `meal_items` 归零）。所以"导入 v4 备份时只替换 meals、
     * 不碰明细"这件事**在物理上做不到** —— 清空 `meals` 本身就是删明细。
     *
     * 这条测试守的是：v4 及更早的备份，饮食四张表**一张都不许换**。
     */
    @Test
    fun v4AndOlderBackupsMustNotReplaceDietTables() {
        assertFalse(BackupRestoreRules.replacesDietTables(0))
        assertFalse(BackupRestoreRules.replacesDietTables(1))
        assertFalse(BackupRestoreRules.replacesDietTables(2))
        assertFalse(BackupRestoreRules.replacesDietTables(3))
        assertFalse(
            "v4 携带 meals 但**不**携带 meal_items → 清 meals 会级联清掉本机明细",
            BackupRestoreRules.replacesDietTables(4),
        )
    }

    @Test
    fun v5BackupsReplaceDietTablesAsAGroup() {
        assertTrue("v5 起 meals + foods + servings + meal_items 一起换", BackupRestoreRules.replacesDietTables(5))
        assertTrue("更高版本沿用同一契约（判定单调）", BackupRestoreRules.replacesDietTables(6))
        assertEquals("饮食明细自 v5 起纳入备份", 5, BackupRestoreRules.DIET_DETAIL_SCHEMA_VERSION)
        assertTrue(
            "携带明细必然也携带 meals：两个判据若脱钩，说明有人单独放宽了其中一个",
            BackupRestoreRules.replacesDietTables(5) ==
                (BackupRestoreRules.carriesMeals(5) && BackupRestoreRules.carriesDietDetail(5)),
        )
    }

    /** 抬版本的人必须同时想到"旧备份从此不再替换饮食表"这条后果。 */
    @Test
    fun dietDetailGateIsStrictlyNewerThanMealsGate() {
        assertTrue(
            "明细的起始版本必须**晚于** meals —— 否则 replacesDietTables 与 carriesMeals 同值，" +
                "那条级联红线就没被任何判据挡住",
            BackupRestoreRules.DIET_DETAIL_SCHEMA_VERSION > BackupRestoreRules.MEALS_SCHEMA_VERSION,
        )
        assertTrue(
            "当前版本必须带明细，否则导出的备份自己就导不回去",
            BackupRestoreRules.replacesDietTables(BackupPayload.CURRENT_SCHEMA_VERSION),
        )
    }
}
