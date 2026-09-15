import java.util.Properties

// =============================================================================
// IronHabit（自律健身）— :app 模块构建脚本
// 包名（namespace / applicationId）：com.ironhabit.app
// release 签名：读取仓库根目录 keystore.properties（CI 中由 android-release.yml 写出；
//              本地不存在时自动回落到未签名/debug 签名，不影响 assembleDebug）
// =============================================================================

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 读取根目录的 keystore.properties（不存在则为空 Properties）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.ironhabit.app"
    // compileSdk 34 → 35。**已由真实编译验证通过**（非静态推演）：
    // ① androidx.core:core / core-ktx 1.15.0 的 AAR 元数据声明
    //    「依赖我的模块必须以 compileSdk >= 35 编译」（AGP checkDebugAarMetadata 报错原文）；
    // ② 本机工具链（JDK 17 / SDK android-35 / Gradle 8.9）实测通过：
    //    :app:checkDebugAarMetadata ✅ → :app:kspDebugKotlin ✅ → :app:compileDebugKotlin ✅
    //    → :app:packageDebug ✅ → BUILD SUCCESSFUL，
    //    产物 app/build/outputs/apk/debug/app-debug.apk（18868962 字节）。
    // 注意：compileSdk 与 targetSdk **不需要同步** —— 前者是编译期可用的 API 面，
    // 后者是对系统的运行时行为声明；本项目 targetSdk 保持 34 不变。
    // 详见 docs/ARCHITECTURE.md §3.8。
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ironhabit.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // 仅当 keystore.properties 存在且提供 storeFile 时才创建 release 签名
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 未配置签名时该值为 null，产物为未签名 APK（CI 中会显式校验）
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Room 导出 schema（exportSchema = true），纳入版本管理，便于编写 Migration
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // ---- AndroidX 基础 ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    // ---- Compose（版本由 BOM 统一）----
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // ---- Hilt（KSP 注解处理）----
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // ---- Room（KSP 注解处理）----
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ---- DataStore ----
    implementation(libs.androidx.datastore.preferences)

    // ---- 业务库（均为纯本地/离线）----
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.android)

    // ---- 调试工具 ----
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // ---- 单元测试（JVM）----
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)

    // ---- Instrumentation 测试 ----
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
