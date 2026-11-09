import io.github.bytejuno.thetacore.gradle.GenerateThetaManifest
import java.util.Random

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("io.github.bytejuno.theta-plugin")
}

val s1BoundaryScenarios = listOf("s1RegisterDisable", "s1RegisterFail")
// Inert bytes make a real partial CODE destination write observable on Android.
// They belong only to the existing disposable normalV2 payload, never the host.
val publicationPadding = layout.buildDirectory.dir("generated/publication-padding")
val paddingFile = publicationPadding.get().file("s1-write-padding.bin").asFile
val generatePublicationPadding = tasks.register("generatePublicationPadding") {
    inputs.property("bytes", 8 * 1024 * 1024)
    inputs.property("seed", 20261109L)
    outputs.file(paddingFile)
    doLast {
        paddingFile.parentFile.mkdirs()
        val random = Random(20261109L)
        val buffer = ByteArray(8192)
        paddingFile.outputStream().use { output ->
            repeat(1024) { random.nextBytes(buffer); output.write(buffer) }
        }
    }
}
val s1BudgetScenarios = listOf("s1BudgetA", "s1BudgetB", "s1BudgetC", "s1BudgetD", "s1BudgetE")
val s1Scenarios = listOf(
    "s1EntryReady", "s1EntryLate", "s1EntryNonCooperative", "s1EntryLongReady",
    "s1ToolSuccess", "s1ToolThrow", "s1ToolBlock", "s1ToolProgress",
    "s1Settings", "s1SettingsCrash", "s1SettingsV2", "s1SettingsFailV2",
    "s1SettingsComposeCrash",
) + s1BoundaryScenarios + s1BudgetScenarios

android {
    namespace = "io.github.bytejuno.thetacore.sample.fixture"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "io.github.bytejuno.thetacore.sample.fixture"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
    }
    // Original r5 cases deliberately share an ID. The runner clears its dedicated host between cases.
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
        // Separate plugin identity; no S1 case replaces the existing r5 fixture.
        s1Scenarios.forEach { scenario ->
            create(scenario) {
                dimension = "scenario"
                applicationIdSuffix = when (scenario) {
                    in s1BudgetScenarios -> ".s1.budget.${scenario.last().lowercaseChar()}"
                    in s1BoundaryScenarios -> ".s1.race.${if (scenario.endsWith("Fail")) "fail" else "disable"}"
                    else -> ".s1"
                }
                val second = scenario.endsWith("V2")
                versionCode = if (second) 2 else 1
                versionName = if (second) "2.0" else "1.0"
                buildConfigField("String", "SCENARIO", "\"$scenario\"")
            }
        }
    }
    sourceSets {
        named("normalV2") { assets.directories += publicationPadding.get().asFile.path }
        // AGP 9 built-in Kotlin ignores Kotlin directories registered on java.
        // Register on AndroidSourceSet.kotlin for every S1 flavor; leave r5 untouched.
        s1Scenarios.forEach { scenario ->
            named(scenario) {
                kotlin.directories += "src/s1/kotlin"
            }
        }
    }
    androidResources { noCompress += "bin" }
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
tasks.configureEach {
    if (name.startsWith("mergeNormalV2") && name.endsWith("Assets")) dependsOn(generatePublicationPadding)
}

theta {
    id = "io.github.bytejuno.thetacore.sample.fixture"
    name = "Fixture"
    apiLevel = 1
    entry = "io.github.bytejuno.thetacore.sample.fixture.FixturePlugin"
    provides = listOf("settings")
}

// The convention plugin registers generation before this callback. Override only S1
// task inputs so r5 metadata, entry point and declared capabilities remain unchanged.
androidComponents {
    onVariants { variant ->
        if (variant.productFlavors.any { (dimension, flavor) ->
                dimension == "scenario" && flavor in s1Scenarios
            }) {
            tasks.named<GenerateThetaManifest>(
                "generate${variant.name.replaceFirstChar(Char::uppercase)}ThetaManifest",
            ) {
                val scenario = variant.productFlavors.single { it.first == "scenario" }.second
                val compositionCrash = scenario == "s1SettingsComposeCrash"
                val suffix = when (scenario) {
                    in s1BudgetScenarios -> ".budget.${scenario.last().lowercaseChar()}"
                    in s1BoundaryScenarios -> ".race.${if (scenario.endsWith("Fail")) "fail" else "disable"}"
                    else -> if (compositionCrash) ".compose" else ""
                }
                pluginId.set("io.github.bytejuno.thetacore.sample.fixture.s1$suffix")
                pluginName.set(when (scenario) {
                    in s1BudgetScenarios, in s1BoundaryScenarios -> "S1 $scenario"
                    else -> if (compositionCrash) "S1 Composition Fixture" else "S1 Fixture"
                })
                entry.set("io.github.bytejuno.thetacore.sample.fixture.s1." + when (scenario) {
                    in s1BudgetScenarios -> "S1BudgetPlugin"
                    in s1BoundaryScenarios -> "S1RegistrationRacePlugin"
                    else -> "S1FixturePlugin"
                })
                provides.set(listOf("settings", "entry", "tool"))
            }
        }
    }
}

dependencies {
    // The convention plugin excludes shared libraries from every variant's runtime classpath.
    compileOnly(project(":sdk"))
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.material3)
}
