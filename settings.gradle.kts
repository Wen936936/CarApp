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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 摇杆库 io.github.controlwear:virtualjoystick 原发布在 JCenter，JCenter 已下线，
        // Maven Central 没有该坐标，这里补上阿里云公共仓库（含 JCenter 存量包）才能解析到 1.10.1
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "CarApp"
include(":app")
