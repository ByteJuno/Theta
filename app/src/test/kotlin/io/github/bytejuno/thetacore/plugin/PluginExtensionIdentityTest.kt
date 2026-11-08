package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.sdk.Extension
import org.junit.Assert.assertSame
import org.junit.Test

class PluginExtensionIdentityTest {
    @Test fun replacementPublicationNeverCallsPluginEquality() {
        class HostileExtension : Extension {
            override val id = "same"
            override fun equals(other: Any?): Boolean = error("Plugin equality must not run on publication")
            override fun hashCode(): Int = error("Plugin hashCode must not run on publication")
        }
        val first = HostileExtension()
        val replacement = HostileExtension()
        val state = pluginExtensionState()
        state.value = listOf(first)
        state.value = listOf(replacement)
        assertSame(replacement, state.value.single())
    }
}
