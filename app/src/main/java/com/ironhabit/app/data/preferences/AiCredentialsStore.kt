package com.ironhabit.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
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
 *    永不出现在异常文案、StringBuilder 调试串或回传值里。
 *
 * 线程模型：`EncryptedSharedPreferences` 读写即普通 SharedPreferences（主线程安全级别同 SP），
 * 写入用 `apply()`；本类方法**不做磁盘大 IO**，`setKey` 声明为 `suspend` 仅为约束调用方
 * 在协程里写（与设置页其它写入一致）。
 */
@Singleton
class AiCredentialsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 懒初始化：首次用到才建 master key（Keystore 操作较贵，且单测环境不触达）。 */
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * 读取已配置的 API Key。
     *
     * @return 未配置 / 空白 → `null`；否则返回 trim 后的 Key（**调用方不得打印或落日志**）
     */
    fun apiKey(): String? =
        prefs.getString(KEY_API_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    /** 是否已配置 Key（设置页据此展示"已配置 / 未配置"，不回传 Key 本身）。 */
    fun isConfigured(): Boolean = apiKey() != null

    /**
     * 写入 / 清除 API Key。
     *
     * @param value `null` / 空白 = 清除（用户"删除 Key"）；否则 trim 后加密落盘
     */
    suspend fun setKey(value: String?) {
        val trimmed = value?.trim()
        prefs.edit().apply {
            if (trimmed.isNullOrEmpty()) {
                remove(KEY_API_KEY)
            } else {
                putString(KEY_API_KEY, trimmed)
            }
        }.apply()
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
    }
}
