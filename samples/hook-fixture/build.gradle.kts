plugins {
    alias(libs.plugins.android.application)
    id("io.github.bytejuno.theta-plugin")
}
android {
    namespace = "io.github.bytejuno.thetacore.sample.hookfixture"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "io.github.bytejuno.thetacore.sample.hookfixture"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1"
    }
    buildTypes { release { signingConfig = signingConfigs.getByName("debug") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
theta {
    id = "io.github.bytejuno.thetacore.sample.hookfixture"
    name = "Hook fixture"
    apiLevel = 1
    entry = "io.github.bytejuno.thetacore.sample.hookfixture.SampleHook"
}
dependencies { compileOnly(project(":sdk")) }
