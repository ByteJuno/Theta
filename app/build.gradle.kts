plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 只有发版工作流会传这四个变量。签名材料不进仓库，CI 用的那份放在 GitHub 的 release environment 里。
val releaseStoreFile = providers.environmentVariable("THETA_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("THETA_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("THETA_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("THETA_RELEASE_KEY_PASSWORD").orNull

android {
    namespace = "io.github.bytejuno.thetacore"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.bytejuno.thetacore"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 没有发版密钥时退回 debug 签名，这样 R8 版本也能装到测试设备上。
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

dependencies {
    implementation(project(":sdk"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
}
