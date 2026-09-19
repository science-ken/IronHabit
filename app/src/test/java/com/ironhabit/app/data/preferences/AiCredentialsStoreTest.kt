package com.ironhabit.app.data.preferences

import android.content.Context
import io.mockk.mockk
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AiCredentialsStore] **崩溃防线**单测（P0-2）。
 *
 * 背景：`MasterKey.Builder().build()` / `EncryptedSharedPreferences.create` 在 Keystore 损坏、
 * 被 OEM ROM 改写、用户清除凭据后可能抛 `GeneralSecurityException` / `IOException`。
 * 历史实现把初始化写成 `by lazy { … }` 直接抛，而调用点都在**主线程同步读**
 * （`SettingsViewModel.init`、`AiCoachViewModel.init`）→ 一进设置页就崩、重进还是崩。
 *
 * 本测试通过次构造注入"打开必炸"的工厂来覆盖这条路径（不需要 Robolectric / 真实 Keystore）：
 * 断言点是**对外契约**——失败等价于"未配置"，而不是抛异常。
 */
class AiCredentialsStoreTest {

    private val context: Context = mockk(relaxed = true)

    /** 造一个"打开加密存储必失败"的实例（生产路径恒为真实实现，此接缝只服务本测试）。 */
    private fun brokenStore(error: Throwable, attempts: (() -> Unit)? = null) =
        AiCredentialsStore(context) {
            attempts?.invoke()
            throw error
        }

    @Test
    fun masterKeyFailure_isReportedAsUnavailable_insteadOfCrashing() {
        val store = brokenStore(GeneralSecurityException("keystore 损坏"))

        assertFalse("存储不可用要能如实上报（UI 据此给「重置加密存储」出口）", store.isStorageAvailable())
        assertNull("读不到 Key 时返回 null，而不是抛异常", store.apiKey())
        assertFalse("对上层等价于「未配置」", store.isConfigured())
    }

    @Test
    fun ioFailureOnCreate_isAlsoSwallowed() {
        val store = brokenStore(IOException("加密文件无法创建"))

        assertFalse(store.isStorageAvailable())
        assertFalse(store.isConfigured())
    }

    @Test
    fun setKeyOnBrokenStorage_returnsFalse_neverPretendsSuccess() = runTest {
        val store = brokenStore(GeneralSecurityException("boom"))

        assertFalse(
            "存不进时必须返回 false —— 否则设置页会假报「已保存」，用户以为配好了",
            store.setKey("sk-1234567890abcdef"),
        )
        assertFalse("清除 Key 同样要如实返回 false", store.setKey(null))
    }

    @Test
    fun failedOpen_isNotRetriedOnEveryRead() {
        var attempts = 0
        val store = brokenStore(GeneralSecurityException("boom")) { attempts += 1 }

        repeat(5) {
            store.apiKey()
            store.isConfigured()
        }

        assertEquals(
            "打开失败后必须记住失败：Keystore 操作昂贵且每次都抛，不能每次读取都重试一遍",
            1,
            attempts,
        )
    }

    @Test
    fun resetStorage_clearsFailureState_soNextReadRetries() {
        var attempts = 0
        val store = brokenStore(GeneralSecurityException("boom")) { attempts += 1 }

        store.isConfigured()
        assertEquals(1, attempts)

        store.resetStorage()
        store.isConfigured()

        assertEquals(
            "重置后必须允许重新尝试打开（这是用户唯一的自恢复出口）",
            2,
            attempts,
        )
    }

    @Test
    fun resetStorage_survivesMissingKeystore() {
        var attempts = 0
        val store = brokenStore(GeneralSecurityException("boom")) { attempts += 1 }

        // 单测环境没有 AndroidKeyStore provider → 内部 KeyStore 清理必然失败，
        // 但**不得**因此抛异常（Keystore 清理是 best-effort，主路径是清失败状态后重试）。
        store.resetStorage()

        assertFalse("清理失败也要如实保持「不可用」，不能假装可用", store.isStorageAvailable())
        assertEquals("清理后应允许重试一次打开", 1, attempts)
    }
}
