package com.ironhabit.app.domain.model

/**
 * 关于"存储本身"的事实 —— 不是业务数据，而是数据存在哪儿、以什么格式存。
 *
 * 为什么要单独放一份在 domain：「我的」页那一块「只存这台手机 · Room v9」要念这个数，
 * 而这个数以前只有 `data.local.AppDatabase.VERSION`。让 UI 去引 Room 的数据库类，
 * 等于让最上层直接看见最底层的实现；提到这里之后方向就顺了 ——
 * **domain 定常量，data 拿它声明 Room 版本，ui 拿它显示给用户**，全工程一个数。
 */
object DatabaseInfo {

    /**
     * Room schema 版本。
     *
     * ⚠️ 加一条 `Migration` 就必须同时改这里，否则 Room 打开老库时发现版本没变、
     * 不会执行迁移，直接 `IllegalStateException`。
     * 迁移清单在 `data/local/Migrations.kt`，注册在 `di/DatabaseModule.kt`。
     */
    const val SCHEMA_VERSION: Int = 9
}
