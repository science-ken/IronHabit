import java.util.Properties

// =============================================================================
// IronHabit锛堣嚜寰嬪仴韬級鈥?:app 妯″潡鏋勫缓鑴氭湰
// 鍖呭悕锛坣amespace / applicationId锛夛細com.ironhabit.app
// release 绛惧悕锛氳鍙栦粨搴撴牴鐩綍 keystore.properties锛圕I 涓敱 android-release.yml 鍐欏嚭锛?
//              鏈湴涓嶅瓨鍦ㄦ椂鑷姩鍥炶惤鍒版湭绛惧悕/debug 绛惧悕锛屼笉褰卞搷 assembleDebug锛?
// =============================================================================

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 璇诲彇鏍圭洰褰曠殑 keystore.properties锛堜笉瀛樺湪鍒欎负绌?Properties锛?
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.ironhabit.app"
    // compileSdk 34 鈫?35銆?*宸茬敱鐪熷疄缂栬瘧楠岃瘉閫氳繃**锛堥潪闈欐€佹帹婕旓級锛?
    // 鈶?androidx.core:core / core-ktx 1.15.0 鐨?AAR 鍏冩暟鎹０鏄?
    //    銆屼緷璧栨垜鐨勬ā鍧楀繀椤讳互 compileSdk >= 35 缂栬瘧銆嶏紙AGP checkDebugAarMetadata 鎶ラ敊鍘熸枃锛夛紱
    // 鈶?鏈満宸ュ叿閾撅紙JDK 17 / SDK android-35 / Gradle 8.9锛夊疄娴嬮€氳繃锛?
    //    :app:checkDebugAarMetadata 鉁?鈫?:app:kspDebugKotlin 鉁?鈫?:app:compileDebugKotlin 鉁?
    //    鈫?:app:packageDebug 鉁?鈫?BUILD SUCCESSFUL锛?
    //    浜х墿 app/build/outputs/apk/debug/app-debug.apk锛?8868962 瀛楄妭锛夈€?
    // 娉ㄦ剰锛歝ompileSdk 涓?targetSdk **涓嶉渶瑕佸悓姝?* 鈥斺€?鍓嶈€呮槸缂栬瘧鏈熷彲鐢ㄧ殑 API 闈紝
    // 鍚庤€呮槸瀵圭郴缁熺殑杩愯鏃惰涓哄０鏄庯紱鏈」鐩?targetSdk 淇濇寔 34 涓嶅彉銆?
    // 璇﹁ docs/ARCHITECTURE.md 搂3.8銆?
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ironhabit.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 22
        versionName = "2.0.11"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // 浠呭綋 keystore.properties 瀛樺湪涓旀彁渚?storeFile 鏃舵墠鍒涘缓 release 绛惧悕
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
            // 鏈厤缃鍚嶆椂璇ュ€间负 null锛屼骇鐗╀负鏈鍚?APK锛圕I 涓細鏄惧紡鏍￠獙锛?
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

// Room 瀵煎嚭 schema锛坋xportSchema = true锛夛紝绾冲叆鐗堟湰绠＄悊锛屼究浜庣紪鍐?Migration
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // ---- AndroidX 鍩虹 ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    // ---- Compose锛堢増鏈敱 BOM 缁熶竴锛?---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // ---- Hilt锛圞SP 娉ㄨВ澶勭悊锛?---
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // ---- Room锛圞SP 娉ㄨВ澶勭悊锛?---
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ---- DataStore ----
    implementation(libs.androidx.datastore.preferences)

    // ---- 瀹夊叏瀛樺偍锛欴eepSeek API Key 瀛?EncryptedSharedPreferences锛堢粷涓嶈繘 DataStore/澶囦唤/鏃ュ織锛?---
    // 鈿狅笍 鏆備互瀛楅潰閲忓紩鍏ワ紙鐗堟湰 security-crypto 1.1.0-alpha06锛夛細
    //    鏇炬寜瑙勮寖鍐欏叆 gradle/libs.versions.toml锛屼絾鏈満鍦ㄣ€宑atalog 鍙樻洿 鈫?Kotlin DSL
    //    accessors 閲嶆柊鐢熸垚銆嶈繖涓€姝ョǔ瀹氳Е鍙?60s TimeoutException锛堣瑙佹眹鎶ワ級锛?
    //    涓轰笉闃诲鑱旇皟鏆傞€€鍥炲瓧闈㈤噺锛沞ngineer 宸茬櫥璁帮紝寰呯幆澧冮棶棰樿В闄ゅ悗鍐嶅綊浣?catalog銆?
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ---- 涓氬姟搴擄紙鍧囦负绾湰鍦?绂荤嚎锛?---
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.android)

    // ---- 璋冭瘯宸ュ叿 ----
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // ---- 鍗曞厓娴嬭瘯锛圝VM锛?---
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)

    // ---- Instrumentation 娴嬭瘯 ----
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
