plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("io.github.bytejuno.theta-plugin")
}

android {
    namespace = "io.github.bytejuno.thetacore.sample.hello"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.bytejuno.thetacore.sample.hello"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        // Local verification sample only; debug signing does not authenticate an author.
        release { signingConfig = signingConfigs.getByName("debug") }
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
    id = "io.github.bytejuno.thetacore.sample.hello"
    name = "Hello"
    apiLevel = 1
    entry = "io.github.bytejuno.thetacore.sample.hello.HelloPlugin"
    provides = listOf("settings")
}

dependencies {
    // 这些都由宿主提供，不打进插件包。
    compileOnly(project(":sdk"))
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.material3)
}
