package io.github.bytejuno.thetacore.hook

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import io.github.libxposed.service.XposedProvider

class TrustedXposedProvider : ContentProvider() {
    private val delegate = XposedProvider()

    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        delegate.attachInfo(context, info)
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
        withTrustedHookBootstrapSender(Binder.getCallingUid()) {
            delegate.call(method, arg, extras)
        }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
