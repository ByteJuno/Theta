package io.github.bytejuno.thetacore.sample.hookfixture;

import io.github.bytejuno.thetacore.sdk.HookEnvironment;
import io.github.bytejuno.thetacore.sdk.HookPlugin;

public final class SampleHook implements HookPlugin {
    @Override public void install(HookEnvironment environment) {
        environment.afterString("io.github.bytejuno.thetacore.sample.hooktarget.TargetActivity",
            "probeValue", original -> "HOOKED (" + original + ")");
    }
}
