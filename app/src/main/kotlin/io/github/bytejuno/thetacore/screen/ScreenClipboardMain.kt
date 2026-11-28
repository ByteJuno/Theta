package io.github.bytejuno.thetacore.screen

import android.annotation.SuppressLint
import android.content.ClipData
import android.os.IBinder
import android.os.PersistableBundle
import androidx.annotation.Keep
import java.io.File
import kotlin.system.exitProcess

/** app_process entry, only run inside RootBridge's supervised group. No input text in argv/logs.
 * Clipboard binder signatures vary by Android release; reject unknown shapes before invoking.
 * A failed write/readback never reaches the shell's following KEYCODE_PASTE.
 */
@Keep
internal object ScreenClipboardMain {
    @JvmStatic
    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
    fun main(args: Array<String>) {
        var writeAttempted = false
        try {
            require(args.size == 1)
            val bytes = readBounded(File(args[0]), 65536)
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            // The clipboard verifies package/UID ownership. Read the private payload as root,
            // then use the real shell UID matching com.android.shell for Binder calls.
            check(android.system.Os.getuid() == 0)
            android.system.Os.setgid(2000)
            android.system.Os.setuid(2000)
            val manager = Class.forName("android.os.ServiceManager")
            val binder = manager.getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
            val stub = Class.forName("android.content.IClipboard\$Stub")
            val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val api = Class.forName("android.content.IClipboard")
            val clip = ClipData.newPlainText("Theta", text).apply {
                description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            }
            fun invoke(name: String, value: ClipData? = null): Any? {
                val prefix = if (value == null) emptyList() else listOf(value)
                val method = api.methods.single { it.name == name }
                val types = method.parameterTypes.drop(prefix.size)
                require(types.size in 3..4 && types[0] == String::class.java && types[1] == String::class.java &&
                    types.drop(2).all { it == Int::class.javaPrimitiveType })
                val tail = listOf<Any?>("com.android.shell", null, 0) + if (types.size == 4) listOf(0) else emptyList()
                return method.invoke(service, *(prefix + tail).toTypedArray())
            }
            // Validate signatures before attempting the write; unknown ROMs can safely fall back.
            for (name in listOf("setPrimaryClip", "getPrimaryClip")) {
                val types = api.methods.single { it.name == name }.parameterTypes.drop(if (name == "setPrimaryClip") 1 else 0)
                require(types.size in 3..4 && types[0] == String::class.java && types[1] == String::class.java &&
                    types.drop(2).all { it == Int::class.javaPrimitiveType })
            }
            writeAttempted = true
            invoke("setPrimaryClip", clip)
            val actual = invoke("getPrimaryClip") as? ClipData
            check(actual?.itemCount == 1 && actual.getItemAt(0).text?.toString() == text)
            exitProcess(0)
        } catch (_: Throwable) { exitProcess(if (writeAttempted) 79 else 78) }
    }
}
