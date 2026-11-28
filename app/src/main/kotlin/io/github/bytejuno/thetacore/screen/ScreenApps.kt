package io.github.bytejuno.thetacore.screen

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.serialization.json.*

internal class ScreenApps(private val context: Context) {
    fun list(): ToolResult {
        val apps = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0),
        ).filter { it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled }
            .distinctBy { it.activityInfo.packageName }.sortedBy { it.activityInfo.packageName }
        var bytes = 0
        val items = apps.take(200).mapNotNull { info ->
            val item = buildJsonObject {
                put("name", shortScreenText(info.loadLabel(context.packageManager).toString(), 80))
                put("packageName", info.activityInfo.packageName)
            }
            bytes += item.toString().toByteArray().size
            item.takeIf { bytes <= SCREEN_TREE_BYTES }
        }
        return ToolResult(structured = buildJsonObject {
            put("apps", JsonArray(items)); put("truncated", items.size < apps.size)
        }, effect = ToolEffect.NONE)
    }
    fun launch(packageName: String): ToolResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ToolResult(context.getString(R.string.screen_app_missing), isError = true, effect = ToolEffect.NONE)
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult(context.getString(R.string.screen_action_sent), effect = ToolEffect.APPLIED)
        } catch (_: Exception) {
            ToolResult(context.getString(R.string.screen_action_unknown), isError = true, effect = ToolEffect.UNKNOWN)
        }
    }
}
