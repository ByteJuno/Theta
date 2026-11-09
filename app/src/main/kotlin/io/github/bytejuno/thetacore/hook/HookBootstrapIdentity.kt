package io.github.bytejuno.thetacore.hook

internal fun <T> withTrustedHookBootstrapSender(callingUid: Int, forward: () -> T): T {
    if (callingUid != 0 && callingUid != 1000) {
        throw SecurityException("Hook bootstrap requires a platform-privileged sender")
    }
    return forward()
}
