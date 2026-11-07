plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("io.github.bytejuno.theta-plugin")
}

android {
    namespace = "io.github.bytejuno.thetacore.sample.fixture"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "io.github.bytejuno.thetacore.sample.fixture"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
    }
    // All cases deliberately share an ID. The runner clears the dedicated host between cases.
    // normalV1 -> normalV2 is a real same-ID upgrade, not two independent plugins.
    // initThrowV2/initBlockV2 are broken updates of normalV1 that damage its data first.
    flavorDimensions += "scenario"
    productFlavors {
        listOf("normalV1", "normalV2", "initThrow", "initBlock", "initThrowV2", "initBlockV2", "disableThrow", "cleanupThrow", "cleanupBlock", "scopeThrow", "scopeInitThrow", "scopeInitThrowV2").forEach { scenario ->
            create(scenario) {
                dimension = "scenario"
                val second = scenario.endsWith("V2")
                versionCode = if (second) 2 else 1
                versionName = if (second) "2.0" else "1.0"
                buildConfigField("String", "SCENARIO", "\"$scenario\"")
            }
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

theta {
    id = "io.github.bytejuno.thetacore.sample.fixture"
    name = "Fixture"
    apiLevel = 1
    entry = "io.github.bytejuno.thetacore.sample.fixture.FixturePlugin"
    provides = listOf("settings")
}

dependencies {
    // The convention plugin excludes shared libraries from every variant's runtime classpath.
    compileOnly(project(":sdk"))
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.material3)
}
