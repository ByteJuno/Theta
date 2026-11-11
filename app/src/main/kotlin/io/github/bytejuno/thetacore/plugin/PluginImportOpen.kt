package io.github.bytejuno.thetacore.plugin

import java.io.InputStream

/** Only source acquisition failures belong to the selected-file diagnostic. */
internal fun openPluginImportStream(open: () -> InputStream?): InputStream =
    try { open() } catch (error: java.io.FileNotFoundException) {
        throw PluginPackageException("Cannot open selected file", PluginFailureReason.FILE_UNAVAILABLE, error)
    } catch (error: SecurityException) {
        throw PluginPackageException("Cannot open selected file", PluginFailureReason.FILE_UNAVAILABLE, error)
    } catch (error: IllegalArgumentException) {
        throw PluginPackageException("Cannot open selected file", PluginFailureReason.FILE_UNAVAILABLE, error)
    } ?: throw PluginPackageException("Cannot open selected file", PluginFailureReason.FILE_UNAVAILABLE)
