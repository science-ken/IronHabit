// =============================================================================
// IronHabit（自律健身）— 根工程设置
// 声明插件/依赖仓库解析策略，并注册 :app 模块。
// 包名已定稿：com.ironhabit.app
// =============================================================================

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 禁止在子模块中单独声明仓库，统一在此处管理
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "IronHabit"
include(":app")
