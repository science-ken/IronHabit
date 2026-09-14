// =============================================================================
// IronHabit（自律健身）— 根构建脚本
// 仅声明全部插件（apply false），实际应用在 :app 模块中完成。
// 版本一律来自 gradle 版本目录（Version Catalog），禁止硬编码版本串。
// =============================================================================

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
