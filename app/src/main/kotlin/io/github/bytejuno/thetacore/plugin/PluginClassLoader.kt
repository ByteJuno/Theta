package io.github.bytejuno.thetacore.plugin

import dalvik.system.DexClassLoader
import java.io.File

/**
 * 插件能从宿主看到的包。平台类不在这里，它们走 boot 加载器。
 *
 * 和 `proguard-rules.pro`、Gradle 插件的排除列表保持同步。
 */
private val SHARED_PACKAGES = listOf(
    "androidx.collection.",
    "androidx.compose.",
    "androidx.lifecycle.",
    "androidx.savedstate.",
    "io.github.bytejuno.thetacore.sdk.",
    "kotlin.",
    "kotlinx.coroutines.",
    "kotlinx.serialization.",
)

/** 插件类加载器的父加载器：平台类照常解析，共享包交给宿主，其余宿主类不可见。 */
private class HostClassFilter(private val host: ClassLoader) : ClassLoader(Any::class.java.classLoader) {
    override fun findClass(name: String): Class<*> {
        if (SHARED_PACKAGES.any(name::startsWith)) return host.loadClass(name)
        throw ClassNotFoundException(name)
    }
}

internal fun createPluginClassLoader(apk: File, host: ClassLoader): ClassLoader =
    DexClassLoader(apk.path, null, null, HostClassFilter(host))
