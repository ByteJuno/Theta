import io.github.bytejuno.thetacore.gradle.StageS1PublicationAssets

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("io.github.bytejuno.theta-plugin") apply false
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
        testInstrumentationRunner = "io.github.bytejuno.thetacore.S1PublicationInstrumentation"
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

// Both lintDebug (PR CI) and preBuild enforce host-owned bilingual text.
val uiResourceParser by configurations.creating
 dependencies { uiResourceParser("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21") }
val verifyUiResources = tasks.register<io.github.bytejuno.thetacore.gradle.VerifyUiResources>("verifyUiResources") {
    sourceDirectory.set(layout.projectDirectory.dir("src/main"))
    parserClasspath.from(uiResourceParser)
}
tasks.matching { it.name == "lintDebug" || it.name == "preBuild" }.configureEach {
    dependsOn(verifyUiResources)
}

// Test APK only. Signed payloads exercise the actual host UI and platform verifier.
val s1PublicationAssets = layout.buildDirectory.dir("generated/s1-publication-test-assets")
val stageS1PublicationAssets = tasks.register<StageS1PublicationAssets>("stageS1PublicationTestAssets") {
    dependsOn(":samples:fixtures:assembleNormalV1Debug", ":samples:fixtures:assembleNormalV2Debug")
    outputDir.set(s1PublicationAssets)
    versionOne.set(project(":samples:fixtures").layout.buildDirectory.file("outputs/apk/normalV1/debug/fixtures-normalV1-debug.apk"))
    versionTwo.set(project(":samples:fixtures").layout.buildDirectory.file("outputs/apk/normalV2/debug/fixtures-normalV2-debug.apk"))
}
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.deviceTests.values.forEach { test ->
            test.sources.assets?.addGeneratedSourceDirectory(stageS1PublicationAssets, StageS1PublicationAssets::outputDir)
        }
    }
}

// Compile the sample's actual platform-independent ZIP generator only into JVM tests.
// Do not depend on its Android application or copy the generator into host fixtures.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.endsWith("UnitTestKotlin")) {
        listOf("FixtureData.kt", "PrefixArchive.kt").forEach { generator ->
            source(rootProject.file(
                "samples/blocked-provider/src/main/kotlin/io/github/bytejuno/thetacore/sample/blockedprovider/$generator",
            ))
        }
    }
}

dependencies {
    implementation(project(":sdk"))
    implementation(project(":agent-core"))
    implementation(libs.androidx.sqlite.framework)
    // Local JVM tests need the desktop JNI loader, not the Android AAR selected by AGP.
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    testImplementation("junit:junit:4.13.2")
    // Real JSON parser for JVM policy tests, not Android's throwing stub.
    testImplementation("org.json:json:20240303")
}
