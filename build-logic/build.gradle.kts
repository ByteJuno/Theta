import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

// 插件作者可能用 JDK 17 运行 Gradle，这里不能编译成更高的版本。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.android.gradle.api)
    // Reuse Kotlin's real PSI parser for the UI resource check, not a home-grown lexer.
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
}

gradlePlugin {
    plugins {
        register("thetaPlugin") {
            id = "io.github.bytejuno.theta-plugin"
            implementationClass = "io.github.bytejuno.thetacore.gradle.ThetaGradlePlugin"
        }
    }
}
