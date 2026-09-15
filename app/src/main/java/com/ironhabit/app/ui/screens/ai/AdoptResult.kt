package com.ironhabit.app.ui.screens.ai

/**
 * 同包别名：`AiCoachViewModel` 里 `AdoptResult.ADDED / ALREADY_EXISTS` 是**无 import 的裸引用**，
 * 而真身在 `domain.model`（领域层不应依赖 UI 层，故不能反过来放这里）。
 *
 * 用 `typealias` 让本包直接可见 —— **不改动** `AiCoachViewModel` / `AiCoachScreen` 一行代码。
 *
 * 后续若给 ViewModel 补上 `import com.ironhabit.app.domain.model.AdoptResult`，
 * 显式 import 与本同包别名**指向同一个类型**，二者共存无歧义，可随时删除本文件。
 *
 * ⚠️ 本文件**不是**业务代码，只是包可见性桥接；请勿在此添加任何逻辑。
 */
typealias AdoptResult = com.ironhabit.app.domain.model.AdoptResult
