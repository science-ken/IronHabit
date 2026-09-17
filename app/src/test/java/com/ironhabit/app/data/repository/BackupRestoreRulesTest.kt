package com.ironhabit.app.data.repository

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
 * `toEntity(importMillis)` 也逐个调用它，所以规则本身是生产路径上的同一份实现。
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
}
