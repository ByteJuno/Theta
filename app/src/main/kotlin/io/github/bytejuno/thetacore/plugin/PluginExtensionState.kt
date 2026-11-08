package io.github.bytejuno.thetacore.plugin

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import io.github.bytejuno.thetacore.sdk.Extension

/** A registration owns an object identity; publication must not call plugin equals/hashCode. */
internal fun pluginExtensionState() =
    mutableStateOf<List<Extension>>(emptyList(), referentialEqualityPolicy())
