buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 的内置 Kotlin 要和 Compose 编译器插件用同一个版本。
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
