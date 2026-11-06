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
}

gradlePlugin {
    plugins {
        register("thetaPlugin") {
            id = "io.github.bytejuno.theta-plugin"
            implementationClass = "io.github.bytejuno.thetacore.gradle.ThetaGradlePlugin"
        }
    }
}
