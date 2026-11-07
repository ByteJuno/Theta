plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("io.github.bytejuno.theta-plugin")
}

android {
    namespace = "io.github.bytejuno.thetacore.sample.probe"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.bytejuno.thetacore.sample.probe"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

theta {
    id = "io.github.bytejuno.thetacore.sample.probe"
    name = "Probe"
    apiLevel = 1
    entry = "io.github.bytejuno.thetacore.sample.probe.ProbePlugin"
    provides = listOf("settings")
    declares = listOf("network")
}

dependencies {
    // 这些都由宿主提供，不打进插件包。
    compileOnly(project(":sdk"))
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.material3)
    compileOnly(libs.kotlinx.serialization.json)

    // 非共享库由插件自带。它依赖的 Kotlin 标准库会被 Gradle 插件排除，运行时用宿主的。
    implementation(libs.okhttp)
}
