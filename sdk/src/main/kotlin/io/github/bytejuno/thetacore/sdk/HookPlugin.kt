package io.github.bytejuno.thetacore.sdk

/** S1 full-trust target-process contract. Xposed types never cross this bridge. */
interface HookPlugin {
    fun install(environment: HookEnvironment)
}

interface HookEnvironment {
    /** Intercept a zero-argument String method after its original returns successfully. */
    fun afterString(className: String, methodName: String, callback: HookStringCallback)
}

fun interface HookStringCallback {
    fun transform(original: String): String
}
