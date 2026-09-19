package com.ironhabit.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeepSeek API Key 的**加密本地存储**（联网一期，`docs/ai-coach-local.md` §6.2 N2 的选型落地）。
 *
 * ## 安全红线（每条都有对应机制，缺一不可）
 * 1. **不进 DataStore**：独立走 [EncryptedSharedPreferences]，
 *    静态加密（key 明文用 AES256_SIV、value 用 AES256_GCM，master key 存 Android Keystore）；
 * 2. **不进备份**：`res/xml/backup_rules.xml` 与 `res/xml/data_extraction_rules.xml`
 *    显式 `exclude` 本文件（[PREFS_FILE]），云备份与设备迁移都带不走；
 * 3. **不进日志 / 异常消息**：本类不打任何 log；Key 只经内存传给 HTTP header，
 *    永不出现在异常文案、StringBuilder 调试串或回传值里；
 * 4. **不降级明文**：存储不可用时**绝不**退回普通 `SharedPreferences` / DataStore，
 *    宁可"用不了"也不静默落明文（见下方崩溃防线）。
 *
 * ## ⚠️ 崩溃防线（P0-2 修复）
 * `MasterKey.Builder().build()` 在 Keystore 损坏 / 被 OEM ROM 改写 / 用户清除凭据后
 * 可能抛 `GeneralSecurityException` / `IOException`；`EncryptedSharedPreferences.create` 亦然。
 * 历史实现是 `by lazy { … }` 直接抛，而调用点（设置页 `init`、AI 页 `init`）都在**主线程同步读**
 * → 结果是一进页面就崩、且重进还是崩，用户**永远进不去设置页**，而诱因只是"没配置过 AI"。
 *
 * 现在的口径：
 * - 打开失败 → 句柄为 `null`，[isStorageAvailable] = `false`，[apiKey] = `null`，
 *   [isConfigured] = `false`（对上层等价于"未配置"，**不崩**）；
 * - [setKey] 返回 `false` 让调用方**如实提示**，而不是假报"已保存"；
 * - 失败后**不再反复重试**（Keystore 操作贵且每次都抛），只有 [resetStorage] 会清除该状态重试。
 *
 * 线程模型：读写即普通 SharedPreferences（主线程安全级别同 SP），写入用 `apply()`；
 * 本类方法**不做磁盘大 IO**，`setKey` 声明为 `suspend` 仅为约束调用方在协程里写。
 */
@Singleton
class AiCredentialsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 单测接缝：注入一个抛异常的工厂即可覆盖"存储不可用"分支（生产恒为 `null` → 走真实实现）。
     *
     * 之所以用次构造而不是构造参数默认值：Hilt 要求 `@Inject` 主构造的**每个**参数都能被注入，
     * 函数类型参数无法满足。
     */
    private var openPrefsOverride: (() -> SharedPreferences)? = null

    internal constructor(context: Context, openPrefs: () -> SharedPreferences) : this(context) {
        openPrefsOverride = openPrefs
    }

    /** 已打开的句柄；`null` = 尚未打开或打开失败。 */
    @Volatile
    private var cached: SharedPreferences? = null

    /** 曾经打开失败（不再重试，避免每次 `isConfigured()` 都触发一次昂贵的 Keystore 操作）。 */
    @Volatile
    private var openFailed: Boolean = false

    private val lock: Any = Any()

    /**
     * 懒初始化 + 失败兜底。
     *
     * @return 可用句柄；打开失败 → `null`（**不抛**）
     */
    private fun prefs(): SharedPreferences? {
        cached?.let { return it }
        if (openFailed) return null
        synchronized(lock) {
            cached?.let { return it }
            if (openFailed) return null
            val opened = runCatching { openPrefs() }.getOrNull()
            if (opened == null) {
                openFailed = true
            } else {
                cached = opened
            }
            return opened
        }
    }

    /** 加密存储是否可用（设置页据此展示"存储不可用，可重置"）。 */
    fun isStorageAvailable(): Boolean = prefs() != null

    /**
     * 读取已配置的 API Key。
     *
     * @return 未配置 / 空白 / 存储不可用 → `null`；否则返回 trim 后的 Key（**调用方不得打印或落日志**）
     */
    fun apiKey(): String? = runCatching {
        prefs()?.getString(KEY_API_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 是否已配置 Key（设置页据此展示"已配置 / 未配置"，不回传 Key 本身）。 */
    fun isConfigured(): Boolean = apiKey() != null

    /**
     * 写入 / 清除 API Key。
     *
     * @param value `null` / 空白 = 清除（用户"删除 Key"）；否则 trim 后加密落盘
     * @return `true` = 已写入；`false` = 加密存储不可用 → **调用方必须如实提示**，不得假装成功
     */
    suspend fun setKey(value: String?): Boolean {
        val handle = prefs() ?: return false
        val trimmed = value?.trim()
        return runCatching {
            handle.edit().apply {
                if (trimmed.isNullOrEmpty()) {
                    remove(KEY_API_KEY)
                } else {
                    putString(KEY_API_KEY, trimmed)
                }
            }.apply()
        }.isSuccess
    }

    /**
     * **重置加密存储**（P0-2）：删掉密文文件与 Keystore 里的 master key 别名，
     * 并清除"打开失败"状态 → 下一次访问会重新生成一套可用的密钥。
     *
     * 用途：Keystore 损坏导致 Key 存不进去 / 读不出来时，给用户一个**可自恢复**的出口
     * （否则设置页会一直停在"存储不可用"，且没有任何补救入口）。
     * 代价：已配置的 Key 会丢失，需要重新填入。
     *
     * @return `true` = 密文文件确实被删除；`false` = 文件本来就不存在（首次即打开失败的情况）。
     *   Keystore 别名清理为 best-effort：删不掉也不影响"清除失败状态 → 下次重试"这条主路径。
     */
    fun resetStorage(): Boolean {
        val fileCleared = runCatching { context.deleteSharedPreferences(PREFS_FILE) }.getOrDefault(false)
        runCatching {
            // master key 别名用 `MasterKey.Builder().build()` 的默认值（本工程未自定义别名）；
            // 别名不存在时 deleteEntry 是 no-op，故无需先查存在性。
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .deleteEntry(DEFAULT_MASTER_KEY_ALIAS)
        }
        cached = null
        openFailed = false
        return fileCleared
    }

    /** 实际的打开动作（单测可经 [openPrefsOverride] 换掉）。 */
    private fun openPrefs(): SharedPreferences =
        openPrefsOverride?.invoke() ?: openEncryptedPrefs()

    /** 真实的打开实现：Keystore 造 master key → 建加密 SharedPreferences。 */
    private fun openEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    companion object {
        /**
         * EncryptedSharedPreferences 文件名。
         *
         * ⚠️ 必须与 `res/xml/backup_rules.xml` / `res/xml/data_extraction_rules.xml`
         * 里 exclude 的 `path="ai_credentials.xml"` 保持一致（系统会自动补 `.xml` 后缀）。
         */
        const val PREFS_FILE: String = "ai_credentials"

        private const val KEY_API_KEY: String = "deepseek_api_key"

        private const val ANDROID_KEYSTORE: String = "AndroidKeyStore"

        /** `MasterKey.Builder().build()` 的默认别名（本工程未自定义 alias）。 */
        private const val DEFAULT_MASTER_KEY_ALIAS: String = "_androidx_security_master_key_"
    }
}
